# Share — JNI Protocol Contract (Kotlin ↔ Rust)

This is the **single source of truth** for the session API between the Kotlin app layer
(Compose UI, NSD/BLE discovery, TCP transport — **appdev**) and the Rust protocol crate
(UKEY2, protobuf framing, secure messages, payload — **rustdev**).

> Do not change native method names, signatures, or the crate/library names
> without updating this doc, `ShareNative.kt`, *and* `share/src/main/rust/src/lib.rs`
> together. Renames break JNI linkage at runtime.

## 1. Crate & library

- **Cargo crate:** `share_nearby` at `share/src/main/rust/` (members entry in root `Cargo.toml`).
- **Shared lib:** `libshare_nearby.so` built via `rustNativeLib("share_nearby", "share")` in
  `share/build.gradle.kts` and wired into `android { ... jniLibs sourceDirectory }` (copy
  `maps` / `library:e2ee-p2p`). Crate exposes `crate-type = ["cdylib","rlib"]`.
- **Kotlin JNI class:** `com.vayunmathur.share.protocol.ShareNative` (the `System.loadLibrary`
  is `share_nearby`).

## 2. Ownership & threading

- **Kotlin owns:** BLE + mDNS advertisement/scanning (Nearby Connections
  `BleAdvertisement` service-data under GATT `0xFEF3`, and `_FC9F5ED42C8A._tcp`), TCP
  listen/connect, file I/O, the foreground notification, permissions.
- **Rust owns:** the Nearby Connections connection handshake
  (`CONNECTION_REQUEST`/`CONNECTION_RESPONSE`), the BetoCore UKEY2 D2D handshake
  (`ukey2_connections` + `crypto_provider_default` rustcrypto,
  **`NextProtocol::Aes256CbcHmacSha256`**), the `D2DConnectionContextV1` secure channel,
  the mandatory paired-key exchange, `OfflineFrame` / Sharing `Frame` encode+decode,
  payload chunking, keep-alive, the per-session outbound queue, and the byte codecs for the
  `BleAdvertisement`, the Nearby Sharing endpoint info and the `WifiLanServiceInfo` mDNS
  instance name.
- **Transport rule:** Kotlin never hands Rust a socket and Rust never performs I/O. All
  bytes cross as `byte[]` via `feedInbound` / `drainOutbound`. Advertisement bytes cross as
  `byte[]` too; Rust returns raw bytes and Kotlin applies Base64 where the medium needs it,
  because Base64 is a platform API.
- **Threading:** calls for a given session handle must be serialized by the caller (one
  coroutine / thread per session). Rust guards the session map with a `Mutex`, but callers
  should still avoid concurrent `feedInbound` + `drainOutbound` on the same handle. The
  discovery helpers take no handle and are pure functions.

### Layering

```
TCP  ►─  int32be(len) ‖ body
                        │
                        ├─ OfflineFrame             (CONNECTION_REQUEST, plaintext)
                        ├─ Ukey2Message             (all three, plaintext)
                        ├─ OfflineFrame             (CONNECTION_RESPONSE, plaintext)
                        └─ D2D-encrypted body
                             └─ OfflineFrame
                                  └─ V1Frame
                                       └─ PayloadTransferFrame
                                            ├─ BYTES payload = Sharing Frame
                                            │    (INTRODUCTION / RESPONSE / PAIRED_KEY_*)
                                            └─ FILE  payload = file bytes
```

A Sharing `Frame` is **never** framed directly on the socket: it is always the body of a
BYTES payload. See `share/QUICK_SHARE_VERIFICATION.md` for the citations.

### Handshake phases

The order is `CONNECTION_REQUEST → UKEY2 → CONNECTION_RESPONSE`, and the response is
exchanged **in plaintext by both sides**. Encryption begins only once both responses
have been seen.

