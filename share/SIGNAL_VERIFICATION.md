# Signal Protocol Verification Report — communicate vs. official signalapp

**Date:** 2026-08-14
**Scope:** `communicate/src/main/java/com/vayunmathur/communicate/data/signal/**` + `communicate/src/main/rust/src/signal.rs` + `communicate/src/main/java/com/vayunmathur/communicate/telephony/SignalSyncService.kt` vs. ground-truth clones outside the repo.

**Reference SHAs (shallow clone depth 1 on 2026-08-14):**
- `C:\Users\Vayun\signal-ref\Signal-Android` — `ad141bb Bump version to 8.23.2` (depth 1, ~10k files; includes `lib/libsignal-service`, `lib/libsignal-service/src/main/protowire/{SignalService,Groups,CDSI}.proto`)
- `C:\Users\Vayun\signal-ref\libsignal` — `b056faa zkcredential: encryption keys must be used consistently` (depth 1, ~1694 files; `rust/net`, `rust/protocol`, `rust/zkgroup`, `java/shared`)
- Storage: `C:\Users\Vayun\signal-ref\` (OUTSIDE `Modern-Apps`; nothing committed from this path)

**How real Signal was grounded:** file/line references below are from the two clones above. Key files: `libsignal/rust/net/src/env.rs` (`CHAT_WEBSOCKET_PATH`, `DOMAIN_CONFIG_CHAT`, `DOMAIN_CONFIG_CDSI`), `libsignal/rust/net/src/chat.rs` (`AuthenticatedChatHeaders`), `libsignal/rust/net/src/proto/chat_websocket.proto` (framing — replaces `WebSocketResources.proto`), `libsignal/rust/protocol/src/{pqxdh,handshake,ratchet,session,kem/{kyber1024}}`, `libsignal/rust/protocol/src/proto/{wire,sealed_sender,service}.proto`, `libsignal/rust/zkgroup`, `libsignal/java/shared`, `Signal-Android/lib/libsignal-service/src/main/protowire/SignalService.proto`, `Signal-Android/.../internal/push/PushServiceSocket.java` (`VERIFICATION_SESSION_PATH`, `REGISTRATION_PATH`, `GROUPSV2_GROUP`), `Signal-Android/lib/network/src/main/java/org/signal/network/api/RegistrationApiV2.kt`, `Signal-Android/app/src/main/java/org/thoughtcrime/securesms/push/SignalServiceNetworkAccess.kt`.

> **Caveat:** This report was written by mirroring WhatsApp's channel and comparing against source. It is specific and actionable, but **live server behavior cannot be verified without `chat.signal.org` / `cdsi.signal.org` / `svr2.signal.org` endpoints**. Items that require a live round-trip are marked **(needs live server)**.

---

## Executive summary (read this first)

The integration was modeled on the WhatsApp channel. Against real Signal it is **not interoperable with Signal's servers** without the fixes below. 11 **Blocker**s prevent registration, WebSocket connect, sending, and receiving. The next tier (**Major**) is required for E2E, post-quantum, sealed sender, groups, and contact discovery.

| Area | Blockers | Majors | Minors |
|---|---|---|---|
| Registration / verification-session REST | 4 | 2 | 1 |
| Transport — WebSocket path/framing/auth/keepalive | 4 | 1 | 1 |
| Envelope / Content / DataMessage / Receipt / Typing / Edits / Reactions | 3 | 2 | — |
| Sealed sender | 1 (counts as Blocker) | 1 | — |
| Crypto / E2E — PQXDH / Double Ratchet / Kyber / sender keys | 1 | 4 | — |
| GroupsV2 / zkgroup / endorsements | — | 3 | — |
| CDSIv2 (contact discovery) | — | 2 | — |
| Rust `signal.rs` stub | — | 1 | 1 |
| Other (persistence, telephony, endpoints) | — | — | 3 |

**Single highest-ROI fix sequence:** (1) registration REST, (2) WS auth/path/framing, (3) `Envelope`/`Content` proto generation + send path, (4) PQXDH bundle, (5) sealed sender, (6) Groups/CDSI.

---

## 1 — Registration / verification-session REST flow + service URLs

### What real Signal does

All paths relative to `chat.signal.org` (domain `grpc.chat.signal.org:443`, see `libsignal/rust/net/src/env.rs:52,924`). Including censored fallback via `reflector-signal.global.ssl.fastly.net` `/service` (see `Signal-Android/app/src/.../push/SignalServiceNetworkAccess.kt`). No `/v1/accounts/…` prefix — see `PushServiceSocket.java:VERIFICATION_SESSION_PATH, REGISTRATION_PATH` and `RegistrationApiV2.kt`.

Full flow (with constants):

| Step | Method | Path / constant | Auth | Key request JSON | Key response JSON |
|---|---|---|---|---|---|
| Create session | `POST` | `/v1/verification/session` (`VERIFICATION_SESSION_PATH`) | none (pre-registration) | `{number: e164, pushToken?, pushTokenType:"fcm", mcc?, mnc?}` (`VerificationSessionMetadataRequestBody.kt`) | `{id, nextSms, nextCall, nextVerificationAttempt, allowedToRequestCode, requestedInformation:["pushChallenge","captcha"], verified, clientReceivedAt, retryAfter}` (`RegistrationSessionMetadataResponse.kt`) |
| Status / patch | `GET,PATCH` | `/v1/verification/session/{sessionId}` | none | `PATCH {captcha?, pushToken?, pushTokenType, pushChallenge?, mcc?, mnc?}` (`UpdateVerificationSessionRequestBody.kt`) | same |
| Request code | `POST` | `/v1/verification/session/{sessionId}/code` (`VERIFICATION_CODE_PATH`) | none; header `Accept-Language` | `{transport:"sms"|"voice", client:"android"|"android-2021-03"}` | same |
| Verify code | `PUT` | `/v1/verification/session/{sessionId}/code` | none | `{"code": verificationCode}` | same (`verified=true`) |
| Register account | `POST` | `/v1/registration` (`REGISTRATION_PATH`) | `Authorization: Basic e164:password` | `RegistrationSessionRequestBody` (`sessionId` **xor** `recoveryPassword`, `accountAttributes:AccountAttributes`, `aciIdentityKey` base64-nopad(IdKey.serialize()), `pniIdentityKey`, `aciSignedPreKey:SignedPreKeyEntity{keyId,publicKey,signature}`, `pniSignedPreKey`, `aciPqLastResortPreKey:KyberPreKeyEntity{keyId,publicKey(KEMPub 1569B base64),signature}`, `pniPqLastResortPreKey`, `gcmToken:GcmRegistrationId{gcmRegistrationId,webSocketChannel:true}?` null if `fetchesMessages:true`, `skipDeviceTransfer:true`, `requireAtomic:true`) | `VerifyAccountResponse/RegisterAccountResponse {uuid/aci, pni, storageCapable, number/e164, reregistration, entitlements?}` or `423 RegistrationLock {timeRemaining, svr2Credentials:{username,password}, svr3Credentials}` |
| Link as secondary | `PUT` | `/v1/devices/link` | `Basic ACI:password` | `{verificationCode (from ProvisionMessage.provisioningCode), accountAttributes:DeviceAttributes{fetchesMessages,registrationId,pniRegistrationId?,name:Base64(encryptDeviceName),capabilities}, aciSignedPreKey, pniSignedPreKey?, aciPqLastResortPreKey, pniPqLastResortPreKey?, gcmToken?}` | `{deviceId}` |

AccountAttributes (`AccountAttributes.kt`): `{registrationLock(= MasterKey.deriveRegistrationLock()), recoveryPassword?, signalingKey:null, registrationId(KeyHelper.generateRegistrationId), pniRegistrationId, unidentifiedAccessKey(UnidentifiedAccess.derive 32B), capabilities:{storage,versionedExpirationTimer,attachmentBackfill,spqr,usernameChangeSyncMessage}, discoverableByPhoneNumber, name}`. See `RegistrationRepository.kt`.

Prekey generation (`PreKeyUtil.java:157`): `ECKeyPair.generate()` + `identityPriv.calculateSignature(pub.serialize())`; Kyber last-resort: `KEMKeyPair.generate(KYBER_1024)` + same signature over `pub.serialize()` (1569B including `0x08` type tag). Post-registration replenishment: `KeysApi.setPreKeysSync(PreKeyUpload{serviceIdType:ACI/PNI, signedPreKey?, oneTimeEcPreKeys:100, lastResortKyberPreKey, oneTimeKyberPreKeys})` → `PUT /v2/keys?identity=...`.

### What we do (and where)

| Our file:line | What we do | What real does (reference) | Severity | Fix |
|---|---|---|---|---|
| `communicate/src/main/java/com/vayunmathur/communicate/data/signal/registration/RegistrationHttpClient.kt:46-53` | `GET /v1/accounts/sms/code/{e164}` / `/v1/accounts/voice/code/{e164}` with no body | `POST /v1/verification/session` then `POST /v1/verification/session/{id}/code {transport,client}` | **Blocker** | Replace `requestCode` to hit real endpoints under `RegistrationApiV2` shape; send `number`, `pushToken`, `mcc/mnc`; persist `sessionId` from response. |
| `RegistrationHttpClient.kt:56-72` | `PUT /v1/accounts/code/{code}` with `buildAccountAttributes` as body | `PUT /v1/verification/session/{id}/code {code}` then `POST /v1/registration` (headers `Authorization: Basic e164:password`) with `RegistrationSessionRequestBody` (Kyber + ACI/PNI signed prekeys, `gcmToken`, `skipDeviceTransfer:true`, `requireAtomic:true`) | **Blocker** | Split into two calls: verify code on verification-session path, then register. Generate **two** identity keypairs (ACI+PNI) + two `registrationId`s, four prekeys, password, derive UAK. Auth with `e164:password`. |
| `RegistrationHttpClient.kt:74-85` | `GET /v1/accounts/exists/{e164}` | No such endpoint in Signal-Android/PushServiceSocket; accounts are scoped to sessions. CDSI is the authority for number→ACI. | **Blocker** | Remove `checkExists` or reimplement via CDSIv2 (see §7). |
| `RegistrationHttpClient.kt:87-104` `SignalPayload.kt:77-94` `SignalAuthData.kt:17-48` | `buildAccountAttributes` sends `{fetchesMessages,registrationId,name,pin?,capabilities:{announcementGroup,senderKey,storage}}` and derives `unidentifiedAccessKey = idPub[0..16]` | Real `AccountAttributes` needs `registrationLock`, `recoveryPassword`, `unidentifiedAccessKey` from `UnidentifiedAccess.derive(32B)`, `pniRegistrationId`, `capabilities:{storage,versionedExpirationTimer,attachmentBackfill,spqr,usernameChangeSyncMessage}`, `discoverableByPhoneNumber`, `signalingKey:null` | **Blocker** | Generate `AccountAttributes` from libsignal-provided keys; use real capability map; derive UAK correctly; set `pin`→`registrationLock` not `pin`. |
| `SignalAuthData.kt:17-48` (`phoneNumber,aci,pni,deviceId,identity*,registrationId,signedPreKey*,pqLastResort* x2`) | Single identity + single `registrationId`; no `pniIdentity`, no password field, `pqLastResortSecret` stores serialized secretKey 3168B as Base64 | Real needs ACI+P N I identityKeyPairs, ACI+PNI signedPreKeys + PQ last-resort (KEM secret 3168B persisted server-side only via `KyberPreKeyRecord.secret_key()`), `password`, `registrationId`+`pniRegistrationId`, `unidentifiedAccessKey` 32B | **Major** | Expand `SignalAuthData` to hold `aciIdentityKeyPair`/`pniIdentityKeyPair` (or derive from `MasterKey`/`IdentityKeyPair`), password (or `signal_ws_password` actually implemented), both regIds, UAK; wire `RegistrationHttpClient` to persist them. |
| `registration/SignalRegistrationKeys.kt:17-53` | `SecureRandom().nextInt(0x3FFF)+1`, single identity via `RustSignalCrypto.generateKeyPairSplit()` (X25519 32B), `SignalPqPreKey.generate(identityPriv,1)` | `KeyHelper.generateRegistrationId()` (same range but via libsignal), `KeyHelper.generateIdentityKeyPair()` (Ed25519+X25519 `IdentityKeyPair`), generation of **both** identities + signedPreKeys (`ECKeyPair.generate()` + `priv.calculateSignature(pub.serialize())`), KEM Kyber-1024 with signature over `pub.serialize()` (includes `0x08`) | **Major** | Generate ACI+PNI identities via `IdentityKeyPair` (use `org.signal.libsignal.protocol.IdentityKeyPair` from `java/shared`); generate EC signed prekeys with libsignal signature; call `SignalPqPreKey` per-identity (see §5). |
| `registration/RegistrationAttestation.kt:18-47` `registration/SignalDeviceFingerprint.kt:16-55` | HMAC/ECDH stub + `fdid/installId/recoveryToken/attestationKey` 32B | No WhatsApp-style attestation on Signal; SVR uses libsignal-net `SvrKey` via `AccountApi.setRegistrationLock(SvrKey(masterKey.serialize()))` and enclave attestation (`rust/attest/src/cds2.rs`, `rust/net/src/enclave.rs`) | **Minor** | Keep as no-ops or remove from Signal path; do not send to `chat.signal.org`. If PIN/reglock is used, wire SVR2/SVRB via `libsignal-net` (`Network.java` / `AuthAccountsService`). |

Potentially **needs live server**: Push challenge replay (FCM token → 5 s `EventBus PushChallengeEvent` latch in `RegistrationRepository.kt`), 423 `RegistrationLock`/`svr2Credentials` handling, `Retry-After` / `nextSms/nextCall` rate-limit headers, `recoveryPassword` re-registration via SVR.

---

## 2 — Transport — authenticated WebSocket path / framing / auth / keepalive

### What real Signal does

- **Host/port/path:** `wss://grpc.chat.signal.org:443/v1/websocket/` (`libsignal/rust/net/src/env.rs:52,924`). Staging `grpc.chat.staging.signal.org`. Censored: `reflector-signal.global.ssl.fastly.net` with SNI `github.githubassets.com/pinterest.com/www.redditstatic.com` prefix `/service` (`env.rs:334+`, `SignalServiceNetworkAccess.kt`).

