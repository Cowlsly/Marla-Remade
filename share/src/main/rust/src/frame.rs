//! Varint length-prefix + protobuf framing for Nearby Share (WireFormat) wire formats.
//!
//! References:
//! - `nearby_clone/sharing/proto/wire_format.proto` (Frame / V1Frame / Introduction etc.)
//! - `nearby_clone/connections/implementation/proto/offline_wire_formats.proto`
//!   (OfflineFrame is not used in the D2D TCP path but kept for tag verification).
//! - Nearby Sharing uses varint length-prefix around each handshake SecureMessage /
//!   SharingFrame / PayloadTransferFrame on the TCP stream. Kotlin does no framing;
//!   Rust buffers partial reads and consumes one frame at a time.

use prost::Message;

// ---------------------------------------------------------------------------
// Varint + length-prefix helpers (protobuf base128)
// ---------------------------------------------------------------------------

pub fn encode_varint(mut v: u32, out: &mut Vec<u8>) {
    loop {
        let mut b = (v & 0x7F) as u8;
        v >>= 7;
        if v != 0 {
            b |= 0x80;
            out.push(b);
        } else {
            out.push(b);
            break;
        }
    }
}

/// Decode a varint from `buf`. Returns (value, bytes_consumed).
pub fn decode_varint(buf: &[u8]) -> Option<(u32, usize)> {
    let mut result: u32 = 0;
    let mut shift = 0;
    for (i, &b) in buf.iter().enumerate() {
        let val = (b & 0x7F) as u32;
        if shift >= 32 {
            return None;
        }
        result |= val << shift;
        if b & 0x80 == 0 {
            return Some((result, i + 1));
        }
        shift += 7;
    }
    None
}

/// Prepends a varint length prefix.
pub fn frame_with_length(payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(5 + payload.len());
    encode_varint(payload.len() as u32, &mut out);
    out.extend_from_slice(payload);
    out
}

/// Try to consume one length-prefixed frame from `buf`. Returns `None` if
/// not enough bytes are buffered yet. Consumes the prefix + payload on success.
pub fn try_consume_frame(buf: &mut Vec<u8>) -> Option<Vec<u8>> {
    let (len, prefix_len) = decode_varint(buf)?;
    let total = prefix_len + len as usize;
    if buf.len() < total {
        return None;
    }
    let payload = buf[prefix_len..total].to_vec();
    buf.drain(..total);
    Some(payload)
}

