//! Socket framing and protobuf wire formats for Quick Share / Nearby Connections.
//!
//! # Ground truth
//!
//! Every constant, tag number and enum value below was recovered from the GMS
//! 26.24.34 decompile under `C:\Users\Vayun\gms-analysis\jadx-out-stable\sources\`.
//! `p000\xxxx.java` references are relative to that root.
//! `share/QUICK_SHARE_VERIFICATION.md` records the recovery method and the
//! two-sided citation for each claim.
//!
//! Tags come from the protobuf-lite `mo127gY` info strings (field numbers), the Java
//! field declarations (types and defaults) and the Kotlin mappers (semantic names) —
//! never from memory or from an upstream `.proto`. Do not change a tag without
//! updating the citation beside it.
//!
//! # Layering
//!
//! ```text
//! TCP  ──►  int32be(len) ‖ body                       p000\dnhn.java:219, :344
//!                         │
//!                         ├─ UKEY2 Ukey2Message       (until the handshake completes)
//!                         └─ D2D-encrypted body       p000\dnhn.java:212, :372
//!                              └─ OfflineFrame        p000\ivla.java
//!                                   └─ V1Frame        p000\ivlu.java
//!                                        └─ PayloadTransferFrame  p000\ivlo.java
//!                                             └─ BYTES payload = Sharing Frame
//!                                                  p000\duvt.java → p000\duwk.java
//! ```

// ---------------------------------------------------------------------------
// Socket framing: 4-byte big-endian int32 length prefix
//
// Write:  `DataOutputStream.writeInt(len); write(body)`  p000\dnhn.java:218-221
// Read:   `int len = DataInputStream.readInt(); readFully(body)`
//         p000\dnhn.java:344, :362; accounting `len + 4` at :223 / :367.
// The same channel carries the UKEY2 handshake messages and, once the encryptor
// is installed, every encrypted OfflineFrame (p000\dnij.java:102-107 writes the
// UKEY2 messages through the very same `mo62623A`).
// ---------------------------------------------------------------------------

/// Width of the length prefix in bytes.
pub const LENGTH_PREFIX_LEN: usize = 4;

/// Upper bound accepted for a peer-supplied frame length.
///
/// GMS bounds the read at `readInt() >= 0 && readInt() <= jwky.m158405ae()`
/// (`p000\dnhn.java:348-349`). `m158405ae` is a Phenotype flag, so its shipped
/// value is server-side and not recoverable from the APK; 5 MiB is our own
/// bound, chosen to comfortably exceed the 16 KiB payload chunk while still
/// refusing an allocation attack. Treat the number as ours, not as GMS's.
pub const MAX_FRAME_LEN: usize = 5 * 1024 * 1024;

/// Result of attempting to pull one length-prefixed frame off a read buffer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConsumeResult {
    /// Fewer bytes are buffered than the prefix announces; read more and retry.
    Incomplete,
    /// One complete frame body, with the prefix and body removed from the buffer.
    Frame(Vec<u8>),
    /// The announced length is negative or above [`MAX_FRAME_LEN`]. The channel
    /// is unusable — GMS logs and tears down here rather than allocating.
    Invalid,
}

/// Prepend the 4-byte big-endian length prefix to `payload`.
pub fn frame_with_length(payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(LENGTH_PREFIX_LEN + payload.len());
    let len = u32::try_from(payload.len()).unwrap_or(u32::MAX);
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(payload);
    out
}

/// Try to consume one length-prefixed frame from the front of `buf`.
///
/// On [`ConsumeResult::Frame`] the prefix and body are drained; on
/// [`ConsumeResult::Incomplete`] and [`ConsumeResult::Invalid`] `buf` is untouched.
pub fn try_consume_frame(buf: &mut Vec<u8>) -> ConsumeResult {
    let Some(prefix) = buf.get(..LENGTH_PREFIX_LEN) else {
        return ConsumeResult::Incomplete;
    };
    let mut prefix_bytes = [0u8; LENGTH_PREFIX_LEN];
    prefix_bytes.copy_from_slice(prefix);
    // GMS reads a *signed* int32, so a high-bit length is a negative length there.
    let announced = i32::from_be_bytes(prefix_bytes);
    if announced < 0 || announced as usize > MAX_FRAME_LEN {
        return ConsumeResult::Invalid;
    }
    let total = LENGTH_PREFIX_LEN.saturating_add(announced as usize);
    let Some(body) = buf.get(LENGTH_PREFIX_LEN..total) else {
        return ConsumeResult::Incomplete;
    };
    let out = body.to_vec();
    let _ = buf.drain(..total);
    ConsumeResult::Frame(out)
}

// ===========================================================================
// Sharing wire format — `sharing/proto/wire_format.proto` equivalent
// ===========================================================================

/// `Frame` — `p000\duvt.java:67`: `1 version` (enum, verifier `duvr`), `2 v1` (message `duwk`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingFrame {
    /// p000\duvt.java:67 field 1, enum verifier p000\duvr.java
    #[prost(enumeration = "SharingVersion", tag = "1")]
    pub version: i32,
    /// p000\duvt.java:67 field 2, message p000\duwk.java
    #[prost(message, optional, tag = "2")]
    pub v1: Option<SharingV1Frame>,
}

/// `Frame.Version` — `p000\duvs.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum SharingVersion {
    /// p000\duvs.java `UNKNOWN_VERSION(0)`
    UnknownVersion = 0,
    /// p000\duvs.java `V1(1)`
    V1 = 1,
}

/// `V1Frame` — `p000\duwk.java:85`, fields 1..8.
///
/// Fields 6 `certificate_info` (`p000\duvh.java`), 7 `progress_update`
/// (`p000\duwd.java`) and 8 `bindings` (`p000\duva.java`) exist on the wire but
/// are deliberately absent here: we neither emit nor need them, and prost does
/// not preserve unknown fields, so declaring them without decoding the nested
/// messages would only invite a wrong guess.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingV1Frame {
    /// p000\duwk.java:85 field 1, enum verifier p000\duwi.java
    #[prost(enumeration = "SharingFrameType", tag = "1")]
    pub r#type: i32,
    /// p000\duwk.java:85 field 2, message p000\duvw.java
    #[prost(message, optional, tag = "2")]
    pub introduction: Option<IntroductionFrame>,
    /// p000\duwk.java:85 field 3, message p000\duvl.java
    #[prost(message, optional, tag = "3")]
    pub connection_response: Option<SharingConnectionResponseFrame>,
    /// p000\duwk.java:85 field 4, message p000\duvx.java
    #[prost(message, optional, tag = "4")]
    pub paired_key_encryption: Option<PairedKeyEncryptionFrame>,
    /// p000\duwk.java:85 field 5, message p000\duwa.java
    #[prost(message, optional, tag = "5")]
    pub paired_key_result: Option<PairedKeyResultFrame>,
}

