//! Session state machine: BetoCore UKEY2 D2D handshake + AES-256-GCM-SIV context + payload.

use std::collections::HashMap;

use crypto_provider_default::CryptoProviderImpl;
use ukey2_connections::{
    D2DConnectionContextV1, D2DHandshakeContext, HandshakeImplementation, InitiatorD2DHandshakeContext, NextProtocol,
    ServerD2DHandshakeContext,
};

use crate::frame::{self, PayloadPacketType};
use crate::payload::{self, FileMeta};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum State {
    Handshaking = 0,
    AwaitingAccept = 1,
    Transferring = 2,
    Completed = 3,
    Failed = 4,
}

struct ActiveSend {
    id: i64,
    name: String,
    total_size: i64,
    sent_offset: i64,
}

struct ActiveRecv {
    #[allow(dead_code)]
    name: String,
    #[allow(dead_code)]
    expected_size: i64,
    received: Vec<u8>,
    next_offset: i64,
    completed: bool,
}

enum HandshakeState {
    Initiator(InitiatorD2DHandshakeContext<CryptoProviderImpl>),
    Server(ServerD2DHandshakeContext<CryptoProviderImpl>),
    Done,
    Failed,
}

pub struct Session {
    pub local_name: String,
    #[allow(dead_code)]
    pub local_endpoint_info: Vec<u8>,
    pub state: State,
    outbound: Vec<Vec<u8>>,
    inbound_buf: Vec<u8>,
    handshake: HandshakeState,
    secure: Option<D2DConnectionContextV1>,
    pending_files: Vec<FileMeta>,
    next_payload_id: i64,
    active_send: Option<ActiveSend>,
    recvs: HashMap<i64, ActiveRecv>,
    accepted: Option<bool>,
    failed_reason: Option<String>,
}

fn new_initiator_handshake() -> InitiatorD2DHandshakeContext<CryptoProviderImpl> {
    InitiatorD2DHandshakeContext::new(
        HandshakeImplementation::Spec,
        vec![NextProtocol::Aes256GcmSiv],
    )
}

fn new_server_handshake() -> ServerD2DHandshakeContext<CryptoProviderImpl> {
    ServerD2DHandshakeContext::new(
        HandshakeImplementation::Spec,
        &[NextProtocol::Aes256GcmSiv],
    )
}

impl Session {
    pub fn new(local_name: String, local_endpoint_info: Vec<u8>) -> Self {
        let mut hs = new_initiator_handshake();
        // Queue the ClientInit the initiator must send first (wrapped Ukey2Message bytes, no extra varint here — we add it below).
        let client_init = hs
            .get_next_handshake_message()
            .expect("initiator must have ClientInit");
        let framed = frame::frame_with_length(&client_init);
        Self {
            local_name,
            local_endpoint_info,
            state: State::Handshaking,
            outbound: vec![framed],
            inbound_buf: Vec::new(),
            handshake: HandshakeState::Initiator(hs),
            secure: None,
            pending_files: Vec::new(),
            next_payload_id: 1,
            active_send: None,
            recvs: HashMap::new(),
            accepted: None,
            failed_reason: None,
        }
    }

    #[cfg(test)]
    pub fn new_responder(local_name: String, local_endpoint_info: Vec<u8>) -> Self {
        let hs = new_server_handshake();
        Self {
            local_name,
            local_endpoint_info,
            state: State::Handshaking,
            outbound: Vec::new(),
            inbound_buf: Vec::new(),
            handshake: HandshakeState::Server(hs),
            secure: None,
            pending_files: Vec::new(),
            next_payload_id: 1,
            active_send: None,
            recvs: HashMap::new(),
            accepted: None,
            failed_reason: None,
        }
    }

    pub fn pending_files(&self) -> &[FileMeta] {
        &self.pending_files
    }

    pub fn outbound_drain(&mut self) -> Option<Vec<u8>> {
        if self.outbound.is_empty() {
            return None;
        }
        let total: usize = self.outbound.iter().map(|v| v.len()).sum();
        let mut out = Vec::with_capacity(total);
        for f in self.outbound.drain(..) {
            out.extend_from_slice(&f);
        }
        Some(out)
    }

    pub fn query_state(&self) -> i32 {
        self.state as i32
    }