// ---------------------------------------------------------------------------
// Sharing WireFormat (Introduction etc.)
// Mirrors `sharing/proto/wire_format.proto` per nearby_clone:
//   message Frame { optional Version version = 1; optional V1Frame v1 = 2; }
//   message V1Frame { optional FrameType type = 1; optional IntroductionFrame introduction = 2;
//                     optional ConnectionResponseFrame connection_response = 3; ... }
// For simplicity we expose IntroductionFrame directly via SharingFrame flattened oneof.
// Tag numbers are verified against nearby_clone/sharing/proto/wire_format.proto.
// ---------------------------------------------------------------------------

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingFrame {
    #[prost(enumeration = "SharingFrameType", tag = "1")]
    pub frame_type: i32,
    #[prost(message, optional, tag = "2")]
    pub introduction: Option<IntroductionFrame>,
    #[prost(message, optional, tag = "3")]
    pub connection_response: Option<SharingConnectionResponse>,
    #[prost(message, optional, tag = "4")]
    pub paired_key_encryption: Option<SharingPairedKeyEncryption>,
    #[prost(message, optional, tag = "5")]
    pub paired_key_result: Option<SharingPairedKeyResult>,
    #[prost(message, optional, tag = "6")]
    pub certificate: Option<CertificateFrame>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum SharingFrameType {
    Unknown = 0,
    Introduction = 1,
    ConnectionResponse = 2,
    PairedKeyEncryption = 3,
    PairedKeyResult = 4,
    Certificate = 5,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct IntroductionFrame {
    #[prost(message, repeated, tag = "1")]
    pub file_metadata: Vec<SharingFileMetadata>,
    #[prost(message, repeated, tag = "2")]
    pub text_metadata: Vec<TextMetadata>,
    #[prost(string, tag = "3")]
    pub required_package: String,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingFileMetadata {
    #[prost(string, tag = "1")]
    pub name: String,
    #[prost(int64, tag = "2")]
    pub size: i64,
    #[prost(string, tag = "3")]
    pub mime_type: String,
    #[prost(enumeration = "SharingFileType", tag = "4")]
    pub r#type: i32,
    #[prost(int64, tag = "5")]
    pub id: i64,
    #[prost(string, tag = "6")]
    pub payload_id: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum SharingFileType {
    Unknown = 0,
    Image = 1,
    Video = 2,
    App = 3,
    Audio = 4,
    File = 5,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct TextMetadata {
    #[prost(string, tag = "1")]
    pub text_title: String,
    #[prost(enumeration = "TextType", tag = "2")]
    pub r#type: i32,
    #[prost(int64, tag = "3")]
    pub size: i64,
    #[prost(int64, tag = "4")]
    pub id: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum TextType {
    Unknown = 0,
    Text = 1,
    Url = 2,
    Address = 3,
    PhoneNumber = 4,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingConnectionResponse {
    #[prost(int32, tag = "1")]
    pub status: i32, // 0 = accept (per wire_format.proto ACCEPT=1, but our legacy used 0=accept; keep for peer compat and map on recv)
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingPairedKeyEncryption {
    #[prost(bytes = "vec", tag = "1")]
    pub signed_data: Vec<u8>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingPairedKeyResult {
    #[prost(int32, tag = "1")]
    pub status: i32,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct CertificateFrame {
    #[prost(bytes = "vec", tag = "1")]
    pub cert_data: Vec<u8>,
}

// ---------------------------------------------------------------------------
// PayloadTransferFrame (chunked file/bytes transfer)
// Mirrors `connections/.../offline_wire_formats.proto` PayloadTransferFrame:
//   packet_type = 1, payload_header = 2, payload_chunk = 3, control_message = 4
// Verified tags: PayloadHeader id=1 type=2 total_size=3 is_sensitive=4 file_name=5 parent_folder=6
//                PayloadChunk flags=1 offset=2 body=3 index=4 (we omit index)
// Keep 16 KiB chunking + FLAG_LAST in payload.rs.
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum PayloadPacketType {
    Unknown = 0,
    Data = 1,
    Control = 2,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PayloadTransferFrame {
    #[prost(enumeration = "PayloadPacketType", tag = "1")]
    pub packet_type: i32,
    #[prost(message, optional, tag = "2")]
    pub payload_header: Option<PayloadHeader>,
    #[prost(message, optional, tag = "3")]
    pub payload_chunk: Option<PayloadChunk>,
    #[prost(message, optional, tag = "4")]
    pub control_message: Option<ControlMessage>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PayloadHeader {
    #[prost(int64, tag = "1")]
    pub id: i64,
    #[prost(enumeration = "PayloadType", tag = "2")]
    pub r#type: i32,
    #[prost(int64, tag = "3")]
    pub total_size: i64,
    #[prost(bool, tag = "4")]
    pub is_sensitive: bool,
    #[prost(string, tag = "5")]
    pub file_name: String,
    #[prost(string, tag = "6")]
    pub parent_folder: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum PayloadType {
    Unknown = 0,
    Bytes = 1,
    File = 2,
    Stream = 3,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PayloadChunk {
    #[prost(int32, tag = "1")]
    pub flags: i32, // bit 0 = last chunk (FLAG_LAST)
    #[prost(int64, tag = "2")]
    pub offset: i64,
    #[prost(bytes = "vec", tag = "3")]
    pub body: Vec<u8>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ControlMessage {
    #[prost(enumeration = "ControlEventType", tag = "1")]
    pub event: i32,
    #[prost(int64, tag = "2")]
    pub offset: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum ControlEventType {
    Unknown = 0,
    PayloadCanceled = 1,
    PayloadError = 2,
    PayloadCompleted = 3,
}

// ---------------------------------------------------------------------------
// Helpers to encode/decode length-prefixed prost messages generically
// ---------------------------------------------------------------------------

pub fn encode_length_prefixed<M: prost::Message>(msg: &M) -> Vec<u8> {
    let mut payload = Vec::new();
    msg.encode(&mut payload).expect("prost encode");
    frame_with_length(&payload)
}

pub fn decode_length_prefixed<M: prost::Message + Default>(bytes: &[u8]) -> Result<M, prost::DecodeError> {
    M::decode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varint_round_trip() {
        for v in [0u32, 1, 127, 128, 300, 16384, u32::MAX] {
            let mut out = Vec::new();
            encode_varint(v, &mut out);
            let (got, n) = decode_varint(&out).unwrap();
            assert_eq!(got, v);
            assert_eq!(n, out.len());
        }
    }

    #[test]
    fn length_prefix_round_trip() {
        let payload = b"hello sharing";
        let framed = frame_with_length(payload);
        let mut buf = framed.clone();
        let got = try_consume_frame(&mut buf).unwrap();
        assert_eq!(got, payload);
        assert!(buf.is_empty());
    }

    #[test]
    fn sharing_introduction_round_trip() {
        let intro = IntroductionFrame {
            file_metadata: vec![SharingFileMetadata {
                name: "photo.jpg".to_string(),
                size: 1234,
                mime_type: "image/jpeg".to_string(),
                r#type: SharingFileType::Image as i32,
                id: 1,
                payload_id: "1".to_string(),
            }],
            text_metadata: vec![],
            required_package: String::new(),
        };
        let frame = SharingFrame {
            frame_type: SharingFrameType::Introduction as i32,
            introduction: Some(intro.clone()),
            connection_response: None,
            paired_key_encryption: None,
            paired_key_result: None,
            certificate: None,
        };
        let mut buf = Vec::new();
        frame.encode(&mut buf).unwrap();
        let back = SharingFrame::decode(buf.as_slice()).unwrap();
        assert_eq!(frame, back);
        assert_eq!(back.introduction.unwrap().file_metadata[0].name, "photo.jpg");
    }

    #[test]
    fn payload_transfer_round_trip() {
        let pt = PayloadTransferFrame {
            packet_type: PayloadPacketType::Data as i32,
            payload_header: Some(PayloadHeader {
                id: 1,
                r#type: PayloadType::File as i32,
                total_size: 999,
                is_sensitive: false,
                file_name: "a.bin".to_string(),
                parent_folder: String::new(),
            }),
            payload_chunk: Some(PayloadChunk {
                flags: 0,
                offset: 0,
                body: b"hello".to_vec(),
            }),
            control_message: None,
        };
        let mut buf = Vec::new();
        pt.encode(&mut buf).unwrap();
        let back = PayloadTransferFrame::decode(buf.as_slice()).unwrap();
        assert_eq!(pt, back);
    }

    #[test]
    fn partial_frame_returns_none() {
        let payload = vec![0u8; 200];
        let framed = frame_with_length(&payload);
        let mut buf = framed[..10].to_vec();
        assert!(try_consume_frame(&mut buf).is_none());
        buf.extend_from_slice(&framed[10..]);
        let got = try_consume_frame(&mut buf).unwrap();
        assert_eq!(got.len(), 200);
    }
}