/// `V1Frame.FrameType` — `p000\duwj.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum SharingFrameType {
    /// p000\duwj.java `UNKNOWN_FRAME_TYPE(0)`
    UnknownFrameType = 0,
    /// p000\duwj.java `INTRODUCTION(1)`
    Introduction = 1,
    /// p000\duwj.java `RESPONSE(2)` — note the name is `RESPONSE`, not `CONNECTION_RESPONSE`.
    Response = 2,
    /// p000\duwj.java `PAIRED_KEY_ENCRYPTION(3)`
    PairedKeyEncryption = 3,
    /// p000\duwj.java `PAIRED_KEY_RESULT(4)`
    PairedKeyResult = 4,
    /// p000\duwj.java `CERTIFICATE_INFO(5)`
    CertificateInfo = 5,
    /// p000\duwj.java `CANCEL(6)`
    Cancel = 6,
    /// p000\duwj.java `PROGRESS_UPDATE(7)`
    ProgressUpdate = 7,
    /// p000\duwj.java `BINDINGS(8)`
    Bindings = 8,
}

/// `IntroductionFrame` — `p000\duvw.java:93`, fields 1..9.
///
/// Field 9 (repeated int64, unpacked) is omitted: `p000\dzra.java` only copies it
/// into `dzrf.f204456h` behind a `use_case` guard and no getter literal survives,
/// so its meaning is not recovered.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct IntroductionFrame {
    /// p000\duvw.java:93 field 1, MESSAGE_LIST of p000\duvq.java;
    /// name from p000\dzra.java `"getFileMetadataList(...)"`
    #[prost(message, repeated, tag = "1")]
    pub file_metadata: Vec<SharingFileMetadata>,
    /// p000\duvw.java:93 field 2, MESSAGE_LIST of p000\duwh.java;
    /// name from p000\dzra.java `"getTextMetadataList(...)"`
    #[prost(message, repeated, tag = "2")]
    pub text_metadata: Vec<TextMetadata>,
    /// p000\duvw.java:93 field 3, STRING
    #[prost(string, tag = "3")]
    pub required_package: String,
    /// p000\duvw.java:93 field 4, MESSAGE_LIST of p000\duwo.java;
    /// name from p000\dzra.java `"getWifiCredentialsMetadataList(...)"`
    #[prost(message, repeated, tag = "4")]
    pub wifi_credentials_metadata: Vec<WifiCredentialsMetadata>,
    /// p000\duvw.java:93 field 5, MESSAGE_LIST of p000\duuy.java;
    /// name from p000\dzra.java `"getAppMetadataList(...)"`
    #[prost(message, repeated, tag = "5")]
    pub app_metadata: Vec<AppMetadata>,
    /// p000\duvw.java:93 field 6, BOOL
    #[prost(bool, tag = "6")]
    pub start_transfer: bool,
    /// p000\duvw.java:93 field 8, ENUM, verifier p000\duvu.java
    #[prost(enumeration = "ShareUseCase", tag = "8")]
    pub use_case: i32,
}

/// `IntroductionFrame.use_case` — `p000\duvv.java`. Note the deliberate gaps.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum ShareUseCase {
    /// p000\duvv.java `UNKNOWN(0)`
    Unknown = 0,
    /// p000\duvv.java `NEARBY_SHARE(1)`
    NearbyShare = 1,
    /// p000\duvv.java `REMOTE_COPY(2)`
    RemoteCopy = 2,
    /// p000\duvv.java `TAP_TO_SHARE(9)`
    TapToShare = 9,
    /// p000\duvv.java `FILE_SYNC(10)`
    FileSync = 10,
}

/// The `mime_type` default baked into `p000\duvq.java:42`.
///
/// proto2 field defaults do not survive into prost, which zero-initialises
/// everything, so builders must set this explicitly and readers must substitute
/// it for an empty string.
pub const DEFAULT_MIME_TYPE: &str = "application/octet-stream";

/// `FileMetadata` — `p000\duvq.java:89`, fields 1..9.
///
/// Semantic names come from the Kotlin mapper `p000\dzra.java`
/// (`"getType(...)"`, `"getMimeType(...)"`, `"getParentFolder(...)"`, and the
/// `dzsg(id, payloadId, …)` argument order, where `id` reads field 6 and
/// `payloadId` reads field 3).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingFileMetadata {
    /// p000\duvq.java:89 field 1, STRING
    #[prost(string, tag = "1")]
    pub name: String,
    /// p000\duvq.java:89 field 2, ENUM, verifier p000\duvo.java
    #[prost(enumeration = "SharingFileType", tag = "2")]
    pub r#type: i32,
    /// p000\duvq.java:89 field 3, INT64
    #[prost(int64, tag = "3")]
    pub payload_id: i64,
    /// p000\duvq.java:89 field 4, INT64
    #[prost(int64, tag = "4")]
    pub size: i64,
    /// p000\duvq.java:89 field 5, STRING, default [`DEFAULT_MIME_TYPE`] (p000\duvq.java:42)
    #[prost(string, tag = "5")]
    pub mime_type: String,
    /// p000\duvq.java:89 field 6, INT64
    #[prost(int64, tag = "6")]
    pub id: i64,
    /// p000\duvq.java:89 field 7, STRING
    #[prost(string, tag = "7")]
    pub parent_folder: String,
    /// p000\duvq.java:89 field 8, INT64
    #[prost(int64, tag = "8")]
    pub hash: i64,
    /// p000\duvq.java:89 field 9, BOOL
    #[prost(bool, tag = "9")]
    pub is_sensitive_content: bool,
}

/// `FileMetadata.Type` — `p000\duvp.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum SharingFileType {
    /// p000\duvp.java `UNKNOWN(0)`
    Unknown = 0,
    /// p000\duvp.java `IMAGE(1)`
    Image = 1,
    /// p000\duvp.java `VIDEO(2)`
    Video = 2,
    /// p000\duvp.java `ANDROID_APP(3)`
    AndroidApp = 3,
    /// p000\duvp.java `AUDIO(4)`
    Audio = 4,
    /// p000\duvp.java `DOCUMENT(5)` — the catch-all. Our previous `File = 5` was a
    /// name that does not exist in the enum.
    Document = 5,
    /// p000\duvp.java `CONTACT_CARD(6)`
    ContactCard = 6,
}

