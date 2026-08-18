//! Session state machine: Nearby Connections handshake, UKEY2, paired key, transfer.
//!
//! # Phases
//!
//! Everything is carried over the 4-byte-big-endian-framed socket described in
//! [`crate::frame`]. The channel changes meaning three times:
//!
//! | Phase | Body of each framed message | Source |
//! |---|---|---|
//! | [`Phase::Connecting`] | plaintext `OfflineFrame` — `CONNECTION_REQUEST` | `p000\dnlx.java:669-675` validates it |
//! | [`Phase::Ukey2`] | plaintext `Ukey2Message` — ClientInit / ServerInit / ClientFinished | `p000\dnij.java:102-107` writes all three through the same channel |
//! | [`Phase::ConnectionAccept`] | plaintext `OfflineFrame` — `CONNECTION_RESPONSE` | `p000\dncj.java:1204` writes it before the encryptor exists at `:1208` |
//! | [`Phase::PairedKey`], [`Phase::Ready`] | D2D-encrypted `OfflineFrame` | `p000\dnhn.java:212` encrypts before the length prefix, `:372` decrypts after it |
//!
//! # Ordering
//!
//! The response comes **after** UKEY2, not before, and both sides send it in
//! plaintext. Verified at four independent sites:
//!
//! - the client writes `CONNECTION_REQUEST` and then *immediately* starts the UKEY2
//!   client without waiting for anything (`p000\dnsi.java:9582` then `:9633`);
//! - the server reads `CONNECTION_REQUEST` and starts the UKEY2 server, sending no
//!   response (`p000\dnsi.java:5106` then `:5129`);
//! - `acceptConnection` writes `CONNECTION_RESPONSE` at `p000\dncj.java:1204` and
//!   only *then* calls `doeq.mo63639c()` at `:1208`, so it cannot have been
//!   encrypted;
//! - the encryptor is installed by `evaluateConnectionResult`
//!   (`p000\dnsi.java:4319`), which returns early unless **both** sides have
//!   accepted (`:4327-4339`).
//!
//! Accepting is programmatic at this layer, not a user prompt — Quick Share calls
//! `acceptConnection` as soon as the connection is offered (`p000\dzuj.java:76`) and
//! prompts the user later, with the Sharing-layer `INTRODUCTION` / `RESPONSE`.
//!
//! Sharing frames (`INTRODUCTION`, `RESPONSE`, `PAIRED_KEY_*`) are never framed
//! directly: each one is the body of a **BYTES** payload inside a
//! `PAYLOAD_TRANSFER` `OfflineFrame`. File bytes are **FILE** payloads.
//!
//! # UKEY2 cipher
//!
//! The record protocol is `AES_256_CBC-HMAC_SHA256` and nothing else. GMS offers
//! exactly that string (`p000\jgzt.java:798`) and rejects any other with
//! `"Incorrect next protocol"` / alert 103 (`p000\jgzt.java:550-551`). Offering
//! `Aes256GcmSiv`, as this module previously did, is rejected outright.

use std::collections::{HashMap, VecDeque};

use crypto_provider_default::CryptoProviderImpl;
use ukey2_connections::{
    D2DConnectionContextV1, D2DHandshakeContext, HandshakeImplementation,
    InitiatorD2DHandshakeContext, NextProtocol, ServerD2DHandshakeContext,
};

use crate::frame::{
    self, ConsumeResult, OfflineFrameType, PairedKeyResultStatus, PayloadPacketType, PayloadType,
    SharingFrameType, SharingResponseStatus,
};
use crate::payload::{self, FileMeta};

/// Public state surfaced over JNI. Ordinals are the `ShareState` contract.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum State {
    /// Connection request, UKEY2 and paired-key exchange in progress.
    Handshaking = 0,
    /// An `INTRODUCTION` arrived; the UI must prompt accept/reject.
    AwaitingAccept = 1,
    /// Accepted; payload bytes are flowing.
    Transferring = 2,
    /// Every announced payload arrived.
    Completed = 3,
    /// Protocol failure or user rejection.
    Failed = 4,
}

/// Which side opened the TCP socket. Determines who sends `CONNECTION_REQUEST`
/// and who plays the UKEY2 server.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    /// Dialled out; sends `CONNECTION_REQUEST` and drives UKEY2 as client.
    Initiator,
    /// Accepted the socket; is the UKEY2 server.
    Responder,
}

/// What the next framed message on the wire means.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Phase {
    /// Plaintext `OfflineFrame`: awaiting `CONNECTION_REQUEST`. Responder only — an
    /// initiator leaves this phase inside [`Session::new`].
    Connecting,
    /// Plaintext `Ukey2Message`.
    Ukey2,
    /// Plaintext `OfflineFrame`: the mutual `CONNECTION_RESPONSE` exchange. The
    /// D2D context already exists but must not be used yet.
    ConnectionAccept,
    /// Encrypted; the mandatory paired-key frame pair is in flight.
    PairedKey,
    /// Encrypted; introduction, response and payloads.
    Ready,
}

struct ActiveSend {
    payload_id: i64,
    name: String,
    total_size: i64,
    sent_offset: i64,
}

struct ActiveRecv {
    name: String,
    expected_size: i64,
    next_offset: i64,
    completed: bool,
}

/// One decrypted FILE chunk, waiting to be handed to Kotlin by
/// [`Session::drain_received`].
///
/// The body is owned only until it is drained, so a `Session` never holds a whole
/// received file: memory stays proportional to the drain interval, not to the file.
struct ReceivedChunk {
    payload_id: i64,
    offset: i64,
    total_size: i64,
    last: bool,
    name: String,
    body: Vec<u8>,
}

/// Version byte of the [`Session::drain_received`] record — `PROTOCOL_CONTRACT.md` §6.
pub const RECEIVED_RECORD_VERSION: u8 = 1;

/// [`ReceivedChunk`] `flags` bit 0: this is the last chunk of its payload.
pub const RECEIVED_FLAG_LAST: u8 = 1;

/// Encode one received chunk in the self-describing layout of
/// `PROTOCOL_CONTRACT.md` §6.
///
/// Big-endian and length-prefixed throughout, matching the wire framing convention,
/// so the whole record can be pinned by a golden-byte test.
fn encode_received_record(chunk: &ReceivedChunk) -> Vec<u8> {
    let name = chunk.name.as_bytes();
    // A name longer than u16 cannot be described by the layout. Truncating on a
    // char boundary keeps the record decodable as UTF-8; Kotlin only uses the name
    // to pick a file name, so a truncated one is recoverable where a corrupt record
    // is not.
    let name = if name.len() > u16::MAX as usize {
        let mut end = u16::MAX as usize;
        while end > 0 && !chunk.name.is_char_boundary(end) {
            end -= 1;
        }
        &chunk.name.as_bytes()[..end]
    } else {
        name
    };
    let mut out = Vec::with_capacity(32 + name.len() + chunk.body.len());
    out.push(RECEIVED_RECORD_VERSION);
    out.extend_from_slice(&chunk.payload_id.to_be_bytes());
    out.extend_from_slice(&chunk.offset.to_be_bytes());
    out.extend_from_slice(&chunk.total_size.to_be_bytes());
    out.push(if chunk.last { RECEIVED_FLAG_LAST } else { 0 });
    out.extend_from_slice(&(name.len() as u16).to_be_bytes());
    out.extend_from_slice(name);
    out.extend_from_slice(&(chunk.body.len() as u32).to_be_bytes());
    out.extend_from_slice(&chunk.body);
    out
}

