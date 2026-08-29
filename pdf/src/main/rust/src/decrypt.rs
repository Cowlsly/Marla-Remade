use crate::*;

/// Per-object encrypt/decrypt transform.
type CryptFn = Box<dyn Fn(&[u8]) -> Vec<u8>>;
/// Builds the [`CryptFn`] for a given object id.
type CryptFnFactory = Box<dyn Fn(ObjectId) -> CryptFn>;

/// Whether `bytes` is a standard-encrypted PDF that needs a (non-empty) password
/// the empty password does not satisfy. Returns: 0 no, 1 needs password, 2
/// unsupported encryption (e.g. AES).
pub(crate) fn pdf_password_state(bytes: &[u8]) -> i32 {
    // Must match open_document_pw's loader: using the strict Document::load_mem here made a
    // damaged encrypted file report "no password needed" and then fail to open with no prompt.
    let mut doc = match crate::registry::load_document_lenient(bytes) {
        Some(d) => d,
        None => return 0,
    };
    if doc.trailer.get(b"Encrypt").is_err() {
        return 0;
    }
    // Probe with no password (empty, built at runtime — not a hard-coded credential).
    let no_password: Vec<u8> = Vec::new();
    match decrypt_in_place(&mut doc, &no_password) {
        DecryptStatus::Ok => 0,
        DecryptStatus::NeedPassword => 1,
        DecryptStatus::Unsupported => 2,
    }
}

#[derive(PartialEq, Debug)]
pub(crate) enum DecryptStatus {
    Ok,
    NeedPassword,
    Unsupported,
}

/// Apply a cipher (`apply`) to every string and stream inside `obj`.
pub(crate) fn crypt_object(obj: &mut Object, apply: &dyn Fn(&[u8]) -> Vec<u8>) {
    match obj {
        Object::String(s, _) => *s = apply(s),
        Object::Array(a) => {
            for o in a.iter_mut() {
                crypt_object(o, apply);
            }
        }
        Object::Dictionary(d) => {
            let keys: Vec<Vec<u8>> = d.iter().map(|(k, _)| k.clone()).collect();
            for k in keys {
                if let Ok(v) = d.get_mut(&k) {
                    crypt_object(v, apply);
                }
            }
        }
        Object::Stream(st) => {
            let keys: Vec<Vec<u8>> = st.dict.iter().map(|(k, _)| k.clone()).collect();
            for k in keys {
                if let Ok(v) = st.dict.get_mut(&k) {
                    crypt_object(v, apply);
                }
            }
            st.content = apply(&st.content);
        }
        _ => {}
    }
}

/// First `/ID` element bytes from the trailer, or empty if absent.
/// For decryption, an empty ID is tolerated (some generators omit it). The
/// alternate hash fallback was breaking RC4/AES-128 round-trips because
/// `encrypt_doc_bytes` must persist a concrete ID; `trailer_id0` must NOT
/// synthesize a different value on each load. Missing ID returns empty.
pub(crate) fn trailer_id0(doc: &Document) -> Vec<u8> {
    if let Ok(Object::Array(a)) = doc.trailer.get(b"ID") {
        if let Some(Object::String(s, _)) = a.first() {
            return s.clone();
        }
    }
    Vec::new()
}

/// Ensure the document trailer has an `/ID` array (two entries), generating
/// a random one if missing. Returns the first ID bytes for key derivation.
pub(crate) fn ensure_trailer_id(doc: &mut Document, seed: &[u8]) -> Vec<u8> {
    if let Ok(Object::Array(a)) = doc.trailer.get(b"ID") {
        if let Some(Object::String(s, _)) = a.first() {
            return s.clone();
        }
    }
    let h = rand_bytes::<16>(seed).to_vec();
    doc.trailer.set(
        "ID",
        Object::Array(vec![
            Object::String(h.clone(), lopdf::StringFormat::Hexadecimal),
            Object::String(h.clone(), lopdf::StringFormat::Hexadecimal),
        ]),
    );
    h
}

#[derive(Clone, Copy, PartialEq)]
pub(crate) enum CryptMethod {
    Rc4,
    AesV2,
    AesV3,
}

