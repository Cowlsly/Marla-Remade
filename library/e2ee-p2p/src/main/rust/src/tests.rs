//! Interop + round-trip tests. The vectors file holds real Bouncy Castle 1.85
//! output (keys as DER, a KEM ciphertext + shared secret, and an ML-DSA
//! signature) — proving the Rust side is byte-compatible with deployed data.

use super::*;
use std::collections::HashMap;

const VECTORS: &str = include_str!("../testvectors/bc_pqc_vectors.txt");

fn unhex(s: &str) -> Vec<u8> {
    let s = s.trim();
    (0..s.len() / 2)
        .map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).unwrap())
        .collect()
}

fn vectors() -> HashMap<String, String> {
    let mut m = HashMap::new();
    for line in VECTORS.lines() {
        if let Some(eq) = line.find('=') {
            m.insert(line[..eq].to_string(), line[eq + 1..].to_string());
        }
    }
    m
}

#[test]
fn rust_decaps_bouncycastle_ciphertext() {
    let v = vectors();
    let priv_der = unhex(&v["MLKEM_PRIV_DER"]);
    let ct = unhex(&v["MLKEM_CT"]);
    let expected_ss = unhex(&v["MLKEM_SS"]);
    let ss = mlkem_decaps_der(&priv_der, &ct).expect("decaps");
    assert_eq!(ss, expected_ss, "Rust ML-KEM decaps must match BC's shared secret");
}

#[test]
fn rust_verifies_bouncycastle_signature() {
    let v = vectors();
    let pub_der = unhex(&v["MLDSA_PUB_DER"]);
    let msg = unhex(&v["MLDSA_MSG"]);
    let sig = unhex(&v["MLDSA_SIG"]);
    assert!(mldsa_verify_der(&pub_der, &msg, &sig), "Rust must verify BC's ML-DSA signature");
    // Tamper -> reject.
    let mut bad = msg.clone();
    bad[0] ^= 1;
    assert!(!mldsa_verify_der(&pub_der, &bad, &sig));
}

#[test]
fn public_key_der_reencodes_identically() {
    // Re-wrapping the raw key extracted from BC's SPKI must reproduce BC's exact DER.
    let v = vectors();
    let kem_pub = unhex(&v["MLKEM_PUB_DER"]);
    let raw = spki_raw(&kem_pub, KEM_EK).unwrap();
    assert_eq!(spki_wrap(&KEM_PUB_PREFIX, raw), kem_pub);
    let dsa_pub = unhex(&v["MLDSA_PUB_DER"]);
    let raw = spki_raw(&dsa_pub, DSA_PK).unwrap();
    assert_eq!(spki_wrap(&DSA_PUB_PREFIX, raw), dsa_pub);
}

#[test]
fn kem_roundtrip() {
    let (pub_der, priv_der) = mlkem_keygen_der();
    assert_eq!(pub_der.len(), KEM_PUB_PREFIX.len() + KEM_EK);
    assert_eq!(priv_der.len(), 2498);
    let (ct, ss1) = mlkem_encaps_der(&pub_der).expect("encaps");
    let ss2 = mlkem_decaps_der(&priv_der, &ct).expect("decaps");
    assert_eq!(ss1, ss2);
}

#[test]
fn dsa_roundtrip() {
    let (pub_der, priv_der) = mldsa_keygen_der();
    assert_eq!(pub_der.len(), DSA_PUB_PREFIX.len() + DSA_PK);
    assert_eq!(priv_der.len(), 4098);
    let msg = b"hello e2ee";
    let sig = mldsa_sign_der(&priv_der, msg).expect("sign");
    assert!(mldsa_verify_der(&pub_der, msg, &sig));
    assert!(!mldsa_verify_der(&pub_der, b"other", &sig));
}

#[test]
fn our_generated_privkey_decaps() {
    // Keys we generate (BC seed+expanded layout) must be readable by our own parser.
    let (pub_der, priv_der) = mlkem_keygen_der();
    let (ct, ss1) = mlkem_encaps_der(&pub_der).unwrap();
    assert_eq!(mlkem_decaps_der(&priv_der, &ct).unwrap(), ss1);
}

// ---------- FindFamily link keys ----------

fn sha256_hex(b: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    let mut h = Sha256::new();
    h.update(b);
    h.finalize().iter().map(|x| format!("{:02x}", x)).collect()
}

/// The KAT seed shared with the browser share page (`decapsKeyFromSeed`).
const KAT_SEED: [u8; LINK_SEED] = [
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
    0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
];

/// Cross-implementation known-answer test. The browser share page implements ML-KEM
/// KeyGen independently in JS; these digests are what proves the two agree. A mismatch
/// means every new share link silently fails to decrypt, so do not "fix" this test by
/// updating the digests — fix whichever side changed.
#[test]
fn link_kat_matches_js_implementation() {
    let (pub_der, priv_der) = mlkem_link_keygen_from_seed(&KAT_SEED).unwrap();
    let ek = spki_raw(&pub_der, KEM_EK).unwrap();
    let dk = pkcs8_expanded(&priv_der, KEM_DK).unwrap();
    assert_eq!(
        sha256_hex(ek),
        "27c74a3d979e6fcbdaea5a6b87968d38f60ab4f75cfeef7d521859c54865e6b3",
        "ML-KEM encapsulation key for KAT_SEED"
    );
    assert_eq!(
        sha256_hex(dk),
        "1e4c02fb2d2c6dcd292a6291d13feee8bc1307073409c460e6bdbfc64c93f03e",
        "ML-KEM decapsulation key for KAT_SEED"
    );
}