| Phase | Wire body | Who sends | Citation |
|---|---|---|---|
| `Connecting` | plaintext `OfflineFrame` `CONNECTION_REQUEST` | initiator | `p000\dnsi.java:9582` |
| `Ukey2` | plaintext `Ukey2Message` × 3 | client, server, client | `p000\dnij.java:92-107`, `:176-180` |
| `ConnectionAccept` | plaintext `OfflineFrame` `CONNECTION_RESPONSE` | **both**, independently | `p000\dncj.java:1204` (written before `mo63639c()` at `:1208`) |
| `PairedKey` | encrypted `PAIRED_KEY_ENCRYPTION` + `PAIRED_KEY_RESULT` | both | see §6 |
| `Ready` | encrypted `INTRODUCTION` / `RESPONSE` / payloads | both | — |

The initiator writes the request and its UKEY2 ClientInit **back to back** without
waiting: GMS does the same (`p000\dnsi.java:9582` then `:9633` = `startClient`), and a
responder that answers the request before UKEY2 makes a real peer try to parse the
response as a `Ukey2Message`. Encryption is installed exactly where
`evaluateConnectionResult` installs it — after both sides accept
(`p000\dnsi.java:4319`, gate at `:4327-4339`).

Accepting at this layer is **programmatic**, not a user prompt: Quick Share calls
`acceptConnection` as soon as the connection is offered (`p000\dzuj.java:76`). The
user-facing accept is the Sharing-layer `INTRODUCTION` / `RESPONSE`, i.e. `nativeAccept`.

## 3. Session lifecycle (call order)

```
// isInitiator: true for the side that dialled the TCP socket, false for the acceptor.
// The initiator sends CONNECTION_REQUEST, is the UKEY2 client, and therefore speaks
// first in the response exchange too, so getting this wrong deadlocks the handshake.
handle = nativeInit(localName, localEndpointInfo, localEndpointId, isInitiator)
loop {
  bytes = tcpSocket.read()                           // blocking read
  if (bytes != null) nativeFeedInbound(handle, bytes)
  while ((rec = nativeDrainReceived(handle)) != null) appendToFile(rec)   // §6 layout
  while ((out = nativeDrainOutbound(handle)) != null) tcpSocket.write(out)
  // drive UI from nativeQueryState / nativeQueryPendingFiles
}

// Sending:
nativeSetFilesToSend(handle, filesJson)              // stage metadata
nativeQueueIntroduction(handle)                      // held until the paired-key phase ends
// once state == TRANSFERRING, per file:
nativeOpenFile(handle, name, size); nativeWriteChunk(handle, chunk)*; nativeCloseFile(handle)

// Receiving:
nativeAccept(handle, userAccepted, destDir)          // when state == AWAITING_ACCEPT
// destDir is vestigial: Rust never touches the filesystem, and Kotlin stages received
// bytes itself from nativeDrainReceived.

// Diagnosing:
reason = nativeQueryFailureReason(handle)            // null while healthy

nativeSendKeepAlive(handle)                          // optional, for long idle transfers
nativeDestroy(handle)                                // always (finally / onCleared)

// Discovery: one endpoint id and one endpoint info for the whole device, shared by all
// three of the BLE advertisement, the mDNS record and CONNECTION_REQUEST.
type  = nativeMdnsServiceType()                      // "_FC9F5ED42C8A._tcp"
ei    = nativeBuildEndpointInfo(deviceName, DEVICE_TYPE_PHONE)
sd    = nativeBuildBleAdvertisement(ei, deviceToken, fast = false)
advertise(sd)                                        // addServiceData(0000FEF3-..., sd)
wlsi  = nativeBuildWifiLanServiceInfo(endpointId)    // 8 bytes -> Base64 instance name
registerService(base64(wlsi), txt = { "n": base64(ei), "IPv4": addr }, port)
info  = nativeParseBleAdvertisement(scannedServiceData)  // null = not ours
peer  = nativeParseEndpointInfo(info)                // null = a real device would drop it
```

`nativeDrainReceived` must be called after **every** `nativeFeedInbound`: Rust drops each
chunk as it hands it over, so an undrained chunk is a lost chunk.

