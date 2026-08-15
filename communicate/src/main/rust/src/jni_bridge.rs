//! JNI surface for `com.vayunmathur.messages.whatsapp.e2e.RustWhatsAppCrypto`.
//! Kotlin owns persistence (Room) and passes session/sender-key records as opaque bytes.

use crate::crypto::{self, CryptoError};
use crate::group;
use crate::session::{self, PreKeyBundle};
use crate::signal;
use crate::wire::{PreKeySignalMessage, SenderKeyDistributionMessage, SignalMessage};
use crate::OsRng;
use jni::objects::{JByteArray, JClass, JObject};
use jni::sys::{jboolean, jbyteArray, jint, jobjectArray};
use jni::JNIEnv;
use rand_core::RngCore;
use std::panic::{catch_unwind, AssertUnwindSafe};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn bytes_in<'a>(env: &mut JNIEnv<'a>, arr: &JByteArray<'a>) -> Option<Vec<u8>> {
    if arr.is_null() {
        return None;
    }
    env.convert_byte_array(arr).ok()
}

fn bytes_out<'a>(env: &mut JNIEnv<'a>, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn pair_out<'a>(env: &mut JNIEnv<'a>, a: &[u8], b: &[u8]) -> jobjectArray {
    let cls = match env.find_class("[B") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let arr = match env.new_object_array(2, &cls, JObject::null()) {
        Ok(o) => o,
        Err(_) => return std::ptr::null_mut(),
    };
    let ja = match env.byte_array_from_slice(a) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };
    let jb = match env.byte_array_from_slice(b) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };
    if env.set_object_array_element(&arr, 0, ja).is_err()
        || env.set_object_array_element(&arr, 1, jb).is_err()
    {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

fn triple_out<'a>(env: &mut JNIEnv<'a>, a: &[u8], b: &[u8], c: &[u8]) -> jobjectArray {
    let cls = match env.find_class("[B") {
        Ok(cl) => cl,
        Err(_) => return std::ptr::null_mut(),
    };
    let arr = match env.new_object_array(3, &cls, JObject::null()) {
        Ok(o) => o,
        Err(_) => return std::ptr::null_mut(),
    };
    let ja = match env.byte_array_from_slice(a) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };
    let jb = match env.byte_array_from_slice(b) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };
    let jc = match env.byte_array_from_slice(c) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };
    if env.set_object_array_element(&arr, 0, ja).is_err()
        || env.set_object_array_element(&arr, 1, jb).is_err()
        || env.set_object_array_element(&arr, 2, jc).is_err()
    {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

fn throw_runtime<'a>(env: &mut JNIEnv<'a>, msg: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

fn parse_32(label: &str, data: &[u8]) -> Result<[u8; 32], String> {
    if data.len() != 32 {
        return Err(format!("{label} must be 32 bytes, got {}", data.len()));
    }
    let mut out = [0u8; 32];
    out.copy_from_slice(data);
    Ok(out)
}

fn parse_64(label: &str, data: &[u8]) -> Result<[u8; 64], String> {
    if data.len() != 64 {
        return Err(format!("{label} must be 64 bytes, got {}", data.len()));
    }
    let mut out = [0u8; 64];
    out.copy_from_slice(data);
    Ok(out)
}

// ---------------------------------------------------------------------------
// Inner implementations (panic-safe)
// ---------------------------------------------------------------------------

fn generate_keypair_inner<'a>(env: &mut JNIEnv<'a>) -> jbyteArray {
    let mut rng = OsRng;
    let (priv_b, pub_b) = crypto::generate_key_pair(&mut rng);
    let mut out = Vec::with_capacity(64);
    out.extend_from_slice(&priv_b);
    out.extend_from_slice(&pub_b);
    bytes_out(env, &out)
}

fn public_from_private_inner<'a>(
    env: &mut JNIEnv<'a>,
    private: JByteArray<'a>,
) -> jbyteArray {
    let priv_bytes = match bytes_in(env, &private) {
        Some(b) => b,
        None => {
            throw_runtime(env, "private key bytes null");
            return std::ptr::null_mut();
        }
    };
    let priv_32 = match parse_32("private", &priv_bytes) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };
    let pub_32 = crypto::public_from_private(&priv_32);
    bytes_out(env, &pub_32)
}