- **Headers on upgrade (authenticated):** `Authorization: Basic base64("{aci}.{deviceId}:{password}")` or OTP `"{ts}:{hex(hmac_sha256(secret, hex(uid)+":"+ts))[0:20]}"` (`libsignal/rust/net/src/auth.rs:25`, `chat.rs:206`). Plus `X-Signal-Receive-Stories:true|false`, `Accept-Language:…` (`chat.rs:221-265`), `User-Agent: Signal-Android 7.20.*` (`env.rs:629`), and server must echo `x-signal-timestamp` (`env.rs:57`) to prove not proxied.

- **Framing (binary protobuf):** `libsignal/rust/net/src/proto/chat_websocket.proto:1`:

```proto
syntax="proto2"; package signal.proto.chat_websocket;
message WebSocketRequestMessage  { optional string verb=1; optional string path=2; optional bytes body=3; optional uint64 id=4; repeated string headers=5; }
message WebSocketResponseMessage { optional uint64 id=1; optional uint32 status=2; optional string message=3; repeated string headers=5; optional bytes body=4; }
message WebSocketMessage { enum Type{UNKNOWN=0;REQUEST=1;RESPONSE=2;} optional Type type=1; optional WebSocketRequestMessage request=2; optional WebSocketResponseMessage response=3; }
```

Serialized with protobuf, sent as **binary WS frames** (`tungstenite`). Inbound server→client is `PUT /api/v1/message` (envelope) or `PUT /api/v1/queue/empty` drain signal (`SignalWebSocket.kt:409`). Client ACKs each REQUEST with `WebSocketResponseMessage {id, status:200, message:"OK"}`.

- **Transport split:** WS multiplexes all chat RPCs (`PUT /v1/messages/{dest}`, `PUT /v1/messages/multi`, attachments…) via correlated `id`; REST/H2 fallback via `SignalRestClient` when WS not alive (`libsignal/rust/net/src/chat.rs:361`, `SignalWebSocket.request()`). Provisioning WS: `GET /v1/websocket/provisioning/` → `ProvisioningAddress` (`chat_provisioning.proto`).

- **Keepalive/idle:** `libsignal-net` `RECOMMENDED_CHAT_WS_CONFIG` (`env.rs:924`, `chat.rs:51`) — server idle disconnect, local idle timeout, `post_request_interface_check=5s`; `SignalWebSocket.kt` `sendKeepAlive()` delegates to `WebSocketConnection.sendKeepAlive()` which issues `PUT /v1/keepalive`; not a bare WS ping.

### What we do