enum HandshakeState {
    None,
    Initiator(Box<InitiatorD2DHandshakeContext<CryptoProviderImpl>>),
    Server(Box<ServerD2DHandshakeContext<CryptoProviderImpl>>),
    Done,
    Failed,
}

/// One peer connection's protocol state.
pub struct Session {
    /// Which side of the socket this is. Decides who sends `CONNECTION_REQUEST` and who
    /// opens the paired-key exchange.
    role: Role,
    /// Human-readable local device name, sent as `ConnectionRequestFrame.endpoint_name`.
    pub local_name: String,
    /// Opaque Nearby Sharing endpoint blob, sent as `ConnectionRequestFrame.endpoint_info`.
    pub local_endpoint_info: Vec<u8>,
    /// Public state for the UI.
    pub state: State,
    phase: Phase,
    local_endpoint_id: String,
    outbound: Vec<Vec<u8>>,
    inbound_buf: Vec<u8>,
    handshake: HandshakeState,
    secure: Option<D2DConnectionContextV1>,
    pending_files: Vec<FileMeta>,
    files_to_send: Vec<FileMeta>,
    introduction_pending: bool,
    next_payload_id: i64,
    keep_alive_seq: u32,
    paired_key_encryption_sent: bool,
    paired_key_result_sent: bool,
    peer_paired_key_result_seen: bool,
    active_send: Option<ActiveSend>,
    recvs: HashMap<i64, ActiveRecv>,
    /// Partially received BYTES payloads, keyed by payload id.
    bytes_recvs: HashMap<i64, Vec<u8>>,
    /// Recent protocol events, for [`Session::trace`]. Bounded; oldest dropped.
    trace: VecDeque<String>,
    received_queue: VecDeque<ReceivedChunk>,
    last_data_payload_id: Option<i64>,
    accepted: Option<bool>,
    failed_reason: Option<String>,
}

/// `AES_256_CBC-HMAC_SHA256` — the only record protocol GMS accepts
/// (`p000\jgzt.java:550`, `:798`).
const NEXT_PROTOCOL: NextProtocol = NextProtocol::Aes256CbcHmacSha256;

/// GMS uses the **Java** UKEY2 encoding, not the spec's.
///
/// `p000\jgzt.java:88-97` decodes the peer's public key by *parsing it as a protobuf*
/// (`jhav`, then `jhao.m136773e`) and alerts with `104 "Cannot parse public key"` when that
/// fails — so the P-256 key travels as a serialized `GenericPublicKey`/`EcP256PublicKey{x,y}`,
/// not as the SEC 1 point [`HandshakeImplementation::Spec`] emits. Using `Spec` against a
/// real device fails with a UKEY2 `BAD_MESSAGE_DATA` alert.
const HANDSHAKE_IMPL: HandshakeImplementation = HandshakeImplementation::PublicKeyInProtobuf;

fn new_initiator_handshake() -> InitiatorD2DHandshakeContext<CryptoProviderImpl> {
    InitiatorD2DHandshakeContext::new(HANDSHAKE_IMPL, vec![NEXT_PROTOCOL])
}

fn new_server_handshake() -> ServerD2DHandshakeContext<CryptoProviderImpl> {
    ServerD2DHandshakeContext::new(HANDSHAKE_IMPL, &[NEXT_PROTOCOL])
}

pub(crate) fn fill_random(buf: &mut [u8]) {
    if getrandom::getrandom(buf).is_err() {
        // getrandom only fails if the OS entropy source is unavailable, which on
        // Android means the process is already unusable. Leaving the buffer zeroed
        // would silently emit a constant-pattern decoy, defeating its purpose, so
        // callers must treat this as fatal — see `Session::enter_paired_key`.
        buf.fill(0);
    }
}

fn random_endpoint_id() -> String {
    // Nearby endpoint ids are 4 characters (`p000\dnlx.java:669` treats them as an
    // opaque required string; GMS generates 4 alphanumerics).
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    let mut raw = [0u8; 4];
    fill_random(&mut raw);
    raw.iter()
        .map(|b| {
            let idx = (*b as usize) % ALPHABET.len();
            char::from(ALPHABET.get(idx).copied().unwrap_or(b'A'))
        })
        .collect()
}

impl Session {
    /// Create a session for `role`.
    ///
    /// An initiator queues its `CONNECTION_REQUEST` **and** UKEY2 ClientInit back to
    /// back and enters [`Phase::Ukey2`] immediately: GMS writes the request and then
    /// starts the UKEY2 client without waiting for a response (`p000\dnsi.java:9582`
    /// then `:9633`). Waiting here is what deadlocks against a real device.
    ///
    /// A responder waits for the request.
    ///
    /// `local_endpoint_id` must be the id this device *advertises* — the mDNS
    /// `WifiLanServiceInfo` instance name carries it, so a session that invents its own
    /// dials out under a different identity than the one the peer discovered. An empty
    /// argument falls back to a fresh random id.
    pub fn new(
        role: Role,
        local_name: String,
        local_endpoint_info: Vec<u8>,
        local_endpoint_id: String,
    ) -> Self {
        let local_endpoint_id = if local_endpoint_id.is_empty() {
            random_endpoint_id()
        } else {
            local_endpoint_id
        };
        let mut session = Self {
            role,
            local_name,
            local_endpoint_info,
            state: State::Handshaking,
            phase: Phase::Connecting,
            local_endpoint_id,
            outbound: Vec::new(),
            inbound_buf: Vec::new(),
            handshake: HandshakeState::None,
            secure: None,
            pending_files: Vec::new(),
            files_to_send: Vec::new(),
            introduction_pending: false,
            next_payload_id: 1,
            keep_alive_seq: 0,
            paired_key_encryption_sent: false,
            paired_key_result_sent: false,
            peer_paired_key_result_seen: false,
            active_send: None,
            recvs: HashMap::new(),
            bytes_recvs: HashMap::new(),
            trace: VecDeque::new(),
            received_queue: VecDeque::new(),
            last_data_payload_id: None,
            accepted: None,
            failed_reason: None,
        };
        if role == Role::Initiator {
            let nonce = {
                let mut raw = [0u8; 4];
                fill_random(&mut raw);
                i32::from_be_bytes(raw).saturating_abs()
            };
            let request = payload::build_connection_request(
                &session.local_endpoint_id,
                &session.local_name,
                &session.local_endpoint_info,
                nonce,
            );
            session.push_plaintext(&request);
            let hs = new_initiator_handshake();
            match hs.get_next_handshake_message() {
                Some(client_init) => {
                    session.push_plaintext(&client_init);
                    session.handshake = HandshakeState::Initiator(Box::new(hs));
                    session.phase = Phase::Ukey2;
                }
                None => session.fail("initiator produced no UKEY2 ClientInit"),
            }
        }
        session
    }

    /// Files announced by the peer's `INTRODUCTION`.
    pub fn pending_files(&self) -> &[FileMeta] {
        &self.pending_files
    }

    /// Record a protocol event. Bounded ring buffer; the oldest entry is dropped.
    ///
    /// This exists because a peer that simply stops talking gives no other clue about which
    /// frame it disliked — the wire is encrypted, so a packet capture cannot answer it
    /// either.
    fn note(&mut self, event: String) {
        const MAX_TRACE: usize = 64;
        if self.trace.len() >= MAX_TRACE {
            let _ = self.trace.pop_front();
        }
        self.trace.push_back(event);
    }

    /// The recent protocol events, oldest first, one per line.
    pub fn trace_text(&self) -> String {
        self.trace.iter().cloned().collect::<Vec<_>>().join("\n")
    }