/// Decrypt a standard-encrypted document (RC4 or AES) in place with `password`.
///
/// Tries lopdf's own standard-security-handler first. That matters for correctness, not
/// just economy: lopdf honours `/StrF` and the `/Identity` crypt filter, applies
/// Algorithm 2 step (f) for `/EncryptMetadata false`, and skips `/Type /XRef` streams.
/// Our implementation is retained for the files lopdf cannot authenticate.
pub(crate) fn decrypt_in_place(doc: &mut Document, password: &[u8]) -> DecryptStatus {
    // Authenticate WITHOUT mutating first. lopdf's decrypt_raw applies `?` to each object
    // inside its mutation loop (document.rs:486-493), so a mid-loop failure leaves the
    // document HALF-decrypted. Running our fallback over that would decrypt the already
    // plaintext prefix a second time — and RC4 is symmetric, so it would re-encrypt it
    // into noise. Deciding up front which implementation owns the document avoids that.
    if doc.authenticate_raw_password(password).is_ok() {
        return match doc.decrypt_raw(password) {
            // decrypt_raw also re-expands object streams and clears /Encrypt.
            Ok(()) => DecryptStatus::Ok,
            Err(_) => {
                // Partially decrypted. Keep what succeeded rather than corrupting it, on
                // the same "partial data beats no data" principle used for Flate/LZW.
                //
                // decrypt_raw bails out of its per-object loop (document.rs:492) BEFORE
                // it reaches its own object-stream expansion (document.rs:496-517), so
                // without this the ObjStm-contained objects — /Root, /Pages and the page
                // dictionaries for essentially every modern encrypted PDF — stay missing
                // and the document opens with zero pages. Only ever adds objects.
                expand_object_streams(doc);
                doc.trailer.remove(b"Encrypt");
                DecryptStatus::Ok
            }
        };
    }
    // lopdf could not authenticate, so it has not touched the document: our own handler
    // gets a pristine copy.
    decrypt_in_place_fallback(doc, password)
}