| Our file:line | What we do | What real does (reference) | Severity | Fix |
|---|---|---|---|---|
| `transport/SignalSocket.kt:73-81,83-95` `SignalAuthData.kt` (no password) | `wss://chat.signal.org:443/v1/websocket/?login=<aci>.<dev>&password=<pw>` query-string auth; `host` defaults to `chat.signal.org` not `grpc.chat.signal.org`; `resolvePassword()` returns **null** always | `wss://grpc.chat.signal.org:443/v1/websocket/` with `Authorization: Basic` header (no `?login=`); host `grpc.chat.signal.org` (`env.rs:52,924`), staging `grpc.chat.staging.signal.org`; password from `SignalAuthData` (server-provided at registration) used via `AuthenticatedChatHeaders` (`chat.rs:206`) | **Blocker** | Change `host` default to `grpc.chat.signal.org`; replace `wsUrl()` query-string with header `Authorization`; actually persist/read `password` from `SignalAuthData` (fix `resolvePassword`). Add censored fallback via `reflector-…/service` if needed. |
| `transport/SignalSocket.kt:121-148` `SignalProtocol.kt:52-80` | `wsUrl = …` then `webSocket(url) { incoming Flow<WsFrame> { Binary→emit bytes, Text→emit UTF8 } }`; `parseWsFrame` parses **JSON** `{"type","verb","path","id":"uuid-string","body":base64,…}` | Real frames are **binary protobuf** `WebSocketMessage` with `uint64 id` (not UUID string), `headers` array (`HEADER: value`), `body` bytes; TLS confirmation via `x-signal-timestamp` header on HTTP upgrade (`env.rs:947`) | **Blocker** | Swap `:library:network` `WsSession` handling to protobuf: decode `WebSocketMessage.ADAPTER.decode(bytes)` (or `libsignal-net ChatConnection`), encode `WebSocketRequestMessage`/`WebSocketResponseMessage` via protobuf. Remove JSON parsing. Change `id` to `uint64` (`long`). |
| `transport/SignalPayload.kt:25-53` | `buildWebSocketRequest(Path)` returns `JSONString {type:"REQUEST",verb,path,body:base64,id:UUID}`; `buildWebSocketResponse` similar | `WebSocketRequestMessage{verb,path,body,headers,id:uint64}` + `WebSocketMessage{type:REQUEST, request:…}` binary | **Blocker** | Regenerate as protobuf: `WebSocketRequestMessage.Builder().verb().path().body().id(randomLong()).headers("Authorization: …")` → encode `WebSocketMessage`. Same for ACKs. |
| `transport/SignalSocket.kt:186-199` | Bare `session.ping()` every 30 s | Real keepalive is `PUT /v1/keepalive` via `WebSocketRequestMessage` plus `RECOMMENDED_WS_CONFIG` timers; `SignalWebSocket.sendKeepAlive()` calls `WebSocketConnection.sendKeepAlive()` not ping | **Major** | Replace `startKeepalive` with periodic `WebSocketRequestMessage{verb:"GET",path:"/v1/keepalive"}` and handle server close codes `4401`/`4409` (`env.rs:39-40`). |
| `SignalSocket.kt:44-51` | Hard-coded `KEEPALIVE_INTERVAL_MS=30s`, `RECONNECT_BASE_MS=2s` binary shift | `RECOMMENDED_CHAT_WS_CONFIG.remote_idle_disconnect_timeout`, `local_idle_timeout`, `post_request_interface_check=5s` (`chat.rs:51-56`) | **Minor** | Align backoff/keepalive with `RECOMMENDED_WS_CONFIG`; add `x-signal-timestamp` validation to defeat captive portals. |
| `SignalSocket.kt:7-8` comment | "Sealed-sender framing at SignalProtocol layer; this only handles transport" | Sealed sender uses `unidentified-access-key` header (`[0;32]` fallback) or `group-send-token` on **unauthenticated** WS (`libsignal/rust/net/chat/src/ws.rs:37`), separate from authenticated WS | **Minor** | Split sockets: keep authenticated WS for own inbox; add unauthenticated WS path for sealed-sender sends with `unidentified-access-key`/`group-send-token` headers. |

**(needs live server):** Domain-fronting path grafting (`buildConfiguredUrl` preserving pins), WS `4401` invalidation / `4409` “connected elsewhere” closes, `X-Signal-Receive-Stories` behavior.

---

## 3 — Envelope & messages — `SignalService.proto` / `Wire` / `Service`

### What real Signal does

- **Envelope** (`SignalService.proto:Envelope`) — `package signalservice; java_package org.whispersystems.signalservice.internal.push`:
  `message Envelope { enum Type{UNKNOWN=0;DOUBLE_RATCHET=1;PREKEY_MESSAGE=3;SERVER_DELIVERY_RECEIPT=5;UNIDENTIFIED_SENDER=6;PLAINTEXT_CONTENT=8;} optional Type type=1; optional string sourceServiceId=11; optional uint32 sourceDeviceId=7; optional string destinationServiceId=13; optional uint64 clientTimestamp=5; optional bytes content=8; // decrypted = version byte + Content
   optional string serverGuid=9; optional uint64 serverTimestamp=10; optional bool ephemeral=12; optional bool urgent=14 [default=true]; optional string updatedPni=15; optional bool story=16; optional bytes report_spam_token=17;
   optional bytes sourceServiceIdBinary=19; optional bytes destinationServiceIdBinary=20; optional bytes serverGuidBinary=21; optional bytes updatedPniBinary=22; }`
  Decrypt: strip first byte version, `SealedSender.decrypt` if `UNIDENTIFIED_SENDER` else `SessionCipher.decrypt`; inner is **`Content`**.

- **Content** (`SignalService.proto:Content`):
  `message Content { oneof content{ DataMessage dataMessage=1; SyncMessage syncMessage=2; CallMessage callMessage=3; NullMessage nullMessage=4; ReceiptMessage receiptMessage=5; TypingMessage typingMessage=6; bytes decryptionErrorMessage=8; StoryMessage storyMessage=9; EditMessage editMessage=11;} optional bytes senderKeyDistributionMessage=7; optional PniSignatureMessage pniSignatureMessage=10; }`

- **DataMessage** (field numbers stable; truncated): `body=1; attachments=2; groupV2=15; flags=4; expireTimer=5; expireTimerVersion=23; profileKey=6; timestamp=7; quote=8; contact=9; preview=10; sticker=11; requiredProtocolVersion=12; isViewOnce=14; reaction=16{emoji,remove,targetAuthorAci/targetAuthorAciBinary,targetSentTimestamp}; delete=17; bodyRanges=18{start,length,mentionAci/mensionAciBinary,style}; groupCallUpdate=19; payment=20; storyContext=21; giftBadge=22; pollCreate=24; pollTerminate=25; pollVote=26; pinMessage=27; unpinMessage=28; adminDelete=29;` plus nested `Quote{id,authorAciBinary,text,attachments,bodyRanges,type}`. **No JSON.**

- **Wire `Envelope.content` (inside `content` bytes):** `libsignal/rust/protocol/src/proto/wire.proto` → `SignalMessage{ratchet_key=1,counter=2,previous_counter=3,ciphertext=4,pq_ratchet=5,addresses=6}`, `PreKeySignalMessage{pre_key_id=1,base_key=2,identity_key=3,message=4,registration_id=5,signed_pre_key_id=6,kyber_pre_key_id=7,kyber_ciphertext=8(1568B)}`. Decrypted `content` before proto is `version||ciphertext`.

- **Other Content members:** `ReceiptMessage{enum Type{DELIVERY=0;READ=1;VIEWED=2;} Type type=1; repeated uint64 timestamp=2;}`, `TypingMessage{enum Action{STARTED=0;STOPPED=1;} timestamp=1; action=2; groupId=3 bytes;}`, `CallMessage{Offer{ id 1 type 3 opaque 4}, Answer, IceUpdate, Busy, Hangup, destinationDeviceId, opaque}`, `GroupContextV2{masterKey 32B=1, revision=2, groupChange=3 serialized GroupChange.Actions}`, `AttachmentPointer{cdnId/cdnKey 15, clientUuid, contentType, key, digest, incrementalMac, chunkSize …}`, `PniSignatureMessage{pni,signature}`, `EditMessage{targetSentTimestamp 1, dataMessage 2}`.

### What we do

