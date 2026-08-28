//! JNI surface for `com.vayunmathur.appstore.data.accrescent.SignifyNative`.
use crate::verify;
use jni::objects::{JByteArray, JClass};
use jni::sys::jboolean;
use jni::JNIEnv;

fn bytes_in(env: &mut JNIEnv, arr: &JByteArray) -> Option<Vec<u8>> {
    env.convert_byte_array(arr).ok()
}

/// Returns `1` only for a valid signature; `0` for everything else, including a
/// failed array read. Never throws — the Kotlin caller treats `false` as "not
/// verified", so a thrown exception would only turn a clean rejection into a
/// crash.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_appstore_data_accrescent_SignifyNative_nativeVerify<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    pubkey: JByteArray<'l>,
    msg: JByteArray<'l>,
    sig: JByteArray<'l>,
) -> jboolean {
    let (pk, m, s) = match (
        bytes_in(&mut env, &pubkey),
        bytes_in(&mut env, &msg),
        bytes_in(&mut env, &sig),
    ) {
        (Some(a), Some(b), Some(c)) => (a, b, c),
        _ => return 0,
    };
    if verify(&pk, &m, &s) {
        1
    } else {
        0
    }
}