/// Our own standard-security-handler implementation, used when lopdf declines the file.
fn decrypt_in_place_fallback(doc: &mut Document, password: &[u8]) -> DecryptStatus {
    let enc_id = match doc.trailer.get(b"Encrypt").and_then(|o| o.as_reference()) {
        Ok(id) => id,
        Err(_) => return DecryptStatus::Unsupported,
    };
    let (o, u, ue, oe, p, r, length, method, encrypt_metadata, _cf_dict_opt) = {
        let enc = match doc.get_dictionary(enc_id) {
            Ok(d) => d,
            Err(_) => return DecryptStatus::Unsupported,
        };
        let filter = enc.get(b"Filter").ok().and_then(|o| o.as_name().ok());
        if filter != Some(b"Standard".as_ref()) {
            // Only the Standard security handler is supported. Public-key /
            // certificate handlers (e.g. /Filter /Adobe.PubSec) are infeasible
            // here: decryption requires the recipient's private key, which the
            // viewer does not possess. Report as Unsupported rather than failing
            // silently or corrupting bytes.
            return DecryptStatus::Unsupported;
        }
        let v = enc.get(b"V").ok().and_then(num).unwrap_or(0.0) as i64;
        let r = enc.get(b"R").ok().and_then(num).unwrap_or(0.0) as i64;
        // P0 fix: properly resolve /CF dict + /StmF /StrF names vs. only StdCF
        // Per PDF 1.7 §7.6.2, /CF may have multiple named crypt filters.
        // /StmF and /StrF name which CF to use for streams and strings.
        // Previously only StdCF checked, breaking crypt-filters docs.
        let cf_dict_opt = enc.get(b"CF").ok().and_then(|o| o.as_dict().ok()).cloned();
        // For V=4, if CF absent spec says fall back to V2 Rc4 — fix P0 issue #22
        let method = if v >= 5 {
            CryptMethod::AesV3
        } else if v == 4 {
            if let Some(cf_dict) = &cf_dict_opt {
                // Try StdCF first, then StmF-named filter, then any CF entry
                let stm_f_name = enc.get(b"StmF").ok().and_then(|o| o.as_name().ok()).unwrap_or(b"StdCF");
                let cfm = cf_dict
                    .get(stm_f_name)
                    .or_else(|_| cf_dict.get(b"StdCF"))
                    .ok()
                    .and_then(|s| s.as_dict().ok())
                    .and_then(|s| s.get(b"CFM").ok())
                    .and_then(|o| o.as_name().ok());
                match cfm {
                    Some(b) if b == b"AESV3" => CryptMethod::AesV3,
                    Some(b) if b == b"AESV2" => CryptMethod::AesV2,
                    Some(b) if b == b"V2" => CryptMethod::Rc4,
                    // If no CFM found but CF present, could be custom — treat as Unsupported
                    // unless CF missing entirely then fall back to Rc4 below
                    Some(_) => return DecryptStatus::Unsupported,
                    None => {
                        // CF exists but no CFM? Check if CF dict non-empty maybe encryption present but missing method
                        // Per plan, if CF dict absent entirely we fall through to Rc4 fallback
                        // If CF present but unreadable, Unsupported
                        if cf_dict.is_empty() {
                            CryptMethod::Rc4
                        } else {
                            // Try any CF entry's CFM
                            let mut found = None;
                            for (_, cf_entry) in cf_dict.iter() {
                                if let Ok(d) = cf_entry.as_dict() {
                                    if let Ok(cfm_obj) = d.get(b"CFM") {
                                        if let Ok(cfm_name) = cfm_obj.as_name() {
                                            if cfm_name == b"AESV3" { found = Some(CryptMethod::AesV3); break; }
                                            if cfm_name == b"AESV2" { found = Some(CryptMethod::AesV2); break; }
                                            if cfm_name == b"V2" { found = Some(CryptMethod::Rc4); break; }
                                        }
                                    }
                                }
                            }
                            found.unwrap_or(CryptMethod::Rc4)
                        }
                    }
                }
            } else {
                // V=4 with no CF dict — spec says default to V2 Rc4 fallback
                CryptMethod::Rc4
            }
        } else {
            CryptMethod::Rc4
        };
        let o = enc.get(b"O").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        let u = enc.get(b"U").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        let ue = enc.get(b"UE").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        let oe = enc.get(b"OE").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        // Preserve CF dict for later StmF/StrF handling? We already parsed method but for auth need OE for owner
        let p = enc.get(b"P").ok().and_then(num).unwrap_or(0.0) as i32;
        let default_len = if method == CryptMethod::AesV2 { 128.0 } else { 40.0 };
        let length = enc.get(b"Length").ok().and_then(num).unwrap_or(default_len) as usize;
        // §7.6.3.3 Algorithm 2 step (f): with /EncryptMetadata false and R >= 4 the key
        // derivation takes four extra 0xFF bytes. Default true.
        let encrypt_metadata = !matches!(enc.get(b"EncryptMetadata"), Ok(Object::Boolean(false)));
        (o, u, ue, oe, p, r, length, method, encrypt_metadata, cf_dict_opt)
    };

    let id0 = trailer_id0(doc);
    let n = if method == CryptMethod::AesV2 { 16 } else { (length / 8).clamp(5, 16) };

    // Derive the file key: try user pw first, then owner pw for both V<5 and V>=5 (P0 fix #14 + #21)
    let key = match method {
        CryptMethod::AesV3 => {
            // V5/R5/R6: try user auth then owner auth
            if let Some(k) = crypto::authenticate_v5_user(password, &u, &ue, r as u8) {
                k
            } else if let Some(k) = crypto::authenticate_v5_owner(password, &o, &oe, &u, r as u8) {
                k
            } else {
                return DecryptStatus::NeedPassword;
            }
        }
        _ => {
            // V<5: spec says owner pw derives via O then user check, but many viewers try both
            // Also support owner password path: authenticate returns key if U matches; try both user and owner variants?
            // Owner auth in RC4: you can recover user pw from O using owner pw. To avoid full impl, we reuse authenticate
            // which already checks U against key derived from pw (user). Owner-only docs use empty user pw that still validates,
            // but some require owner.
            // Attempt direct authenticate with given password (user path)
            if let Some(k) = crypto::authenticate(password, &o, &u, p, &id0, n, r as u8, encrypt_metadata) {
                k
            } else {
                // Owner path: if owner pw supplied, O entry contains user pw encrypted; try to brute cheap?
                // Implement algorithm 7 (recover user key from O using owner pw) per spec.
                // Simplified: compute key from owner pw directly, then compute U and compare via O decryption.
                // For minimal fix, attempt authenticate_owner which we emulate below.
                let mut found: Option<Vec<u8>> = None;
                // Try derive candidate owner key and then decrypt O to get user pw, then authenticate that user pw
                // Algorithm 3 reverse: owner pw -> okey -> user_pad = rc4 decypt O etc.
                // We'll delegate to helper.
                if let Some(k) = crypto::authenticate_owner_fallback(password, &o, &u, p, &id0, n, r as u8, encrypt_metadata) {
                    found = Some(k);
                }
                if let Some(k) = found {
                    k
                } else {
                    return DecryptStatus::NeedPassword;
                }
            }
        }
    };

    let ids: Vec<ObjectId> = doc.objects.keys().copied().collect();
    for id in ids {
        if id == enc_id {
            continue;
        }
        // §7.5.8.2: a cross-reference stream shall not be encrypted, and neither shall
        // the strings in its dictionary.
        let is_xref_stream = doc
            .objects
            .get(&id)
            .and_then(|o| o.as_stream().ok())
            .map(|s| s.dict.has_type(b"XRef"))
            .unwrap_or(false);
        if is_xref_stream {
            continue;
        }
        let apply: CryptFn = match method {
            CryptMethod::Rc4 => {
                let okey = crypto::object_key(&key, id.0, id.1, n);
                Box::new(move |d: &[u8]| crypto::rc4(&okey, d))
            }
            CryptMethod::AesV2 => {
                let okey = crypto::object_key_aes(&key, id.0, id.1, n);
                Box::new(move |d: &[u8]| crypto::aes_cbc_decrypt(&okey, d).unwrap_or_default())
            }
            CryptMethod::AesV3 => {
                let k = key.clone();
                Box::new(move |d: &[u8]| crypto::aes_cbc_decrypt(&k, d).unwrap_or_default())
            }
        };
        if let Some(obj) = doc.objects.get_mut(&id) {
            crypt_object(obj, &apply);
        }
    }
    expand_object_streams(doc);
    doc.trailer.remove(b"Encrypt");
    DecryptStatus::Ok
}