| Our file:line | What we do | What real does (reference) | Severity | Fix |
|---|---|---|---|---|
| `SignalProtocol.kt:30-41` | `data class SignalEnvelope(type:String, sourceAci:String, sourceDevice:Int, timestamp:Long, content:ByteArray, serverGuid:String?, isGroup:Boolean, groupId:String?)` — JSON envelope | Real `Envelope` (above) with `Type` enum **uint32 1**, `sourceServiceId/sourceServiceIdBinary` (bytes = 16B ACI + service discriminator), `destinationServiceIdBinary`, `clientTimestamp` vs `serverTimestamp` distinction, `content` = version+encrypted `Content`, `serverGuidBinary` (ack idempotency) — `SignalService.proto:Envelope` | **Blocker** | Generate protos via Gradle (`SignalService.proto` + `wire.proto`/`sealed_sender.proto`/`service.proto` from `libsignal/rust/protocol/src/proto`). Replace `SignalEnvelope` with `org.whispersystems.signalservice.internal.push.Envelope` generated type. Handle `sourceServiceIdBinary`↔ACI. |
| `SignalProtocol.kt:89-119` `SignalPayload.kt:59-71` `SignalClient.kt:162-206` | `tryParseEnvelope` reads JSON `{"type","source","sourceDevice","timestamp","content":base64,"serverGuid","groupId"}`; `buildDataMessage` builds `JSONObject{body,timestamp,groupId:base64,quoteId}` | Same wire is protobuf `DataMessage` (above) inside `Content.dataMessage`; group is `GroupContextV2` not bare `groupId` | **Blocker** | Delete JSON envelope handling. In `handleInboundFrame` decode `Envelope.ADAPTER.decode(body)` from `WebSocketRequestMessage.body`; decrypt to `Content.ADAPTER.decode(plaintext)`. Building sends: `Content{dataMessage: DataMessage{body,timestamp,groupV2:…}}`→encrypt→`WebSocketRequestMessage`. |
| `SignalClient.kt:162-268` `SignalProtocol.kt:121-134` | `sendMessage` uses `SignalPayload.buildWebSocketRequest("PUT", "/api/v1/messages/$aci", json{"destination","content":b64,"timestamp"})`; `sendReaction` → `PUT /api/v1/messages/$id/reaction`, `editMessage` → `PUT …/edit`, `revoke` → `DELETE /api/v1/messages/…`, `poll` → `…/poll`, `readReceipt` → `…/receipt` | Real chat WS **does not** use those sub-paths. All content is an encrypted `Content` in a single `PUT /v1/messages/{destination}` family (or `PUT /v1/messages/multi`) — see `SignalWebSocket.request()` and `libsignal/rust/net/chat/`. `DataMessage.reaction/edit/delete/polls` are carried **inside** `DataMessage` via `Content.editMessage` top-level wrapper; `ReceiptMessage`/`TypingMessage` are `Content` oneof peers, not REST sub-resources | **Blocker** | Remove bespoke sub-paths. Implement single send path `sendContent(aci, deviceId?, Content)` via `PUT /v1/messages/{aci}` (and `multi`). Encode attachments via `AttachmentPointer` + CDS upload form (see `PushServiceSocket` attachment flow). Map reactions→`DataMessage.reaction`, edits→`Content.editMessage{targetSentTimestamp,dataMessage}`, delete→`DataMessage.delete`, poll→`DataMessage.pollCreate/pollTerminate/pollVote`. |
| `SignalEventProcessor.kt:84-92` `SignalClient.kt:243-267` | Edits/deletes measured as separate events updating `body` directly | Real edits are full `DataMessage` replacements identified by `targetSentTimestamp`, not messageId mutation | **Major** | Persist edits keyed by `targetSentTimestamp` + `authorAciBinary`. |
| `SignalClient.kt:280-306` | Polls as `JSONObject{question,options[]}` and `pollVote {pollMessageId,options}` over custom path | Real `DataMessage.pollCreate{question,options[]}` + `pollVote{bodyRange?}` / `pollTerminate` via `Content` | **Major** | Reimplement polling via `DataMessage` poll protos; see `SignalService.proto:DataMessage` 24-26. |

**(needs live server):** `requiredProtocolVersion` gating (CURRENT=8 POLLS), `BodyRange` mentions/styles round-trip, `GroupContextV2.revision` conflict handling, view-once semantics, story `Content.storyMessage`.

---

## 4 — Sealed sender (UnidentifiedSenderMessage)

### What real Signal does

- `libsignal/rust/protocol/src/proto/sealed_sender.proto:UnidentifiedSenderMessage{Message{enum Type{PREKEY_MESSAGE=1;MESSAGE=2;SENDERKEY_MESSAGE=7;PLAINTEXT_CONTENT=8;} enum ContentHint{RESENDABLE=1;IMPLICIT=2;} Type type=1; bytes senderCertificate=2; bytes content=3; // version+Content
   ContentHint contentHint=4; bytes groupId=5;} ephemeralPublic=1; encryptedStatic=2; encryptedMessage=3; }` plus `SenderCertificate{Certificate{senderE164?,senderUuid{uuidString|uuidBytes},senderDevice 2, expires 3, identityKey 4, signer certificate/id}}` — hybrid X25519 encrypt, `senderCertificate` signed by server `ServerCertificate`. Client must validate `SenderCertificate` + `ServerCertificate`.

### What we do

| Our file:line | What we do | What real does (reference) | Severity | Fix |
|---|---|---|---|---|
| `communicate/src/main/rust/src/signal.rs:18-66` | `sealed_encrypt = version 1 || iv 12 || AES-256-CTR(key=sha256(aci),…) || HMAC-SHA256(iv\|aad\|ct)[0..16]` tagged as AES-GCM but is CTR+HMAC | Hybrid sealed sender with `ephemeralPublic` + `encryptedStatic` + `encryptedMessage`, sender certs (`sealed_sender.proto`) decrypteable only by recipient using `IdentityKeyPair` + `KyberPreKey` etc. via `SealedSender.decrypt` | **Blocker** | Drop `signal.rs` crypto; call `libsignal-protocol` `SealedSender.{encrypt,decrypt}` via `org.signal.libsignal.protocol.SealedSender` JNI (or `libsignal/rust/protocol/src/sealed_sender.rs`). Generate/use `SenderCertificate` from server. |
| `e2e/RustSignalCrypto.kt:74-80` `e2e/SignalE2E.kt:147-157` `SignalClient.kt:149-157,392-430` | `sealedSenderEncrypt(plaintext, recipientAci, deviceId)` derives `sha256(aci)` key, ignores deviceId; decrypt tries empty-key fallback `sealed_key("")` | Ephemeral X25519 keys + per-device envelopes; `decrypt` needs recipient `IdentityKeyPair`, `SenderCertificate` validation | **Major** | After replacing with libsignal, wire `sealedSenderEncrypt` to require `SenderCertificate` from server (`SignalWebSocket` refresh) and `sealedSenderDecrypt` to validate against `IdentityKeyPair` + `SenderCertificateValidator`. |

---

## 5 — Crypto / E2E — PQXDH / Double Ratchet / sender keys / identity / Kyber

### What real Signal does

- **PQXDH** (`libsignal/rust/protocol/src/pqxdh.rs:36` `impl Handshake for Pqxdh`, `rust/protocol/src/handshake.rs:31`): `secrets = 0xFF*32 || DH1||DH2||DH3||[DH4]||KEM-SS` where `DH1 = DH(our_id_priv, their_signed_prekey)`, `DH2 = DH(our_eph_priv, their_identity_pub)`, `DH3 = DH(our_eph_priv, their_signed_prekey)`, `DH4 = DH(our_eph_priv, their_one_time)` (optional), `KEM-SS = KEM.encaps(their_kyber_pub)/decaps(our_kyber_sk, ciphertext)`. `KDF = HKDF-SHA256(None, secrets) expand "WhisperText_X25519_SHA-256_CRYSTALS-KYBER-1024" → (root_key 32, chain_key 32, pqr_key 32)` (`pqxdh.rs:72`, `ratchet.rs:27` `spqr_chain_params(max_jump 25000 or MAX if self)`). Alice init `ratchet::initialize_alice_session`, Bob `initialize_bob_session(:spqr::initial_state direction B2A)` (`ratchet.rs:44,118`). `SessionState` version `CIPHERTEXT_MESSAGE_CURRENT_VERSION=4` (`protocol.rs:19`) — v3 (no Kyber) rejected (`session.rs:107`).

- **KEM:** `kem.rs:199` `KeyType::Kyber1024=0x08` (and `MlKem1024=0x0A`). `kem/kyber1024.rs:17` → `libcrux_ml_kem::mlkem1024`: `PUBLIC_KEY=1568`, `SECRET_KEY=3168`, `CIPHERTEXT=1568`, `SHARED_SECRET=32`. Wire serialized = `1-byte key_type (0x08) || raw` (1569B) (`serialize()` includes tag). Java: `KEMKeyPair.generate(KYBER_1024)` (`KEMKeyPair.kt`).

- **PreKeys:** `state/{prekey,signed_prekey,kyber_prekey}.rs` — `PreKeyRecord`, `SignedPreKeyRecord{id,public,private,signature,timestamp}`, `KyberPreKeyRecord` (wraps `SignedPreKeyRecordStructure` with KEM keypair signed by `identityPrivate.calculate_signature(pub.serialize())`). Bundle `state/bundle.rs:124` `PreKeyBundle{registration_id,device_id,pre_key{Option}, ec_signed_pre_key{id,pub,sig}, identity_key, kyber_pre_key{id,pub,sig}}` — getters `kyber_pre_key_public(): kem::PublicKey`.

- **Processing:** `session.rs:181` `process_prekey_bundle()` verifies both signedPreKey+kyber signatures with `their_identity_key.public_key().verify_signature(pub.serialize(),sig)`, generates `our_base KeyPair`, `initialize_alice_session`, `set_unacknowledged_pre_key_message`+`set_unacknowledged_kyber_pre_key_id`, `set_local_registration_id`, `set_remote_registration_id`, saves via `store_session`. Incoming `process_prekey()` (`session.rs:46`) checks trusted identity, `promote_matching_session(version, base_key)` for replay, loads `our_signed_pre_key_pair + our_kyber_pre_key_pair` (must have kyber_ciphertext), optional one-time `→ initialize_bob_session`.

- **Fallback / replay:** `KyberPreKeyStore::mark_kyber_pre_key_used(kyber_id, signed_ec_id, base_key)` (`storage/traits.rs:129`, `storage/inmem.rs:200`) — one-time → delete; last-resort (`isLastResort` flag) → `base_keys_seen: HashMap<(KyberId,SignedPreKeyId), Vec<PublicKey>>` rejects reuse of `(kyber_id,ec_id,base_key)`.

- **Wire:** `PreKeySignalMessage{pre_key_id 1, base_key 2, identity_key 3, message 4, registration_id 5, signed_pre_key_id 6, kyber_pre_key_id 7, kyber_ciphertext 8}` must have 7+8 together for v≥4 (`protocol.rs:493`). `SignalMessage{pq_ratchet 5, addresses 6}`.