    fn fail(&mut self, reason: &str) {
        self.state = State::Failed;
        self.failed_reason = Some(reason.to_string());
        self.handshake = HandshakeState::Failed;
    }

    pub fn feed_inbound(&mut self, bytes: &[u8]) -> i32 {
        if self.state == State::Failed || self.state == State::Completed {
            return 0;
        }
        self.inbound_buf.extend_from_slice(bytes);
        loop {
            let frame_opt = {
                let mut tmp = self.inbound_buf.clone();
                frame::try_consume_frame(&mut tmp)
            };
            if frame_opt.is_none() {
                break;
            }
            let payload = frame::try_consume_frame(&mut self.inbound_buf).expect("just checked");
            let rc = self.handle_one_frame(&payload);
            if rc < 0 {
                return rc;
            }
        }
        0
    }

    fn is_handshake_done(&self) -> bool {
        self.secure.is_some()
    }

    fn handle_one_frame(&mut self, payload: &[u8]) -> i32 {
        if !self.is_handshake_done() {
            return self.handle_handshake_frame(payload);
        }
        // SecureMessage phase: payload is the SecureMessage protobuf bytes (after our varint).
        let secure = self.secure.as_mut().expect("secure exists");
        let inner = match secure.decode_message_from_peer::<CryptoProviderImpl>(payload, None::<&[u8]>) {
            Ok(v) => v,
            Err(e) => {
                self.fail(&format!("secure decode failed: {e:?}"));
                return -2;
            }
        };
        self.handle_secure_inner(&inner)
    }

    fn handle_handshake_frame(&mut self, payload: &[u8]) -> i32 {
        // payload is a wrapped Ukey2Message (ClientInit / ServerInit / ClientFinished / Alert).
        // Route by handshake role.
        match &mut self.handshake {
            HandshakeState::Initiator(hs) => {
                // Initiator expects ServerInit; if we get ClientInit it's a race — switch to server.
                // Peek message type by trying to handle: if InvalidState from the library, treat as race.
                // Simpler: try to detect ClientInit by attempting hs.handle; on InvalidState or BadMessage, try as server.
                let res = hs.handle_handshake_message(payload);
                match res {
                    Ok(()) => {
                        if hs.is_handshake_complete() {
                            // Initiator now has ClientFinished to send. Move to Done and derive context.
                            let ctx = match hs.to_connection_context() {
                                Ok(c) => c,
                                Err(e) => {
                                    self.fail(&format!("initiator to_connection_context: {e:?}"));
                                    return -2;
                                }
                            };
                            let cf = match hs.get_next_handshake_message() {
                                Some(m) => m,
                                None => {
                                    self.fail("initiator missing ClientFinished");
                                    return -2;
                                }
                            };
                            self.outbound.push(frame::frame_with_length(&cf));
                            self.secure = Some(ctx);
                            self.handshake = HandshakeState::Done;
                        } else if let Some(server_init) = hs.get_next_handshake_message() {
                            // Not complete but server accepted? Should not happen for initiator; ignore.
                            let _ = server_init;
                        }
                        0
                    }
                    Err(e) => {
                        // Race: peer also initiated. Switch this side to server role and re-handle as ServerInit's peer ClientInit.
                        let is_race = matches!(e, ukey2_connections::HandleMessageError::InvalidState | ukey2_connections::HandleMessageError::BadMessage);
                        if is_race {
                            let mut server = new_server_handshake();
                            match server.handle_handshake_message(payload) {
                                Ok(()) => {
                                    let server_init = match server.get_next_handshake_message() {
                                        Some(m) => m,
                                        None => {
                                            self.fail("server missing ServerInit after race ClientInit");
                                            return -2;
                                        }
                                    };
                                    self.outbound.clear(); // drop our optimistic ClientInit? Keep both? Keep peer's flow; drop ours to avoid confusion.
                                    self.outbound.push(frame::frame_with_length(&server_init));
                                    self.handshake = HandshakeState::Server(server);
                                    0
                                }
                                Err(e2) => {
                                    self.fail(&format!("race Server handle ClientInit failed: {e2:?} (orig initiator err {e:?})"));
                                    -2
                                }
                            }
                        } else {
                            self.fail(&format!("initiator handle ServerInit failed: {e:?}"));
                            -2
                        }
                    }
                }
            }
            HandshakeState::Server(hs) => {
                let was_complete_before = hs.is_handshake_complete();
                let res = hs.handle_handshake_message(payload);
                match res {
                    Ok(()) => {
                        if !was_complete_before {
                            if let Some(msg) = hs.get_next_handshake_message() {
                                // After ClientInit we send ServerInit
                                self.outbound.push(frame::frame_with_length(&msg));
                            } else if hs.is_handshake_complete() {
                                let ctx = match hs.to_connection_context() {
                                    Ok(c) => c,
                                    Err(e) => {
                                        self.fail(&format!("server to_connection_context: {e:?}"));
                                        return -2;
                                    }
                                };
                                self.secure = Some(ctx);
                                self.handshake = HandshakeState::Done;
                            }
                        } else {
                            // Was waiting for ClientFinished, now complete
                            if hs.is_handshake_complete() {
                                let ctx = match hs.to_connection_context() {
                                    Ok(c) => c,
                                    Err(e) => {
                                        self.fail(&format!("server to_connection_context after ClientFinished: {e:?}"));
                                        return -2;
                                    }
                                };
                                self.secure = Some(ctx);
                                self.handshake = HandshakeState::Done;
                            }
                        }
                        0
                    }
                    Err(e) => {
                        // If server was waiting for ClientInit and got something else, surface error bytes if any
                        if let ukey2_connections::HandleMessageError::ErrorMessage(ref alert) = e {
                            self.outbound.push(frame::frame_with_length(alert));
                        }
                        self.fail(&format!("server handle handshake failed: {e:?}"));
                        -2
                    }
                }
            }
            HandshakeState::Done => {
                self.fail("handshake already done but got handshake frame");
                -2
            }
            HandshakeState::Failed => {
                self.fail("handshake in failed state");
                -2
            }
        }
    }