/// `TextMetadata` — `p000\duwh.java:79`, fields **2..7** (there is no field 1).
///
/// Names from `p000\dzra.java`: `"getTextTitle(...)"`, `"getType(...)"`, and the
/// `dzsr(id, payloadId, type, textTitle, size, isSensitive)` argument order which
/// reads fields 6, 4, 3, 2, 5, 7 in that sequence (`p000\dzsr.java:20-31`, where
/// the constructor rejects `size <= 0`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct TextMetadata {
    /// p000\duwh.java:79 field 2, STRING
    #[prost(string, tag = "2")]
    pub text_title: String,
    /// p000\duwh.java:79 field 3, ENUM, verifier p000\duwf.java
    #[prost(enumeration = "TextType", tag = "3")]
    pub r#type: i32,
    /// p000\duwh.java:79 field 4, INT64
    #[prost(int64, tag = "4")]
    pub payload_id: i64,
    /// p000\duwh.java:79 field 5, INT64
    #[prost(int64, tag = "5")]
    pub size: i64,
    /// p000\duwh.java:79 field 6, INT64
    #[prost(int64, tag = "6")]
    pub id: i64,
    /// p000\duwh.java:79 field 7, BOOL
    #[prost(bool, tag = "7")]
    pub is_sensitive_text: bool,
}

/// `TextMetadata.Type` — `p000\duwg.java`.
///
/// This enum is **1-based**: `UNKNOWN` occupies 0. The plan's writeup §5.3 listed
/// `0=TEXT, 1=URL, …`, which the decompile contradicts.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum TextType {
    /// p000\duwg.java `UNKNOWN(0)`
    Unknown = 0,
    /// p000\duwg.java `TEXT(1)`
    Text = 1,
    /// p000\duwg.java `URL(2)`
    Url = 2,
    /// p000\duwg.java `ADDRESS(3)`
    Address = 3,
    /// p000\duwg.java `PHONE_NUMBER(4)`
    PhoneNumber = 4,
}

/// `WifiCredentialsMetadata` — `p000\duwo.java`, fields **2..5** (no field 1).
///
/// Names from `p000\dzra.java`: `"getSsid(...)"`, `"getSecurityType(...)"`, and the
/// same `(id, payloadId, …)` argument order that reads field 5 then field 4.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct WifiCredentialsMetadata {
    /// p000\duwo.java field 2, STRING
    #[prost(string, tag = "2")]
    pub ssid: String,
    /// p000\duwo.java field 3, ENUM, verifier p000\duwm.java
    #[prost(enumeration = "WifiSecurityType", tag = "3")]
    pub security_type: i32,
    /// p000\duwo.java field 4, INT64
    #[prost(int64, tag = "4")]
    pub payload_id: i64,
    /// p000\duwo.java field 5, INT64
    #[prost(int64, tag = "5")]
    pub id: i64,
}

/// `WifiCredentialsMetadata.SecurityType` — `p000\duwn.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum WifiSecurityType {
    /// p000\duwn.java `UNKNOWN_SECURITY_TYPE(0)`
    UnknownSecurityType = 0,
    /// p000\duwn.java `OPEN(1)`
    Open = 1,
    /// p000\duwn.java `WPA_PSK(2)`
    WpaPsk = 2,
    /// p000\duwn.java `WEP(3)`
    Wep = 3,
    /// p000\duwn.java `SAE(4)`
    Sae = 4,
}

/// `AppMetadata` — `p000\duuy.java`, fields 1..7.
///
/// Names from `p000\dzra.java`: `"getAppName(...)"` (field 1),
/// `"getPackageName(...)"` (field 7), and the per-APK zip of field 5 with fields 3
/// and 6 into `dzqn(fileName, payloadId, fileSize)` plus `dzqo(id, appName, size,
/// packageName, files)` which reads field 4 then 1 then 2 then 7.
///
/// Present so a GMS-originated APK introduction round-trips; `:share` never emits it.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct AppMetadata {
    /// p000\duuy.java field 1, STRING
    #[prost(string, tag = "1")]
    pub app_name: String,
    /// p000\duuy.java field 2, INT64
    #[prost(int64, tag = "2")]
    pub size: i64,
    /// p000\duuy.java field 3, INT64_LIST_PACKED
    #[prost(int64, repeated, tag = "3")]
    pub payload_id: Vec<i64>,
    /// p000\duuy.java field 4, INT64
    #[prost(int64, tag = "4")]
    pub id: i64,
    /// p000\duuy.java field 5, STRING_LIST
    #[prost(string, repeated, tag = "5")]
    pub file_name: Vec<String>,
    /// p000\duuy.java field 6, INT64_LIST_PACKED
    #[prost(int64, repeated, tag = "6")]
    pub file_size: Vec<i64>,
    /// p000\duuy.java field 7, STRING
    #[prost(string, tag = "7")]
    pub package_name: String,
}

/// `ConnectionResponseFrame` (Sharing) — `p000\duvl.java:67`.
///
/// Field 2 is a `map<int64, p000\duuz.java>` (`p000\duvi.java` declares the entry
/// as `INT64 -> MESSAGE duuz`). We neither emit nor read it.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingConnectionResponseFrame {
    /// p000\duvl.java:67 field 1, ENUM, verifier p000\duvj.java
    #[prost(enumeration = "SharingResponseStatus", tag = "1")]
    pub status: i32,
}

/// `ConnectionResponseFrame.Status` — `p000\duvk.java`.
///
/// `ACCEPT` is **1**. The previous code sent `0` to accept, i.e. `UNKNOWN`, and `1`
/// to reject, i.e. `ACCEPT` — an inversion that also made accept indistinguishable
/// from an unset field.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum SharingResponseStatus {
    /// p000\duvk.java `UNKNOWN(0)`
    Unknown = 0,
    /// p000\duvk.java `ACCEPT(1)`
    Accept = 1,
    /// p000\duvk.java `REJECT(2)`
    Reject = 2,
    /// p000\duvk.java `NOT_ENOUGH_SPACE(3)`
    NotEnoughSpace = 3,
    /// p000\duvk.java `UNSUPPORTED_ATTACHMENT_TYPE(4)`
    UnsupportedAttachmentType = 4,
    /// p000\duvk.java `TIMED_OUT(5)`
    TimedOut = 5,
}