Errors are returned as negative `int` codes from the `native* -> int` methods; a null
`byte[]`/`String` from `drainOutbound` / `queryPendingFiles` / the discovery helpers means
"nothing, no session, or not parseable".

## 4. State machine

`nativeQueryState` returns the `State` ordinal below. Kotlin maps it via `ShareState`.

| Code | Name             | Meaning |
|------|------------------|---------|
| 0 | `Handshaking`     | `CONNECTION_REQUEST`/`RESPONSE`, UKEY2, and the paired-key exchange are in progress; keep pumping bytes. |
| 1 | `AwaitingAccept`  | An `INTRODUCTION` decoded; the UI should surface `queryPendingFiles` and prompt Accept/Reject. |
| 2 | `Transferring`    | Accepted; payload bytes are flowing (`openFile`/`writeChunk`/`closeFile`). |
| 3 | `Completed`       | Every announced payload arrived. |
| 4 | `Failed`          | Protocol failure, peer rejection, or local rejection. |

## 5. JNI surface (exact signatures)

Class: `com.vayunmathur.share.protocol.ShareNative`
Rust symbols: `Java_com_vayunmathur_share_protocol_ShareNative_<method>` (`extern "system"`).

```kotlin
internal object ShareNative {
  external fun nativeInit(localName: String, localEndpointInfo: ByteArray, localEndpointId: String, isInitiator: Boolean): Long
  external fun nativeFeedInbound(handle: Long, bytes: ByteArray): Int
  external fun nativeDrainOutbound(handle: Long): ByteArray?         // null = nothing to send
  external fun nativeQueryState(handle: Long): Int                   // State code, -1 = bad handle
  external fun nativeQueryPendingFiles(handle: Long): ByteArray?     // JSON utf8, see §6
  external fun nativeAccept(handle: Long, accept: Boolean, destDir: String): Int
  external fun nativeSetFilesToSend(handle: Long, json: ByteArray): Int   // same JSON shape as §6
  external fun nativeQueueIntroduction(handle: Long): Int
  external fun nativeSendKeepAlive(handle: Long): Int
  external fun nativeOpenFile(handle: Long, fileName: String, fileSize: Long): Int
  external fun nativeWriteChunk(handle: Long, chunk: ByteArray): Int
  external fun nativeCloseFile(handle: Long): Int
  external fun nativeDrainReceived(handle: Long): ByteArray?    // one §6 record, null = none
  external fun nativeQueryFailureReason(handle: Long): String?  // null = healthy
  external fun nativeDestroy(handle: Long)                           // void

  // Discovery — derived in ble_adv.rs from SHA-256("NearbySharing"). Pure functions.
  external fun nativeMdnsServiceType(): String?                      // "_FC9F5ED42C8A._tcp"
  external fun nativeBleServiceIdHash(): ByteArray?                  // FC 9F 5E
  external fun nativeBuildBleAdvertisement(data: ByteArray, deviceToken: ByteArray, fast: Boolean): ByteArray?
  external fun nativeParseBleAdvertisement(serviceData: ByteArray): ByteArray?  // null = not ours
  external fun nativeFastInitiationServiceData(metadata: ByteArray): ByteArray? // FC128E ‖ metadata

  // Endpoint info + WifiLanServiceInfo — endpoint_info.rs. Raw bytes; Kotlin does Base64.
  external fun nativeBuildEndpointInfo(deviceName: String, deviceType: Int): ByteArray?
  external fun nativeParseEndpointInfo(blob: ByteArray): ByteArray?  // JSON utf8, null = invalid
  external fun nativeBuildWifiLanServiceInfo(endpointId: String): ByteArray?    // 8 bytes
  external fun nativeParseWifiLanServiceInfo(raw: ByteArray): ByteArray?        // JSON utf8

  // Nearby Presence — retained but NOT on the Quick Share path. See §7.
  external fun nativeBuildPresenceAdvert(deviceName: String): ByteArray?
  external fun nativeParsePresenceAdvert(serviceData: ByteArray): ByteArray?
  external fun nativeParsePresenceAdvertName(advertBytes: ByteArray): String?
}
```