    fn handle_secure_inner(&mut self, inner: &[u8]) -> i32 {
        if let Ok(sharing) = payload::parse_sharing_frame(inner) {
            if sharing.frame_type == crate::frame::SharingFrameType::Introduction as i32
                && sharing.introduction.is_some()
            {
                let files = payload::parse_introduction_files(&sharing);
                if !files.is_empty() || sharing.introduction.as_ref().map_or(false, |i| !i.text_metadata.is_empty()) {
                    self.pending_files = files;
                    if self.state == State::Handshaking {
                        self.state = State::AwaitingAccept;
                    }
                    return 0;
                }
            } else if sharing.frame_type == crate::frame::SharingFrameType::ConnectionResponse as i32
                && sharing.connection_response.is_some()
            {
                if let Some(resp) = sharing.connection_response {
                    // Per wire_format.proto ConnectionResponseFrame.Status: ACCEPT=1, REJECT=2, ...
                    // Our SharingConnectionResponse uses status 0=accept legacy; treat 0 or 1 as accept for interop.
                    let is_accept = resp.status == 0 || resp.status == 1;
                    if is_accept {
                        if self.state == State::Handshaking || self.state == State::AwaitingAccept {
                            self.state = State::Transferring;
                        }
                    } else {
                        self.state = State::Failed;
                    }
                }
                return 0;
            }
        }
        if let Ok(pt) = payload::decode_payload_frame(inner) {
            if pt.packet_type != 0 || pt.payload_header.is_some() || pt.payload_chunk.is_some() {
                return self.handle_payload_frame(pt);
            }
        }
        0
    }

    fn handle_payload_frame(&mut self, pt: crate::frame::PayloadTransferFrame) -> i32 {
        match pt.packet_type {
            x if x == PayloadPacketType::Data as i32 => {
                let header = pt.payload_header.as_ref();
                let chunk = pt.payload_chunk.as_ref();
                if let (Some(h), Some(c)) = (header, chunk) {
                    let entry = self.recvs.entry(h.id).or_insert_with(|| ActiveRecv {
                        name: h.file_name.clone(),
                        expected_size: h.total_size,
                        received: Vec::new(),
                        next_offset: 0,
                        completed: false,
                    });
                    if c.offset != entry.next_offset {
                        // tolerate gap; still append
                    }
                    entry.received.extend_from_slice(&c.body);
                    entry.next_offset += c.body.len() as i64;
                    if (c.flags & payload::FLAG_LAST) != 0 {
                        entry.completed = true;
                        let all_done = self.recvs.values().all(|r| r.completed);
                        if all_done && !self.recvs.is_empty() {
                            self.state = State::Completed;
                        }
                    }
                } else if let Some(c) = chunk {
                    if let Some((_id, entry)) = self.recvs.iter_mut().find(|(_, r)| !r.completed) {
                        entry.received.extend_from_slice(&c.body);
                        entry.next_offset += c.body.len() as i64;
                        if (c.flags & payload::FLAG_LAST) != 0 {
                            entry.completed = true;
                            if self.recvs.values().all(|r| r.completed) {
                                self.state = State::Completed;
                            }
                        }
                    }
                }
                0
            }
            _ => 0,
        }
    }