#[test]
fn link_keygen_is_deterministic() {
    let seed = mlkem_link_seed_new();
    let (p1, s1) = mlkem_link_keygen_from_seed(&seed).unwrap();
    let (p2, s2) = mlkem_link_keygen_from_seed(&seed).unwrap();
    assert_eq!(p1, p2);
    assert_eq!(s1, s2);
    // Distinct seeds must give distinct keys.
    let (p3, _) = mlkem_link_keygen_from_seed(&mlkem_link_seed_new()).unwrap();
    assert_ne!(p1, p3);
}

#[test]
fn link_keys_use_the_same_der_encoding_as_identity_keys() {
    let (pub_der, priv_der) = mlkem_link_keygen_from_seed(&KAT_SEED).unwrap();
    assert_eq!(pub_der.len(), KEM_PUB_PREFIX.len() + KEM_EK);
    assert_eq!(priv_der.len(), 2498);
    assert!(pub_der.starts_with(&KEM_PUB_PREFIX));
    assert!(priv_der.starts_with(&KEM_PRIV_PREFIX));
}

#[test]
fn link_key_roundtrip() {
    let seed = mlkem_link_seed_new();
    let (pub_der, priv_der) = mlkem_link_keygen_from_seed(&seed).unwrap();
    let (ct, ss1) = mlkem_encaps_der(&pub_der).expect("encaps");
    let ss2 = mlkem_decaps_der(&priv_der, &ct).expect("decaps");
    assert_eq!(ss1, ss2);
    // A private key re-derived from the seed alone decapsulates the same ciphertext.
    let (_, priv_again) = mlkem_link_keygen_from_seed(&seed).unwrap();
    assert_eq!(mlkem_decaps_der(&priv_again, &ct).unwrap(), ss1);
}

#[test]
fn link_keygen_rejects_wrong_seed_lengths() {
    assert!(mlkem_link_keygen_from_seed(&[]).is_none());
    assert!(mlkem_link_keygen_from_seed(&[0u8; 31]).is_none());
    assert!(mlkem_link_keygen_from_seed(&[0u8; 33]).is_none());
    assert!(mlkem_link_keygen_from_seed(&[0u8; 64]).is_none());
}

/// The C bridge (iOS) must produce exactly the bundle the core keygen implies:
/// [4B kemPubLen][kemPub] with an empty DSA half.
#[test]
fn c_bridge_link_pub_matches_core_keygen() {
    let (expected_pub, _) = mlkem_link_keygen_from_seed(&KAT_SEED).unwrap();
    let mut bundle: *mut u8 = std::ptr::null_mut();
    let mut bundle_len: usize = 0;
    let bundle = unsafe {
        assert_eq!(
            crate::c_bridge::pqc_link_pub_from_seed(
                KAT_SEED.as_ptr(),
                KAT_SEED.len(),
                &mut bundle,
                &mut bundle_len,
            ),
            1
        );
        let v = std::slice::from_raw_parts(bundle, bundle_len).to_vec();
        crate::c_bridge::pqc_free(bundle, bundle_len);
        v
    };
    assert_eq!(bundle.len(), 4 + expected_pub.len());
    assert_eq!(
        u32::from_be_bytes([bundle[0], bundle[1], bundle[2], bundle[3]]) as usize,
        expected_pub.len()
    );
    assert_eq!(&bundle[4..], &expected_pub[..]);
    // The public bundle must still round-trip through the normal encaps path.
    let (ct, ss1) = mlkem_encaps_der(&bundle[4..]).unwrap();
    let (_, priv_der) = mlkem_link_keygen_from_seed(&KAT_SEED).unwrap();
    assert_eq!(mlkem_decaps_der(&priv_der, &ct).unwrap(), ss1);
}

#[test]
fn c_bridge_link_keygen_seed_is_usable() {
    let mut seed: *mut u8 = std::ptr::null_mut();
    let mut seed_len: usize = 0;
    let mut bundle: *mut u8 = std::ptr::null_mut();
    let mut bundle_len: usize = 0;
    unsafe {
        assert_eq!(
            crate::c_bridge::pqc_link_keygen(
                &mut seed,
                &mut seed_len,
                &mut bundle,
                &mut bundle_len,
            ),
            1
        );
        let seed_v = std::slice::from_raw_parts(seed, seed_len).to_vec();
        let bundle_v = std::slice::from_raw_parts(bundle, bundle_len).to_vec();
        crate::c_bridge::pqc_free(seed, seed_len);
        crate::c_bridge::pqc_free(bundle, bundle_len);
        assert_eq!(seed_v.len(), LINK_SEED);
        let (pub_der, _) = mlkem_link_keygen_from_seed(&seed_v).unwrap();
        assert_eq!(&bundle_v[4..], &pub_der[..]);
    }
}