Kotlin also ships `ShareSession` (`share/domain/protocol/ShareSession.kt`) as the
`AutoCloseable` wrapper (handle lifetime, JSON decode, `ShareState` mapping) — the
preferred call site for app code. Direct `ShareNative` calls are allowed but
`ShareSession` should be used for lifecycle correctness. The discovery helpers are called
directly on `ShareNative` (no session handle); callers must tolerate `null`.

## 6. Data formats

- **Bytes:** all wire frames are opaque `byte[]`. Kotlin does no framing. Rust applies a
  **4-byte big-endian int32 length prefix** to every message on the socket — the UKEY2
  handshake messages and, once the channel is encrypted, every `OfflineFrame` — and it
  buffers partial reads internally. Peer-supplied lengths are bounded before allocation.
- **Secure channel:** the UKEY2 record protocol is **`AES_256_CBC-HMAC_SHA256`** with a
  per-direction sequence number, via
  `D2DConnectionContextV1::encode_message_to_peer` / `decode_message_from_peer`
  (`associated_data` `None`). GMS rejects any other record protocol outright, so this is
  not a tunable.
- **Frame nesting:** see the diagram in §2. A Sharing `Frame` is always the body of a BYTES
  payload inside `PAYLOAD_TRANSFER`; file bytes are FILE payloads split into 16 KiB
  `PayloadChunk`s with `FLAG_LAST` on the final one. The header is repeated on every chunk.
- **Paired key:** after the `CONNECTION_RESPONSE` exchange both sides must exchange
  `PAIRED_KEY_ENCRYPTION` and `PAIRED_KEY_RESULT` before the introduction. `:share` holds
  no contact certificate, so it sends a 72-byte random `signed_data` and a 6-byte random
  `secret_id_hash` — the exact widths GMS uses when signing fails — and answers
  `PAIRED_KEY_RESULT{UNABLE}`. Skipping this makes the peer time out after roughly five
  seconds.
- **`CONNECTION_RESPONSE`:** `status` (field 1) is `0` to accept and `8004` to reject
  (`p000\dncj.java:1204`, `:1622`), and `response` (field 3) is `1 = ACCEPT`,
  `2 = REJECT` (`p000\dnlx.java:1041-1051`). Both are written. `status = 0` is emitted
  **explicitly**: with field 3 absent, `p000\dnsi.java:6911` reads a merely-defaulted
  status as a rejection.
- **Endpoint info** (`nativeBuildEndpointInfo` / `nativeParseEndpointInfo`). The Nearby
  Sharing blob a peer needs to list us, minimum 17 bytes:
  `header(1) ‖ salt(2) ‖ encryptedMetadataKey(14) ‖ nameLen(1) ‖ name(1..32) ‖ TLVs*`, where
  the header is `version` in bits 7..5 (we write 1), the contact-only flag in bit 4 (we
  write 0 so the plaintext name follows) and `deviceType` in bits 3..1. Names longer than 32
  bytes are truncated on a UTF-8 boundary, since the peer rejects a name that decodes to
  U+FFFD. The metadata key is 16 random bytes: Everyone mode never needs it to decrypt to
  anything. Cited in full in `share/QUICK_SHARE_VERIFICATION.md`.
- **`WifiLanServiceInfo`** (`nativeBuildWifiLanServiceInfo` / `nativeParseWifiLanServiceInfo`).
  The mDNS instance name, exactly 8 bytes:
  `(version << 5) | pcp` with `version = 1` and `pcp = 3`, then the 4 ASCII endpoint-id
  bytes, then the 3-byte service-id hash `FC 9F 5E`. The trailing UWB-address and flags
  bytes are optional and deliberately omitted. Rust returns raw bytes; Kotlin Base64s them
  with `URL_SAFE or NO_PADDING or NO_WRAP`, which is the flag GMS passes.