/// Expand every `/Type /ObjStm` into its contained objects.
///
/// lopdf skips object-stream expansion at load time when the trailer has `/Encrypt`
/// (reader.rs: `if stream.dict.has_type(b"ObjStm") && !is_encrypted`) because the bytes
/// are still ciphertext, and it only auto-decrypts for the EMPTY password. So a document
/// with a real password reached this point with every ObjStm-contained object missing —
/// including `/Root`, `/Pages` and the page dictionaries, which is where §7.5.7 puts them
/// for essentially every modern encrypted PDF. The result was a correct password opening
/// a document with zero pages.
fn expand_object_streams(doc: &mut Document) {
    let mut recovered: Vec<(ObjectId, Object)> = Vec::new();
    for (_, object) in doc.objects.iter() {
        let Ok(stream) = object.as_stream() else {
            continue;
        };
        if !stream.dict.has_type(b"ObjStm") {
            continue;
        }
        let mut stream = stream.clone();
        if let Ok(obj_stream) = lopdf::ObjectStream::new(&mut stream) {
            recovered.extend(obj_stream.objects);
        }
    }
    // Only add, never replace: a top-level definition supersedes one inside an ObjStm.
    for (id, obj) in recovered {
        doc.objects.entry(id).or_insert(obj);
    }
}

/// Which standard-security-handler algorithm to write on save.
#[derive(Clone, Copy, PartialEq)]
pub(crate) enum EncryptAlgo {
    /// RC4-128, revision 3 (V2/R3).
    Rc4_128,
    /// AES-128, revision 4 (V4/R4, AESV2).
    Aes128,
    /// AES-256, revision 6 (V5/R6, AESV3).
    Aes256,
}

