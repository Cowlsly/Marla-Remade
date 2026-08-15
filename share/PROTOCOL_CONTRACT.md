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

- **Kotlin owns:** BLE + NSD advertisement/scanning (GATT 0xFCF1 service-data via `presence.rs`), TCP listen/connect, file I/O (SAF/MediaStore),
  foreground notification, permissions.
- **Rust owns:** BetoCore UKEY2 D2D handshake (`ukey2_connections` + `crypto_provider_default` rustcrypto, `NextProtocol::Aes256GcmSiv`), `D2DConnectionContextV1` AES-256-GCM-SIV per-direction seq, frame encode/decode, `Introduction` + payload chunking (16 KiB, `FLAG_LAST`), per-session outbound queue, and Nearby Presence advert build/parse (`np_adv` UnencryptedEncoder, Everyone/public only).
- **Transport rule:** Kotlin never hands Rust a socket and Rust never performs I/O. All bytes
  cross as `byte[]` via `feedInbound` / `drainOutbound`. Presence adverts cross as service-data `byte[]` via `nativeBuildPresenceAdvert` / `nativeParsePresenceAdvert`.
- **Threading:** Calls for a given session handle must be serialized by the caller (one
  coroutine / thread per session). Rust guards the session map with a `Mutex`, but callers
  should still avoid concurrent `feedInbound` + `drainOutbound` on the same handle. Presence `nativeBuild`/`nativeParse` are pure functions, no handle, thread-safe.

## 3. Session lifecycle (call order)

```
handle = nativeInit(localName, localEndpointInfo)   // 1. create session (queues ClientInit via ukey2_connections Initiator)
loop {
  bytes = tcpSocket.read()                           // blocking read
  if (bytes != null) nativeFeedInbound(handle, bytes)
  while ((out = nativeDrainOutbound(handle)) != null) tcpSocket.write(out)
  // drive UI from nativeQueryState / nativeQueryPendingFiles
}
nativeAccept(handle, userAccepted, destDir)          // when state == AWAITING_ACCEPT
// during TRANSFERRING, per file:
nativeOpenFile(handle, name, size); nativeWriteChunk(handle, chunk)*; nativeCloseFile(handle)
nativeDestroy(handle)                                // always (finally / onCleared)

// BLE discovery (Everyone/public, no Google certs):
advBytes = nativeBuildPresenceAdvert(deviceName)     // 1. build GATT 0xFCF1 service-data (np_adv UnencryptedEncoder V0, includes version header)
advertise(advBytes)                                  //    addServiceData(ParcelUuid 0000fcf1-..., advBytes)
jsonBytes = nativeParsePresenceAdvert(serviceData)   // 2. parse scanned service-data -> JSON utf8
// jsonBytes decodes to {"deviceName":"Pixel 7","deviceType":1,"txPower":0,"isTruncated":false}
// or via alias: deviceName = nativeParsePresenceAdvertName(serviceData)
```

Errors are returned as negative `int` codes from the `native* -> int` methods; a null
`byte[]` from `drainOutbound` / `queryPendingFiles` / `parsePresenceAdvert` means "nothing / no session / invalid advert".

## 4. State machine

`nativeQueryState` returns the `State` ordinal below. Kotlin maps it via `ShareState`.

| Code | Name             | Meaning |
|------|------------------|---------|
| 0 | `Handshaking`     | BetoCore UKEY2 D2D in progress (Initiator ClientInit → Server ServerInit → ClientFinished) then GCM-SIV; keep pumping bytes. |
| 1 | `AwaitingAccept`  | Introduction decoded; UI should surface `queryPendingFiles` and prompt Accept/Reject. |
| 2 | `Transferring`    | Accepted; file bytes are flowing (use `openFile`/`writeChunk`/`closeFile`). |
| 3 | `Completed`       | All files transferred + secure-message closed cleanly. |
| 4 | `Failed`          | Handshake/auth/decrypt failure or user rejected. |

## 5. JNI surface (exact signatures)

Class: `com.vayunmathur.share.protocol.ShareNative`
Rust symbols: `Java_com_vayunmathur_share_protocol_ShareNative_<method>` (`extern "system"`).

```kotlin
object ShareNative {
  external fun nativeInit(localName: String, localEndpointInfo: ByteArray): Long
  external fun nativeFeedInbound(handle: Long, bytes: ByteArray): Int
  external fun nativeDrainOutbound(handle: Long): ByteArray?         // null = nothing to send
  external fun nativeQueryState(handle: Long): Int                   // State code, -1 = bad handle
  external fun nativeQueryPendingFiles(handle: Long): ByteArray?     // JSON utf8, see §6
  external fun nativeAccept(handle: Long, accept: Boolean, destDir: String): Int
  external fun nativeOpenFile(handle: Long, fileName: String, fileSize: Long): Int
  external fun nativeWriteChunk(handle: Long, chunk: ByteArray): Int
  external fun nativeCloseFile(handle: Long): Int
  external fun nativeDestroy(handle: Long)                           // void

  // Presence (BetoCore np_adv, Everyone/public, GATT 0xFCF1) — pure functions, no handle
  external fun nativeBuildPresenceAdvert(deviceName: String): ByteArray?   // null on error; else version header + DEs for addServiceData(0xFCF1, bytes)
  external fun nativeParsePresenceAdvert(serviceData: ByteArray): ByteArray? // null on invalid advert; else JSON utf8 {"deviceName","deviceType","txPower","isTruncated"}
  external fun nativeParsePresenceAdvertName(advertBytes: ByteArray): String? // convenience alias: display name or null
}
```