- **Sender keys:** `wire.proto:SenderKeyMessage` distributed via `Content.senderKeyDistributionMessage=7` (SKDM). Java `SenderKeyStore` mirrors WhatsApp pattern but backed by zkgroup `GroupSendEndorsement` for groups.

### What we do

| Our file:line | What we do | What real does (reference) | Severity | Fix |
|---|---|---|---|---|
| `e2e/RustSignalCrypto.kt:44-65` `e2e/SignalE2E.kt:96-110` `registration/SignalRegistrationKeys.kt:21-38` | `processPreKeyBundle(localIdentityPrivate, localIdentityPublic, localRegistrationId, registrationId, preKeyId, preKeyPublic?, signedPreKeyId, signedPreKeyPublic, signedPreKeySignature, identityKey)` — **no Kyber fields**; `generateKeyPair()` X25519; `sign(identityPriv, msg)` signs `0x05||pub32` | Need `kyberPreKeyId + kyberPreKeyPublic + kyberPreKeySignature + kyberCiphertext` + registration/device ids, and `pq_ratchet`/`kyber_ciphertext` in PreKeySignalMessage; version enforcement v4 only | **Blocker** | Extend `processPreKeyBundle` JNI to include Kyber; call `libsignal` `SessionState.process_prekey_bundle` / `PreKeyBundle` constructor that validates Kyber signatures. Replace `generateKeyPair` with `IdentityKeyPair`/`ECKeyPair`/`KEMKeyPair` from libsignal. |
| `registration/SignalPqPreKey.kt:23-40` | `signingInput = 0x08||pubRaw(1568)` then `RustSignalCrypto.sign(identityPriv, input)`; `stripTypeTag` strips `0x08` if `len==1569`; `pub = stripTypeTag(kp.publicKey.serialize())` → stores 1568 | libsignal `pub.serialize()` is already `0x08||1568` (1569); signing is over `pub.serialize()` **including** the type byte; `stripTypeTag` discards the tag that should be kept for wire; server validates including tag | **Major** | Keep `kp.publicKey.serialize()` verbatim (1569B) for upload/base64; sign **exactly** that buffer. Remove `stripTypeTag`. Persist 1569B. |
| `e2e/SignalE2E.kt:58-84,112-145` `SignalDatabase.kt:319-351` | `encryptDM`/`decryptDM` treat sessions as opaque `record` ByteArray; `parsePreKeyIdFromMessage` manually parses varint field 1; `createSenderKeyDistribution` ↔ `RustSignalCrypto.createSenderKey` / `encryptGroup` (WhatsApp-derived `group.rs`) | Real session stores `kyber_ciphertext`, `pqr_state`, chain `pq_ratchet`, addresses, `local/remote_registration_id`; `processPreKey` handles replay + SKDM; sender keys are scoped to group send endorsements | **Major** | Swap underlying `session.rs` / `storage` from WhatsApp's `group.rs` (which lacks Kyber) to `libsignal`'s `SessionStore`+`KyberPreKeyStore`. Store `SignalE2ESession.record` as `SessionState.serialize()` not custom. Remove `parsePreKeyIdFromMessage` hand-parse; use `PreKeySignalMessage` decode. |
| `e2e/SignalE2E.kt:89-94` `SignalPqPreKey:33-38` | Signed prekey signature = `sign(identityPriv, 0x05||signedPub32)` via Rust | Real `signedPreKeySignature = identityPriv.calculateSignature(signedPreKeyPub.serialize())` — no `0x05` prefix; libsignal does `EcPublicKey.serialize()` = `5||32` already (type byte 5) but input to `calculateSignature` is that exact 33B, not our extra prefix doubling | **Major** | Use `IdentityKeyPair.privateKey.calculateSignature(pub.serialize())` from libsignal; verify against `SignalService.proto` `SignedPreKeyEntity` signature. |
| `e2e/RustSignalCrypto.kt:67-73` `SignalDatabase:345-351` | `createSenderKey() → [state,skdm]` locally generated, no server `GroupSendEndorsement` | Groups sender key distribution involves `GroupSendEndorsementResponse` from `PUT /v2/groups/` etc., encrypted blob key via `GroupSecretParams` (`rust/zkgroup/src/api/groups/group_params.rs:49`) | **Major** | After fixing `libsignal` sender keys, also fetch `group_send_endorsement` from `PushServiceSocket` group calls and include `GroupSendFullToken` in unauth WS path; see `GroupsV2Api` / `PushServiceSocket:GROUPSV2_TOKEN`. |

---

## 6 — GroupsV2 — zkgroup, master key, revision, groupChange, endorsements

### What real Signal does

- **Wire:** `SignalService.proto:GroupContextV2{masterKey 32B=1, revision=2, groupChange=3 (serialized GroupChange.Actions)}` — only GroupsV2 exists (v1 removed).

- **Storage protos:** `Signal-Android/lib/libsignal-service/src/main/protowire/Groups.proto` (`Group{publicKey,title,description,avatarUrl,accessControl,version:revision,members,membersPending…,inviteLinkPassword,GroupAttributeBlob{title1,avatar2,timer3,descriptionText4}, GroupChange{actions,serverSignature,changeEpoch}, AccessControl,…}`), `DecryptedGroups.proto` (`DecryptedGroup{title,avatar,timer,accessControl,revision,members:DecryptedMember{aciBytes,pniBytes,role,profileKey,label…}}`).

- **Server paths:** `PushServiceSocket.java:168-190` — `PUT /v2/groups/` (create), `GET /v2/groups/`, `GET /v2/groups/logs/{id}?maxSupportedChangeEpoch=7&…`, `GET /v2/groups/avatar/form`, `PATCH /v2/groups/` (or `?inviteLinkPassword=%s`), `GET /v2/groups/join/%s`, `GET /v2/groups/token`, `GET /v2/groups/joined_at_version`, `GET /v1/certificate/auth/group?redemptionStartSeconds…`. `GroupsV2Operations.java:HIGHEST_KNOWN_EPOCH=7`.

- **zkgroup:** `rust/zkgroup/src/api/groups/group_params.rs:20` — `GroupMasterKey bytes[32]`, `GroupSecretParams{derive_from_master_key via Sho("Signal_ZKGroup_20200424_GroupMasterKey…", masterKey) → group_id 32B, blob_key AesKey, UidEncKeyPair, ProfileKeyEncKeyPair }`, `GroupPublicParams`. `GroupSendDerivedKeyPair{key_pair:ServerDerivedKeyPair, expiration:Timestamp} tag_info=ShoHmacSha256("20240215_Signal_GroupSendEndorsement", expiration.be_bytes)`; `GroupSendEndorsementsResponse{issue(..), receive_with_service_ids(..)}`, `GroupSendEndorsement{to_token(&GroupSecretParams)->GroupSendToken, combine/remove}`, `GroupSendFullToken{verify(user_ids,now,&derivedKeyPair)}` (`zkgroup/.../group_send_endorsement.rs:38`). Java: `GroupMasterKey`, `GroupSecretParams`, `ClientZkGroupCipher.encryptServiceId/decrypt, encryptBlob/decryptBlob`, `GroupSend*`.

### What we do

| Our file:line | What we do | What real does (reference) | Severity | Fix |
|---|---|---|---|---|
| `SignalClient.kt:316-351` `transport/SignalPayload.kt:102-103` | `PUT /api/v1/groups` with `{"name","members":[aci…]}`; `group:${randomHex}` local id; `PUT /api/v1/groups/$id/name`, `PUT …/members {"members","action"}` | `PUT /v2/groups/` with encrypted `Group.body` + `GroupAttributeBlob` via `ClientZkGroupCipher.encryptBlob`, members as `Presentation` zk proofs, `GroupsV2AuthorizationString` header from `AuthCredentialWithPniResponse` (`GroupsV2Api.java`, `PushServiceSocket`) | **Major** | Reimplement `createGroup` via `GroupsV2Operations.createNewGroup` + `GroupsV2Api.putNewGroup(NewGroup, GroupsV2AuthorizationString)`: generate `GroupMasterKey` 32B → `GroupSecretParams`, encrypt `title/description/timer/avatar` into `GroupAttributeBlob`, build `GroupChange.Actions`, upload avatar via `GET /v2/groups/avatar/form` → CDN0. Store `masterKey` + `revision` (use `GroupContextV2` for sends). |
| `SignalClient.kt:330-340` `SignalContactSync.kt:92` | Participants CSV `SignalConversation.participants`, `aci = e164` (phone number) | Members are `ServiceId` (ACI/PNI) via `UuidCiphertext` (`zkgroup`), `DecryptedMember.aciBytes/pniBytes` + `joinedAtVersion`, CSV leaks PII | **Major** | Migrate `SignalConversation.participants` to `Group` members via zkgroup; map `phoneE164→ACI` through CDSI first (see §7). |
| `e2e/SignalE2E.kt:112-145` | Sender keys via local `createSenderKey()`; no group-send endorsement | Real group sends require `GroupSendEndorsementsResponse` (expiration+endorsements) fetched per-revision and `group-send-token` header on unauth WS (`groupsend/`, `ReceivedGroupSendEndorsements.kt`) | **Major** | Add `GroupsV2Api.getGroupSendEndorsements` / `PushServiceSocket.getGroupHistory` to retrieve `group_send_endorsements_response`; derive `GroupSendFullToken` via `GroupSendEndorsement.toFullToken`. Cache per-revision; include as `group-send-token` header when sending `DataMessage` with `GroupContextV2`. |
| `SignalServiceData.kt:29-31` | `isGroup, groupParticipantCount` | Missing `masterKey`, `revision`, `groupChange` | **Minor** | Extend `SignalServiceData` to carry `groupMasterKey`, `GroupContextV2` fields if UI needs them. |

