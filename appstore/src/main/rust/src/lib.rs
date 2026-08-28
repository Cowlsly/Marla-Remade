//! Raw ed25519 verification for `:appstore`, replacing Bouncy Castle.
//!
//! Scope is deliberately narrow: the signify wire format (base64 blobs, the
//! `2-byte algo id || 8-byte key id || key/signature` framing, and the
//! `untrusted comment:` line) is parsed in Kotlin by `AccrescentSignify` and
//! stays there. This crate receives an already-extracted 32-byte public key,
//! the exact signed bytes, and a 64-byte signature.
//!
//! Everything fails closed by returning `false`: this is the whole trust anchor
//! for the Accrescent app source, and a panic across the JNI boundary would
//! abort the process rather than reject the signature.

use ed25519_dalek::{Signature, VerifyingKey};

/// Length of a raw ed25519 public key.
const PUBKEY_LEN: usize = 32;

/// Length of a raw ed25519 signature.
const SIG_LEN: usize = 64;

/// True only if `sig` is a valid ed25519 signature over `msg` by `pubkey`.
///
/// Returns `false` — never panics — for a wrong-length key or signature, a
/// public key that is not a canonically-encoded curve point, or a bad
/// signature.
///
/// Uses `verify_strict`, which additionally rejects small-order public keys and
/// non-canonically-encoded `R`/`A` values. Bouncy Castle's `Ed25519Signer`
/// accepted those, so this is strictly narrower than what it replaced: a real
/// signify key cannot be small-order, and for a trust anchor the stricter check
/// is the safer direction to differ in.
pub fn verify(pubkey: &[u8], msg: &[u8], sig: &[u8]) -> bool {
    let pubkey: [u8; PUBKEY_LEN] = match pubkey.try_into() {
        Ok(k) => k,
        Err(_) => return false,
    };
    let sig: [u8; SIG_LEN] = match sig.try_into() {
        Ok(s) => s,
        Err(_) => return false,
    };
    let key = match VerifyingKey::from_bytes(&pubkey) {
        Ok(k) => k,
        Err(_) => return false,
    };
    key.verify_strict(msg, &Signature::from_bytes(&sig)).is_ok()
}

mod jni_bridge;

#[cfg(test)]
mod tests;