/// `PairedKeyEncryptionFrame` — `p000\duvx.java:66`, four `bytes` fields.
///
/// The 1:1 mapping is fixed by the mapper `p000\dzrr.java:9-23`, which builds
/// `dzrs(signedData, optionalSignedData, secretIdHash, qrCodeHandshakeData)`
/// (`p000\dzrs.java:133`) from java fields `c`, `e`, `d`, `f` respectively — i.e.
/// fields 1, 3, 2, 4. The declaration order is therefore **not** the constructor
/// order: `secret_id_hash` is field 2, `optional_signed_data` is field 3.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PairedKeyEncryptionFrame {
    /// p000\duvx.java:66 field 1, BYTES (`dzrr.java:12` → `signedData`)
    #[prost(bytes = "vec", tag = "1")]
    pub signed_data: Vec<u8>,
    /// p000\duvx.java:66 field 2, BYTES (`dzrr.java:19` → `secretIdHash`)
    #[prost(bytes = "vec", tag = "2")]
    pub secret_id_hash: Vec<u8>,
    /// p000\duvx.java:66 field 3, BYTES (`dzrr.java:15` → `optionalSignedData`)
    #[prost(bytes = "vec", tag = "3")]
    pub optional_signed_data: Vec<u8>,
    /// p000\duvx.java:66 field 4, BYTES (`dzrr.java:21` → `qrCodeHandshakeData`)
    #[prost(bytes = "vec", tag = "4")]
    pub qr_code_handshake_data: Vec<u8>,
}

/// `PairedKeyResultFrame` — `p000\duwa.java:66`: `1 status`, `2 os_type`.
///
/// Field 2 is an enum, not an int: its verifier `p000\iwkq.java` delegates to
/// `p000\iwkr.java`, the OS-type enum.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PairedKeyResultFrame {
    /// p000\duwa.java:66 field 1, ENUM, verifier p000\duvy.java
    #[prost(enumeration = "PairedKeyResultStatus", tag = "1")]
    pub status: i32,
    /// p000\duwa.java:66 field 2, ENUM, verifier p000\iwkq.java
    #[prost(enumeration = "OsType", tag = "2")]
    pub os_type: i32,
}

/// `PairedKeyResultFrame.Status` — `p000\duvz.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum PairedKeyResultStatus {
    /// p000\duvz.java `UNKNOWN(0)`
    Unknown = 0,
    /// p000\duvz.java `SUCCESS(1)`
    Success = 1,
    /// p000\duvz.java `FAIL(2)`
    Fail = 2,
    /// p000\duvz.java `UNABLE(3)` — what a device with no paired-key certificate sends.
    Unable = 3,
}

/// OS type — `p000\iwkr.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum OsType {
    /// p000\iwkr.java `UNKNOWN_OS_TYPE(0)`
    UnknownOsType = 0,
    /// p000\iwkr.java `ANDROID(1)`
    Android = 1,
    /// p000\iwkr.java `CHROME_OS(2)`
    ChromeOs = 2,
    /// p000\iwkr.java `IOS(3)`
    Ios = 3,
    /// p000\iwkr.java `WINDOWS(4)`
    Windows = 4,
    /// p000\iwkr.java `MACOS(5)`
    Macos = 5,
}

// ===========================================================================
// Nearby Connections wire format — `offline_wire_formats.proto` equivalent
// ===========================================================================

/// `OfflineFrame` — `p000\ivla.java`: `1 version` (enum, verifier `ivky`), `2 v1`.
///
/// Everything sent after the UKEY2 handshake is one of these, encrypted and then
/// length-prefixed (`p000\dnhn.java:212, :219` on write; `:344, :372` on read,
/// where the plaintext is handed to `dnlx.m62950a` to be parsed as an
/// `OfflineFrame`, `p000\dnhn.java:377`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct OfflineFrame {
    /// p000\ivla.java field 1, ENUM, verifier p000\ivky.java
    #[prost(enumeration = "OfflineVersion", tag = "1")]
    pub version: i32,
    /// p000\ivla.java field 2, MESSAGE p000\ivlu.java
    #[prost(message, optional, tag = "2")]
    pub v1: Option<OfflineV1Frame>,
}

/// `OfflineFrame.Version` — `p000\ivkz.java` accepts exactly `{0, 1}`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum OfflineVersion {
    /// p000\ivkz.java maps 0
    UnknownVersion = 0,
    /// p000\ivkz.java maps 1
    V1 = 1,
}

/// `V1Frame` (Nearby Connections) — `p000\ivlu.java`, fields 1..13.
///
/// We declare only the fields on the WIFI_LAN direct path. Omitted on purpose:
/// 5 `bandwidth_upgrade_negotiation` (`ivkb`), 8 `paired_key_encryption` (`ivle`,
/// the *Connections* one — distinct from the Sharing frame above), 9
/// `authentication_message` (`ivjb`), 10 `authentication_result` (`ivjc`), 11
/// `auto_resume` (`ivji`), 12 `auto_reconnect` (`ivjf`), 13
/// `bandwidth_upgrade_retry` (`ivkf`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct OfflineV1Frame {
    /// p000\ivlu.java field 1, ENUM, verifier p000\ivls.java
    #[prost(enumeration = "OfflineFrameType", tag = "1")]
    pub r#type: i32,
    /// p000\ivlu.java field 2, MESSAGE p000\ivkl.java
    #[prost(message, optional, tag = "2")]
    pub connection_request: Option<ConnectionRequestFrame>,
    /// p000\ivlu.java field 3, MESSAGE p000\ivko.java
    #[prost(message, optional, tag = "3")]
    pub connection_response: Option<OfflineConnectionResponseFrame>,
    /// p000\ivlu.java field 4, MESSAGE p000\ivlo.java
    #[prost(message, optional, tag = "4")]
    pub payload_transfer: Option<PayloadTransferFrame>,
    /// p000\ivlu.java field 5, MESSAGE p000\ivkb.java
    #[prost(message, optional, tag = "5")]
    pub bandwidth_upgrade_negotiation: Option<BandwidthUpgradeNegotiationFrame>,
    /// p000\ivlu.java field 6, MESSAGE p000\ivks.java
    #[prost(message, optional, tag = "6")]
    pub keep_alive: Option<KeepAliveFrame>,
    /// p000\ivlu.java field 7, MESSAGE p000\ivkq.java
    #[prost(message, optional, tag = "7")]
    pub disconnection: Option<DisconnectionFrame>,
}