**(needs live server):** `GroupChange` revision conflict resolution, `Member.pending*`/`banned` states, `AccessControl` mutation, `inviteLinkPassword` rotation, avatar CDN0 multipart.

---

## 7 — Contact discovery — CDSIv2 (phone number → ACI/PNI)

### What real Signal does

- **Enclave endpoint:** `libsignal/rust/net/src/env.rs:90` `DOMAIN_CONFIG_CDSI{cdsi.signal.org:443, ip 40.122.45.194, v6 …, proxy /cdsi}`, staging `cdsi.staging.signal.org`, path `POST /v1/{hex(mrenclave)}/discovery` (`enclave.rs:53`). Enclave `ENCLAVE_ID_CDSI_PROD=15637fa1e54fe655176d3df1a9f94b87c01ed377acaa570682dc5d72c95ef07b` (`rust/attest/src/constants.rs:69`).

- **Proto over attested Noise WS:** `rust/net/src/proto/cds2.proto` (mirrored `SignalService/.../CDSI.proto:8`) → `ClientRequest{aci_uak_pairs:32B each (16B ACI||16B UAK), prev_e164s/new_e164s/discard_e164s: each e164 as 8B big-endian uint64, token?, token_ack, returnAcisWithoutUaks}`, `ClientResponse{e164_pni_aci_triples: each 40B (8B e164+16B PNI+16B ACI, zeros if not found; padded to (2+32)*|e164|), token 3, debug_permits_used}`. Client builds via `CdsiConnection::send_request(LookupRequest{new_e164s:Vec<E164>, prev_e164s, acis_and_access_keys:Vec<AciAndAccessKey>, token})` (`cdsi.rs:82`).

- **UAK:** 16B derived from `ProfileKey`↔ACI in `AciAndAccessKey{aci, access_key[16]}` — never plaintext phone numbers; previous E164s discounted for rate-limit; token opaque enclave rate-limit credential (`CloseCode 4003/4008/4101`).

- **Java:** `CdsiV2Service.java:40` `getRegisteredUsers(username,password,Request{previousE164s,newE164s,serviceIds:Map<ServiceId,ProfileKey>, token})` → `network.cdsiLookup(username,password,CdsiLookupRequest, tokenSaver)`, maps to `CdsiInvalidToken/ResourceExhausted/InvalidArgumentException`.

### What we do

| Our file:line | What we do | What real does (reference) | Severity | Fix |
|---|---|---|---|---|
| `SignalContactSync.kt:24-97` | Reads address book, normalizes E.164 via `PhoneNumberUtil`, persists `SignalContact(aci=e164, phoneE164=e164, onSignal=false)`; comment notes "CDS discovery — POST to `https://cms.smsfaith.org` / `https://chat.signal.org` … TODO" | Must `POST` to `https://cdsi.signal.org/v1/{mrenclave}/discovery` (attested) with Noise + SGX evidence/endorsement; host is `cdsi.signal.org` not `cms.smsfaith.org`/`chat.signal.org`; need UAK per `ACI` | **Major** | Replace `sync` with `CdsiV2Service` via `libsignal-net Network.cdsiLookup`; supply `username="{aci}.{deviceId}" password` + per-contact `ProfileKey→UAK`. Persist `SignalContact(aci: ACI UUID string, pni: PNI UUID, onSignal=true)` from `e164_pni_aci_triples` (parse 40B triples). Handle `token` persistence across calls. |
| `SignalContactSync.kt:38-49` `SignalAuthData` | `normalizeE164` + `defaultRegion` OK, but stored `ACI` is the E.164 string | ACI is a 16B UUID string (`aci.service_id_string()`), onSignal false until CDSI says so | **Major** | After CDSI, overwrite `SignalContact.aci` with real ACI UUID; keep `phoneE164` column distinct. UI should key conversations on ACI not E.164. |

**(needs live server):** SGX `ClientHandshakeStart{evidence,endorsement}` validation (`rust/attest/src/cds2.rs:15`, advisories `INTEL-SA-00615/00657`), rate-limit `retryAfter`/`RetryLater`, MR enclave rotation.

---

## 8 — Receipts / typing / edits / reactions / read / presence

### What real Signal does

- `Content.oneof`: `ReceiptMessage` and `TypingMessage` are **peer**s of `dataMessage`, not REST paths. `SignalService.proto:Content.ReceiptMessage{Type DELIVERY=0/READ=1/VIEWED=2; repeated uint64 timestamp=2}` (timestamps of the DataMessages being acked). `SyncMessage{read=5, viewed=16}` for multi-device read sync. `TypingMessage{Action STARTED/STOPPED; timestamp; groupId bytes}` (groupId is 32B `GroupIdentifier` bytes, not CSV). `EditMessage{targetSentTimestamp, dataMessage}` carries the replacement `DataMessage`. `DataMessage.reaction{emoji,remove,targetAuthorAciBinary,targetSentTimestamp}` and `delete=17`. Reactions carry `targetAuthorAciBinary` (16B) + stamp. See §3 and `wire.proto`/`service.proto`.

### What we do

| Our file:line | What we do | What real does (reference) | Severity | Fix |
|---|---|---|---|---|
| `SignalClient.kt:296-314` `SignalProtocol.kt:89-119` | `PUT /api/v1/messages/$id/receipt JSONObject{timestamp,messageId}`; `markRead` marks `SignalCachedMessage.status=3` then calls `readReceipt` | `Content{receiptMessage: ReceiptMessage{type:READ|DELIVERY,timestamp:[targetTs…]}}` sealed inside `Envelope`; `PUT /v1/receipt` is `PUT /api/v1/receipt` via `MessageServiceApi`? See `SignalService` receipts via `PUT /v1/receipt` batch or inside `Content`; not per-message-id query | **Major** | Replace with `Content{receiptMessage: ReceiptMessage{type,timestamp:[…]}}` encrypted; send via `PUT /v1/messages/{aci}` content or `MessageReceiptsApi` (`PUT /v1/receipt`). Collector expects `timestamp` array, not `messageId`. |
| `SignalClient.kt:353-358` `SignalClient.kt:392-430` `SignalEvent.TypingIndicator` | `PUT /api/v1/messages/$id/typing JSONObject{typing:bool}`; local emit only; inbound typing treated as generic | `TypingMessage{timestamp, action:STARTED/STOPPED, groupId:bytes}` (group is raw 32B id). No JSON `typing` bool | **Major** | Send `Content{typingMessage: TypingMessage{…}}` similarly; parse inbound `Content.typingMessage` in `handleInboundFrame`; map `groupId` 32B to conversationId. |
| `SignalClient.kt:224-241,245-267` | Reaction `JSONObject{targetMessageId,emoji,timestamp}` via `PUT …/reaction`; remove → `emoji:""`; edit → `JSONObject{targetMessageId,body}` → `PUT …/edit` | `DataMessage.reaction{emoji,remove,targetAuthorAciBinary,targetSentTimestamp}` inside `Content.dataMessage` for reactions; edits via top-level `Content.editMessage{targetSentTimestamp,dataMessage}` (the new DataMessage) not a separate REST verb | **Major** | Switch to `Content{dataMessage: DataMessage{reaction:…}}` and `Content{editMessage: EditMessage{targetSentTimestamp,dataMessage:DataMessage{body,timestamp}}}` paths. Keep `emoji:""` as `remove:true`. |
| `SignalEventProcessor.kt:84-99` | Edits/removes keyed by `messageId` string | Real edits keyed by `targetSentTimestamp` (uint64) + `targetAuthorAciBinary` | **Major** | Align `MessageEdited.targetSentTimestamp` handling; look up by timestamp-author pair. |
| `SignalClient.kt:369-373` | `GET /api/v1/accounts/$id/presence → emit PresenceUpdate(isOnline:false)` | Signal has no such presence REST; typing/read are only presence cues; `SignalService.proto` does not define presence | **Minor** | Remove `refreshPresence` or replace via storage-service profile fetch. |

---

## 9 — Rust `communicate/src/main/rust/src/signal.rs` + JNI vs. libsignal