fn x25519_agreement_inner<'a>(
    env: &mut JNIEnv<'a>,
    private: JByteArray<'a>,
    public: JByteArray<'a>,
) -> jbyteArray {
    let (priv_bytes, pub_bytes) = match (bytes_in(env, &private), bytes_in(env, &public)) {
        (Some(a), Some(b)) => (a, b),
        _ => {
            throw_runtime(env, "private or public null");
            return std::ptr::null_mut();
        }
    };
    let p32 = match parse_32("private", &priv_bytes) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };
    let pub32 = match parse_32("public", &pub_bytes) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };
    let shared = crypto::agreement(&p32, &pub32);
    bytes_out(env, &shared)
}

fn sign_inner<'a>(
    env: &mut JNIEnv<'a>,
    private: JByteArray<'a>,
    message: JByteArray<'a>,
) -> jbyteArray {
    let (priv_bytes, msg_bytes) = match (bytes_in(env, &private), bytes_in(env, &message)) {
        (Some(a), Some(b)) => (a, b),
        _ => {
            throw_runtime(env, "private or message null");
            return std::ptr::null_mut();
        }
    };
    let p32 = match parse_32("private", &priv_bytes) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };
    let mut rng = OsRng;
    let mut random = [0u8; 64];
    rng.fill_bytes(&mut random);
    let sig = crypto::sign(&p32, &msg_bytes, &random);
    bytes_out(env, &sig)
}

fn verify_inner<'a>(
    env: &mut JNIEnv<'a>,
    public: JByteArray<'a>,
    message: JByteArray<'a>,
    signature: JByteArray<'a>,
) -> jboolean {
    let (pub_b, msg_b, sig_b) = match (
        bytes_in(env, &public),
        bytes_in(env, &message),
        bytes_in(env, &signature),
    ) {
        (Some(a), Some(b), Some(c)) => (a, b, c),
        _ => return 0,
    };
    let pub32 = match parse_32("public", &pub_b) {
        Ok(v) => v,
        Err(_) => return 0,
    };
    let sig64 = match parse_64("signature", &sig_b) {
        Ok(v) => v,
        Err(_) => return 0,
    };
    if crypto::verify(&pub32, &msg_b, &sig64) {
        1
    } else {
        0
    }
}

#[allow(clippy::too_many_arguments)]
fn process_prekey_bundle_inner<'a>(
    env: &mut JNIEnv<'a>,
    local_priv: JByteArray<'a>,
    local_pub: JByteArray<'a>,
    local_reg_id: jint,
    reg_id: jint,
    pre_key_id: jint,
    pre_key_public: JByteArray<'a>,
    signed_pre_key_id: jint,
    signed_pre_key_public: JByteArray<'a>,
    signed_pre_key_sig: JByteArray<'a>,
    identity_key: JByteArray<'a>,
) -> jbyteArray {
    let local_priv_b = match bytes_in(env, &local_priv) {
        Some(b) => b,
        None => {
            throw_runtime(env, "local_identity_private null");
            return std::ptr::null_mut();
        }
    };
    let local_pub_b = match bytes_in(env, &local_pub) {
        Some(b) => b,
        None => {
            throw_runtime(env, "local_identity_public null");
            return std::ptr::null_mut();
        }
    };
    let spk_pub_b = match bytes_in(env, &signed_pre_key_public) {
        Some(b) => b,
        None => {
            throw_runtime(env, "signed_pre_key_public null");
            return std::ptr::null_mut();
        }
    };
    let spk_sig_b = match bytes_in(env, &signed_pre_key_sig) {
        Some(b) => b,
        None => {
            throw_runtime(env, "signed_pre_key_signature null");
            return std::ptr::null_mut();
        }
    };
    let id_key_b = match bytes_in(env, &identity_key) {
        Some(b) => b,
        None => {
            throw_runtime(env, "identity_key null");
            return std::ptr::null_mut();
        }
    };

    let local_priv_32 = match parse_32("local_identity_private", &local_priv_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };
    let local_pub_32 = match parse_32("local_identity_public", &local_pub_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };
    let spk_pub_32 = match parse_32("signed_pre_key_public", &spk_pub_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };
    let id_key_32 = match parse_32("identity_key", &id_key_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };

    let (pre_key_id_opt, pre_key_pub_opt) = if pre_key_public.is_null() || pre_key_id < 0 {
        (None, None)
    } else {
        let pk_pub_b = match bytes_in(env, &pre_key_public) {
            Some(b) => b,
            None => {
                throw_runtime(env, "pre_key_public conversion failed");
                return std::ptr::null_mut();
            }
        };
        let pk_pub_32 = match parse_32("pre_key_public", &pk_pub_b) {
            Ok(v) => v,
            Err(e) => {
                throw_runtime(env, &e);
                return std::ptr::null_mut();
            }
        };
        (Some(pre_key_id as u32), Some(pk_pub_32))
    };

    let bundle = PreKeyBundle {
        registration_id: reg_id as u32,
        pre_key_id: pre_key_id_opt,
        pre_key_public: pre_key_pub_opt,
        signed_pre_key_id: signed_pre_key_id as u32,
        signed_pre_key_public: spk_pub_32,
        signed_pre_key_signature: spk_sig_b,
        identity_key: id_key_32,
    };

    let mut rng = OsRng;
    let state = match session::process_pre_key_bundle(
        &mut rng,
        &bundle,
        &local_priv_32,
        &local_pub_32,
        local_reg_id as u32,
    ) {
        Ok(s) => s,
        Err(CryptoError(msg)) => {
            throw_runtime(env, msg);
            return std::ptr::null_mut();
        }
    };
    bytes_out(env, &state.serialize())
}