Kotlin also ships `ShareSession` (`share/protocol/ShareSession.kt`) as the
`AutoCloseable` wrapper (handle lifetime, JSON decode, `ShareState` mapping) — the
preferred call site for app code. Direct `ShareNative` calls are allowed but
`ShareSession` should be used for lifecycle correctness.
Presence helpers are called directly on `ShareNative` (no session handle); callers must tolerate `null` and fall back to BT name.

## 6. Data formats

- **Bytes:** All wire frames are opaque `byte[]`. Kotlin does no framing; Rust
  is responsible for varint length-prefix around each `Ukey2Message` / `SecureMessage` / SharingFrame / `PayloadTransferFrame`, protobuf via `prost`/`protobuf` through BetoCore, and buffering
  partial reads internally. D2D secure messages are AES-256-GCM-SIV (AEAD, no separate HMAC), per-direction seq, via `D2DConnectionContextV1::encode_message_to_peer` / `decode_message_from_peer` (associated_data `None`).
- **`queryPendingFiles`:** UTF-8 JSON `byte[]` — `[{"name":"photo.jpg","sizeBytes":1234,"mimeType":"image/jpeg"}]`.
  Empty list is `[]` or (contextually) the skeleton may return a stub; rustdev
  must populate real Introduction metadata before shipping.
- **`parsePresenceAdvert`:** UTF-8 JSON `byte[]` — `{"deviceName":"Pixel 7","deviceType":1,"txPower":0,"isTruncated":false}` (`deviceType` per `np_adv::shared_data::DeviceType`: 0 Unknown, 1 Phone, 2 Tablet, 3 Display, 4 Laptop, 5 TV, 6 Watch, 7 ChromeOS, 8 Foldable, 9 Automotive, 10 Speaker). `null` on invalid/encrypted advert; Kotlin should ignore. `parsePresenceAdvertName` returns `String` display name directly.
- **Presence advert bytes:** V0 unencrypted, `UnencryptedEncoder`, `DeviceInfo` (Phone, 5..9 byte name + truncation bit) + `TxPower` (0 dBm). Version header `0x00` for V0. UUID `0000fcf1-0000-1000-8000-00805f9b34fb` (little-endian on wire `[0xF1,0xFC]`). Constructed in `share/src/main/rust/src/presence.rs` via `np_adv::AdvBuilder`.
- **Files:** Kotlin opens the destination file (SAF/MediaStore/app-cache as
  appropriate) and Rust streams decrypted payload bytes via `writeChunk`. The
  current skeleton `nativeOpenFile`/`nativeWriteChunk`/`nativeCloseFile` are
  placeholders that ack — rustdev should wire them to the secure-message decryptor
  and return `<0` on auth failure.
- **Errors:** `nativeFeedInbound` / `nativeAccept` / `native*File` return `0` on
  success, `<0` on error (`-1` bad handle/args, `-2` wrong state, etc.). The
  caller should transition to `Failed` and surface the error.

## 7. What rustdev implemented (P1 BetoCore)

Inside `share/src/main/rust/src/`:

- `presence.rs` — `np_adv` V0 `AdvBuilder<UnencryptedEncoder>` build + `deserialize_advertisement` + `CredentialBookBuilder<EmptyMatchedCredential>` parse (Everyone/public only, no LDT).
- `frame.rs` — varint/length-prefix + `prost` Sharing WireFormat / `PayloadTransferFrame` (tags verified vs `nearby_clone/sharing/proto/wire_format.proto` and `offline_wire_formats.proto`), keep varint helpers, 16 KiB `FLAG_LAST`.
- `session.rs` — BetoCore `D2DHandshakeContext` (Initiator/Server via `ukey2_connections`, `HandshakeImplementation::Spec`, `NextProtocol::Aes256GcmSiv`, `crypto_provider_default::CryptoProviderImpl` rustcrypto) → `D2DConnectionContextV1` AES-256-GCM-SIV encode/decode (varint-framed `SecureMessage` protobuf), adapted `frame.rs`/`payload.rs` Introduction / `ConnectionResponse` / `PayloadTransferFrame` over the new context.
- Deleted `handshake.rs` (hand-rolled P-256/commitment/HKDF UKEY2) and `secure.rs` (AES-256-CBC + HMAC) — full drop, no fallbacks.

Existing JNI names/contract in `lib.rs` kept stable (init/feed/drain/query/accept/open/write/close/destroy); added `nativeBuildPresenceAdvert` / `nativeParsePresenceAdvert` (`ByteArray?` JSON) + `nativeParsePresenceAdvertName` (`String?` alias).

## 8. What appdev must implement

- `MainActivity` + Compose screens (using `:library:ui` — direct
  `androidx.compose.material*` imports are banned by lint) for:
  discovery/teaming (BLE + NSD/mDNS), nearby list, incoming banner (Accept/Reject),
  progress, and completion. Wire `ShareSession` in a `ViewModel` (one session per peer).
- TCP transport: listen on ephemeral port, advertise via BLE/NSD, connect to peer,
  pump `feedInbound`/`drainOutbound` on a background dispatcher, post state to UI via
  `queryState` polling or callbacks.
- BLE discovery: build via `nativeBuildPresenceAdvert(deviceName)` → `addServiceData(0xFCF1, bytes)`, scan via `nativeParsePresenceAdvert(serviceData): ByteArray?` JSON or `nativeParsePresenceAdvertName: String?`, tolerate `null`.
- File I/O: destination directory picker (SAF), creating file entries, `openFile`/
  `writeChunk`/`closeFile` sequence during `Transferring`.
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