- **One identity per device.** The same endpoint id and the same endpoint-info blob must go
  into the BLE advertisement, the mDNS record (instance name and `n` TXT) **and**
  `nativeInit`'s `localEndpointId` / `localEndpointInfo`, because the peer matches
  `CONNECTION_REQUEST` against what it discovered and hangs up otherwise. Renaming the
  device changes the blob, so it must be rebuilt and re-advertised.
- **`queryPendingFiles` / `setFilesToSend`:** UTF-8 JSON `byte[]` —
  `[{"name":"photo.jpg","sizeBytes":1234,"mimeType":"image/jpeg"}]`. An empty list is `[]`.
  `sizeBytes` must be a plain non-negative integer.
- **`drainReceived` record.** One decrypted FILE `PayloadChunk`, self-describing, all
  integers big-endian:

  ```
  u8   version = 1
  i64  payload_id
  i64  offset        (within the payload)
  i64  total_size    (the payload's announced length)
  u8   flags         (bit 0 = last chunk of this payload)
  u16  name_len
  u8[] name          (UTF-8)
  u32  body_len
  u8[] body
  ```

  BYTES payloads are Sharing frames, handled inside Rust, and never appear here. Rust
  keeps no copy of the body, so memory is constant in the file size — and an undrained
  chunk is lost.
- **Where received files go.** Kotlin appends each record to
  `filesDir/received/<name>` (`ReceivedFileStore`) and closes the file on the last-chunk
  flag. Nothing is written to public storage: `:share` declares no storage permission.
  The only exits are **Share** (system chooser over a `FileProvider` URI) and **Save**
  (copy into a directory the user picks with `OpenDocumentTree`, via `DocumentsContract`).
- **`BleAdvertisement` service-data:** header byte
  `(version<<5) | (socketVersion<<2) | (fast?2:0) | (rx?1:0)` with version and socket
  version both 2, then — in extended mode — the 3-byte `serviceIdHash` `FC 9F 5E`, a 4-byte
  big-endian data length, the data, and an optional 2-byte device token. Fast mode omits the
  hash and uses a single length byte, within a 27-byte total budget. Built and parsed in
  `share/src/main/rust/src/ble_adv.rs`; advertised under
  `0000FEF3-0000-1000-8000-00805F9B34FB`. The `data` payload is the endpoint-info blob
  below, and a peer that cannot parse it discards the whole advertisement.
- **`parsePresenceAdvert`:** UTF-8 JSON `byte[]` —
  `{"deviceName":"Pixel 7","deviceType":1,"txPower":0,"isTruncated":false}` (`deviceType`
  per `np_adv::shared_data::DeviceType`). Retained but unused by discovery; see §7.
- **Files:** Kotlin owns both ends of the filesystem — it reads outgoing files and stages
  incoming ones — while Rust owns chunk sequencing, offsets and payload-id bookkeeping.
  Received payloads are keyed by the `payload_id` announced in the `INTRODUCTION`.
- **Errors:** `nativeFeedInbound` / `nativeAccept` / `native*File` return `0` on success and
  `<0` on error (`-1` bad handle or args, `-2` wrong state or protocol failure). The caller
  should transition to `Failed` and surface `nativeQueryFailureReason`, which names the
  phase that broke.

## 7. Implementation map

Inside `share/src/main/rust/src/`:

- `frame.rs` — 4-byte big-endian framing with a sanity bound, plus the `prost` definitions
  for the Sharing wire format (`Frame` → `V1Frame` → oneof, `FileMetadata`,
  `TextMetadata`, `WifiCredentialsMetadata`, `AppMetadata`, `ConnectionResponseFrame`,
  `PairedKey*`) and the Nearby Connections wire format (`OfflineFrame` → `V1Frame` →
  `ConnectionRequest` / `ConnectionResponse` / `PayloadTransfer` / `KeepAlive` /
  `Disconnection`). **Every field carries a `p000\*.java` citation** naming the decompiled
  class it was recovered from.
- `payload.rs` — frame builders (introduction with `payload_id`/`use_case`/`start_transfer`,
  accept/reject, paired-key decoys, connection request/response, keep-alive), the BYTES vs
  FILE payload split, and chunking.