    pub fn accept(&mut self, accept: bool, _dest_dir: &str) -> i32 {
        if self.state != State::AwaitingAccept {
            return -2;
        }
        let Some(secure) = self.secure.as_mut() else {
            return -2;
        };
        let resp_plain = payload::build_connection_response(accept);
        let wire = secure.encode_message_to_peer::<CryptoProviderImpl>(&resp_plain, None::<&[u8]>);
        self.outbound.push(frame::frame_with_length(&wire));
        self.accepted = Some(accept);
        if accept {
            self.state = State::Transferring;
        } else {
            self.state = State::Failed;
        }
        0
    }

    pub fn queue_introduction(&mut self) -> i32 {
        let Some(secure) = self.secure.as_mut() else {
            return -2;
        };
        if self.pending_files.is_empty() {
            return 0;
        }
        let plain = payload::build_introduction_frame(&self.pending_files, self.next_payload_id);
        let wire = secure.encode_message_to_peer::<CryptoProviderImpl>(&plain, None::<&[u8]>);
        self.outbound.push(frame::frame_with_length(&wire));
        0
    }

    pub fn set_pending_files_for_send(&mut self, files: Vec<FileMeta>) {
        self.pending_files = files;
    }

    pub fn open_file(&mut self, file_name: &str, file_size: i64) -> i32 {
        if self.secure.is_none() {
            return -2;
        }
        let id = self.next_payload_id;
        self.next_payload_id += 1;
        self.active_send = Some(ActiveSend {
            id,
            name: file_name.to_string(),
            total_size: file_size,
            sent_offset: 0,
        });
        self.recvs.entry(id).or_insert_with(|| ActiveRecv {
            name: file_name.to_string(),
            expected_size: file_size,
            received: Vec::new(),
            next_offset: 0,
            completed: false,
        });
        0
    }

    pub fn write_chunk(&mut self, chunk: &[u8]) -> i32 {
        let active = match self.active_send.as_mut() {
            Some(a) => a,
            None => return -2,
        };
        if self.secure.is_none() {
            return -2;
        }
        let id = active.id;
        let file_name = active.name.clone();
        let total_size = active.total_size;
        let offset = active.sent_offset;
        let is_last = offset + chunk.len() as i64 >= total_size || chunk.is_empty();
        let is_first = offset == 0;
        let pt = crate::frame::PayloadTransferFrame {
            packet_type: PayloadPacketType::Data as i32,
            payload_header: if is_first {
                Some(crate::frame::PayloadHeader {
                    id,
                    r#type: crate::frame::PayloadType::File as i32,
                    total_size,
                    is_sensitive: false,
                    file_name: file_name.clone(),
                    parent_folder: String::new(),
                })
            } else {
                None
            },
            payload_chunk: Some(crate::frame::PayloadChunk {
                flags: if is_last { payload::FLAG_LAST } else { 0 },
                offset,
                body: chunk.to_vec(),
            }),
            control_message: None,
        };
        let plain = payload::encode_payload_frame(&pt);
        // take secure mut, encode, push framed SecureMessage
        let secure = self.secure.as_mut().unwrap();
        let wire = secure.encode_message_to_peer::<CryptoProviderImpl>(&plain, None::<&[u8]>);
        self.outbound.push(frame::frame_with_length(&wire));
        active.sent_offset += chunk.len() as i64;
        if is_last {
            self.active_send = None;
        }
        0
    }

    pub fn close_file(&mut self) -> i32 {
        if self.active_send.is_some() {
            self.active_send = None;
        }
        0
    }