| Our file:line / symbol | What we do | What real does (reference) | Severity | Fix |
|---|---|---|---|---|
| `signal.rs:1-129` whole module | Minimal `sha256(aci)`→CTR→HMAC stub; `sealed_key()`, `aes_gcm_encrypt()` (uses AES-CTR not AES-GCM), tag = 16B `hmac_sha256(key, iv||aad||ct)` truncated; `sealed_sender_decrypt_any` falls back to `key=sha256("")` | See §4-§5; real calls `libsignal-protocol`/`libsignal-net` | **Major** | Remove stub; depend on `signal-protocol` crate (`libsignal-protocol-rust` → `org.signal.libsignal.protocol.*`) via JNI; expose same `sealedSenderEncrypt/Decrypt` surface but backed by `SealedSender`. Do the same for `processPreKeyBundle`. |
| `signal.rs:122-129` `jni_bridge.rs` hints (`use crate::crypto`, `session`, `group`) | Assumes Signal 1:1 crypto == WhatsApp `session.rs` / `group.rs` `X3DH` | WhatsApp session lacks PQXDH — `kyber_ciphertext` 1568B + `pq_ratchet` 32 + `spqr` state; reusing `WhatsAppE2E` session record breaksinterop | **Major** | Compile `libsignal-protocol` (`ratchet.rs`, `pqxdh.rs`) or link `libsignal-java` via cargo feature; replace `jni_bridge.rs`'s `session`/`group` impl for Signal paths (or split crates). |
| `SignalPqPreKey.kt:13-41` comment | "signature is XEdDSA over `0x08||pqPublicKey`" but `generate()` signs `0x08||pub(1568)` and strips tag — half-right | Real signs `pub.serialize()` (which already includes `0x08`) — see §5 | **Major** | As above: sign `serialize()` verbatim; see libsignal `KyberPreKeyRecord::generate`. |

---

## 10 — Other integration points

