//! Nearby Share / Quick Share protocol core (no Play Services).
//!
//! Owns: BetoCore UKEY2 D2D handshake + AES-256-GCM-SIV secure messages,
//! Nearby Presence advertisement build/parse, and the Introduction / Transfer
//! payload state machine. Transport (NSD/BLE discovery, TCP socket) stays in Kotlin.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

mod frame;
mod payload;
mod presence;
mod session;

use session::{Session, State};

// ---------------------------------------------------------------------------
// Global session registry
// ---------------------------------------------------------------------------

static SESSIONS: OnceLock<Mutex<HashMap<i64, Session>>> = OnceLock::new();
static NEXT_ID: OnceLock<Mutex<i64>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<i64, Session>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn next_id() -> &'static Mutex<i64> {
    NEXT_ID.get_or_init(|| Mutex::new(1))
}

fn state_to_jint(s: State) -> jint {
    s as jint
}

// ---------------------------------------------------------------------------
// JNI helpers
// ---------------------------------------------------------------------------

fn bytes_in(env: &mut JNIEnv, arr: &JByteArray) -> Option<Vec<u8>> {
    env.convert_byte_array(arr).ok()
}
fn bytes_out(env: &JNIEnv, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
fn str_in(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|j| j.to_string_lossy().into_owned())
}

// ---------------------------------------------------------------------------
// JNI API — must match share/PROTOCOL_CONTRACT.md and ShareNative.kt.
// Class: com.vayunmathur.share.protocol.ShareNative
// Naming: Java_com_vayunmathur_share_protocol_ShareNative_<method>
// ---------------------------------------------------------------------------

/// long nativeInit(String localName, byte[] localEndpointInfo) -> sessionHandle
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeInit<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jLocalName: JString<'l>,
    jLocalEndpointInfo: JByteArray<'l>,
) -> jlong {
    let local_name = str_in(&mut env, &jLocalName).unwrap_or_else(|| "Share".to_string());
    let local_info = bytes_in(&mut env, &jLocalEndpointInfo).unwrap_or_default();
    let id = {
        let mut g = next_id().lock().unwrap();
        let id = *g;
        *g += 1;
        id
    };
    let sess = Session::new(local_name, local_info);
    sessions().lock().unwrap().insert(id, sess);
    id
}

/// int nativeFeedInbound(long handle, byte[] bytes) -> 0 ok, <0 error
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeFeedInbound<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    jBytes: JByteArray<'l>,
) -> jint {
    let bytes = match bytes_in(&mut env, &jBytes) {
        Some(b) => b,
        None => return -1,
    };
    let mut map = sessions().lock().unwrap();
    let sess = match map.get_mut(&handle) {
        Some(s) => s,
        None => return -2,
    };
    match sess.feed_inbound(&bytes) {
        0 => 0,
        e => e as jint,
    }
}

/// byte[] nativeDrainOutbound(long handle) -> bytes to send, or null if none.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeDrainOutbound<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jbyteArray {
    let mut map = sessions().lock().unwrap();
    let sess = match map.get_mut(&handle) {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    match sess.outbound_drain() {
        Some(out) if !out.is_empty() => bytes_out(&env, &out),
        _ => std::ptr::null_mut(),
    }
}

/// int nativeQueryState(long handle) -> State ordinal (see PROTOCOL_CONTRACT.md)
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueryState<'l>(
    _env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jint {
    let map = sessions().lock().unwrap();
    match map.get(&handle) {
        Some(s) => s.query_state(),
        None => -1,
    }
}