    #[cfg(test)]
    pub fn loopback_one(&mut self) -> i32 {
        if self.outbound.is_empty() {
            return -1;
        }
        let wire = self.outbound.remove(0);
        self.feed_inbound(&wire)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn two_sessions_handshake() -> (Session, Session) {
        let mut a = Session::new("Alice".to_string(), vec![]);
        let mut b = Session::new_responder("Bob".to_string(), vec![]);
        let out_a = a.outbound_drain().unwrap();
        b.feed_inbound(&out_a);
        let out_b = b.outbound_drain().unwrap();
        a.feed_inbound(&out_b);
        let out_a2 = a.outbound_drain().unwrap();
        b.feed_inbound(&out_a2);
        assert!(a.secure.is_some());
        assert!(b.secure.is_some());
        (a, b)
    }

    #[test]
    fn handshake_establishes_secure_channel() {
        let (a, b) = two_sessions_handshake();
        assert!(a.secure.is_some());
        assert!(b.secure.is_some());
    }

    #[test]
    fn gcm_siv_encode_decode_round_trip() {
        let (mut a, mut b) = two_sessions_handshake();
        let pt = b"hello gcm-siv";
        let wire = a.secure.as_mut().unwrap().encode_message_to_peer::<CryptoProviderImpl>(pt, None::<&[u8]>);
        let framed = frame::frame_with_length(&wire);
        b.feed_inbound(&framed);
        // feed via handle_secure_inner path: we already consumed via decode_message_from_peer in handle_one_frame
        // Instead test direct decode:
        let mut a2 = Session::new("A".to_string(), vec![]);
        let mut b2 = Session::new_responder("B".to_string(), vec![]);
        let oa = a2.outbound_drain().unwrap();
        b2.feed_inbound(&oa);
        let ob = b2.outbound_drain().unwrap();
        a2.feed_inbound(&ob);
        let oa2 = a2.outbound_drain().unwrap();
        b2.feed_inbound(&oa2);
        let w = a2.secure.as_mut().unwrap().encode_message_to_peer::<CryptoProviderImpl>(pt, None::<&[u8]>);
        let got = b2.secure.as_mut().unwrap().decode_message_from_peer::<CryptoProviderImpl>(&w, None::<&[u8]>).unwrap();
        assert_eq!(got, pt);
    }

    #[test]
    fn introduction_flow() {
        let (mut sender, mut receiver) = two_sessions_handshake();
        let files = vec![FileMeta {
            name: "photo.jpg".to_string(),
            size_bytes: 1234,
            mime_type: "image/jpeg".to_string(),
        }];
        sender.set_pending_files_for_send(files);
        sender.queue_introduction();
        let wire = sender.outbound_drain().unwrap();
        receiver.feed_inbound(&wire);
        assert_eq!(receiver.state, State::AwaitingAccept);
        assert_eq!(receiver.pending_files.len(), 1);
        assert_eq!(receiver.pending_files[0].name, "photo.jpg");
        receiver.accept(true, "/tmp");
        assert_eq!(receiver.state, State::Transferring);
        let resp_wire = receiver.outbound_drain().unwrap();
        sender.feed_inbound(&resp_wire);
        assert_eq!(sender.state, State::Transferring);
    }

    #[test]
    fn chunked_payload_transfer() {
        let (mut sender, mut receiver) = two_sessions_handshake();
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "a.bin".to_string(),
            size_bytes: 5000,
            mime_type: "application/octet-stream".to_string(),
        }]);
        sender.queue_introduction();
        let w = sender.outbound_drain().unwrap();
        receiver.feed_inbound(&w);
        receiver.accept(true, "/tmp");
        let w2 = receiver.outbound_drain().unwrap();
        sender.feed_inbound(&w2);

        let data: Vec<u8> = (0..5000u32).map(|i| (i % 251) as u8).collect();
        sender.open_file("a.bin", data.len() as i64);
        for chunk in data.chunks(1024) {
            sender.write_chunk(chunk);
        }
        sender.close_file();
        while let Some(wire) = sender.outbound_drain() {
            receiver.feed_inbound(&wire);
        }
        let recv = receiver.recvs.get(&1).expect("payload id 1");
        assert_eq!(recv.received, data);
        assert!(recv.completed);
        assert_eq!(receiver.state, State::Completed);
    }
}