| Our file:line | What we do | What real does (reference) | Severity | Fix |
|---|---|---|---|---|
| `transport/SignalPayload.kt:100-103` `SignalClient.kt:212-222,362-367` | CDN upload `POST https://cdn.signal.org/attachments/` with raw bytes; user agent `Signal-Android 7.20.0` | Attachments: `GET /v2/attachments/form/upload` → multipart `POST` to CDN host with `key,credential,acl,algorithm,date,policy,signature` from `AttachmentUploadForm`; encrypted `AttachmentPointer` fields (`key` 64B, `digest`, `incrementalMac`, `cdnNumber`, `cdnKey`); see `PushServiceSocket` + `SignalRestClient` attachment API. User-Agent from `env.rs:629` `StandardUserAgentInterceptor` | **Major** | Implement attachment form fetch + CDN upload + `AttachmentPointer` construction; encrypt attachment with `AttachmentCipher` (`libsignal`). Rotate UA to `libsignal` current. |
| `SignalDatabase.kt:1-477` (12 entities, version 1) | 12 entities mirroring WhatsApp (Signal prefix); `SignalSession.jid`, `SignalDevice.platform`, etc. | Server model uses `AccountAttributes`+`DeviceAttributes`+`PreKeyState`; local DB in `Signal-Android` is SQLCipher `RecipientDatabase/ThreadDatabase/MessageDatabase` separate from libsignal stores | **Minor** | For fix phase, leave Room but align columns: rename `jid`→`aci:deviceId`, add `kyberPreKeyId/ciphertext` to session row, add `groupMasterKey/groupRevision` to conversations. |
| `SignalContactSync.kt:25` comment, `SignalClient.kt:193` fallback | Fallback comment `https://cms.smsfaith.org` | Real `chat.signal.org / cdn.signal.org / cdsi.signal.org / storage.signal.org / svr2.signal.org` (`SignalServiceNetworkAccess.kt`, `env.rs`) | **Minor** | Remove `cms.smsfaith.org` reference; centralize `SignalServiceConfiguration`. |
| `SignalClient.kt:432-447` `SignalLineSession.kt:28-35` | `padMessage` random pad 1..16 bytes, pad = byte `padSize` (PKCS#7-ish); own `unpadMessage` | Padding is handled inside `libsignal` `Content`→`DataMessage` encryption (no custom pad); Signal does not use WhatsApp-style random pad | **Minor** | Remove manual pad; rely on libsignal cipher. If manual pad retained for non-E2E fallback, document as nonstandard. |
| `SignalClient.kt:375-388` | `placeCall` emits `CallOffer` local only; `rejectCall` `DELETE /api/v1/call/$id` | Calls: `CallMessage{offer{type,opaque}, answer, iceUpdate, hangup,busy,opaque}` inside `Content.callMessage`; signaling via authenticated WS; SFU via `SIGNAL_SFU_URL` | **Minor** | Wire `CallMessage` protos; remove invented `/api/v1/call` path. |

---

## 11 — Items that cannot be verified without live servers

These require a successful TLS-pinned handshake against `grpc.chat.signal.org`/`cdsi.signal.org` with a registered account and cannot be asserted from source alone:

- `Retry-After` / `nextSms`/`nextCall` / `nextVerificationAttempt` / `allowedToRequestCode` precise retry policy and 429 handling.
- `requestedInformation:["pushChallenge","captcha"]` server challenge selection; hCaptcha token validation.
- 423 `RegistrationLock` SVR2/SVR3 credential presentation and `recoveryPassword` re-registration.
- `skipDeviceTransfer` / `requireAtomic` atomicity edge cases on `POST /v1/registration`.
- Domain-fronting path grafting for censored countries (EG/AE/… → Fastly/G) — actual reachability of `reflector-…` paths.
- Rate-limited attachment/CDN form (`/v2/attachments/form/upload` expiry) and `cdn.signal.org` host selection (CDN0 vs CDN2/3).
- `SVR` (PIN/backup) enclave rafts vs plain `registrationLock` string policy enforcement on server.
- `GroupChange` conflict detection for concurrent `PATCH /v2/groups/` (revision bump race).
- CDSI `token`/`token_ack` continuation flow and `CloseCode 4008 RateLimitExceeded` retry.
- Live sealed-sender cert rotation (`SenderCertificate` expiry ≈1 day, `ServerCertificate` chain).

---

## 12 — Prioritized fix list (condensed, grouped by area)

Order by dependency — each lane unblocks the next.

### P0 — Registration (blocks everything)
- [ ] `RegistrationHttpClient.kt:41-54,56-72` — Rewrite to `POST /v1/verification/session`, `PATCH …/pushChallenge|captcha`, `POST …/code {transport}`, `PUT …/code {code}`, `POST /v1/registration` with `Basic e164:password`. Persist `sessionId`. (Blocker; compare `PushServiceSocket:VERIFICATION_SESSION_PATH/VERIFICATION_CODE_PATH/REGISTRATION_PATH`, `RegistrationApiV2.kt`)
- [ ] `RegistrationHttpClient.kt:87-104`, `SignalPayload.kt:77-94` — Build `RegistrationSessionRequestBody` with **both** identities, `aciSignedPreKey/pniSignedPreKey`, `aciPqLastResortPreKey/pniPqLastResortPreKey`, `gcmToken`, `skipDeviceTransfer:true`, `requireAtomic:true`. (Blocker; `RegistrationSessionRequestBody.kt`)
- [ ] `SignalAuthData.kt`, `registration/SignalRegistrationKeys.kt` — Hold ACI+PNI identities, two `registrationId`s, password, UAK (32B via `UnidentifiedAccess.derive`), capabilities `{storage,versionedExpirationTimer,attachmentBackfill,spqr,usernameChangeSyncMessage}`. (Major; `SignalServiceAccountManager.java`, `AccountAttributes.kt`, `KeyHelper`)
- [ ] `RegistrationHttpClient.kt:46-53` — Handle push challenge (FCM token → `PATCH … pushChallenge` within 5 s) and captcha (`PATCH … captcha`). (Major; `RegistrationRepository:requestAndVerifyPushToken()`)

### P0 — Transport (blocks connect/receive)
- [ ] `transport/SignalSocket.kt:73-81,83-95` — Use `wss://grpc.chat.signal.org:443/v1/websocket/` with `Authorization: Basic {aci}.{dev}:{pw}` (`auth.rs:25`, `chat.rs:206`, `env.rs:52,924`). Fix password persistence. (Blocker)
- [ ] `transport/SignalSocket.kt:121-148`, `transport/SignalPayload.kt:25-53`, `SignalProtocol.kt:30-80` — Replace JSON with **protobuf** framing (`libsignal/rust/net/src/proto/chat_websocket.proto`; `WebSocketMessage` binary; `uint64 id`). (Blocker)
- [ ] `SignalSocket.kt:186-199` — Replace `ping()` with `PUT /v1/keepalive` + `RECOMMENDED_WS_CONFIG`; handle `x-signal-timestamp`, close codes 4401/4409. (Major; `chat.rs:51`, `env.rs:39-40`)

### P0 — Envelope / Content / wire (blocks messaging)
- [ ] Add `SignalService.proto` + `libsignal/rust/protocol/src/proto/{wire,sealed_sender,service}.proto` codegen to Gradle (wire → `org.whispersystems.signalservice.internal.push.*`, `wire.proto` → `SignalMessage`/`PreKeySignalMessage`). Replace `SignalProtocol.SignalEnvelope/SignalPayload.buildDataMessage` JSON with protobuf decode/encode. (`SignalClient.kt:162-206`, `SignalProtocol.kt:89-119`) (Blocker)
- [ ] `SignalClient.kt:162-267` — Collapse custom `…/messages/$id/reaction|edit|poll|receipt|typing` into single `PUT /v1/messages/{aci}(/multi)` carrying encrypted `Content` (`DataMessage` or `Content.editMessage`/`receiptMessage`/`typingMessage`/`poll*`/`delete`/`reaction`). (Blocker)
- [ ] `attachments`: implement form fetch `GET /v2/attachments/form/upload`, `AttachmentPointer` crypto, CDN host routing (`SignalServiceNetworkAccess:cdnUrlMap`, `PushServiceSocket`). (Major)

### P1 — Crypto / E2E (PQXDH)
- [ ] `e2e/RustSignalCrypto.kt:44-65` / `e2e/SignalE2E.kt:96-110` — Add Kyber params to bundle; verify signed+kyber signatures; `CIPHERTEXT_MESSAGE_CURRENT_VERSION=4` gate; handle `kyber_ciphertext`/`pq_ratchet`/`addresses` (`session.rs:181`, `pqxdh.rs:36`, `protocol.rs:19,493`). Replace `generateKeyPair` with `IdentityKeyPair/ECKeyPair/KEMKeyPair` (`libsignal/java/shared/java/org/signal/libsignal/protocol/`). (Blocker+Major)
- [ ] `registration/SignalPqPreKey.kt:23-40` — Fix signing: keep `0x08||1568` (1569B) as `serialize()`; sign that buffer; persist 1569B (tag intact); fix `SignedPreKey` signature prefix. (Major; `state/kyber_prekey.rs:53`, `PreKeyUtil.java:157`)
- [ ] Last-resort replay guard `KyberPreKeyStore.mark_kyber_pre_key_used` (tuple `(kyberId,signedEcId,baseKey)`) + one-time deletion. (`storage/traits.rs:129`, `inmem.rs:200`) (Major)

### P1 — Sealed sender
- [ ] `communicate/src/main/rust/src/signal.rs:18-129` + `e2e/RustSignalCrypto:sealedSender*` — Drop CTR+HMAC stub; delegate to `libsignal` `SealedSender.{encrypt,decrypt}` with `SenderCertificate`/`ServerCertificate` validation (`sealed_sender.proto`). Handle device-scoped per-device envelopes. (`e2e/SignalE2E:147-157`, `SignalClient:392-430`) (Blocker+Major; needs live server for cert fetch)

### P1 — GroupsV2
- [ ] `SignalClient.kt:316-351` — Replace `/api/v1/groups` with `GroupsV2Operations` / `GroupsV2Api.putNewGroup` + `GroupsV2AuthorizationString` (`PushServiceSocket:GROUPSV2_GROUP="/v2/groups/"`, `Groups.proto:Group`, `GroupAttributeBlob`, `GroupChange`, `HIGHEST_KNOWN_EPOCH=7`). Use `GroupContextV2{masterKey 32B,revision,groupChange}`. (Major)
- [ ] `rust/zkgroup` + `java/shared/.../zkgroup/groups` — Generate `GroupMasterKey` (32B) → `GroupSecretParams.derive_from_master_key` (`group_params.rs:49`), encrypt members/titles via `ClientZkGroupCipher`. (Major)
- [ ] Sender-key endorsements: fetch `group_send_endorsements_response` per revision, derive `GroupSendFullToken` (`group_send_endorsement.rs:38`) and send as `group-send-token` on unauth WS. (Major)

### P1 — CDSIv2
- [ ] `SignalContactSync.kt:76-98` — Replace local-only persist with `CdsiV2Service.getRegisteredUsers` / `libsignal/rust/net/src/cdsi.rs` attested `POST /v1/{mrenclave}/discovery` to `cdsi.signal.org` (IP `40.122.45.194`, `cds2.proto`: `ClientRequest` `aci_uak_pairs 32B`, `new_e164s` 8B BE, `e164_pni_aci_triples` 40B; `CdsiV2Service.java:40`, `env.rs:90`, `enclave.rs:53`). (Major)
- [ ] Derive `Unidentified Access Key` (16B UAK) per `ProfileKey` (`AciAndAccessKey`) and handle `token/token_ack` continuation + `4008` rate-limit. (Major)

### P1 — Receipts / typing / reactions / edits
- [ ] `SignalClient.kt:224-358,296-314` + `SignalProtocol/ SignalEventProcessor` — Map reactions/edits/deletes/polls to `DataMessage`/`Content.editMessage`/`Delete`/`PollCreate/Vote/Terminate`; receipts to `ReceiptMessage{type,timestamp[]}`; typing to `TypingMessage{action,groupId 32B}`. Parse inbound `Content.{receiptMessage,typingMessage,editMessage,pniSignatureMessage}`. (Major; `SignalService.proto:Content{1,5,6,11}`)
- [ ] Align `MessageEdited`/`MessageDeleted` keys to `targetSentTimestamp`+`authorAciBinary` not `messageId`. (Major)

### P2 — Cleanup
- [ ] `SignalPayload.userAgent()` → `StandardUserAgentInterceptor.USER_AGENT` (`env.rs:629`); `registration/RegistrationAttestation` + `SignalDeviceFingerprint` to no-ops for Signal (SVR replaces them); remove `cms.smsfaith.org` ref. (Minor)
- [ ] `SignalDatabase` — add `kyberPreKeyId/ciphertext`, `groupMasterKey/revision` columns; fix `SignalSession.jid→aci+deviceId` composite key naming. (Minor)
- [ ] Manual `padMessage/unpadMessage` removal (libsignal handles padding). (Minor)

---

## References used for each area

| Area | Reference paths |
|---|---|
| Registration/verification | `lib/libsignal-service/src/main/java/org/whispersystems/signalservice/internal/push/{PushServiceSocket.java:VERIFICATION_SESSION_PATH/VERIFICATION_CODE_PATH/REGISTRATION_PATH, RegistrationSessionMetadataResponse.kt, VerificationSessionMetadataRequestBody.kt, UpdateVerificationSessionRequestBody.kt, RegistrationSessionRequestBody.kt, VerifyAccountResponse.java}`, `lib/network/src/main/java/org/signal/network/api/RegistrationApiV2.kt`, `app/.../registration/data/RegistrationRepository.kt` |
| Service URLs | `C:\Users\Vayun\signal-ref\libsignal\rust\net\src\env.rs:52(DOMAIN_CONFIG_CHAT),66(STAGING),90(CDSI),795(Chat domain),924(CHAT_WEBSOCKET_PATH)`, `Signal-Android/app/.../push/SignalServiceNetworkAccess.kt:uncensoredConfiguration/censorshipConfiguration` |
| WS framing/auth | `C:\Users\Vayun\signal-ref\libsignal\rust\net\src\proto\chat_websocket.proto`, `chat_provisioning.proto`, `rust/net/src/chat.rs:49,206`, `auth.rs:25`, `Signal-Android/.../api/websocket/SignalWebSocket.kt`, `LibSignalChatConnection.kt` |
| Envelope/Content protos | `libsignal-service/src/main/protowire/SignalService.proto:Envelope/Content/DataMessage/ReceiptMessage/TypingMessage/CallMessage/GroupContextV2/BodyRange/AttachmentPointer/PniSignatureMessage/EditMessage`, `libsignal/rust/protocol/src/proto/{wire,sealed_sender,service}.proto` |
| Sealed sender | `libsignal/rust/protocol/src/proto/sealed_sender.proto:UnidentifiedSenderMessage/SenderCertificate` |
| PQXDH/ratchet/Kyber | `libsignal/rust/protocol/src/{pqxdh.rs:36,handshake.rs:31,ratchet.rs:27,session.rs:46/181,protocol.rs:19, kem.rs:199, kem/kyber1024.rs:17, state/{prekey,signed_prekey,kyber_prekey,bundle}.rs}` |
| Fallback replay | `rust/protocol/src/storage/{traits.rs:129,inmem.rs:200}:mark_kyber_pre_key_used` |
| GroupsV2 | `Groups.proto, DecryptedGroups.proto, SignalService.proto:GroupContextV2`, `GroupsV2Operations.java, GroupsV2Api.java, PushServiceSocket:GROUPSV2_GROUP, zkgroup/src/api/groups/{group_params.rs:20,group_send_endorsement.rs:38}, common/constants.rs:18` |
| CDSIv2 | `libsignal/rust/net/src/{env.rs:90, enclave.rs:53, cdsi.rs:50/215, proto/cds2.proto}, rust/attest/src/cds2.rs:15, java/client/.../cds2/Cds2Client.java:24, Signal-Android/.../api/cds/CdsiV2Service.java:40` |
| Receipts/etc | `SignalService.proto:Content/ReceiptMessage/TypingMessage/EditMessage/DataMessage.Reaction/Delete/Poll*/PinMessage` |

---

*Report generated by `signal-verify/researcher` — research/analysis only, no source files modified, no commits made. Written to `c:\Users\Vayun\Documents\code\Modern-Apps\share\SIGNAL_VERIFICATION.md`.*