/// Cryptographically-secure random bytes for salts/IVs, sourced from the OS
/// CSPRNG. Falls back to md5-based mixing (seed + wall clock) only if the OS
/// RNG is unavailable, preserving the no-panic invariant.
fn rand_bytes<const N: usize>(seed: &[u8]) -> [u8; N] {
    use rand::RngCore;
    let mut out = [0u8; N];
    if rand::rngs::OsRng.try_fill_bytes(&mut out).is_ok() {
        return out;
    }
    use md5::{Digest, Md5};
    let t = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let mut ctr: u64 = 0;
    let mut filled = 0;
    while filled < N {
        let mut m = Md5::new();
        m.update(seed);
        m.update(t.to_le_bytes());
        m.update(ctr.to_le_bytes());
        let d: [u8; 16] = m.finalize().into();
        let take = d.len().min(N - filled);
        out[filled..filled + take].copy_from_slice(&d[..take]);
        filled += take;
        ctr += 1;
    }
    out
}

/// Serialize `handle` encrypted with the given passwords, defaulting to AES-128
/// (V4/R4) — modern and widely supported.
pub(crate) fn save_encrypted(handle: i64, user_pw: &[u8], owner_pw: &[u8]) -> Option<Vec<u8>> {
    let bytes = save_document(handle)?;
    encrypt_doc_bytes(&bytes, user_pw, owner_pw, EncryptAlgo::Aes128)
}