/// `BandwidthUpgradeNegotiationFrame` - `p000\ivkb.java`.
///
/// Field numbering from `ivkb`'s protobuf-lite schema: `1` is the event type (enum verified
/// by `p000\ivjn.java` → `p000\ivjo.java`), `2` `upgrade_path_info` (`ivka`), `3`
/// `client_introduction` (`ivjl`), `4` (`ivjm`) and `5` (`ivjp`).
///
/// Only the event type is modelled: `:share` never upgrades, it only declines.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BandwidthUpgradeNegotiationFrame {
    /// p000\ivkb.java field 1, ENUM, verifier p000\ivjn.java
    #[prost(enumeration = "BandwidthUpgradeEvent", tag = "1")]
    pub event_type: i32,
}

/// `BandwidthUpgradeNegotiationFrame.EventType` - `p000\ivjo.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum BandwidthUpgradeEvent {
    /// p000\ivjo.java `UNKNOWN_EVENT_TYPE(0)`
    UnknownEventType = 0,
    /// p000\ivjo.java `UPGRADE_PATH_AVAILABLE(1)`
    UpgradePathAvailable = 1,
    /// p000\ivjo.java `LAST_WRITE_TO_PRIOR_CHANNEL(2)`
    LastWriteToPriorChannel = 2,
    /// p000\ivjo.java `SAFE_TO_CLOSE_PRIOR_CHANNEL(3)`
    SafeToClosePriorChannel = 3,
    /// p000\ivjo.java `CLIENT_INTRODUCTION(4)`
    ClientIntroduction = 4,
    /// p000\ivjo.java `UPGRADE_FAILURE(5)` - what `:share` always answers with.
    UpgradeFailure = 5,
    /// p000\ivjo.java `CLIENT_INTRODUCTION_ACK(6)`
    ClientIntroductionAck = 6,
    /// p000\ivjo.java `UPGRADE_PATH_REQUEST(7)`
    UpgradePathRequest = 7,
}

/// `V1Frame.FrameType` (Nearby Connections) - `p000\ivlt.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum OfflineFrameType {
    /// p000\ivlt.java `UNKNOWN_FRAME_TYPE(0)`
    UnknownFrameType = 0,
    /// p000\ivlt.java `CONNECTION_REQUEST(1)`
    ConnectionRequest = 1,
    /// p000\ivlt.java `CONNECTION_RESPONSE(2)`
    ConnectionResponse = 2,
    /// p000\ivlt.java `PAYLOAD_TRANSFER(3)`
    PayloadTransfer = 3,
    /// p000\ivlt.java `BANDWIDTH_UPGRADE_NEGOTIATION(4)`
    BandwidthUpgradeNegotiation = 4,
    /// p000\ivlt.java `KEEP_ALIVE(5)`
    KeepAlive = 5,
    /// p000\ivlt.java `DISCONNECTION(6)`
    Disconnection = 6,
    /// p000\ivlt.java `PAIRED_KEY_ENCRYPTION(7)`
    PairedKeyEncryption = 7,
    /// p000\ivlt.java `AUTHENTICATION_MESSAGE(8)`
    AuthenticationMessage = 8,
    /// p000\ivlt.java `AUTHENTICATION_RESULT(9)`
    AuthenticationResult = 9,
    /// p000\ivlt.java `AUTO_RESUME(10)`
    AutoResume = 10,
    /// p000\ivlt.java `AUTO_RECONNECT(11)`
    AutoReconnect = 11,
    /// p000\ivlt.java `BANDWIDTH_UPGRADE_RETRY(12)`
    BandwidthUpgradeRetry = 12,
}

/// `ConnectionRequestFrame` — `p000\ivkl.java:120`, 15 fields plus a oneof.
///
/// Field vocabulary from `p000\dnlw.java:305`
/// (`ConnectRequestParameters{endpointId=…, endpointInfo=…, handshakeData=…,
/// nonce=…, mediums=…, keepAliveIntervalMillis=…, keepAliveTimeoutMillis=…,
/// deviceType=…, localDeviceInfo=…}`). Fields 1 and 2 are both validated as
/// required by `p000\dnlx.java:669` (`"missing endpointId field."`) and `:675`
/// (`"missing endpointName field."`).
///
/// Omitted: 7 `medium_metadata` (`ivkw`), 10 `device_type`, 11 `device_info`,
/// the 12/13 oneof (`ivkp` / `ivlr`), 14 `connections_device_type`
/// (`p000\ivkh.java`), 15 (`ivkt`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ConnectionRequestFrame {
    /// p000\ivkl.java:120 field 1, STRING — required per p000\dnlx.java:669
    #[prost(string, tag = "1")]
    pub endpoint_id: String,
    /// p000\ivkl.java:120 field 2, STRING — required per p000\dnlx.java:675
    #[prost(string, tag = "2")]
    pub endpoint_name: String,
    /// p000\ivkl.java:120 field 3, BYTES
    #[prost(bytes = "vec", tag = "3")]
    pub handshake_data: Vec<u8>,
    /// p000\ivkl.java:120 field 4, INT32
    #[prost(int32, tag = "4")]
    pub nonce: i32,
    /// p000\ivkl.java:120 field 5, ENUM_LIST (unpacked — hence `packed = "false"`),
    /// verifier p000\ivkj.java
    #[prost(enumeration = "ConnectionsMedium", repeated, packed = "false", tag = "5")]
    pub mediums: Vec<i32>,
    /// p000\ivkl.java:120 field 6, BYTES
    #[prost(bytes = "vec", tag = "6")]
    pub endpoint_info: Vec<u8>,
    /// p000\ivkl.java:120 field 8, INT32
    #[prost(int32, tag = "8")]
    pub keep_alive_interval_millis: i32,
    /// p000\ivkl.java:120 field 9, INT32
    #[prost(int32, tag = "9")]
    pub keep_alive_timeout_millis: i32,
}

/// `Medium` — `p000\ivkk.java`. `:share` only ever offers `WifiLan`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum ConnectionsMedium {
    /// p000\ivkk.java `UNKNOWN_MEDIUM(0)`
    UnknownMedium = 0,
    /// p000\ivkk.java `MDNS(1)`
    Mdns = 1,
    /// p000\ivkk.java `BLUETOOTH(2)`
    Bluetooth = 2,
    /// p000\ivkk.java `WIFI_HOTSPOT(3)`
    WifiHotspot = 3,
    /// p000\ivkk.java `BLE(4)`
    Ble = 4,
    /// p000\ivkk.java `WIFI_LAN(5)`
    WifiLan = 5,
    /// p000\ivkk.java `WIFI_AWARE(6)`
    WifiAware = 6,
    /// p000\ivkk.java `NFC(7)`
    Nfc = 7,
    /// p000\ivkk.java `WIFI_DIRECT(8)`
    WifiDirect = 8,
    /// p000\ivkk.java `WEB_RTC(9)`
    WebRtc = 9,
    /// p000\ivkk.java `BLE_L2CAP(10)`
    BleL2cap = 10,
    /// p000\ivkk.java `USB(11)`
    Usb = 11,
    /// p000\ivkk.java `WEB_RTC_NON_CELLULAR(12)`
    WebRtcNonCellular = 12,
    /// p000\ivkk.java `AWDL(13)`
    Awdl = 13,
}