/// byte[] nativeQueryPendingFiles(long handle) -> JSON array of {name,sizeBytes,mimeType}
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueryPendingFiles<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jbyteArray {
    let map = sessions().lock().unwrap();
    let sess = match map.get(&handle) {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    let files = sess.pending_files();
    let mut json = String::from("[");
    for (i, f) in files.iter().enumerate() {
        if i > 0 {
            json.push(',');
        }
        let name = f.name.replace('\\', "\\\\").replace('"', "\\\"");
        let mime = f.mime_type.replace('\\', "\\\\").replace('"', "\\\"");
        json.push_str(&format!(
            "{{\"name\":\"{name}\",\"sizeBytes\":{},\"mimeType\":\"{mime}\"}}",
            f.size_bytes
        ));
    }
    json.push(']');
    bytes_out(&env, json.as_bytes())
}

/// int nativeAccept(long handle, boolean accept, String destDir) 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeAccept<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    accept: jboolean,
    jDestDir: JString<'l>,
) -> jint {
    let dest = str_in(&mut env, &jDestDir).unwrap_or_default();
    let mut map = sessions().lock().unwrap();
    let sess = match map.get_mut(&handle) {
        Some(s) => s,
        None => return -1,
    };
    let ok = accept != 0;
    sess.accept(ok, &dest) as jint
}

/// int nativeOpenFile(long handle, String fileName, long fileSize) -> 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeOpenFile<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    jFileName: JString<'l>,
    fileSize: jlong,
) -> jint {
    let name = str_in(&mut env, &jFileName).unwrap_or_default();
    if name.is_empty() {
        return -1;
    }
    let mut map = sessions().lock().unwrap();
    let sess = match map.get_mut(&handle) {
        Some(s) => s,
        None => return -1,
    };
    sess.open_file(&name, fileSize) as jint
}

/// int nativeWriteChunk(long handle, byte[] chunk) -> 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeWriteChunk<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    jChunk: JByteArray<'l>,
) -> jint {
    let bytes = match bytes_in(&mut env, &jChunk) {
        Some(b) => b,
        None => return -1,
    };
    let mut map = sessions().lock().unwrap();
    let sess = match map.get_mut(&handle) {
        Some(s) => s,
        None => return -2,
    };
    sess.write_chunk(&bytes) as jint
}

/// int nativeCloseFile(long handle) -> 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeCloseFile<'l>(
    _env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jint {
    let mut map = sessions().lock().unwrap();
    let sess = match map.get_mut(&handle) {
        Some(s) => s,
        None => return -1,
    };
    sess.close_file() as jint
}

// ---------------------------------------------------------------------------
// Presence (Nearby Presence advert build/parse, public/Everyone mode, GATT 0xFCF1)
// ---------------------------------------------------------------------------

/// byte[] nativeBuildPresenceAdvert(String deviceName) -> advert bytes or null
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeBuildPresenceAdvert<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jName: JString<'l>,
) -> jbyteArray {
    let name = str_in(&mut env, &jName).unwrap_or_else(|| "Share".to_string());
    match crate::presence::build_presence_advert(&name) {
        Some(b) => bytes_out(&env, &b),
        None => std::ptr::null_mut(),
    }
}

/// byte[] nativeParsePresenceAdvert(byte[] serviceData) -> JSON utf8 or null
/// Returns JSON: {"deviceName":"Pixel 7","deviceType":1,"txPower":0,"isTruncated":false}
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeParsePresenceAdvert<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jBytes: JByteArray<'l>,
) -> jbyteArray {
    let bytes = match bytes_in(&mut env, &jBytes) {
        Some(b) => b,
        None => return std::ptr::null_mut(),
    };
    match crate::presence::parse_presence_advert_json(&bytes) {
        Some(json) => bytes_out(&env, &json),
        None => std::ptr::null_mut(),
    }
}

#[allow(non_snake_case)]
/// String nativeParsePresenceAdvertName(byte[] advertBytes) -> display name or null
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeParsePresenceAdvertName<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jBytes: JByteArray<'l>,
) -> jni::sys::jobject {
    let bytes = match bytes_in(&mut env, &jBytes) {
        Some(b) => b,
        None => return std::ptr::null_mut(),
    };
    let name = match crate::presence::parse_presence_advert_name(&bytes) {
        Some(n) => n,
        None => return std::ptr::null_mut(),
    };
    match env.new_string(name) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// Allow non-snake-case for the JNI surface (mirrors e2ee-p2p's jni_bridge).
#[allow(non_snake_case)]
/// void nativeDestroy(long handle)
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeDestroy<'l>(
    _env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) {
    sessions().lock().unwrap().remove(&handle);
}
