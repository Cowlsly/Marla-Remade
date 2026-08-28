//! Interop test against the real, live Accrescent trust anchor.
//!
//! `testvectors/` holds the actual `repodata.1.json` and `repodata.1.json.sig`
//! served by `repo.accrescent.app` — files the Bouncy Castle path this crate
//! replaces accepted in production. They are checked in byte-exact (see the
//! `-text` entry in `.gitattributes`) because the signature covers the raw file
//! bytes, so a single EOL conversion would invalidate it.
//!
//! The base64 + offset extraction here mirrors `AccrescentSignify.kt` rather
//! than reusing it, so the vectors stay inspectable as the files they are.

use super::*;
use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;

/// The exact bytes that were signed.
const REPODATA: &[u8] = include_bytes!("../testvectors/repodata.1.json");

/// The full `.sig` file: an `untrusted comment:` line plus the base64 blob.
const SIG_FILE: &str = include_str!("../testvectors/repodata.1.json.sig");

/// Must stay equal to `AccrescentRepo.REPODATA_PUBKEY`.
const PUBKEY_B64: &str = "RWT8aZ/NdUmXCPqQ0we7UyCe34q1xRfncBFVK5dI3ok9BkL1bFF3mgh3";

/// `2-byte algo id || 8-byte key id || 32-byte ed25519 public key`.
fn pubkey() -> Vec<u8> {
    STANDARD.decode(PUBKEY_B64).unwrap()[10..42].to_vec()
}

/// `2-byte algo id || 8-byte key id || 64-byte ed25519 signature`.
fn signature() -> Vec<u8> {
    let blob = SIG_FILE
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with("untrusted comment:"))
        .last()
        .unwrap();
    STANDARD.decode(blob).unwrap()[10..74].to_vec()
}

#[test]
fn verifies_real_accrescent_repodata_signature() {
    assert!(
        verify(&pubkey(), REPODATA, &signature()),
        "must accept the signature Bouncy Castle accepted in production"
    );
}

#[test]
fn rejects_tampered_message() {
    let mut tampered = REPODATA.to_vec();
    // Byte 3 is inside the `timestamp` value, the anti-rollback field.
    tampered[3] ^= 1;
    assert!(!verify(&pubkey(), &tampered, &signature()));
}

#[test]
fn rejects_tampered_signature() {
    let mut sig = signature();
    sig[0] ^= 1;
    assert!(!verify(&pubkey(), REPODATA, &sig));
}

#[test]
fn rejects_wrong_public_key() {
    let mut pk = pubkey();
    pk[0] ^= 1;
    assert!(!verify(&pk, REPODATA, &signature()));
}

#[test]
fn rejects_truncated_inputs() {
    let (pk, sig) = (pubkey(), signature());
    assert!(!verify(&pk[..31], REPODATA, &sig));
    assert!(!verify(&pk, REPODATA, &sig[..63]));
    assert!(!verify(&[], REPODATA, &sig));
    assert!(!verify(&pk, REPODATA, &[]));
}