/// `ConnectionResponseFrame` (Nearby Connections) — `p000\ivko.java:86`.
///
/// The info string declares fields 1, 2, 3, 4, 5, 7, 8, 9 — field 6 is gone.
/// Two status fields coexist and GMS always writes **both**: the legacy int32
/// `status` (field 1) and the enum `response` (field 3), which `p000\dnlx.java:1041-1051`
/// derives from the status as `(status == 0 ? 2 : 3) - 1`.
///
/// `status` is `Option` rather than a bare `i32` because **its presence is
/// load-bearing**, unlike every other scalar in this file. `p000\dnsi.java:6911`
/// reads acceptance as:
///
/// ```text
/// has(response) ? verify(response) == 2       // i.e. response == 1
///               : has(status) && status == 0
/// ```
///
/// so a response carrying neither field, or `status = 0` merely *defaulted* rather
/// than written, reads as a **rejection**. Emitting `Some(0)` reproduces GMS's
/// `08 00`.
///
/// Omitted: 4 `os_info` (`ivld`), 7, 8 (`ivkt`), 9.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct OfflineConnectionResponseFrame {
    /// p000\ivko.java:86 field 1, INT32 — legacy status; presence matters, see above
    #[prost(int32, optional, tag = "1")]
    pub status: Option<i32>,
    /// p000\ivko.java:86 field 2, BYTES
    #[prost(bytes = "vec", tag = "2")]
    pub handshake_data: Vec<u8>,
    /// p000\ivko.java:86 field 3, ENUM, verifier p000\ivkm.java → p000\ivkn.java
    #[prost(enumeration = "OfflineResponseStatus", optional, tag = "3")]
    pub response: Option<i32>,
    /// p000\ivko.java:86 field 5, INT32
    #[prost(int32, tag = "5")]
    pub multiplex_socket_bitmask: i32,
}

impl OfflineConnectionResponseFrame {
    /// Whether the peer accepted, by GMS's own rule (`p000\dnsi.java:6911`).
    ///
    /// Field 3 wins when present; only if it is absent does the legacy `status`
    /// decide, and then it must have been explicitly written.
    pub fn accepted(&self) -> bool {
        match self.response {
            Some(response) => response == OfflineResponseStatus::Accept as i32,
            None => self.status == Some(OFFLINE_RESPONSE_STATUS_ACCEPT),
        }
    }
}

/// `ConnectionResponseFrame.ResponseStatus` (field 3) — `p000\ivkn.java` accepts
/// exactly `{0, 1, 2}`.
///
/// R8 stripped the value names, but the mapping is still recoverable from the code
/// on both ends. `p000\dnlx.java:1041-1051` (`m62960k`, the sole builder) writes
/// `(status == 0 ? 2 : 3) - 1`, and `p000\dnsi.java:6911` accepts when
/// `ivkn.m131628a(response) == 2`, where `m131628a` maps `0→1, 1→2, 2→3`
/// (`p000\ivkn.java`). Both agree: **1 is accept, 2 is reject**.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum OfflineResponseStatus {
    /// p000\ivkn.java maps 0 — never written by GMS.
    Unknown = 0,
    /// `status == 0`, i.e. accepted.
    Accept = 1,
    /// `status != 0`, i.e. rejected.
    Reject = 2,
}

/// Legacy `ConnectionResponseFrame.status` value meaning "connection accepted".
///
/// `p000\dncj.java:1204` (`acceptConnection`) calls the builder with a literal `0`.
pub const OFFLINE_RESPONSE_STATUS_ACCEPT: i32 = 0;

/// Legacy `ConnectionResponseFrame.status` value meaning "connection rejected".
///
/// `p000\dncj.java:1622` (`rejectConnection`) calls the builder with a literal `8004`,
/// the same code `evaluateConnectionResult` reports for "rejected by one or both
/// sides" (`p000\dnsi.java:4383`).
pub const OFFLINE_RESPONSE_STATUS_REJECT: i32 = 8004;

/// `KeepAliveFrame` — `p000\ivks.java`: `1 ack:bool`, `2 seq_num:uint32`.
///
/// Field 2 is UINT32 (info-string type `0x0B`), not INT32.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct KeepAliveFrame {
    /// p000\ivks.java field 1, BOOL
    #[prost(bool, tag = "1")]
    pub ack: bool,
    /// p000\ivks.java field 2, UINT32
    #[prost(uint32, tag = "2")]
    pub seq_num: u32,
}

/// `DisconnectionFrame` — `p000\ivkq.java`: two BOOL fields.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct DisconnectionFrame {
    /// p000\ivkq.java field 1, BOOL
    #[prost(bool, tag = "1")]
    pub request_safe_to_disconnect: bool,
    /// p000\ivkq.java field 2, BOOL
    #[prost(bool, tag = "2")]
    pub ack_safe_to_disconnect: bool,
}

/// `PayloadTransferFrame` — `p000\ivlo.java`: `1 packet_type` (enum, verifier
/// `ivli`), `2 payload_header`, `3 payload_chunk`, `4 control_message`.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PayloadTransferFrame {
    /// p000\ivlo.java field 1, ENUM, verifier p000\ivli.java
    #[prost(enumeration = "PayloadPacketType", tag = "1")]
    pub packet_type: i32,
    /// p000\ivlo.java field 2, MESSAGE p000\ivln.java
    #[prost(message, optional, tag = "2")]
    pub payload_header: Option<PayloadHeader>,
    /// p000\ivlo.java field 3, MESSAGE p000\ivlk.java
    #[prost(message, optional, tag = "3")]
    pub payload_chunk: Option<PayloadChunk>,
    /// p000\ivlo.java field 4, MESSAGE
    #[prost(message, optional, tag = "4")]
    pub control_message: Option<ControlMessage>,
}

/// `PayloadTransferFrame.PacketType`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum PayloadPacketType {
    /// Unset.
    Unknown = 0,
    /// A header and/or chunk of payload bytes.
    Data = 1,
    /// A control event (cancel / error / completed).
    Control = 2,
}