fn encrypt_inner<'a>(
    env: &mut JNIEnv<'a>,
    session_bytes: JByteArray<'a>,
    plaintext: JByteArray<'a>,
) -> jobjectArray {
    let sess_b = match bytes_in(env, &session_bytes) {
        Some(b) => b,
        None => {
            throw_runtime(env, "session_bytes null");
            return std::ptr::null_mut();
        }
    };
    let pt_b = match bytes_in(env, &plaintext) {
        Some(b) => b,
        None => {
            throw_runtime(env, "plaintext null");
            return std::ptr::null_mut();
        }
    };
    let mut state = match session::SessionState::deserialize(&sess_b) {
        Ok(s) => s,
        Err(CryptoError(msg)) => {
            throw_runtime(env, msg);
            return std::ptr::null_mut();
        }
    };
    let encrypted = match session::encrypt(&mut state, &pt_b) {
        Ok(e) => e,
        Err(CryptoError(msg)) => {
            throw_runtime(env, msg);
            return std::ptr::null_mut();
        }
    };
    let new_sess = state.serialize();
    let is_pre = vec![if encrypted.is_pre_key { 1u8 } else { 0u8 }];
    triple_out(env, &is_pre, &encrypted.body, &new_sess)
}

fn decrypt_message_inner<'a>(
    env: &mut JNIEnv<'a>,
    session_bytes: JByteArray<'a>,
    ciphertext: JByteArray<'a>,
) -> jobjectArray {
    let sess_b = match bytes_in(env, &session_bytes) {
        Some(b) => b,
        None => {
            throw_runtime(env, "session_bytes null");
            return std::ptr::null_mut();
        }
    };
    let ct_b = match bytes_in(env, &ciphertext) {
        Some(b) => b,
        None => {
            throw_runtime(env, "ciphertext null");
            return std::ptr::null_mut();
        }
    };
    let mut state = match session::SessionState::deserialize(&sess_b) {
        Ok(s) => s,
        Err(CryptoError(msg)) => {
            throw_runtime(env, msg);
            return std::ptr::null_mut();
        }
    };
    let msg = match SignalMessage::parse(&ct_b) {
        Ok(m) => m,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    let mut rng = OsRng;
    let pt = match session::decrypt(&mut rng, &mut state, &msg) {
        Ok(p) => p,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    let new_sess = state.serialize();
    pair_out(env, &pt, &new_sess)
}

fn decrypt_prekey_inner<'a>(
    env: &mut JNIEnv<'a>,
    local_priv: JByteArray<'a>,
    local_pub: JByteArray<'a>,
    signed_pre_priv: JByteArray<'a>,
    one_time_priv: JByteArray<'a>,
    prekey_bytes: JByteArray<'a>,
) -> jobjectArray {
    let local_priv_b = match bytes_in(env, &local_priv) {
        Some(b) => b,
        None => {
            throw_runtime(env, "local_identity_private null");
            return std::ptr::null_mut();
        }
    };
    let local_pub_b = match bytes_in(env, &local_pub) {
        Some(b) => b,
        None => {
            throw_runtime(env, "local_identity_public null");
            return std::ptr::null_mut();
        }
    };
    let signed_priv_b = match bytes_in(env, &signed_pre_priv) {
        Some(b) => b,
        None => {
            throw_runtime(env, "signed_pre_key_private null");
            return std::ptr::null_mut();
        }
    };
    let pkmsg_b = match bytes_in(env, &prekey_bytes) {
        Some(b) => b,
        None => {
            throw_runtime(env, "prekey_bytes null");
            return std::ptr::null_mut();
        }
    };

    let local_priv_32 = match parse_32("local_identity_private", &local_priv_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };
    let local_pub_32 = match parse_32("local_identity_public", &local_pub_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };
    let signed_priv_32 = match parse_32("signed_pre_key_private", &signed_priv_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };

    let one_time_opt: Option<[u8; 32]> = if one_time_priv.is_null() {
        None
    } else {
        match bytes_in(env, &one_time_priv) {
            Some(b) => {
                if b.is_empty() {
                    None
                } else {
                    match parse_32("one_time_private", &b) {
                        Ok(v) => Some(v),
                        Err(e) => {
                            throw_runtime(env, &e);
                            return std::ptr::null_mut();
                        }
                    }
                }
            }
            None => None,
        }
    };

    let prekey_msg = match PreKeySignalMessage::parse(&pkmsg_b) {
        Ok(m) => m,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };

    let mut state = match session::process_pre_key_message(
        &prekey_msg,
        &local_priv_32,
        &local_pub_32,
        &signed_priv_32,
        one_time_opt.as_ref(),
    ) {
        Ok(s) => s,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };

    let inner_msg = match SignalMessage::parse(&prekey_msg.message) {
        Ok(m) => m,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };

    let mut rng = OsRng;
    let pt = match session::decrypt(&mut rng, &mut state, &inner_msg) {
        Ok(p) => p,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };

    let new_sess = state.serialize();
    pair_out(env, &pt, &new_sess)
}

// Group

fn create_sender_key_inner<'a>(env: &mut JNIEnv<'a>) -> jobjectArray {
    let mut rng = OsRng;
    let (state, skdm) = group::create(&mut rng);
    let state_bytes = state.serialize();
    let skdm_bytes = skdm.serialize();
    pair_out(env, &state_bytes, &skdm_bytes)
}