    /// Take everything queued for the socket, already length-prefixed.
    pub fn outbound_drain(&mut self) -> Option<Vec<u8>> {
        if self.outbound.is_empty() {
            return None;
        }
        let total: usize = self.outbound.iter().map(Vec::len).sum();
        let mut out = Vec::with_capacity(total);
        for f in self.outbound.drain(..) {
            out.extend_from_slice(&f);
        }
        Some(out)
    }

    /// State ordinal for JNI.
    pub fn query_state(&self) -> i32 {
        self.state as i32
    }

    fn fail(&mut self, reason: &str) {
        self.state = State::Failed;
        self.failed_reason = Some(reason.to_string());
        self.handshake = HandshakeState::Failed;
    }

    fn push_plaintext(&mut self, body: &[u8]) {
        self.outbound.push(frame::frame_with_length(body));
    }

    fn send_encrypted(&mut self, plain: &[u8]) -> i32 {
        let Some(secure) = self.secure.as_mut() else {
            return -2;
        };
        let wire = secure.encode_message_to_peer::<CryptoProviderImpl>(plain, None::<&[u8]>);
        self.outbound.push(frame::frame_with_length(&wire));
        0
    }

    fn alloc_payload_id(&mut self) -> i64 {
        let id = self.next_payload_id;
        self.next_payload_id = self.next_payload_id.saturating_add(1);
        id
    }

    /// Send a Sharing `Frame` as the body of a BYTES payload.
    fn send_sharing(&mut self, sharing_frame: &[u8]) -> i32 {
        let id = self.alloc_payload_id();
        let frames = payload::bytes_payload(id, sharing_frame);
        self.note(format!(
            "out BYTES id {id} as {} frame(s), {}B body",
            frames.len(),
            sharing_frame.len(),
        ));
        for offline in frames {
            let rc = self.send_encrypted(&offline);
            if rc < 0 {
                return rc;
            }
        }
        0
    }

    // ------------------------------------------------------------------
    // Inbound
    // ------------------------------------------------------------------

    /// Feed raw socket bytes. Returns 0 on success, negative on protocol failure.
    pub fn feed_inbound(&mut self, bytes: &[u8]) -> i32 {
        if self.state == State::Failed || self.state == State::Completed {
            return 0;
        }
        self.inbound_buf.extend_from_slice(bytes);
        loop {
            match frame::try_consume_frame(&mut self.inbound_buf) {
                ConsumeResult::Incomplete => return 0,
                ConsumeResult::Invalid => {
                    self.fail("peer announced a negative or oversized frame length");
                    return -2;
                }
                ConsumeResult::Frame(body) => {
                    let rc = self.handle_one_frame(&body);
                    if rc < 0 {
                        return rc;
                    }
                }
            }
        }
    }

    fn handle_one_frame(&mut self, body: &[u8]) -> i32 {
        match self.phase {
            Phase::Connecting => self.handle_connecting_frame(body),
            Phase::Ukey2 => self.handle_handshake_frame(body),
            Phase::ConnectionAccept => self.handle_connection_accept_frame(body),
            Phase::PairedKey | Phase::Ready => self.handle_encrypted_frame(body),
        }
    }