/// `PayloadHeader` — `p000\ivln.java`, fields 1..7.
///
/// Every field carries a hasbit. The protobuf-lite info string at `p000\ivln.java:82` types
/// them `ဂ`/`᠌`/`ဂ`/`ဇ`/`ဈ`/`ဈ`/`ဂ` = `0x1002, 0x180C, 0x1002, 0x1007, 0x1008, 0x1008,
/// 0x1002`; the `0x1000` bit is explicit presence. So `is_sensitive = false` is meant to be
/// *present and false*, which is not the same wire image as absent.
///
/// This matters: `is_sensitive` is modelled as `Option<bool>` and always written, because a
/// `PayloadHeader` without field 4 is not one GMS acts on. `rquickshare`, an independent
/// non-GMS implementation that interoperates with real Quick Share, likewise sets
/// `is_sensitive: Some(false)` on every header it builds
/// (`core_lib/src/hdl/inbound.rs::send_encrypted_frame`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PayloadHeader {
    /// p000\ivln.java field 1, INT64
    #[prost(int64, tag = "1")]
    pub id: i64,
    /// p000\ivln.java field 2, ENUM, verifier p000\ivll.java
    #[prost(enumeration = "PayloadType", tag = "2")]
    pub r#type: i32,
    /// p000\ivln.java field 3, INT64
    #[prost(int64, tag = "3")]
    pub total_size: i64,
    /// p000\ivln.java field 4, BOOL with a hasbit — presence is load-bearing, see above.
    #[prost(bool, optional, tag = "4")]
    pub is_sensitive: Option<bool>,
    /// p000\ivln.java field 5, STRING
    #[prost(string, tag = "5")]
    pub file_name: String,
    /// p000\ivln.java field 6, STRING
    #[prost(string, tag = "6")]
    pub parent_folder: String,
}

/// `PayloadHeader.PayloadType`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum PayloadType {
    /// Unset.
    Unknown = 0,
    /// An in-band byte blob. Sharing `Frame`s travel as this.
    Bytes = 1,
    /// A file streamed in chunks.
    File = 2,
    /// A stream payload (not implemented).
    Stream = 3,
}

/// `PayloadChunk` — `p000\ivlk.java`: `1 flags:int32`, `2 offset:int64`,
/// `3 body:bytes`, `4 index:int32`.
///
/// Every field has a hasbit, and here presence is **load-bearing**. The info string at
/// `p000\ivlk.java:73` types them `င`/`ဂ`/`ည`/`င` = `0x1004, 0x1002, 0x100A, 0x1004`; the
/// `0x1000` bit is explicit presence. A first chunk carries `flags = 0`, so emitting it as a
/// bare `int32` drops it from the wire and GMS rejects the whole frame:
///
/// ```text
/// iuun: OfflineFrame PAYLOAD_TRANSFER(DATA) missing flags field.
/// PayloadManager failed to retrieve Payload 219401524532613 for chunk at offset 90, discarding.
/// ```
///
/// The data chunk is discarded, so when the `FLAG_LAST` terminator arrives there is no
/// payload to attach it to and it is discarded too — the Sharing layer never sees the frame
/// and the peer eventually times out with `AUTH_FAILURE`. Measured against a Pixel 7 Pro on
/// GMS 26.24.34. `rquickshare` likewise sets `flags: Some(0)` and `offset: Some(0)`
/// (`core_lib/src/hdl/inbound.rs::send_encrypted_frame`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PayloadChunk {
    /// p000\ivlk.java field 1, INT32 with a hasbit. Bit 0 is the last-chunk flag.
    #[prost(int32, optional, tag = "1")]
    pub flags: Option<i32>,
    /// p000\ivlk.java field 2, INT64 with a hasbit.
    #[prost(int64, optional, tag = "2")]
    pub offset: Option<i64>,
    /// p000\ivlk.java field 3, BYTES with a hasbit.
    #[prost(bytes = "vec", optional, tag = "3")]
    pub body: Option<Vec<u8>>,
}

impl PayloadChunk {
    /// True when this chunk closes the payload.
    pub fn is_last(&self) -> bool {
        self.flags() & 1 != 0
    }
}

/// `PayloadTransferFrame.ControlMessage`.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ControlMessage {
    /// Control event kind.
    #[prost(enumeration = "ControlEventType", tag = "1")]
    pub event: i32,
    /// Offset the event refers to.
    #[prost(int64, tag = "2")]
    pub offset: i64,
}

/// `ControlMessage.EventType`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum ControlEventType {
    /// Unset.
    Unknown = 0,
    /// Sender or receiver cancelled the payload.
    PayloadCanceled = 1,
    /// The payload failed.
    PayloadError = 2,
    /// The payload finished.
    PayloadCompleted = 3,
}

