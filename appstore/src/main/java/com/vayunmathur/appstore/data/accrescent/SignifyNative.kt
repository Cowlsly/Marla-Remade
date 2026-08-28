// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
package com.vayunmathur.appstore.data.accrescent

/**
 * JNI surface for the native ed25519 verifier (libappstore_signify.so), backed
 * by the Rust `ed25519-dalek` crate. Replaces Bouncy Castle, whose only use
 * anywhere in the repo was this one signature check.
 *
 * Takes already-extracted raw bytes: signify's base64 and framing are parsed by
 * [AccrescentSignify], which stays in Kotlin. Returns false for a bad signature
 * and for any malformed input, so the caller's fail-closed contract holds
 * without this ever throwing.
 */
internal object SignifyNative {
    external fun nativeVerify(pubkey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    init {
        System.loadLibrary("appstore_signify")
    }
}