fn process_sender_key_inner<'a>(env: &mut JNIEnv<'a>, skdm_bytes: JByteArray<'a>) -> jbyteArray {
    let skdm_b = match bytes_in(env, &skdm_bytes) {
        Some(b) => b,
        None => {
            throw_runtime(env, "skdm_bytes null");
            return std::ptr::null_mut();
        }
    };
    let skdm = match SenderKeyDistributionMessage::parse(&skdm_b) {
        Ok(s) => s,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    let state = match group::process(&skdm) {
        Ok(s) => s,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    bytes_out(env, &state.serialize())
}

fn encrypt_group_inner<'a>(
    env: &mut JNIEnv<'a>,
    state_bytes: JByteArray<'a>,
    plaintext: JByteArray<'a>,
) -> jobjectArray {
    let sess_b = match bytes_in(env, &state_bytes) {
        Some(b) => b,
        None => {
            throw_runtime(env, "state_bytes null");
            return std::ptr::null_mut();
        }
    };
    let pt_b = match bytes_in(env, &plaintext) {
        Some(b) => b,
        None => {
            throw_runtime(env, "plaintext null");
            return std::ptr::null_mut();
        }
    };
    let mut state = match group::SenderKeyState::deserialize(&sess_b) {
        Ok(s) => s,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    let mut rng = OsRng;
    let ct = match group::encrypt(&mut rng, &mut state, &pt_b) {
        Ok(c) => c,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    let new_state = state.serialize();
    pair_out(env, &ct, &new_state)
}

fn decrypt_group_inner<'a>(
    env: &mut JNIEnv<'a>,
    state_bytes: JByteArray<'a>,
    ciphertext: JByteArray<'a>,
) -> jobjectArray {
    let sess_b = match bytes_in(env, &state_bytes) {
        Some(b) => b,
        None => {
            throw_runtime(env, "state_bytes null");
            return std::ptr::null_mut();
        }
    };
    let ct_b = match bytes_in(env, &ciphertext) {
        Some(b) => b,
        None => {
            throw_runtime(env, "ciphertext null");
            return std::ptr::null_mut();
        }
    };
    let mut state = match group::SenderKeyState::deserialize(&sess_b) {
        Ok(s) => s,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    let pt = match group::decrypt(&mut state, &ct_b) {
        Ok(p) => p,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    let new_state = state.serialize();
    pair_out(env, &pt, &new_state)
}

// ---------------------------------------------------------------------------
// JNI exports — wrapped in catch_unwind
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_generateKeyPair<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| generate_keypair_inner(&mut env))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in generateKeyPair",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_publicFromPrivate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    private: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| public_from_private_inner(&mut env, private))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in publicFromPrivate",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_x25519Agreement<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    private: JByteArray<'local>,
    public: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| x25519_agreement_inner(&mut env, private, public))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in x25519Agreement",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_sign<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    private: JByteArray<'local>,
    message: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| sign_inner(&mut env, private, message))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new("java/lang/RuntimeException", "Native panic in sign");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_verify<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    public: JByteArray<'local>,
    message: JByteArray<'local>,
    signature: JByteArray<'local>,
) -> jboolean {
    match catch_unwind(AssertUnwindSafe(|| verify_inner(&mut env, public, message, signature))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_processPreKeyBundle<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    local_private: JByteArray<'local>,
    local_public: JByteArray<'local>,
    local_reg_id: jint,
    reg_id: jint,
    pre_key_id: jint,
    pre_key_public: JByteArray<'local>,
    signed_pre_key_id: jint,
    signed_pre_key_public: JByteArray<'local>,
    signed_pre_key_sig: JByteArray<'local>,
    identity_key: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        process_prekey_bundle_inner(
            &mut env,
            local_private,
            local_public,
            local_reg_id,
            reg_id,
            pre_key_id,
            pre_key_public,
            signed_pre_key_id,
            signed_pre_key_public,
            signed_pre_key_sig,
            identity_key,
        )
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in processPreKeyBundle",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_encrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: JByteArray<'local>,
    plaintext: JByteArray<'local>,
) -> jobjectArray {
    match catch_unwind(AssertUnwindSafe(|| encrypt_inner(&mut env, session, plaintext))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new("java/lang/RuntimeException", "Native panic in encrypt");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_decryptMessage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: JByteArray<'local>,
    ciphertext: JByteArray<'local>,
) -> jobjectArray {
    match catch_unwind(AssertUnwindSafe(|| decrypt_message_inner(&mut env, session, ciphertext))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in decryptMessage",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_decryptPreKeyMessage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    local_private: JByteArray<'local>,
    local_public: JByteArray<'local>,
    signed_private: JByteArray<'local>,
    one_time_private: JByteArray<'local>,
    prekey_bytes: JByteArray<'local>,
) -> jobjectArray {
    match catch_unwind(AssertUnwindSafe(|| {
        decrypt_prekey_inner(
            &mut env,
            local_private,
            local_public,
            signed_private,
            one_time_private,
            prekey_bytes,
        )
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in decryptPreKeyMessage",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_createSenderKey<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jobjectArray {
    match catch_unwind(AssertUnwindSafe(|| create_sender_key_inner(&mut env))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in createSenderKey",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_processSenderKey<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    skdm: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| process_sender_key_inner(&mut env, skdm))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in processSenderKey",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_encryptGroup<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    state: JByteArray<'local>,
    plaintext: JByteArray<'local>,
) -> jobjectArray {
    match catch_unwind(AssertUnwindSafe(|| encrypt_group_inner(&mut env, state, plaintext))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in encryptGroup",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_decryptGroup<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    state: JByteArray<'local>,
    ciphertext: JByteArray<'local>,
) -> jobjectArray {
    match catch_unwind(AssertUnwindSafe(|| decrypt_group_inner(&mut env, state, ciphertext))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in decryptGroup",
            );
            std::ptr::null_mut()
        }
    }
}

// -- Signal sealed sender (same Rust primitives, Signal JNI class) --

fn sealed_sender_encrypt_inner<'a>(
    env: &mut JNIEnv<'a>,
    plaintext: JByteArray<'a>,
    recipient_aci: jni::objects::JString<'a>,
) -> jbyteArray {
    let pt = match bytes_in(env, &plaintext) {
        Some(b) => b,
        None => { throw_runtime(env, "plaintext null"); return std::ptr::null_mut(); }
    };
    let aci: String = match env.get_string(&recipient_aci) {
        Ok(s) => s.into(),
        Err(_) => { throw_runtime(env, "recipientAci null"); return std::ptr::null_mut(); }
    };
    let sealed = signal::sealed_sender_encrypt(&pt, &aci);
    bytes_out(env, &sealed)
}

fn sealed_sender_decrypt_inner<'a>(
    env: &mut JNIEnv<'a>,
    ciphertext: JByteArray<'a>,
) -> jbyteArray {
    let ct = match bytes_in(env, &ciphertext) {
        Some(b) => b,
        None => { throw_runtime(env, "ciphertext null"); return std::ptr::null_mut(); }
    };
    // Try empty-key path (Kotlin supplies ACI out-of-band; Rust stub uses empty for round-trip tests)
    let pt = match signal::sealed_sender_decrypt_any(&ct) {
        Ok(p) => p,
        Err(CryptoError(e)) => { throw_runtime(env, e); return std::ptr::null_mut(); }
    };
    bytes_out(env, &pt)
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_sealedSenderEncrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    plaintext: JByteArray<'local>,
    recipient_aci: jni::objects::JString<'local>,
    _recipient_device_id: jint,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| sealed_sender_encrypt_inner(&mut env, plaintext, recipient_aci))) {
        Ok(v) => v, Err(_) => { let _ = env.exception_clear(); let _ = env.throw_new("java/lang/RuntimeException", "Native panic in sealedSenderEncrypt"); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_sealedSenderDecrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ciphertext: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| sealed_sender_decrypt_inner(&mut env, ciphertext))) {
        Ok(v) => v, Err(_) => { let _ = env.exception_clear(); let _ = env.throw_new("java/lang/RuntimeException", "Native panic in sealedSenderDecrypt"); std::ptr::null_mut() }
    }
}

// Also expose the same symbols under RustWhatsAppCrypto so a single .so serves both clients
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_sealedSenderEncrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    plaintext: JByteArray<'local>,
    recipient_aci: jni::objects::JString<'local>,
    recipient_device_id: jint,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| sealed_sender_encrypt_inner(&mut env, plaintext, recipient_aci))) {
        Ok(v) => v, Err(_) => { let _ = env.exception_clear(); let _ = env.throw_new("java/lang/RuntimeException", "Native panic in sealedSenderEncrypt"); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_sealedSenderDecrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ciphertext: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| sealed_sender_decrypt_inner(&mut env, ciphertext))) {
        Ok(v) => v, Err(_) => { let _ = env.exception_clear(); let _ = env.throw_new("java/lang/RuntimeException", "Native panic in sealedSenderDecrypt"); std::ptr::null_mut() }
    }
}