#[cfg(test)]
mod tests {
    use super::*;
    use prost::Message;

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02X}")).collect()
    }

    #[test]
    fn length_prefix_is_four_byte_big_endian() {
        // 13 bytes of payload must be announced as 00 00 00 0D, not as a varint 0x0D.
        let payload = b"hello sharing";
        let framed = frame_with_length(payload);
        assert_eq!(&framed[..4], &[0x00, 0x00, 0x00, 0x0D]);
        assert_eq!(framed.len(), 4 + payload.len());

        // 300 bytes: 00 00 01 2C. A varint prefix would have been AC 02.
        let big = vec![0u8; 300];
        assert_eq!(&frame_with_length(&big)[..4], &[0x00, 0x00, 0x01, 0x2C]);
    }

    #[test]
    fn length_prefix_round_trip() {
        let payload = b"hello sharing";
        let mut buf = frame_with_length(payload);
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Frame(payload.to_vec()));
        assert!(buf.is_empty());
    }

    #[test]
    fn partial_read_split_mid_prefix() {
        let payload = vec![0xABu8; 200];
        let framed = frame_with_length(&payload);

        // Two of the four prefix bytes: still Incomplete, buffer untouched.
        let mut buf = framed[..2].to_vec();
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Incomplete);
        assert_eq!(buf.len(), 2);

        // Whole prefix but only part of the body.
        buf.extend_from_slice(&framed[2..10]);
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Incomplete);
        assert_eq!(buf.len(), 10);

        buf.extend_from_slice(&framed[10..]);
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Frame(payload));
        assert!(buf.is_empty());
    }

    #[test]
    fn two_frames_back_to_back() {
        let mut buf = frame_with_length(b"one");
        buf.extend_from_slice(&frame_with_length(b"two"));
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Frame(b"one".to_vec()));
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Frame(b"two".to_vec()));
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Incomplete);
    }

    #[test]
    fn negative_and_oversized_lengths_are_rejected() {
        // High bit set = negative int32, which p000\dnhn.java:348 refuses.
        let mut buf = vec![0xFF, 0xFF, 0xFF, 0xFF];
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Invalid);
        assert_eq!(buf.len(), 4, "buffer must not be drained on Invalid");

        let mut too_big = ((MAX_FRAME_LEN + 1) as u32).to_be_bytes().to_vec();
        assert_eq!(try_consume_frame(&mut too_big), ConsumeResult::Invalid);
    }

    #[test]
    fn zero_length_frame_is_valid() {
        let mut buf = frame_with_length(&[]);
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Frame(Vec::new()));
    }

    #[test]
    fn file_metadata_golden_bytes() {
        // Golden encoding pins the tag numbers. A round-trip test cannot: it passes
        // against any self-consistent (including wrong) tag assignment.
        let meta = SharingFileMetadata {
            name: "a.txt".to_string(),
            r#type: SharingFileType::Document as i32,
            payload_id: 7,
            size: 300,
            mime_type: "text/plain".to_string(),
            ..Default::default()
        };
        // field 1 (name)      : tag 0x0A, len 5, "a.txt"
        // field 2 (type)      : tag 0x10, varint 5   (DOCUMENT)
        // field 3 (payload_id): tag 0x18, varint 7
        // field 4 (size)      : tag 0x20, varint 300 = AC 02
        // field 5 (mime_type) : tag 0x2A, len 10, "text/plain"
        let want = "0A05612E7478741005180720AC022A0A746578742F706C61696E";
        assert_eq!(hex(&meta.encode_to_vec()), want);
    }

    #[test]
    fn connection_response_accept_encodes_status_one() {
        let accept = SharingConnectionResponseFrame {
            status: SharingResponseStatus::Accept as i32,
        };
        // field 1 varint 1 => 08 01. The old code emitted 00 bytes (status 0 is the
        // proto3 default and is not serialised at all), i.e. nothing.
        assert_eq!(hex(&accept.encode_to_vec()), "0801");

        let reject = SharingConnectionResponseFrame {
            status: SharingResponseStatus::Reject as i32,
        };
        assert_eq!(hex(&reject.encode_to_vec()), "0802");
    }

    #[test]
    fn sharing_frame_nesting_golden_bytes() {
        let frame = SharingFrame {
            version: SharingVersion::V1 as i32,
            v1: Some(SharingV1Frame {
                r#type: SharingFrameType::Introduction as i32,
                introduction: Some(IntroductionFrame {
                    required_package: "x".to_string(),
                    start_transfer: true,
                    use_case: ShareUseCase::NearbyShare as i32,
                    ..Default::default()
                }),
                ..Default::default()
            }),
        };
        // Frame.version = 1                         -> 08 01
        // Frame.v1 (len 11)                         -> 12 0B
        //   V1Frame.type = INTRODUCTION(1)          -> 08 01
        //   V1Frame.introduction (len 7)            -> 12 07
        //     required_package = "x"                -> 1A 01 78
        //     start_transfer = true                 -> 30 01
        //     use_case = NEARBY_SHARE(1)            -> 40 01
        assert_eq!(hex(&frame.encode_to_vec()), "0801120B080112071A017830014001");

        let back = SharingFrame::decode(frame.encode_to_vec().as_slice()).expect("decode");
        assert_eq!(back, frame);
    }

    #[test]
    fn paired_key_encryption_field_order() {
        // secret_id_hash is field 2 (tag 0x12) and optional_signed_data is field 3
        // (tag 0x1A), per p000\dzrr.java:12-21. Getting these two the wrong way round
        // is invisible to a round-trip test.
        let frame = PairedKeyEncryptionFrame {
            signed_data: vec![0x01],
            secret_id_hash: vec![0x02],
            optional_signed_data: vec![0x03],
            qr_code_handshake_data: vec![0x04],
        };
        assert_eq!(hex(&frame.encode_to_vec()), "0A01011201021A0103220104");
    }

    #[test]
    fn offline_frame_wraps_payload_transfer() {
        let offline = OfflineFrame {
            version: OfflineVersion::V1 as i32,
            v1: Some(OfflineV1Frame {
                r#type: OfflineFrameType::PayloadTransfer as i32,
                payload_transfer: Some(PayloadTransferFrame {
                    packet_type: PayloadPacketType::Data as i32,
                    payload_header: Some(PayloadHeader {
                        id: 9,
                        r#type: PayloadType::Bytes as i32,
                        total_size: 5,
                        ..Default::default()
                    }),
                    payload_chunk: Some(PayloadChunk {
                        flags: Some(1),
                        offset: Some(0),
                        body: Some(b"hello".to_vec()),
                    }),
                    ..Default::default()
                }),
                ..Default::default()
            }),
        };
        let back = OfflineFrame::decode(offline.encode_to_vec().as_slice()).expect("decode");
        assert_eq!(back, offline);
        // payload_transfer is field 4 of V1Frame -> tag 0x22.
        assert!(hex(&offline.encode_to_vec()).contains("22"));
    }

    #[test]
    fn connection_request_mediums_are_unpacked() {
        let req = ConnectionRequestFrame {
            endpoint_id: "ABCD".to_string(),
            endpoint_name: "n".to_string(),
            mediums: vec![ConnectionsMedium::WifiLan as i32],
            ..Default::default()
        };
        // field 1 "ABCD"  -> 0A 04 41 42 43 44
        // field 2 "n"     -> 12 01 6E
        // field 5 unpacked varint 5 -> 28 05  (packed would be 2A 01 05)
        assert_eq!(hex(&req.encode_to_vec()), "0A044142434412016E2805");
    }

    #[test]
    fn text_and_wifi_metadata_start_at_field_two() {
        let text = TextMetadata {
            text_title: "t".to_string(),
            ..Default::default()
        };
        // text_title is field 2 -> tag 0x12, not 0x0A.
        assert_eq!(hex(&text.encode_to_vec()), "120174");

        let wifi = WifiCredentialsMetadata {
            ssid: "s".to_string(),
            security_type: WifiSecurityType::WpaPsk as i32,
            ..Default::default()
        };
        // ssid field 2 -> 12 01 73; security_type field 3 -> 18 02
        assert_eq!(hex(&wifi.encode_to_vec()), "1201731802");
    }

    #[test]
    fn keep_alive_round_trip() {
        let ka = KeepAliveFrame { ack: true, seq_num: 3 };
        assert_eq!(hex(&ka.encode_to_vec()), "08011003");
        assert_eq!(
            KeepAliveFrame::decode(ka.encode_to_vec().as_slice()).expect("decode"),
            ka
        );
    }
}
