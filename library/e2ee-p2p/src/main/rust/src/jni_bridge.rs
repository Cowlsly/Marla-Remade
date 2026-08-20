//! JNI surface for `com.vayunmathur.e2ee.PqcNative`. All keys cross as DER bytes.

use crate::*;
use jni::objects::{JByteArray, JClass, JObject};
use jni::sys::{jboolean, jbyteArray, jobjectArray};
use jni::JNIEnv;

fn bytes_in(env: &mut JNIEnv, arr: &JByteArray) -> Option<Vec<u8>> {
    env.convert_byte_array(arr).ok()
}

fn bytes_out(env: &JNIEnv, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Return a Java `byte[][]` of exactly two elements, or null.
fn pair_out(env: &mut JNIEnv, a: &[u8], b: &[u8]) -> jobjectArray {
    let cls = match env.find_class("[B") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let arr = match env.new_object_array(2, &cls, JObject::null()) {
        Ok(a) => a,
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

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemKeygen<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jobjectArray {
    let (pub_der, priv_der) = mlkem_keygen_der();
    pair_out(&mut env, &pub_der, &priv_der)
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemLinkKeygen<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jobjectArray {
    let seed = mlkem_link_seed_new();
    match mlkem_link_keygen_from_seed(&seed) {
        Some((pub_der, _)) => pair_out(&mut env, &seed, &pub_der),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemLinkPubFromSeed<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    seed: JByteArray<'l>,
) -> jbyteArray {
    let s = match bytes_in(&mut env, &seed) {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    match mlkem_link_keygen_from_seed(&s) {
        Some((pub_der, _)) => bytes_out(&env, &pub_der),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMldsaKeygen<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jobjectArray {
    let (pub_der, priv_der) = mldsa_keygen_der();
    pair_out(&mut env, &pub_der, &priv_der)
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemEncaps<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    pub_der: JByteArray<'l>,
) -> jobjectArray {
    let der = match bytes_in(&mut env, &pub_der) {
        Some(d) => d,
        None => return std::ptr::null_mut(),
    };
    match mlkem_encaps_der(&der) {
        Some((ct, ss)) => pair_out(&mut env, &ct, &ss),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemDecaps<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    priv_der: JByteArray<'l>,
    ct: JByteArray<'l>,
) -> jbyteArray {
    let (der, ctb) = match (bytes_in(&mut env, &priv_der), bytes_in(&mut env, &ct)) {
        (Some(a), Some(b)) => (a, b),
        _ => return std::ptr::null_mut(),
    };
    match mlkem_decaps_der(&der, &ctb) {
        Some(ss) => bytes_out(&env, &ss),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMldsaSign<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    priv_der: JByteArray<'l>,
    msg: JByteArray<'l>,
) -> jbyteArray {
    let (der, m) = match (bytes_in(&mut env, &priv_der), bytes_in(&mut env, &msg)) {
        (Some(a), Some(b)) => (a, b),
        _ => return std::ptr::null_mut(),
    };
    match mldsa_sign_der(&der, &m) {
        Some(sig) => bytes_out(&env, &sig),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMldsaVerify<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    pub_der: JByteArray<'l>,
    msg: JByteArray<'l>,
    sig: JByteArray<'l>,
) -> jboolean {
    let (der, m, s) = match (
        bytes_in(&mut env, &pub_der),
        bytes_in(&mut env, &msg),
        bytes_in(&mut env, &sig),
    ) {
        (Some(a), Some(b), Some(c)) => (a, b, c),
        _ => return 0,
    };
    if mldsa_verify_der(&der, &m, &s) {
        1
    } else {
        0
    }
}