// Signal PQXDH Kyber bridge — real PQXDH goes via libsignal Java SessionBuilder; Rust stub keeps build green
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_processPreKeyBundle<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    local_private: JByteArray<'local>,
    local_public: JByteArray<'local>,
    local_reg_id: jint,
    reg_id: jint,
    pre_key_id: jint,
    pre_key_public: JByteArray<'local>,
    signed_pre_key_id: jint,
    signed_pre_key_public: JByteArray<'local>,
    signed_pre_key_sig: JByteArray<'local>,
    identity_key: JByteArray<'local>,
    _kyber_pre_key_id: jint,
    _kyber_pre_key_public: JByteArray<'local>,
    _kyber_pre_key_signature: JByteArray<'local>,
    _kyber_ciphertext: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        process_prekey_bundle_inner(&mut env, local_private, local_public, local_reg_id, reg_id, pre_key_id, pre_key_public, signed_pre_key_id, signed_pre_key_public, signed_pre_key_sig, identity_key)
    })) { Ok(v) => v, Err(_) => { let _ = env.exception_clear(); let _ = env.throw_new("java/lang/RuntimeException", "Native panic in RustSignalCrypto.processPreKeyBundle"); std::ptr::null_mut() } }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_markKyberPreKeyUsed<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    kyber_id: jint,
    signed_ec_id: jint,
    base_key: JByteArray<'local>,
) -> jboolean {
    match catch_unwind(AssertUnwindSafe(|| {
        let b = match bytes_in(&mut env, &base_key) { Some(x) => x, None => { let _ = env.throw_new("java/lang/RuntimeException","baseKey null"); return 0 as jboolean; } };
        if crate::signal::mark_kyber_pre_key_used(kyber_id, signed_ec_id, &b) { 1 } else { 0 }
    })) { Ok(v) => v, Err(_) => { let _ = env.exception_clear(); 0 } }
}