    /// Responder only: read `CONNECTION_REQUEST` and become the UKEY2 server.
    ///
    /// No `CONNECTION_RESPONSE` is written here. GMS's `onIncomingConnection` goes
    /// straight from the parsed request (`p000\dnsi.java:5106`) to `startServer`
    /// (`:5129`); the response is written later, by `acceptConnection`
    /// (`p000\dncj.java:1204`). Answering early makes a real peer try to parse the
    /// response as UKEY2 message 1 and abort.
    fn handle_connecting_frame(&mut self, body: &[u8]) -> i32 {
        let Ok(offline) = payload::parse_offline_frame(body) else {
            self.fail("first frame is not a parseable OfflineFrame");
            return -2;
        };
        let Some(v1) = offline.v1 else {
            self.fail("OfflineFrame without v1");
            return -2;
        };
        if v1.r#type != OfflineFrameType::ConnectionRequest as i32 {
            // GMS's own wording for this: "In readConnectionRequestFrame, expected a
            // CONNECTION_REQUEST v1 OfflineFrame but got a %s frame instead"
            // (p000\dnsi.java:4067).
            self.fail("expected a CONNECTION_REQUEST OfflineFrame");
            return -2;
        }
        self.handshake = HandshakeState::Server(Box::new(new_server_handshake()));
        self.phase = Phase::Ukey2;
        0
    }

    /// Read the peer's plaintext `CONNECTION_RESPONSE`.
    ///
    /// Both sides have now accepted, which is exactly the gate
    /// `evaluateConnectionResult` applies before installing the encryptor
    /// (`p000\dnsi.java:4327-4339`, install at `:4366`), so encryption starts here.
    fn handle_connection_accept_frame(&mut self, body: &[u8]) -> i32 {
        let Ok(offline) = payload::parse_offline_frame(body) else {
            self.fail("CONNECTION_RESPONSE is not a parseable OfflineFrame");
            return -2;
        };
        let Some(v1) = offline.v1 else {
            self.fail("OfflineFrame without v1");
            return -2;
        };
        if v1.r#type != OfflineFrameType::ConnectionResponse as i32 {
            self.fail("expected a CONNECTION_RESPONSE OfflineFrame");
            return -2;
        }
        let Some(response) = v1.connection_response else {
            self.fail("CONNECTION_RESPONSE without a connection_response field");
            return -2;
        };
        if !response.accepted() {
            let status = response.status.unwrap_or_default();
            self.fail(&format!(
                "peer rejected the connection (status {status})"
            ));
            return -2;
        }
        // Both sides have accepted, so the channel is encrypted from here. Who speaks
        // first in the paired-key exchange is role-dependent: see `enter_paired_key`.
        self.enter_paired_key()
    }

    fn handle_handshake_frame(&mut self, body: &[u8]) -> i32 {
        // Take the handshake out so its borrow does not collide with `self.fail`
        // and `self.push_plaintext` below.
        let mut hs = std::mem::replace(&mut self.handshake, HandshakeState::None);
        let fed = match &mut hs {
            HandshakeState::Initiator(ctx) => {
                ctx.handle_handshake_message(body).map_err(|e| format!("{e:?}"))
            }
            HandshakeState::Server(ctx) => {
                ctx.handle_handshake_message(body).map_err(|e| format!("{e:?}"))
            }
            _ => Err("handshake frame arrived with no handshake in progress".to_string()),
        };
        if let Err(reason) = fed {
            self.handshake = hs;
            self.fail(&format!("UKEY2 handshake failed: {reason}"));
            return -2;
        }
        // Any message the library wants to send next (ServerInit / ClientFinished).
        let next = match &mut hs {
            HandshakeState::Initiator(ctx) => ctx.get_next_handshake_message(),
            HandshakeState::Server(ctx) => ctx.get_next_handshake_message(),
            _ => None,
        };
        if let Some(msg) = next {
            self.push_plaintext(&msg);
        }
        let complete = match &hs {
            HandshakeState::Initiator(ctx) => ctx.is_handshake_complete(),
            HandshakeState::Server(ctx) => ctx.is_handshake_complete(),
            _ => false,
        };
        if !complete {
            self.handshake = hs;
            return 0;
        }
        let ctx_result = match hs {
            HandshakeState::Initiator(mut ctx) => {
                ctx.to_connection_context().map_err(|e| format!("{e:?}"))
            }
            HandshakeState::Server(mut ctx) => {
                ctx.to_connection_context().map_err(|e| format!("{e:?}"))
            }
            _ => Err("handshake completed without a context".to_string()),
        };
        match ctx_result {
            Ok(ctx) => {
                self.secure = Some(ctx);
                self.handshake = HandshakeState::Done;
                self.enter_connection_accept()
            }
            Err(reason) => {
                self.fail(&format!("UKEY2 context derivation failed: {reason}"));
                -2
            }
        }
    }

    /// Send our plaintext `CONNECTION_RESPONSE{status: 0, response: ACCEPT}`.
    ///
    /// The D2D context exists by now but stays unused: the channel is still
    /// plaintext, because GMS writes this frame before `doeq.mo63639c()`
    /// (`p000\dncj.java:1204-1208`) and installs the encryptor only once both sides
    /// have accepted. Dispatch is keyed on [`Phase`], so holding the context in
    /// `secure` early cannot leak encryption into this phase.
    ///
    /// Accepting unconditionally mirrors Quick Share, which accepts at the Nearby
    /// Connections layer programmatically (`p000\dzuj.java:76`) and asks the user
    /// later, with the Sharing-layer `INTRODUCTION`.
    fn enter_connection_accept(&mut self) -> i32 {
        self.phase = Phase::ConnectionAccept;
        let response = payload::build_offline_connection_response(true);
        self.push_plaintext(&response);
        0
    }

    /// Move to [`Phase::PairedKey`], and send `PAIRED_KEY_ENCRYPTION` only as the
    /// responder.
    ///
    /// The frame pair is mandatory — a GMS peer waits for it and gives up after roughly
    /// five seconds — but **the responder speaks first**, and an initiator that sends it
    /// proactively is disconnected.
    ///
    /// Measured against a Pixel 7 Pro on GMS 26.24.34, `:share` initiating: sending our
    /// `PAIRED_KEY_ENCRYPTION` as soon as the `CONNECTION_RESPONSE` arrived drew a
    /// `DISCONNECTION` frame 250 ms later, while staying silent had the receiver send its
    /// own frame first and accept our reply. The receiver's Sharing layer is still
    /// validating the incoming connection (`p000\each.java:2092-2112`) when the response
    /// lands, so it is not yet ready to be spoken to.
    ///
    /// `:share` responds to `:share` under the same rule, since the responder there is
    /// also the receiver.
    fn enter_paired_key(&mut self) -> i32 {
        self.phase = Phase::PairedKey;
        if self.role == Role::Initiator {
            return 0;
        }
        self.send_paired_key_encryption()
    }

    /// `PAIRED_KEY_ENCRYPTION` with decoy fields, sent at most once.
    ///
    /// `:share` has no contact certificate, so both byte fields are fresh random decoys of
    /// the exact widths GMS uses when signing fails.
    fn send_paired_key_encryption(&mut self) -> i32 {
        if self.paired_key_encryption_sent {
            return 0;
        }
        self.paired_key_encryption_sent = true;
        let frame = payload::build_paired_key_encryption_decoy(fill_random);
        self.send_sharing(&frame)
    }

    fn handle_encrypted_frame(&mut self, body: &[u8]) -> i32 {
        let Some(secure) = self.secure.as_mut() else {
            self.fail("encrypted frame before the secure channel existed");
            return -2;
        };
        let plain = match secure.decode_message_from_peer::<CryptoProviderImpl>(body, None::<&[u8]>) {
            Ok(v) => v,
            Err(e) => {
                self.fail(&format!("D2D decrypt failed: {e:?}"));
                return -2;
            }
        };
        let Ok(offline) = payload::parse_offline_frame(&plain) else {
            // GMS logs exactly this situation as "Read an unencrypted (or garbage)
            // frame when we expected an encrypted frame." (p000\dnhn.java:381).
            self.fail("decrypted body is not a parseable OfflineFrame");
            return -2;
        };
        let Some(v1) = offline.v1 else {
            self.fail("OfflineFrame without v1");
            return -2;
        };
        if v1.r#type == OfflineFrameType::KeepAlive as i32 {
            if let Some(ka) = v1.keep_alive {
                if !ka.ack {
                    let reply = payload::build_keep_alive(true, ka.seq_num);
                    return self.send_encrypted(&reply);
                }
            }
            return 0;
        }
        self.note(format!("in OfflineFrame type {}", v1.r#type));
        // A bandwidth upgrade must be answered even though `:share` never takes one: it is
        // already on WIFI_LAN, the medium a peer upgrades *to*. Measured against a Pixel 7
        // Pro on GMS 26.24.34 — it sends BANDWIDTH_UPGRADE_RETRY, and if that goes
        // unanswered it never sends PAIRED_KEY_RESULT or the INTRODUCTION, keep-alives for
        // ~15 s and then disconnects.
        if v1.r#type == OfflineFrameType::BandwidthUpgradeNegotiation as i32
            || v1.r#type == OfflineFrameType::BandwidthUpgradeRetry as i32
        {
            let event = v1
                .bandwidth_upgrade_negotiation
                .as_ref()
                .map_or(0, |b| b.event_type);
            self.note(format!("declining bandwidth upgrade (event {event})"));
            let decline = payload::build_bandwidth_upgrade_failure();
            return self.send_encrypted(&decline);
        }
        if v1.r#type == OfflineFrameType::Disconnection as i32 {
            if self.state == State::Transferring || self.state == State::Handshaking {
                // Name the phase: a peer that hangs up on us does so for a reason specific
                // to what it just read, and the phase is the only clue we get.
                let reason = format!(
                    "peer disconnected during {:?} (paired-key sent: {}, peer result seen: {})",
                    self.phase, self.paired_key_result_sent, self.peer_paired_key_result_seen,
                );
                self.fail(&reason);
                return -2;
            }
            return 0;
        }
        if v1.r#type != OfflineFrameType::PayloadTransfer as i32 {
            // BANDWIDTH_UPGRADE_NEGOTIATION and the auth frames are not implemented;
            // ignoring them keeps a GMS peer's optional traffic from killing the session.
            return 0;
        }
        let Some(pt) = v1.payload_transfer else {
            return 0;
        };
        self.handle_payload_transfer(pt)
    }

    fn handle_payload_transfer(&mut self, pt: frame::PayloadTransferFrame) -> i32 {
        if pt.packet_type != PayloadPacketType::Data as i32 {
            return 0;
        }
        let Some(chunk) = pt.payload_chunk else {
            return 0;
        };
        let (payload_id, payload_type, name, total_size) = match pt.payload_header.as_ref() {
            Some(h) => (h.id, h.r#type, h.file_name.clone(), h.total_size),
            None => {
                // Continuation chunk with no header: it belongs to the payload the
                // previous DATA frame identified. `:share` repeats the header on
                // every chunk, so this only happens for a peer that does not.
                let Some(id) = self.last_data_payload_id else {
                    return 0;
                };
                let Some(entry) = self.recvs.get(&id) else {
                    return 0;
                };
                (id, PayloadType::File as i32, entry.name.clone(), entry.expected_size)
            }
        };
        self.last_data_payload_id = Some(payload_id);
        // The peer's chunking convention, measured rather than assumed: a BYTES payload may
        // arrive as one body+FLAG_LAST chunk or as a body chunk closed by an empty one.
        self.note(format!(
            "in PAYLOAD id {payload_id} type {payload_type} off {} len {} flags {}",
            chunk.offset,
            chunk.body.len(),
            chunk.flags,
        ));

        if payload_type == PayloadType::Bytes as i32 {
            // A BYTES payload carries a Sharing Frame, but not necessarily in one chunk:
            // the body may arrive on a chunk with no flags and be closed by a later,
            // empty chunk carrying FLAG_LAST. Reassemble by offset and dispatch on the
            // flag, which handles both that form and a single body+FLAG_LAST chunk.
            let last = (chunk.flags & payload::FLAG_LAST) != 0;
            let offset = usize::try_from(chunk.offset).unwrap_or(usize::MAX);
            let have = self.bytes_recvs.entry(payload_id).or_default();
            let gap = offset > have.len();
            if offset == have.len() {
                have.extend_from_slice(&chunk.body);
            }
            if gap {
                let have_len = have.len();
                let _ = self.bytes_recvs.remove(&payload_id);
                self.note(format!("BYTES {payload_id} gap: chunk at {offset}, have {have_len}"));
                return 0;
            }
            if !last {
                return 0;
            }
            let body = self.bytes_recvs.remove(&payload_id).unwrap_or_default();
            return self.handle_sharing_frame(&body);
        }

        let entry = self.recvs.entry(payload_id).or_insert_with(|| ActiveRecv {
            name,
            expected_size: total_size,
            next_offset: 0,
            completed: false,
        });
        let last = (chunk.flags & payload::FLAG_LAST) != 0;
        let record = ReceivedChunk {
            payload_id,
            offset: chunk.offset,
            total_size: entry.expected_size,
            last,
            name: entry.name.clone(),
            body: chunk.body,
        };
        entry.next_offset = entry.next_offset.saturating_add(record.body.len() as i64);
        if last {
            entry.completed = true;
        }
        // Queued, not accumulated: the body leaves the session on the next
        // `drain_received`, so a multi-gigabyte payload costs one chunk of memory.
        self.received_queue.push_back(record);
        if last {
            let all_done = !self.recvs.is_empty() && self.recvs.values().all(|r| r.completed);
            if all_done {
                self.state = State::Completed;
            }
        }
        0
    }

    fn handle_sharing_frame(&mut self, body: &[u8]) -> i32 {
        let Ok(sharing) = payload::parse_sharing_frame(body) else {
            // Not fatal: a BYTES payload we do not understand is ignorable.
            self.note(format!("in Sharing frame undecodable ({}B)", body.len()));
            return 0;
        };
        let Some(v1) = sharing.v1 else {
            self.note("in Sharing frame without v1".to_string());
            return 0;
        };
        self.note(format!("in Sharing type {}", v1.r#type));
        if v1.r#type == SharingFrameType::PairedKeyEncryption as i32 {
            // The peer spoke first, which is the responder's job; ours goes out now that it
            // is demonstrably ready to be spoken to.
            let rc = self.send_paired_key_encryption();
            if rc < 0 {
                return rc;
            }
            if !self.paired_key_result_sent {
                self.paired_key_result_sent = true;
                // UNABLE, not FAIL: we cannot verify a paired key at all, as
                // opposed to having verified one and rejected it (p000\duvz.java).
                let reply = payload::build_paired_key_result(PairedKeyResultStatus::Unable);
                let rc = self.send_sharing(&reply);
                if rc < 0 {
                    return rc;
                }
            }
            self.maybe_enter_ready();
            return 0;
        }
        if v1.r#type == SharingFrameType::PairedKeyResult as i32 {
            self.peer_paired_key_result_seen = true;
            self.maybe_enter_ready();
            return 0;
        }
        if v1.r#type == SharingFrameType::Cancel as i32 {
            self.fail("peer cancelled the transfer");
            return -2;
        }
        if v1.r#type == SharingFrameType::Introduction as i32 {
            let Some(intro) = v1.introduction else {
                return 0;
            };
            let files = payload::introduction_files(&intro);
            if files.is_empty() && intro.text_metadata.is_empty() {
                return 0;
            }
            self.pending_files = files;
            if self.state == State::Handshaking {
                self.state = State::AwaitingAccept;
            }
            return 0;
        }
        if v1.r#type == SharingFrameType::Response as i32 {
            let Some(resp) = v1.connection_response else {
                return 0;
            };
            return self.handle_response_status(resp.status);
        }
        0
    }

    fn handle_response_status(&mut self, status: i32) -> i32 {
        if status == SharingResponseStatus::Accept as i32 {
            if self.state == State::Handshaking || self.state == State::AwaitingAccept {
                self.state = State::Transferring;
            }
            return 0;
        }
        let reason = if status == SharingResponseStatus::Reject as i32 {
            "peer rejected the transfer"
        } else if status == SharingResponseStatus::NotEnoughSpace as i32 {
            "peer has not enough space"
        } else if status == SharingResponseStatus::UnsupportedAttachmentType as i32 {
            "peer does not support this attachment type"
        } else if status == SharingResponseStatus::TimedOut as i32 {
            "peer timed out waiting for the user"
        } else {
            "peer sent an unknown connection response status"
        };
        self.fail(reason);
        0
    }

    /// Move to [`Phase::Ready`] once our half of the paired-key exchange is done.
    ///
    /// Deliberately **not** gated on the peer's `PAIRED_KEY_RESULT`. Measured against a
    /// Pixel 7 Pro on GMS 26.24.34: it sends `PAIRED_KEY_ENCRYPTION`, accepts our
    /// encryption + `UNABLE` result, and then sends no result of its own — it is waiting
    /// for the `INTRODUCTION`. Waiting for a result it never sends deadlocks both sides
    /// until the peer gives up (~15 s) and sends `DISCONNECTION`.
    ///
    /// `peer_paired_key_result_seen` is still tracked, for the failure reason.
    fn maybe_enter_ready(&mut self) {
        if self.phase != Phase::PairedKey {
            return;
        }
        if !self.paired_key_result_sent {
            return;
        }
        self.phase = Phase::Ready;
        if self.introduction_pending {
            self.introduction_pending = false;
            let _ = self.emit_introduction();
        }
    }

    // ------------------------------------------------------------------
    // Outbound API
    // ------------------------------------------------------------------

    /// Answer an `INTRODUCTION` with `ACCEPT` or `REJECT`.
    pub fn accept(&mut self, accept: bool, _dest_dir: &str) -> i32 {
        if self.state != State::AwaitingAccept {
            return -2;
        }
        let frame = payload::build_connection_response(accept);
        let rc = self.send_sharing(&frame);
        if rc < 0 {
            return rc;
        }
        self.accepted = Some(accept);
        if accept {
            self.state = State::Transferring;
        } else {
            self.fail("local user rejected the transfer");
        }
        0
    }

    /// Stage the files this side intends to send.
    pub fn set_pending_files_for_send(&mut self, files: Vec<FileMeta>) {
        self.files_to_send = files;
    }

    /// Queue the `INTRODUCTION` frame, deferring it until the paired-key exchange
    /// finishes if it has not yet.
    pub fn queue_introduction(&mut self) -> i32 {
        if self.files_to_send.is_empty() {
            return 0;
        }
        if self.phase != Phase::Ready {
            self.introduction_pending = true;
            return 0;
        }
        self.emit_introduction()
    }

    fn emit_introduction(&mut self) -> i32 {
        let first_id = self.alloc_payload_id();
        // Reserve one id per file so `alloc_payload_id` cannot hand the same id to
        // a later BYTES payload.
        let extra = self.files_to_send.len().saturating_sub(1) as i64;
        self.next_payload_id = self.next_payload_id.saturating_add(extra);
        let frame = payload::build_introduction_frame(&self.files_to_send, first_id);
        for (i, f) in self.files_to_send.iter_mut().enumerate() {
            f.payload_id = first_id.saturating_add(i as i64);
        }
        self.send_sharing(&frame)
    }

    /// Emit a keep-alive so a long transfer does not look idle.
    pub fn send_keep_alive(&mut self) -> i32 {
        if self.phase != Phase::Ready && self.phase != Phase::PairedKey {
            return -2;
        }
        self.keep_alive_seq = self.keep_alive_seq.wrapping_add(1);
        let seq = self.keep_alive_seq;
        let frame = payload::build_keep_alive(false, seq);
        self.send_encrypted(&frame)
    }

    /// Begin a FILE payload.
    ///
    /// The payload id comes from the `INTRODUCTION` we already sent, so the peer can
    /// match the bytes to the metadata it showed the user. A file we never announced
    /// gets a fresh id, which a GMS peer will ignore.
    pub fn open_file(&mut self, file_name: &str, file_size: i64) -> i32 {
        if self.secure.is_none() {
            return -2;
        }
        let announced = self
            .files_to_send
            .iter()
            .find(|f| f.name == file_name)
            .map(|f| f.payload_id)
            .filter(|id| *id > 0);
        let payload_id = match announced {
            Some(id) => id,
            None => self.alloc_payload_id(),
        };
        self.active_send = Some(ActiveSend {
            payload_id,
            name: file_name.to_string(),
            total_size: file_size,
            sent_offset: 0,
        });
        0
    }

    /// Send one chunk of the open FILE payload.
    pub fn write_chunk(&mut self, chunk: &[u8]) -> i32 {
        let Some(active) = self.active_send.as_ref() else {
            return -2;
        };
        if self.secure.is_none() {
            return -2;
        }
        let payload_id = active.payload_id;
        let file_name = active.name.clone();
        let total_size = active.total_size;
        let offset = active.sent_offset;
        // `chunk_payload` splits down to the wire chunk size, so the Kotlin read
        // buffer size and the payload chunk size stay independent.
        let frames = payload::chunk_payload(payload_id, chunk, &file_name, total_size, offset);
        let mut cursor = offset;
        let mut finished = false;
        for frame in &frames {
            let body_len = frame
                .payload_chunk
                .as_ref()
                .map_or(0, |c| c.body.len() as i64);
            let is_last = frame
                .payload_chunk
                .as_ref()
                .is_some_and(|c| (c.flags & payload::FLAG_LAST) != 0);
            let offline = payload::wrap_payload_transfer(frame.clone());
            let rc = self.send_encrypted(&offline);
            if rc < 0 {
                return rc;
            }
            cursor = cursor.saturating_add(body_len);
            if is_last {
                finished = true;
            }
        }
        if finished {
            self.active_send = None;
        } else if let Some(active) = self.active_send.as_mut() {
            active.sent_offset = cursor;
        }
        0
    }

    /// Finish the open FILE payload.
    pub fn close_file(&mut self) -> i32 {
        self.active_send = None;
        0
    }

    /// Take the oldest received FILE chunk, encoded per `PROTOCOL_CONTRACT.md` §6.
    ///
    /// `None` once the queue is empty. BYTES payloads are Sharing frames handled
    /// in-process and never appear here.
    pub fn drain_received(&mut self) -> Option<Vec<u8>> {
        let chunk = self.received_queue.pop_front()?;
        Some(encode_received_record(&chunk))
    }

    /// The specific reason this session failed, for the UI and for logcat.
    pub fn failure_reason(&self) -> Option<&str> {
        self.failed_reason.as_deref()
    }

    #[cfg(test)]
    fn is_ready(&self) -> bool {
        self.phase == Phase::Ready
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Split a drained outbound buffer back into its individual framed messages.
    fn split_frames(raw: &[u8]) -> Vec<Vec<u8>> {
        let mut buf = raw.to_vec();
        let mut out = Vec::new();
        loop {
            match frame::try_consume_frame(&mut buf) {
                ConsumeResult::Frame(body) => out.push(body),
                ConsumeResult::Incomplete => break,
                ConsumeResult::Invalid => panic!("drained buffer is not well framed"),
            }
        }
        assert!(buf.is_empty(), "trailing bytes after the last frame");
        out
    }

    /// The `V1Frame` of `body`, if `body` really is a plaintext `OfflineFrame`.
    ///
    /// Re-encoding and requiring byte equality is what makes this a *proof* rather
    /// than a guess: `prost` will happily decode many byte strings into a mostly
    /// empty message, but an encrypted body will not round-trip.
    fn plaintext_offline(body: &[u8]) -> Option<frame::OfflineV1Frame> {
        let offline = payload::parse_offline_frame(body).ok()?;
        if prost::Message::encode_to_vec(&offline) != body {
            return None;
        }
        offline.v1
    }

    fn frame_types(raw: &[u8]) -> Vec<Option<i32>> {
        split_frames(raw)
            .iter()
            .map(|f| plaintext_offline(f).map(|v1| v1.r#type))
            .collect()
    }

    /// One decoded `drain_received` record.
    struct Drained {
        payload_id: i64,
        offset: i64,
        total_size: i64,
        last: bool,
        name: String,
        body: Vec<u8>,
    }

    fn decode_received_record(raw: &[u8]) -> Drained {
        fn i64_at(raw: &[u8], at: usize) -> i64 {
            i64::from_be_bytes(raw[at..at + 8].try_into().expect("8 bytes"))
        }
        assert_eq!(raw[0], RECEIVED_RECORD_VERSION, "record version");
        let payload_id = i64_at(raw, 1);
        let offset = i64_at(raw, 9);
        let total_size = i64_at(raw, 17);
        let flags = raw[25];
        let name_len = u16::from_be_bytes(raw[26..28].try_into().expect("2 bytes")) as usize;
        let name = String::from_utf8(raw[28..28 + name_len].to_vec()).expect("utf8 name");
        let body_at = 28 + name_len;
        let body_len =
            u32::from_be_bytes(raw[body_at..body_at + 4].try_into().expect("4 bytes")) as usize;
        let body = raw[body_at + 4..body_at + 4 + body_len].to_vec();
        assert_eq!(body_at + 4 + body_len, raw.len(), "record has trailing bytes");
        Drained {
            payload_id,
            offset,
            total_size,
            last: (flags & RECEIVED_FLAG_LAST) != 0,
            name,
            body,
        }
    }

    fn drain_all_received(s: &mut Session) -> Vec<Drained> {
        let mut out = Vec::new();
        while let Some(raw) = s.drain_received() {
            out.push(decode_received_record(&raw));
        }
        out
    }

    /// Pump both sides until neither has anything left to send.
    fn settle(a: &mut Session, b: &mut Session) {
        for _ in 0..32 {
            let mut moved = false;
            if let Some(out) = a.outbound_drain() {
                assert!(b.feed_inbound(&out) >= 0, "b failed: {:?}", b.failed_reason.as_deref());
                moved = true;
            }
            if let Some(out) = b.outbound_drain() {
                assert!(a.feed_inbound(&out) >= 0, "a failed: {:?}", a.failed_reason.as_deref());
                moved = true;
            }
            if !moved {
                return;
            }
        }
        panic!("sessions did not settle");
    }

    /// A session with a random endpoint id, which the empty-id fallback supplies.
    fn test_session(role: Role, local_name: &str, local_endpoint_info: Vec<u8>) -> Session {
        Session::new(
            role,
            local_name.to_string(),
            local_endpoint_info,
            String::new(),
        )
    }

    fn connected_pair() -> (Session, Session) {
        let mut initiator = test_session(Role::Initiator, "Alice", b"alice-info".to_vec());
        let mut responder = test_session(Role::Responder, "Bob", b"bob-info".to_vec());
        settle(&mut initiator, &mut responder);
        (initiator, responder)
    }

    #[test]
    fn connection_request_then_ukey2_then_paired_key_reaches_ready() {
        let (initiator, responder) = connected_pair();
        assert!(initiator.secure.is_some(), "initiator has no secure channel");
        assert!(responder.secure.is_some(), "responder has no secure channel");
        assert!(initiator.is_ready(), "initiator not Ready: {:?}", initiator.failed_reason.as_deref());
        assert!(responder.is_ready(), "responder not Ready: {:?}", responder.failed_reason.as_deref());
        assert_eq!(initiator.state, State::Handshaking);
    }

    #[test]
    fn initiators_first_batch_is_the_request_and_ukey2_together() {
        // The interop fix: GMS writes CONNECTION_REQUEST and then starts the UKEY2
        // client without waiting (p000\dnsi.java:9582 then :9633). An initiator that
        // blocks for a CONNECTION_RESPONSE here deadlocks against a real device.
        let mut initiator = test_session(Role::Initiator, "Alice", vec![]);
        let raw = initiator.outbound_drain().expect("initiator sends immediately");
        let frames = split_frames(&raw);
        assert_eq!(frames.len(), 2, "expected CONNECTION_REQUEST + UKEY2 ClientInit");

        let first = plaintext_offline(&frames[0]).expect("first frame is an OfflineFrame");
        assert_eq!(first.r#type, OfflineFrameType::ConnectionRequest as i32);

        // The second frame is UKEY2 message 1 and nothing else: a real UKEY2 server
        // accepts it.
        assert!(
            plaintext_offline(&frames[1]).is_none(),
            "the second frame must not be an OfflineFrame"
        );
        let mut server = new_server_handshake();
        server
            .handle_handshake_message(&frames[1])
            .expect("second frame is a UKEY2 ClientInit");

        assert!(
            !frame_types(&raw).contains(&Some(OfflineFrameType::ConnectionResponse as i32)),
            "no CONNECTION_RESPONSE may be sent before UKEY2"
        );
        assert!(initiator.outbound_drain().is_none(), "nothing else is queued");
    }

    #[test]
    fn the_connection_request_carries_the_advertised_identity() {
        // The mDNS WifiLanServiceInfo publishes this endpoint id and the `n` TXT
        // attribute publishes this endpoint info, so dialling out under a different
        // identity is what makes a peer log "Failed to parse incoming connection from
        // endpoint %s. Disconnecting." (p000\each.java:2092-2097).
        let endpoint_info =
            crate::endpoint_info::build("Alice", crate::endpoint_info::DeviceType::Phone, |b| {
                b.fill(7)
            })
            .expect("endpoint info");
        let mut initiator = Session::new(
            Role::Initiator,
            "Alice".to_string(),
            endpoint_info.clone(),
            "WXYZ".to_string(),
        );
        let raw = initiator.outbound_drain().expect("initiator sends immediately");
        let frames = split_frames(&raw);
        let offline = plaintext_offline(&frames[0]).expect("CONNECTION_REQUEST");
        let request = offline.connection_request.expect("connection_request");
        assert_eq!(request.endpoint_id, "WXYZ");
        assert_eq!(request.endpoint_info, endpoint_info);
    }

    #[test]
    fn responder_sends_no_connection_response_until_ukey2_completes() {
        let mut initiator = test_session(Role::Initiator, "Alice", vec![]);
        let mut responder = test_session(Role::Responder, "Bob", vec![]);
        let first = initiator.outbound_drain().expect("request + ClientInit");
        let frames = split_frames(&first);

        // Only the CONNECTION_REQUEST: a real peer is waiting for UKEY2 message 1 at
        // this point, so answering here is what it misparses as a Ukey2Message.
        assert_eq!(responder.feed_inbound(&frame::frame_with_length(&frames[0])), 0);
        assert!(
            responder.outbound_drain().is_none(),
            "responder must stay silent until UKEY2 message 1 arrives"
        );

        assert_eq!(responder.feed_inbound(&frame::frame_with_length(&frames[1])), 0);
        let server_init = responder.outbound_drain().expect("ServerInit");
        assert!(
            !frame_types(&server_init)
                .contains(&Some(OfflineFrameType::ConnectionResponse as i32)),
            "the response comes after UKEY2, not during it"
        );

        // ClientFinished completes the handshake, and only now is the response due.
        assert_eq!(initiator.feed_inbound(&server_init), 0);
        let client_finished = initiator.outbound_drain().expect("ClientFinished");
        assert_eq!(responder.feed_inbound(&client_finished), 0);
        assert_eq!(
            frame_types(&responder.outbound_drain().expect("CONNECTION_RESPONSE"))
                .first()
                .copied()
                .flatten(),
            Some(OfflineFrameType::ConnectionResponse as i32),
        );
    }

    #[test]
    fn connection_response_is_plaintext_and_encryption_starts_after_both() {
        let mut initiator = test_session(Role::Initiator, "Alice", vec![]);
        let mut responder = test_session(Role::Responder, "Bob", vec![]);

        // Collect, in order, what each side put on the wire across the whole handshake.
        let mut initiator_sent: Vec<Option<i32>> = Vec::new();
        let mut responder_sent: Vec<Option<i32>> = Vec::new();
        for _ in 0..32 {
            let mut moved = false;
            if let Some(out) = initiator.outbound_drain() {
                initiator_sent.extend(frame_types(&out));
                assert!(responder.feed_inbound(&out) >= 0);
                moved = true;
            }
            if let Some(out) = responder.outbound_drain() {
                responder_sent.extend(frame_types(&out));
                assert!(initiator.feed_inbound(&out) >= 0);
                moved = true;
            }
            if !moved {
                break;
            }
        }

        for (who, sent) in [("initiator", &initiator_sent), ("responder", &responder_sent)] {
            let response_at = sent
                .iter()
                .position(|t| *t == Some(OfflineFrameType::ConnectionResponse as i32))
                .unwrap_or_else(|| panic!("{who} never sent a plaintext CONNECTION_RESPONSE"));
            // Everything up to and including the response is plaintext-parseable only
            // if it is an OfflineFrame; the UKEY2 messages are not, and neither is
            // anything after the response.
            assert!(
                sent[response_at + 1..].iter().all(Option::is_none),
                "{who} sent a plaintext OfflineFrame after CONNECTION_RESPONSE"
            );
            assert!(
                sent.len() > response_at + 1,
                "{who} sent no encrypted frame after the response"
            );
        }
        assert!(initiator.is_ready() && responder.is_ready());
    }

    #[test]
    fn a_rejecting_connection_response_fails_the_session_with_the_peers_status() {
        let mut initiator = test_session(Role::Initiator, "Alice", vec![]);
        let mut responder = test_session(Role::Responder, "Bob", vec![]);
        // Run UKEY2 to completion, then answer with a rejection instead.
        let first = initiator.outbound_drain().expect("request + ClientInit");
        assert_eq!(responder.feed_inbound(&first), 0);
        let server_init = responder.outbound_drain().expect("ServerInit");
        assert_eq!(initiator.feed_inbound(&server_init), 0);
        let client_finished = initiator.outbound_drain().expect("ClientFinished");
        assert_eq!(responder.feed_inbound(&client_finished), 0);
        let _ = responder.outbound_drain();

        let reject = frame::frame_with_length(&payload::build_offline_connection_response(false));
        assert!(initiator.feed_inbound(&reject) < 0);
        assert_eq!(initiator.state, State::Failed);
        assert_eq!(
            initiator.failure_reason(),
            Some("peer rejected the connection (status 8004)"),
        );
    }

    #[test]
    fn responder_rejects_a_ukey2_message_before_the_connection_request() {
        let mut responder = test_session(Role::Responder, "Bob", vec![]);
        let junk = frame::frame_with_length(b"not an offline frame");
        assert!(responder.feed_inbound(&junk) < 0);
        assert_eq!(responder.state, State::Failed);
    }

    #[test]
    fn oversized_length_prefix_fails_the_session() {
        let mut responder = test_session(Role::Responder, "Bob", vec![]);
        assert!(responder.feed_inbound(&[0xFF, 0xFF, 0xFF, 0xFF]) < 0);
        assert_eq!(responder.state, State::Failed);
        assert!(responder.failed_reason.as_deref().unwrap_or_default().contains("oversized"));
    }

    #[test]
    fn introduction_and_accept_flow() {
        let (mut sender, mut receiver) = connected_pair();
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "photo.jpg".to_string(),
            size_bytes: 1234,
            mime_type: "image/jpeg".to_string(),
            payload_id: 0,
        }]);
        assert_eq!(sender.queue_introduction(), 0);
        settle(&mut sender, &mut receiver);

        assert_eq!(receiver.state, State::AwaitingAccept);
        assert_eq!(receiver.pending_files().len(), 1);
        assert_eq!(receiver.pending_files()[0].name, "photo.jpg");
        assert!(receiver.pending_files()[0].payload_id > 0);

        assert_eq!(receiver.accept(true, "/tmp"), 0);
        assert_eq!(receiver.state, State::Transferring);
        settle(&mut sender, &mut receiver);
        assert_eq!(sender.state, State::Transferring);
    }

    #[test]
    fn reject_fails_the_sender() {
        let (mut sender, mut receiver) = connected_pair();
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "a.bin".to_string(),
            size_bytes: 1,
            mime_type: String::new(),
            payload_id: 0,
        }]);
        sender.queue_introduction();
        settle(&mut sender, &mut receiver);
        receiver.accept(false, "");
        // Drain by hand: `settle` asserts no side fails, and this one must.
        let out = receiver.outbound_drain().expect("reject frame");
        sender.feed_inbound(&out);
        assert_eq!(sender.state, State::Failed);
        assert_eq!(sender.failed_reason.as_deref(), Some("peer rejected the transfer"));
    }

    #[test]
    fn introduction_queued_before_ready_is_deferred_not_dropped() {
        let mut sender = test_session(Role::Initiator, "Alice", vec![]);
        let mut receiver = test_session(Role::Responder, "Bob", vec![]);
        // Queue while still in Connecting.
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "early.txt".to_string(),
            size_bytes: 3,
            mime_type: "text/plain".to_string(),
            payload_id: 0,
        }]);
        assert_eq!(sender.queue_introduction(), 0);
        settle(&mut sender, &mut receiver);
        assert_eq!(receiver.state, State::AwaitingAccept);
        assert_eq!(receiver.pending_files()[0].name, "early.txt");
    }

    #[test]
    fn chunked_transfer_is_keyed_by_the_announced_payload_id() {
        let (mut sender, mut receiver) = connected_pair();
        let data: Vec<u8> = (0..5000u32).map(|i| (i % 251) as u8).collect();
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "a.bin".to_string(),
            size_bytes: data.len() as u64,
            mime_type: String::new(),
            payload_id: 0,
        }]);
        sender.queue_introduction();
        settle(&mut sender, &mut receiver);
        let announced = receiver.pending_files()[0].payload_id;
        receiver.accept(true, "/tmp");
        settle(&mut sender, &mut receiver);

        assert_eq!(sender.open_file("a.bin", data.len() as i64), 0);
        for chunk in data.chunks(1024) {
            assert_eq!(sender.write_chunk(chunk), 0);
        }
        sender.close_file();
        settle(&mut sender, &mut receiver);

        let records = drain_all_received(&mut receiver);
        assert!(!records.is_empty(), "nothing was queued for Kotlin");
        assert!(
            records.iter().all(|r| r.payload_id == announced),
            "payload must land under the id announced in the INTRODUCTION"
        );
        assert!(records.iter().all(|r| r.name == "a.bin"));
        assert!(records.iter().all(|r| r.total_size == data.len() as i64));
        let mut reassembled = Vec::new();
        for r in &records {
            assert_eq!(r.offset as usize, reassembled.len(), "offsets are contiguous");
            reassembled.extend_from_slice(&r.body);
        }
        assert_eq!(reassembled, data);
        assert_eq!(
            records.iter().filter(|r| r.last).count(),
            1,
            "exactly one record carries the last-chunk flag"
        );
        assert!(records.last().expect("last record").last);
        assert_eq!(receiver.state, State::Completed);
    }

    #[test]
    fn a_streamed_payload_is_never_retained_in_the_session() {
        // Constant memory: the body lives in the drain queue and nowhere else, so a
        // caller that drains as it pumps holds one chunk at a time regardless of the
        // file size. `ActiveRecv` has no body field for it to accumulate into.
        let (mut sender, mut receiver) = connected_pair();
        let data: Vec<u8> = (0..200_000u32).map(|i| (i % 253) as u8).collect();
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "big.bin".to_string(),
            size_bytes: data.len() as u64,
            mime_type: String::new(),
            payload_id: 0,
        }]);
        sender.queue_introduction();
        settle(&mut sender, &mut receiver);
        receiver.accept(true, "");
        settle(&mut sender, &mut receiver);

        assert_eq!(sender.open_file("big.bin", data.len() as i64), 0);
        let mut reassembled = Vec::new();
        for chunk in data.chunks(4096) {
            assert_eq!(sender.write_chunk(chunk), 0);
            let out = sender.outbound_drain().expect("chunk frames");
            assert!(receiver.feed_inbound(&out) >= 0);
            for r in drain_all_received(&mut receiver) {
                reassembled.extend_from_slice(&r.body);
            }
            assert!(
                receiver.received_queue.is_empty(),
                "draining must empty the queue"
            );
        }
        sender.close_file();
        assert_eq!(reassembled, data);
        assert_eq!(receiver.state, State::Completed);
        assert!(receiver.drain_received().is_none());
    }

    #[test]
    fn received_record_layout_is_stable() {
        let record = ReceivedChunk {
            payload_id: 7,
            offset: 2,
            total_size: 5,
            last: true,
            name: "a.txt".to_string(),
            body: b"xyz".to_vec(),
        };
        let raw = encode_received_record(&record);
        assert_eq!(
            raw,
            [
                // version
                0x01,
                // payload_id = 7
                0, 0, 0, 0, 0, 0, 0, 7,
                // offset = 2
                0, 0, 0, 0, 0, 0, 0, 2,
                // total_size = 5
                0, 0, 0, 0, 0, 0, 0, 5,
                // flags = last
                0x01,
                // name_len = 5, "a.txt"
                0x00, 0x05, b'a', b'.', b't', b'x', b't',
                // body_len = 3, "xyz"
                0x00, 0x00, 0x00, 0x03, b'x', b'y', b'z',
            ],
        );
        let decoded = decode_received_record(&raw);
        assert_eq!(decoded.payload_id, 7);
        assert_eq!(decoded.offset, 2);
        assert_eq!(decoded.total_size, 5);
        assert!(decoded.last);
        assert_eq!(decoded.name, "a.txt");
        assert_eq!(decoded.body, b"xyz");
    }

    #[test]
    fn keep_alive_is_acked() {
        let (mut a, mut b) = connected_pair();
        assert_eq!(a.send_keep_alive(), 0);
        let out = a.outbound_drain().expect("keep alive");
        assert_eq!(b.feed_inbound(&out), 0);
        // The peer must answer with an ack rather than ignoring it.
        let ack = b.outbound_drain().expect("keep alive ack");
        assert_eq!(a.feed_inbound(&ack), 0);
        assert_eq!(a.state, State::Handshaking);
    }
}