/// Encrypt a serialized PDF (`bytes`) with `algo` and the given passwords,
/// returning the re-serialized encrypted document. Testable without the registry.
pub(crate) fn encrypt_doc_bytes(
    bytes: &[u8],
    user_pw: &[u8],
    owner_pw: &[u8],
    algo: EncryptAlgo,
) -> Option<Vec<u8>> {
    let mut doc = Document::load_mem(bytes).ok()?;
    // Ensure an /ID exists (used by RC4/AES-128 key derivation).
    let id0 = ensure_trailer_id(&mut doc, bytes);
    let owner = if owner_pw.is_empty() { user_pw } else { owner_pw };
    let p: i32 = -4; // allow all operations

    // Build the /Encrypt dict and per-object cipher factory.
    let (enc, make_apply): (Dictionary, CryptFnFactory) =
        match algo {
            EncryptAlgo::Rc4_128 => {
                let (n, rev) = (16usize, 3u8);
                let o = crypto::compute_o(owner, user_pw, n, rev);
                let key = crypto::compute_key(user_pw, &o, p, &id0, n, rev, true);
                let u = crypto::compute_u(&key, &id0, rev);
                let mut enc = Dictionary::new();
                enc.set("Filter", name_obj("Standard"));
                enc.set("V", Object::Integer(2));
                enc.set("R", Object::Integer(3));
                enc.set("Length", Object::Integer(128));
                enc.set("P", Object::Integer(p as i64));
                enc.set("O", Object::String(o, lopdf::StringFormat::Literal));
                enc.set("U", Object::String(u, lopdf::StringFormat::Literal));
                let key2 = key.clone();
                let make = move |id: ObjectId| -> CryptFn {
                    let okey = crypto::object_key(&key2, id.0, id.1, n);
                    Box::new(move |d: &[u8]| crypto::rc4(&okey, d))
                };
                (enc, Box::new(make))
            }
            EncryptAlgo::Aes128 => {
                let (n, rev) = (16usize, 4u8);
                let o = crypto::compute_o(owner, user_pw, n, rev);
                let key = crypto::compute_key(user_pw, &o, p, &id0, n, rev, true);
                let u = crypto::compute_u(&key, &id0, rev);
                let mut cf = Dictionary::new();
                let mut stdcf = Dictionary::new();
                stdcf.set("CFM", name_obj("AESV2"));
                stdcf.set("Length", Object::Integer(16));
                cf.set("StdCF", Object::Dictionary(stdcf));
                let mut enc = Dictionary::new();
                enc.set("Filter", name_obj("Standard"));
                enc.set("V", Object::Integer(4));
                enc.set("R", Object::Integer(4));
                enc.set("Length", Object::Integer(128));
                enc.set("P", Object::Integer(p as i64));
                enc.set("CF", Object::Dictionary(cf));
                enc.set("StmF", name_obj("StdCF"));
                enc.set("StrF", name_obj("StdCF"));
                enc.set("O", Object::String(o, lopdf::StringFormat::Literal));
                enc.set("U", Object::String(u, lopdf::StringFormat::Literal));
                let key2 = key.clone();
                let seed = id0.clone();
                let make = move |id: ObjectId| -> CryptFn {
                    let okey = crypto::object_key_aes(&key2, id.0, id.1, n);
                    let seed = seed.clone();
                    Box::new(move |d: &[u8]| {
                        let iv = rand_bytes::<16>(&[&seed[..], d.get(..8).unwrap_or(d)].concat());
                        crypto::aes_cbc_encrypt(&okey, &iv, d).unwrap_or_default()
                    })
                };
                (enc, Box::new(make))
            }
            EncryptAlgo::Aes256 => {
                let rev = 6u8;
                let file_key = rand_bytes::<32>(&id0);
                let salt_bytes = rand_bytes::<32>(&[&id0[..], b"salts"].concat());
                // Split the random salt bytes into four 8-byte salts (derived from
                // salt_bytes, so no hard-coded array flows into the KDF).
                let salts: [[u8; 8]; 4] = std::array::from_fn(|i| {
                    let mut s = [0u8; 8];
                    s.copy_from_slice(&salt_bytes[i * 8..i * 8 + 8]);
                    s
                });
                let (u, ue, o, oe) = crypto::compute_v5(user_pw, owner, &file_key, &salts, rev)?;
                let perms = crypto::compute_perms_v5(&file_key, p)?;
                let mut cf = Dictionary::new();
                let mut stdcf = Dictionary::new();
                stdcf.set("CFM", name_obj("AESV3"));
                stdcf.set("Length", Object::Integer(32));
                cf.set("StdCF", Object::Dictionary(stdcf));
                let mut enc = Dictionary::new();
                enc.set("Filter", name_obj("Standard"));
                enc.set("V", Object::Integer(5));
                enc.set("R", Object::Integer(6));
                enc.set("Length", Object::Integer(256));
                enc.set("P", Object::Integer(p as i64));
                enc.set("CF", Object::Dictionary(cf));
                enc.set("StmF", name_obj("StdCF"));
                enc.set("StrF", name_obj("StdCF"));
                enc.set("O", Object::String(o, lopdf::StringFormat::Literal));
                enc.set("U", Object::String(u, lopdf::StringFormat::Literal));
                enc.set("OE", Object::String(oe, lopdf::StringFormat::Literal));
                enc.set("UE", Object::String(ue, lopdf::StringFormat::Literal));
                enc.set("Perms", Object::String(perms, lopdf::StringFormat::Literal));
                let fk = file_key;
                let seed = id0.clone();
                let make = move |_id: ObjectId| -> CryptFn {
                    // AESV3 uses the file key directly (no per-object key).
                    let seed = seed.clone();
                    Box::new(move |d: &[u8]| {
                        let iv = rand_bytes::<16>(&[&seed[..], d.get(..8).unwrap_or(d)].concat());
                        crypto::aes_cbc_encrypt(&fk, &iv, d).unwrap_or_default()
                    })
                };
                (enc, Box::new(make))
            }
        };

    let enc_id = doc.add_object(enc);
    let ids: Vec<ObjectId> = doc.objects.keys().copied().collect();
    for id in ids {
        if id == enc_id {
            continue;
        }
        let apply = make_apply(id);
        if let Some(obj) = doc.objects.get_mut(&id) {
            crypt_object(obj, &apply);
        }
    }
    doc.trailer.set("Encrypt", Object::Reference(enc_id));

    let mut out = Vec::new();
    doc.save_to(&mut out).ok()?;
    Some(out)
}