- `session.rs` — the five-phase state machine (`Connecting` → `Ukey2` →
  `ConnectionAccept` → `PairedKey` → `Ready`) over `ukey2_connections` with
  `HandshakeImplementation::Spec`, `NextProtocol::Aes256CbcHmacSha256` and
  `crypto_provider_default::CryptoProviderImpl`, plus the received-chunk queue.
- `ble_adv.rs` — the `BleAdvertisement` byte codec (extended and fast mode, with their
  length budgets) and the `SHA-256("NearbySharing")` derivations for the mDNS service type
  and the BLE `serviceIdHash`.
- `endpoint_info.rs` — the Nearby Sharing endpoint-info blob and the `WifiLanServiceInfo`
  mDNS instance name, both build and parse, with golden-byte tests. These are what make a
  device *listed*; see §6 and `share/QUICK_SHARE_VERIFICATION.md`.
- `presence.rs` — `np_adv` V0 unencrypted build/parse. **Retained but not on the Quick Share
  path**: Presence is a separate subsystem advertising under `0xFCF1`, BetoCore's
  credential/D2D/payload FFI has no Java callers in GMS 26.24.34, and whether betocore is
  live for Quick Share at all could not be determined. Discovery uses `ble_adv.rs`.

Ground truth and the list of corrected defects live in
`share/QUICK_SHARE_VERIFICATION.md`. There is no `nearby_clone` checkout and no `.proto`
file anywhere in this claim chain — the tags come from the decompiled protobuf-lite schema.

## 8. What appdev must implement

- `MainActivity` + Compose screens (using `:library:ui` — direct
  `androidx.compose.material*` imports are banned by lint) for:
  discovery/teaming (BLE + mDNS), nearby list, incoming banner (Accept/Reject),
  progress, and completion. Wire `ShareSession` in a `ViewModel` (one session per peer).
- TCP transport: listen on an ephemeral port, advertise via BLE/mDNS, connect to a peer,
  pump `feedInbound`/`drainOutbound` on a background dispatcher, post state to the UI via
  `queryState` polling. The dialling side must construct its session with
  `isInitiator = true` and the accepting side with `false`.
- BLE/mDNS discovery: build one endpoint-info blob with `nativeBuildEndpointInfo(...)` and
  use it everywhere. Register under `nativeMdnsServiceType()` with the Base64
  `nativeBuildWifiLanServiceInfo(endpointId)` as the instance name, the Base64 blob in the
  `n` TXT attribute and the local address in `IPv4`; advertise
  `nativeBuildBleAdvertisement(blob, token, fast)` under `0xFEF3`, preferring extended
  advertising because a full-length blob does not fit a legacy advertisement; scan with
  `setLegacy(false)` and filter with `nativeParseBleAdvertisement` then
  `nativeParseEndpointInfo` (null from either means "not a Quick Share advertiser, or one a
  real device would drop"). Optionally beacon `0xFE2C` FastInitiation. Only the mDNS leg
  yields a connectable host/port.
- File I/O: `ReceivedFileStore` stages incoming payloads in app-private storage from
  `nativeDrainReceived`; the UI offers Share and Save per file. On the send side,
  `openFile`/`writeChunk`/`closeFile` during `Transferring`.
- Foreground service `ShareTransferService` for transfers that outlive the Activity.
- Permissions flow: `NEARBY_WIFI_DEVICES` / `BLUETOOTH_*` / `ACCESS_FINE_LOCATION`
  (see `AndroidManifest.xml`), and the `ACTION_SEND` / `ACTION_SEND_MULTIPLE`
  incoming share intents.

## 9. Build & verify

```powershell
# Build just the Share module (no other apps):
./gradlew :share:assembleDev

# Lint (Material-via-:library:ui, manifests, etc.):
./gradlew :share:lintDev

# Rust only (host build, no NDK):
cargo test -p share_nearby
cargo check -p share_nearby
# NDK:
cargo check -p share_nearby --target aarch64-linux-android
```
