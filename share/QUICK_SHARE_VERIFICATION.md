# Quick Share verification — `:share` against GMS 26.24.34

This document records **both sides** of every wire-format claim `:share` makes: what our
code does, and the decompiled GMS code that says it should. It exists because the previous
version of this module cited a source that does not exist and attributed invented constants
to "GMS-analysis findings". Anything here that is a guess is labelled a guess.

## Sources

| Name | Location | Notes |
|---|---|---|
| Decompile | `C:\Users\Vayun\gms-analysis\jadx-out-stable\sources\` | jadx output of `com.google.android.gms@262434035@26.24.34`. All `p000\xxxx.java` paths below are relative to this. |
| Writeup | `C:\Users\Vayun\gms-analysis\QUICK_SHARE_INTERNALS.md` | Prose analysis derived from the decompile. Cited as "writeup §N". Where it disagrees with the decompile, **the decompile wins** — see [Corrections](#corrections-to-the-writeup). |

There is **no** `nearby_clone` checkout on this machine. `frame.rs` and
`PROTOCOL_CONTRACT.md` previously claimed the tags were "verified against
`nearby_clone/sharing/proto/wire_format.proto`". They were not; that path never existed.

## How protobuf tags were recovered

protobuf-lite keeps a machine-readable schema in each generated class. The recipe:

1. Fields are declared in field-number order, `bitField0_` (java field `b`) first.
2. `new jeqn(DEFAULT, "<info>", new Object[]{…})` inside `mo127gY` carries the field
   **numbers and types** in the info string and the field **names** in the object array,
   in the same order. Non-string entries in the array are enum verifiers or nested
   message classes and do not consume a field slot.
3. The info string header is
   `flags, fieldCount, oneofCount, hasBitsCount, minFieldNumber, maxFieldNumber, entryCount, mapFieldCount, repeatedFieldCount, checkInitialized`,
   then one entry per field: `fieldNumber, typeChar, hasBitIndex`. The low byte of
   `typeChar` is the `FieldType` ordinal (`0 DOUBLE … 8 STRING, 9 MESSAGE, 10 BYTES,
   11 UINT32, 12 ENUM, … 18+ *_LIST, 27 MESSAGE_LIST, 35+ *_LIST_PACKED, 50 MAP,
   51+ ONEOF_*`).
4. **Types** come from the Java field declarations; **proto2 defaults** come from the
   private constructor.
5. **Semantic names** come from the Kotlin mappers, which preserve them in
   `kddz.m172584e(x, "getFoo(...)")` literals (`kddz.m172585f/e` is
   `Intrinsics.checkNotNullParameter/checkNotNullExpressionValue`).

Worked example — `FileMetadata`, `p000\duvq.java:89`:

```
info    "\u0001\t\u0000\u0001\u0001\t\t\u0000\u0000\u0000"
        + "\u0001ဈ\u0000" "\u0002᠌\u0001" "\u0003ဂ\u0002" "\u0004ဂ\u0003" "\u0005ဈ\u0004"
        + "\u0006ဂ\u0005" "\u0007ဈ\u0006" "\bဂ\u0007" "\tဇ\b"
objects {bits, "c", "d", duvo, "e", "f", "g", "h", "i", "j", "k"}
```

9 fields numbered 1..9. `ဈ`=0x1008→STRING, `᠌`=0x180C→ENUM, `ဂ`=0x1002→INT64,
`ဇ`=0x1007→BOOL. Java declares `c:String, d:int, e:long, f:long, g:String =
"application/octet-stream", h:long, i:String, j:long, k:boolean`. Names from the mapper
`p000\dzra.java` (`"getType(...)"`, `"getMimeType(...)"`, `"getParentFolder(...)"`, and
the `(id, payloadId, …)` constructor order which reads `h` then `e`). Result: the table in
[Sharing wire format](#sharing-wire-format).

## Transport, discovery and crypto

| Fact | Value | Our code | GMS |
|---|---|---|---|
| Socket framing | 4-byte **big-endian int32** length prefix, then that many bytes | `frame.rs` `frame_with_length` / `try_consume_frame` | write `p000\dnhn.java:218-221` (`writeInt`, `write`, `flush`); read `:344` (`readInt`), `:362` (`readFully`), accounting `len + 4` at `:223`/`:367` |
| Length sanity bound | reject negative, reject `> MAX_FRAME_LEN` | `frame.rs` `ConsumeResult::Invalid` | `p000\dnhn.java:348-349` (`readInt >= 0 && readInt <= jwky.m158405ae()`) |
| Applies to | UKEY2 messages **and** every encrypted frame, same channel | `session.rs` `Phase` | `p000\dnij.java:102-107` writes all three UKEY2 messages via the same `mo62623A`; `p000\dnhn.java:212` encrypts before the prefix, `:372` decrypts after it |
| Post-handshake body | an `OfflineFrame` | `session.rs` `handle_encrypted_frame` | `p000\dnhn.java:377` parses the decrypted bytes with `dnlx.m62950a` and reads an `ivlt` frame type |
| UKEY2 record protocol | **`AES_256_CBC-HMAC_SHA256`**, strictly enforced | `session.rs` `NEXT_PROTOCOL` | offered `p000\jgzt.java:798`; anything else rejected with alert 103 `"Incorrect next protocol"` at `:550-551` |
| Handshake cipher offered | ordinal `101` only | vendored `ukey2_connections` decides | `p000\jgzr.java:12` (`f505123b = 101`) |
| Nearby Connections service id | `"NearbySharing"` | `ble_adv.rs` `SERVICE_ID` | `p000\dzqx.java:108`, `:143`, `:150` |
| mDNS service type | `_%s._tcp` with `%s` = uppercase hex of `SHA-256(serviceId)[0..6]` → **`_FC9F5ED42C8A._tcp`** | `ble_adv.rs` `mdns_service_type`, asserted by `mdns_service_type_matches_gms` | `p000\dsmo.java:119-121`, `:180`; `drwj.m66219Z` = SHA-256 then `Arrays.copyOf`; `blqe.m21076e` with the uppercase alphabet at `blqe.java:8` |
| mDNS TXT | `IPv4` attribute holds the address; attributes round-trip verbatim | `NsdDiscoveryManager.kt` `TXT_IPV4` | read preference `p000\dsmo.java:127-134`; set `:550`; round-trip `:288-301` |
| BLE service UUID | **`0000FEF3-0000-1000-8000-00805F9B34FB`**, secondary `0000FC73-…` | `BleDiscoveryManager.kt` `NEARBY_CONNECTIONS_SERVICE_UUID` | `p000\dses.java:21`, `:22`; GATT-server variant `:20`; usage `p000\dsbl.java:167`, `:455-480`, `:871-876` |
| BLE `serviceIdHash` | `SHA-256("NearbySharing")[0..3]` → `FC9F5E` | `ble_adv.rs` `ble_service_id_hash` | 3-byte width enforced at `p000\dscb.java:44`, `:86` |
| BLE advertisement layout | header `(version<<5)&0xE0 \| (socketVersion<<2)&0x1C \| (fast?2:0) \| (rx?1:0)`; if not fast a 3-byte `serviceIdHash`; length 1 byte if fast else 4-byte BE; data; optional 2-byte `deviceToken` | `ble_adv.rs` `BleAdvertisement` | serialise `p000\dscb.java:126-146`; parse `p000\dsdu.java:239-297`; version/socketVersion must both be 2 (`dscb.java:56-68`, `dsdu.java:242-247`) |
| BLE advertisement budget | 27 B fast / 512 B extended | `ble_adv.rs` `MAX_FAST_ADV_LEN` / `MAX_EXTENDED_ADV_LEN` | `p000\dscb.java:116-123` |
| FastInitiation | service UUID `0xFE2C`, service-data prefix `FC128E` | `ble_adv.rs` `fast_initiation_service_data`, `BleDiscoveryManager.kt` `FAST_INITIATION_SERVICE_UUID` | `p000\dvyf.java:10`, `:34-38`; advertised `p000\dvys.java:288-295` |
| Paired-key handshake | mandatory frame pair, after the `CONNECTION_RESPONSE` exchange | `session.rs` `enter_paired_key` | writeup §11.3; signature over `roleByte ‖ authToken` at `p000\dzvh.java:233` |
| Role domain separation | signer `SENDER→1, RECEIVER→2` | not applicable (we sign nothing) | `p000\dzux.java:15-24`; role names `p000\dzur.java:14-17` |
| `secret_id_hash` decoy | **6 random bytes** | `payload.rs` `SECRET_ID_HASH_LEN` | `p000\dzvh.java:243` (`ebuk.m70456c(6)`), same width as the real `ebuk.m70455b(token, secretId, 6)` at `p000\dzux.java:7-13` |
| `signed_data` decoy | **72 random bytes** | `payload.rs` `SIGNED_DATA_DECOY_LEN` | `p000\dzkz.java:509`, `:522`, `:528` all return `m69683E(72)` when signing fails |

## Connection handshake ordering

The order is `CONNECTION_REQUEST → UKEY2 → CONNECTION_RESPONSE`, the response is
exchanged **in plaintext by both sides**, and encryption starts only once both have
accepted. Four independent sites agree:

| Fact | Citation |
|---|---|
| The client writes `CONNECTION_REQUEST` and then **immediately** starts the UKEY2 client, waiting for nothing | `p000\dnsi.java:9582` (`"In requestConnection(), wrote ConnectionRequestFrame to endpoint %s"`) then `:9633` (`dnij.m62692c` = `startClient`) |
| The server reads `CONNECTION_REQUEST` and starts the UKEY2 server, sending no response | `p000\dnsi.java:5106` (parsed `ivkl`) then `:5129` (`dnij.m62693d` = `startServer`) |
| All three UKEY2 messages go over the same framed channel, unencrypted | `p000\dnij.java:92-107` (`startClient` writes msg 1, reads 2, writes 3 via `mo62623A`/`mo62628F`), `:175-180` (`startServer`) |
| `CONNECTION_RESPONSE` is written **inside** `acceptConnection`, after UKEY2 and before the encryptor exists | `p000\dncj.java:1204-1205`, with `doeqVar.mo63639c()` only at `:1208` |
| The encryptor is installed only once **both** sides have accepted | `p000\dnsi.java:4319` (`evaluateConnectionResult`), early return at `:4327-4339`, install at `:4370`/`:4375` |
| Accepting at this layer is programmatic, not a user prompt | `p000\dzuj.java:76` calls `acceptConnection(endpointId, payloadCallback)` and awaits it with a resolution timeout; the user-facing accept is the Sharing-layer `INTRODUCTION`/`RESPONSE` |

`ConnectionResponseFrame`'s two status fields are now fully recovered, and GMS writes
**both** from one builder (`p000\dnlx.java:1031-1051`, `m62960k`):

| Field | Accept | Reject | Citation |
|---|---|---|---|
| 1 `status:int32` | `0` | `8004` | `p000\dncj.java:1204` (`m62960k(0, …)`), `:1622` (`m62960k(8004, …)`); the same `8004` `evaluateConnectionResult` reports at `p000\dnsi.java:4383` |
| 3 `response:enum` | `1` | `2` | written as `(status == 0 ? 2 : 3) - 1` at `p000\dnlx.java:1041-1051`; read back as accepted when `ivkn.m131628a(response) == 2` at `p000\dnsi.java:6911`, and `m131628a` maps `0→1, 1→2, 2→3` (`p000\ivkn.java`) |

Field 1's **presence** is load-bearing. `p000\dnsi.java:6911` reads acceptance as
`has(response) ? verify(response) == 2 : has(status) && status == 0`, so a response with
neither field — or with `status = 0` merely defaulted rather than written — is a
*rejection*. `payload.rs` therefore emits `08 00` explicitly, and
`OfflineConnectionResponseFrame::accepted()` applies the same rule when reading.

## Sharing wire format

`sharing/proto/wire_format.proto` equivalent.

| Message | GMS class | Fields |
|---|---|---|
| `Frame` | `p000\duvt.java:67` | `1 version:enum` (`p000\duvs.java`: `UNKNOWN_VERSION=0, V1=1`), `2 v1:message` |
| `V1Frame` | `p000\duwk.java:85` | `1 type:enum`, `2 introduction`, `3 connection_response`, `4 paired_key_encryption`, `5 paired_key_result`, `6 certificate_info` (`duvh`), `7 progress_update` (`duwd`), `8 bindings` (`duva`) |
| `V1Frame.FrameType` | `p000\duwj.java` | `UNKNOWN_FRAME_TYPE=0, INTRODUCTION=1, RESPONSE=2, PAIRED_KEY_ENCRYPTION=3, PAIRED_KEY_RESULT=4, CERTIFICATE_INFO=5, CANCEL=6, PROGRESS_UPDATE=7, BINDINGS=8` |
| `IntroductionFrame` | `p000\duvw.java:93` | `1 file_metadata[]`, `2 text_metadata[]`, `3 required_package:string`, `4 wifi_credentials_metadata[]`, `5 app_metadata[]`, `6 start_transfer:bool`, `8 use_case:enum`, `9 repeated int64` (unpacked) |
| `IntroductionFrame.use_case` | `p000\duvv.java` | `UNKNOWN=0, NEARBY_SHARE=1, REMOTE_COPY=2, TAP_TO_SHARE=9, FILE_SYNC=10` |
| `FileMetadata` | `p000\duvq.java:89` | `1 name:string`, `2 type:enum`, `3 payload_id:int64`, `4 size:int64`, `5 mime_type:string = "application/octet-stream"`, `6 id:int64`, `7 parent_folder:string`, `8 hash:int64`, `9 is_sensitive_content:bool` |
| `FileMetadata.Type` | `p000\duvp.java` | `UNKNOWN=0, IMAGE=1, VIDEO=2, ANDROID_APP=3, AUDIO=4, DOCUMENT=5, CONTACT_CARD=6` |
| `TextMetadata` | `p000\duwh.java:79` | **starts at field 2**: `2 text_title:string`, `3 type:enum`, `4 payload_id:int64`, `5 size:int64`, `6 id:int64`, `7 is_sensitive_text:bool` |
| `TextMetadata.Type` | `p000\duwg.java` | `UNKNOWN=0, TEXT=1, URL=2, ADDRESS=3, PHONE_NUMBER=4` |
| `WifiCredentialsMetadata` | `p000\duwo.java` | **starts at field 2**: `2 ssid:string`, `3 security_type:enum`, `4 payload_id:int64`, `5 id:int64` |
| `…SecurityType` | `p000\duwn.java` | `UNKNOWN_SECURITY_TYPE=0, OPEN=1, WPA_PSK=2, WEP=3, SAE=4` |
| `AppMetadata` | `p000\duuy.java` | `1 app_name:string`, `2 size:int64`, `3 payload_id:repeated int64` (packed), `4 id:int64`, `5 file_name:repeated string`, `6 file_size:repeated int64` (packed), `7 package_name:string` |
| `ConnectionResponseFrame` | `p000\duvl.java:67` | `1 status:enum`, `2 map<int64, duuz>` (entry types from `p000\duvi.java`) |
| `…Frame.Status` | `p000\duvk.java` | `UNKNOWN=0, ACCEPT=1, REJECT=2, NOT_ENOUGH_SPACE=3, UNSUPPORTED_ATTACHMENT_TYPE=4, TIMED_OUT=5` |
| `PairedKeyEncryptionFrame` | `p000\duvx.java:66` | `1 signed_data:bytes`, `2 secret_id_hash:bytes`, `3 optional_signed_data:bytes`, `4 qr_code_handshake_data:bytes` |
| `PairedKeyResultFrame` | `p000\duwa.java:66` | `1 status:enum`, `2 os_type:enum` |
| `…Result.Status` | `p000\duvz.java` | `UNKNOWN=0, SUCCESS=1, FAIL=2, UNABLE=3` |
| OS type | `p000\iwkr.java` | `UNKNOWN_OS_TYPE=0, ANDROID=1, CHROME_OS=2, IOS=3, WINDOWS=4, MACOS=5` |

The `PairedKeyEncryptionFrame` field order is fixed by the mapper `p000\dzrr.java:9-23`,
which builds `dzrs(signedData, optionalSignedData, secretIdHash, qrCodeHandshakeData)`
(names from `p000\dzrs.java:133`) out of java fields `c`, `e`, `d`, `f` — that is, fields
**1, 3, 2, 4**. The declaration order is *not* the constructor order.

## Nearby Connections wire format

`offline_wire_formats.proto` equivalent.

| Message | GMS class | Fields |
|---|---|---|
| `OfflineFrame` | `p000\ivla.java` | `1 version:enum` (`p000\ivkz.java` accepts `{0,1}`), `2 v1:message` |
| `V1Frame` | `p000\ivlu.java` | `1 type`, `2 connection_request` (`ivkl`), `3 connection_response` (`ivko`), `4 payload_transfer` (`ivlo`), `5 bandwidth_upgrade_negotiation` (`ivkb`), `6 keep_alive` (`ivks`), `7 disconnection` (`ivkq`), `8 paired_key_encryption` (`ivle`), `9 authentication_message` (`ivjb`), `10 authentication_result` (`ivjc`), `11 auto_resume` (`ivji`), `12 auto_reconnect` (`ivjf`), `13 bandwidth_upgrade_retry` (`ivkf`) |
| `V1Frame.FrameType` | `p000\ivlt.java` | `UNKNOWN=0, CONNECTION_REQUEST=1, CONNECTION_RESPONSE=2, PAYLOAD_TRANSFER=3, BANDWIDTH_UPGRADE_NEGOTIATION=4, KEEP_ALIVE=5, DISCONNECTION=6, PAIRED_KEY_ENCRYPTION=7, AUTHENTICATION_MESSAGE=8, AUTHENTICATION_RESULT=9, AUTO_RESUME=10, AUTO_RECONNECT=11, BANDWIDTH_UPGRADE_RETRY=12` |
| `ConnectionRequestFrame` | `p000\ivkl.java:120` | `1 endpoint_id:string`, `2 endpoint_name:string`, `3 handshake_data:bytes`, `4 nonce:int32`, `5 mediums:repeated enum` (**unpacked**), `6 endpoint_info:bytes`, `7 medium_metadata` (`ivkw`), `8 keep_alive_interval_millis:int32`, `9 keep_alive_timeout_millis:int32`, `10 device_type:int32`, `11 device_info:bytes`, `12/13` oneof (`ivkp` / `ivlr`), `14 connections_device_type:enum`, `15` (`ivkt`) |
| `Medium` | `p000\ivkk.java` | `UNKNOWN_MEDIUM=0, MDNS=1, BLUETOOTH=2, WIFI_HOTSPOT=3, BLE=4, WIFI_LAN=5, WIFI_AWARE=6, NFC=7, WIFI_DIRECT=8, WEB_RTC=9, BLE_L2CAP=10, USB=11, WEB_RTC_NON_CELLULAR=12, AWDL=13` |
| `ConnectionResponseFrame` | `p000\ivko.java:86` | `1 status:int32`, `2 handshake_data:bytes`, `3 response:enum`, `4 os_info` (`ivld`), `5 multiplex_socket_bitmask:int32`, `7 int32`, `8` (`ivkt`), `9 int32`. **No field 6.** |
| `PayloadTransferFrame` | `p000\ivlo.java` | `1 packet_type:enum`, `2 payload_header`, `3 payload_chunk`, `4 control_message` |
| `PayloadHeader` | `p000\ivln.java` | `1 id:int64`, `2 type:enum`, `3 total_size:int64`, `4 is_sensitive:bool`, `5 file_name:string`, `6 parent_folder:string`, `7 int64` |
| `PayloadChunk` | `p000\ivlk.java` | `1 flags:int32`, `2 offset:int64`, `3 body:bytes`, `4 index:int32` |
| `KeepAliveFrame` | `p000\ivks.java` | `1 ack:bool`, `2 seq_num:**uint32**` |
| `DisconnectionFrame` | `p000\ivkq.java` | `1 bool`, `2 bool` |

`endpoint_id` and `endpoint_name` are both validated as required:
`p000\dnlx.java:669` (`"OfflineFrame CONNECTION_REQUEST missing endpointId field."`) and
`:675` (`"… missing endpointName field."`). Field vocabulary from `p000\dnlw.java:305`
(`ConnectRequestParameters{endpointId=…, endpointInfo=…, handshakeData=…, nonce=…,
mediums=…, keepAliveIntervalMillis=…, keepAliveTimeoutMillis=…, deviceType=…,
localDeviceInfo=…}`).

## Discovery: the endpoint-info blob and `WifiLanServiceInfo`

Being *listed* by a Quick Share device needs two structures that used to be listed under
[Not determinable](#not-determinable-from-the-apk). Both are fully recovered. The
obfuscated classes kept their `toString()` names: `dzqk` is
`Advertisement(version, encryptedMetadataKey, deviceType, deviceName,
qrCodeAdvertisingToken, vendorId)`, `dzrl` is `EncryptedMetadataKey(cipherText, salt)`.

**Nearby Sharing endpoint info** — builder `dzqk.m69796b()` (`p000\dzqk.java:134-186`),
parser `dzqj.m69794a()` (`p000\dzqj.java:11-92`), bit helpers `dzrm.m69822a` / `m69823b`
(`p000\dzrm.java`).

| Offset | Field | Notes |
|---|---|---|
| 0 bits 7..5 | `version` | 0 or 1 accepted (`dzqj.java:26-29`); GMS always writes 1 (`dzph.java:355`, `dzxp.java:360`, `eair.java:93`) |
| 0 bit 4 | visibility | `1` = contact-only, no name follows; `0` = plaintext name follows |
| 0 bits 3..1 | `deviceType` | `UNKNOWN=0, PHONE=1, TABLET=2, LAPTOP=3, CAR=4, FOLDABLE=5, XR=6` (`p000\eanu.java`) |
| 1..2 | `salt` | exactly 2 bytes (`p000\dzrl.java:20-23`) |
| 3..16 | `encryptedMetadataKey` | exactly 14 bytes (same check); written after the salt (`dzqk.java:150`) |
| 17 | `nameLen` | `1..32`, present only when the visibility bit is 0 (`dzqj.java:47`) |
| 18.. | `name` | UTF-8; rejected if decoding yields U+FFFD (`dzqj.java:51-54`) |
| then | TLVs | `type(1) len(1) value(len)`; `1` = QR token, `2` = 1-byte vendor id |

The parser's first check is `length < 17` →
`"Incorrect advertisement format: size (%s) is less than minimum size (%s)."`
(`dzqj.java:18-21`).

**Everyone mode needs no real metadata key.** `eafm.m70022a` (`p000\eafm.java:7-14`) looks
the credential up (`dzvb.m69877b`) and passes the result — `null` when nothing matches — to
`dzqk.m69795a`, which only bails with `"Decode contact-only mode advertisement without
credential"` when the **plaintext name is absent**. So 2 + 14 random bytes plus a plaintext
name decode fine; `endpoint_info.rs` documents them as decoys, exactly like
`SECRET_ID_HASH_LEN` / `SIGNED_DATA_DECOY_LEN` in `payload.rs`.

**`WifiLanServiceInfo`** (the mDNS instance name) — serializer `p000\dnuw.java:175-207`,
deserializer `p000\dnux.java:66-145`. The struct is URL-safe, unpadded, unwrapped Base64:
`bloa.m20888c` / `m20891f` / `m20892g` all pass Android flag `11`
(`p000\bloa.java:29`, `:53`, `:61`).

| Offset | Field | Notes |
|---|---|---|
| 0 bits 7..5 | `version` | must be 1 (`dnux.java:91-95`); written as `pcp \| 32` (`dnuw.java:176`) |
| 0 bits 4..0 | `pcp` | must be 1, 2 or 3 (`dnux.java:110-113`). Quick Share advertises `Strategy(1,1)` (`p000\dzye.java:23`), which `dnsi.m63137x` maps to **3** (`p000\dnsi.java:922`) |
| 1..4 | `endpointId` | exactly 4 ASCII bytes (`dnuw.java:179-181`) |
| 5..7 | `serviceIdHash` | `FC 9F 5E`, the same 3 bytes `ble_adv.rs` already derives |
| 8 | UWB address length | optional; `0`, `2` or `8` |
| 9 | flags | optional |

Minimum accepted length is 8 (`dnux.java:87-90`) and both trailing fields are guarded by
`wrap.remaining() > 0` (`:119`, `:134`), so `:share` emits exactly 8 bytes. That is
deliberate: the flags byte's base value (`r24` at `dnuw.java:194-199`) did not survive
decompilation, and omitting an optional field guesses nothing. If hardware testing shows
the peer wants those bytes, that is where to look.

The endpoint info also carries the peer's display name in the `n` TXT attribute
(`dnuw.java:208`, name literal `"n"`), which `dnux.java:98-106` requires.

### The same blob is required in three places

| # | Where | What the peer does without it |
|---|---|---|
| 1 | BLE `0xFEF3` `BleAdvertisement.data` | `p000\eafg.java:89-93`: parse fails → `"Failed to parse endpoint %s (%s)"` → never listed |
| 2 | mDNS `_FC9F5ED42C8A._tcp` record | `p000\dnux.java:103-105`: `"Cannot deserialize WifiLanServiceInfo: EndpointInfo is missing"` → never listed |
| 3 | `ConnectionRequestFrame.endpoint_info` | `p000\each.java:2092-2097`: `"Failed to parse incoming connection from endpoint %s. Disconnecting."` → hangs up |

Site 2 is the one that decides whether a transfer can happen at all, because **WIFI_LAN is
the only medium `:share` can accept a connection on**.

## Defects fixed

| # | Was | Is now | Severity |
|---|---|---|---|
| A | protobuf base-128 **varint** length prefix (`frame.rs` `encode_varint`/`frame_with_length`) | 4-byte big-endian int32 (`p000\dnhn.java:219`, `:344`) plus a sanity bound (`:348`) | Blocker |
| B | `NextProtocol::Aes256GcmSiv` — rejected by GMS with alert 103 | `NextProtocol::Aes256CbcHmacSha256` (`p000\jgzt.java:550`, `:798`) | Blocker |
| C | emitted a **bare** `PayloadTransferFrame`; no `OfflineFrame` wrapper, no connection phase, no keep-alive | full `OfflineFrame` layer, `CONNECTION_REQUEST`/`CONNECTION_RESPONSE` phase, `KEEP_ALIVE`; Sharing `Frame`s ride inside a BYTES `PAYLOAD_TRANSFER` | Blocker |
| D | flattened `SharingFrame` with no `Frame{version,v1}` wrapper | `SharingFrame` → `SharingV1Frame` → oneof (`p000\duvt.java`, `p000\duwk.java`) | Blocker |
| E | `FileMetadata` as `name=1, size=2, mime_type=3, type=4, id=5, payload_id=6(**string**)` | the verified 9-field layout with `payload_id=3:int64` (`p000\duvq.java:89`) | Blocker |
| F | sent `status=0` to accept and `1` to reject; accepted `0 or 1` | `ACCEPT=1` / `REJECT=2` with the other four statuses mapped to a failure reason (`p000\duvk.java`) | Blocker |
| G | `IntroductionFrame` had fields 1–3 only | fields 1–6 and 8, including `start_transfer` and `use_case` | Major |
| I | `SharingFileType::File = 5`, a name that does not exist; no `CONTACT_CARD` | `Document = 5`, `AndroidApp = 3`, `ContactCard = 6` (`p000\duvp.java`) | Major |
| J | never sent `PAIRED_KEY_ENCRYPTION` / `PAIRED_KEY_RESULT`; the stubs had one field each | mandatory pair with 6-byte and 72-byte random decoys and `PairedKeyResultStatus::Unable` | Blocker |
| K | mDNS `_up._tcp` with a random 16-byte instance name and no TXT | `_FC9F5ED42C8A._tcp` derived from the digest, a Base64 `WifiLanServiceInfo` instance name, `IPv4` and `n` TXT | Blocker |
| L | advertised/scanned `0xFCF1` with an `np_adv` V0 unencrypted advert | `0xFEF3` with a Nearby Connections `BleAdvertisement`; optional `0xFE2C` FastInitiation | Blocker |
| M | asserted verification against `nearby_clone`, which does not exist, and attributed `0xFCF1`/`_up._tcp`/`np_adv` to "GMS-analysis findings" | this document; every field in `frame.rs` carries a `p000\*.java` citation | Doc integrity |
| N | ordering inverted: `CONNECTION_REQUEST → CONNECTION_RESPONSE → UKEY2`, so our initiator **blocked** waiting for a response the peer never sends before UKEY2, and our responder answered where the peer expects UKEY2 message 1 | `CONNECTION_REQUEST → UKEY2 → CONNECTION_RESPONSE`, response plaintext from both sides, encryption starting once both accept (see [Connection handshake ordering](#connection-handshake-ordering)) | Blocker |
| O | `ConnectionResponseFrame.status` used a **guessed** `8` to reject and left field 3 unset | `0` / `8004` on field 1, `1` / `2` on field 3, both written, `status = 0` explicitly present | Blocker |
| P | received payloads accumulated into `ActiveRecv.received: Vec<u8>` with **no JNI entry point to read them** — every received file was written to memory and dropped, and a large one OOMed the process | `nativeDrainReceived` hands out one chunk at a time; `ReceivedFileStore` streams them to `filesDir/received` | Blocker |
| Q | `FileSaveHelper`, `TcpTransport.writeIncomingChunk` and `closeIncomingFile` had **zero callers**, and `writeIncomingChunk` called `session.writeChunk` — the *send* path — on received data | all three deleted; the pump drains `nativeDrainReceived` into the store | Major |
| R | `ConnectionRequestFrame` omitted `keep_alive_interval_millis` / `keep_alive_timeout_millis`, which GMS's builder always populates (`p000\dnlw.java:305`) | both set (values ours, since GMS's are Phenotype-driven) | Minor |
| S | BLE advertised the **bare UTF-8 device name** as `BleAdvertisement.data`, which fails `dzqj.java`'s 17-byte floor — a Google device discarded us before the handshake this repo had spent its effort on | a real endpoint-info blob (`endpoint_info.rs`), so `p000\eafg.java:91` parses instead of logging `"Failed to parse endpoint"` | Blocker |
| T | mDNS instance name was the raw 4-character endpoint id and `n` was the plaintext device name, so `dnux.m63241a` failed Base64-decoding the instance name and then rejected the record with `"EndpointInfo is missing"` | Base64 `WifiLanServiceInfo` instance name and Base64 endpoint info in `n` — both directions of the only medium `:share` can accept a connection on | Blocker |
| U | `ConnectionRequestFrame.endpoint_info` was **empty** (`ShareViewModel` never passed `localEndpointInfo` to `TcpTransport`) and `Session::new` generated its own `endpoint_id`, so we advertised one identity and dialled out under another | one endpoint id and one endpoint info shared by the BLE advertisement, the mDNS record and `CONNECTION_REQUEST` | Blocker |
| V | only legacy BLE advertising was reachable, and a real blob does not fit 31 bytes; the advertisement also carried a redundant `addServiceUuid` next to `addServiceData`, and the scan used a legacy-only `ScanSettings` | `startAdvertisingSet` with a fast-mode legacy fallback (`p000\dsbl.java:513`, `:624`), service data only (`:454`, `:876`), and `setLegacy(false)` on the scan (`:884`) | Blocker |
| — | a failed session surfaced only `"Protocol error (-2)"` | `nativeQueryFailureReason` surfaces the specific reason, which names the phase that broke | Minor |
| — | `session.rs` cloned the whole inbound buffer once per loop iteration to peek | `try_consume_frame` operates on `&mut self.inbound_buf` directly | Minor |
| — | received payloads were matched by `recvs.iter_mut().find(!completed)` | keyed by the `payload_id` announced in the `INTRODUCTION` | Major |
| — | `payload.rs` carried a `_touch_text_metadata` dead-code hack to keep an import alive | `TextMetadata` is genuinely defined and decoded | Minor |

Already correct before this change, and unchanged: `PayloadTransferFrame`,
`PayloadHeader` fields 1–6, `PayloadChunk` fields 1–3, and payload type
`BYTES=1, FILE=2, STREAM=3`.

## Corrections to the writeup

The writeup and the decompile disagree in two places. The decompile is primary.

1. **`TextMetadata.Type` is 1-based.** Writeup §5.3 lists
   `0=TEXT, 1=URL, 2=ADDRESS, 3=PHONE_NUMBER`. `p000\duwg.java` reads
   `UNKNOWN(0), TEXT(1), URL(2), ADDRESS(3), PHONE_NUMBER(4)`. The pre-existing `frame.rs`
   values were therefore already right and were **not** changed.
2. **`PairedKeyEncryptionFrame` field order.** The declaration order `signedData,
   optionalSignedData, secretIdHash, qrCodeHandshakeData` is the *constructor* order of
   `dzrs`, not the field order. `p000\dzrr.java:9-23` shows `secret_id_hash` is field 2 and
   `optional_signed_data` is field 3.

## Not determinable from the APK

Stated here rather than papered over.

- **Contact-based visibility is not implementable.** It needs per-contact certificates
  minted against a Google account via `nearbysharing-pa.googleapis.com` (writeup §11.7).
  `:share` is Everyone-mode only and its paired-key frames will always be decoys. That is
  why `PAIRED_KEY_RESULT` carries `UNABLE` (cannot verify) rather than `FAIL` (verified
  and rejected).
- **The `WifiLanServiceInfo` flags byte.** Its base value (`r24` at
  `p000\dnuw.java:194-199`) did not survive decompilation. The field is optional, so
  `:share` omits it and the UWB-address byte rather than guessing — see
  [Discovery](#discovery-the-endpoint-info-blob-and-wifilanserviceinfo).
- **Which mediums a given Google device will actually use.** Quick Share's enabled set
  comes from `dzyl.f204956d` via `dmyg.m61906b` (`p000\dmyg.java:14-46`, where medium `5`
  is WIFI_LAN) and is Phenotype-driven. A device with WIFI_LAN discovery disabled will find
  us over BLE and then try to connect over BLE GATT/L2CAP, which `:share` does not
  implement: it would list us and then fail at connect.
- **Bandwidth upgrade is not implemented.** `:share` rides WIFI_LAN directly; GMS
  bootstraps on BLE and then upgrades (writeup §10.6), so a peer that starts on BLE and
  expects an upgrade will not be satisfied by a LAN-only implementation.
- **UKEY2 DH curve.** Only handshake cipher ordinal `101` is observable (`p000\jgzr.java:12`,
  writeup §11.4). The vendored `ukey2_connections` chooses the curve. No comment in this
  repository asserts X25519.
- **`IntroductionFrame` field 9** (repeated int64, unpacked) exists but its meaning is not
  recovered: `p000\dzra.java` copies it into `dzrf.f204456h` only behind a `use_case`
  guard and no getter literal survives. It is not declared in `frame.rs`.
- **`MAX_FRAME_LEN`.** GMS bounds the read with the Phenotype flag
  `jwky.m158405ae()` (`p000\dnhn.java:349`), whose shipped value is server-side. The 5 MiB
  in `frame.rs` is **ours**.
- **`MAX_CHUNK`.** 16 KiB is ours too; Nearby's chunk size is Phenotype-tunable.
- **Timeouts are Phenotype-driven** (writeup §13.2), so any constant is a guess at a
  server-side value. The only shipped defaults worth honouring are the ~5 s paired-key read
  timeout and the 20 s connection timeout (writeup §9.9) — and note the stale
  `"15 seconds"` log string, which does not match the value it reports.
- **Download directory.** GMS defaults to `"Quick Share"` under public Downloads and does
  **no** `MediaStore` insert on the receive path (writeup §9.8). `:share` streams received
  files into app-private `filesDir/received` instead and lets the user Share or Save each
  one; that is a product decision, not a protocol one, and it needs no storage permission.

### Previously listed here, now recovered

- **`BleAdvertisement.data` / the Nearby Sharing endpoint-info blob.** This section used to
  say the layout "must be recovered from `p000\dzrl.java` / the sharing advertisement
  builder before BLE bootstrap can be claimed to work", and that mDNS was the path that
  worked. **Both halves of that were wrong.** The layout is fully readable — builder
  `dzqk.m69796b`, parser `dzqj.m69794a`, key widths `dzrl.java:20-23` — including the
  accept/reject semantics for a missing credential, and the mDNS record needed the *same*
  blob, so it was not working either. See
  [Discovery](#discovery-the-endpoint-info-blob-and-wifilanserviceinfo) and defects S–V.
  The same shape of mistake as the `ConnectionResponseFrame` guess below: an unrecovered
  field was written off instead of read.
- **`ConnectionResponseFrame.response` (field 3) value names.** This section used to say
  the 1/2 mapping "cannot be read out of the APK" because R8 stripped the names, and that
  `:share` would write only field 1 with an **inferred** `0` for accept and a **guessed**
  `8` for reject. The guess was wrong twice over. Both the builder
  (`p000\dnlx.java:1041-1051`) and the reader (`p000\dnsi.java:6911` with
  `p000\ivkn.java`) pin the mapping without needing the names, the reject status is
  `8004` (`p000\dncj.java:1622`), and field 1's presence is load-bearing. See
  [Connection handshake ordering](#connection-handshake-ordering).

## How this is defended

A round-trip test cannot catch a wrong tag number — it passes against any self-consistent
assignment, which is exactly why the previous suite was green against a fabricated format.
So `frame.rs` asserts **golden bytes**:

| Test | Pins |
|---|---|
| `length_prefix_is_four_byte_big_endian` | `00 00 00 0D` for 13 bytes, `00 00 01 2C` for 300 — a varint prefix would be `0D` and `AC 02` |
| `partial_read_split_mid_prefix` | buffering across a split inside the 4-byte prefix |
| `negative_and_oversized_lengths_are_rejected` | the `dnhn.java:348` bound, and that the buffer is not drained on rejection |
| `file_metadata_golden_bytes` | `0A05612E7478741005180720AC022A0A746578742F706C61696E` |
| `connection_response_accept_encodes_status_one` | `0801` for accept, `0802` for reject |
| `sharing_frame_nesting_golden_bytes` | the full `Frame{version:V1, v1:{type:INTRODUCTION, introduction:{…}}}` nesting |
| `paired_key_encryption_field_order` | `secret_id_hash` at tag `0x12`, `optional_signed_data` at tag `0x1A` |
| `connection_request_mediums_are_unpacked` | `2805`, not the packed `2A0105` |
| `text_and_wifi_metadata_start_at_field_two` | tag `0x12` for `text_title` and `ssid`, not `0x0A` |
| `mdns_service_type_matches_gms` | `_FC9F5ED42C8A._tcp` from `SHA-256("NearbySharing")[0..6]` |
| `ble_service_id_hash_is_the_first_three_digest_bytes` | `FC 9F 5E` |
| `extended_advertisement_round_trip` | header byte `0x48`, the 3-byte hash, the 4-byte BE length |
| `chunked_transfer_is_keyed_by_the_announced_payload_id` | received bytes land under the id from the `INTRODUCTION`, and reach Kotlin through `drain_received` |
| `offline_connection_response_golden_bytes` | `08 00 18 01` for accept and `08 C4 3E 18 02` for reject — both status fields, with `status = 0` present rather than defaulted |
| `a_response_with_neither_status_nor_field_three_is_a_rejection` | GMS's acceptance rule at `p000\dnsi.java:6911` |
| `received_record_layout_is_stable` | the whole `drain_received` record, byte for byte |
| `endpoint_info_golden_bytes` | header `0x22` for `version=1, visibility=0, deviceType=PHONE`, the 16 key bytes, then the length-prefixed name |
| `the_bare_device_name_is_not_a_valid_endpoint_info` | the bug this fixed cannot come back: `parse` refuses the bare UTF-8 name |
| `parse_rejects_a_blob_shorter_than_the_minimum` / `parse_rejects_a_bad_name_length` / `parse_rejects_an_invalid_utf8_name` / `parse_rejects_an_unknown_version` / `parse_rejects_a_truncated_tlv` | one named `dzqj.java` check each |
| `a_multibyte_name_round_trips_and_truncates_on_a_char_boundary` | a 32-byte cap that never splits a character, since a split one decodes to the U+FFFD `dzqj.java:51-54` rejects |
| `wifi_lan_service_info_golden_bytes` | `0x23` for `version=1, pcp=3`, the 4-byte id, `FC 9F 5E`, and exactly 8 bytes |
| `parse_wifi_lan_service_info_applies_the_gms_checks` | the length, version and PCP checks at `dnux.java:86-118` |
| `fast_mode_only_fits_a_short_endpoint_info` | why extended advertising is required: a full-length name does not fit the 27-byte fast budget |
| `the_connection_request_carries_the_advertised_identity` | the advertised endpoint id and endpoint info reach `CONNECTION_REQUEST`, instead of a second random id and an empty blob |

The previous suite was green against the **wrong ordering**, because both sides of a
round-trip test shared the mistake. These four tests would have caught it:

| Test | Pins |
|---|---|
| `initiators_first_batch_is_the_request_and_ukey2_together` | the first thing on the wire is `CONNECTION_REQUEST` **and** a message a real UKEY2 server accepts, with no `CONNECTION_RESPONSE` |
| `responder_sends_no_connection_response_until_ukey2_completes` | a responder fed only the request stays silent; the response appears only after ClientFinished |
| `connection_response_is_plaintext_and_encryption_starts_after_both` | the response round-trips as plaintext protobuf on both sides, and every frame after it does not |
| `a_rejecting_connection_response_fails_the_session_with_the_peers_status` | `8004` is read as a rejection and reported verbatim |

Plus two on the receive path: `a_streamed_payload_is_never_retained_in_the_session`
(draining as you pump empties the queue, so memory is constant in the file size) and the
record round-trip above.

## Interop status

**Ordering, wire format and discovery corrected against the decompile; interop still
unverified.**

The handshake matches GMS phase for phase, the two constants that were guessed are
recovered, and the two discovery structures that were written off as unrecoverable are now
byte-correct against the decompile. That is a decompile-level claim, not an interop result:
**nothing here has been run against a Google device**, so the reported "does not
interoperate with Quick Share" cannot be called fixed until step 2 or 3 below passes on
hardware. What changed is that discovery is no longer *known* to be broken.

Verified on the host: `cargo test -p share_nearby` (69 tests) and
`cargo check -p share_nearby --target aarch64-linux-android`. The session tests drive two
`:share` sessions through the complete
`CONNECTION_REQUEST → UKEY2 → CONNECTION_RESPONSE → PAIRED_KEY_* → INTRODUCTION → RESPONSE → PAYLOAD_TRANSFER`
sequence, which proves internal consistency — both sides share any remaining error — plus
the ordering and discovery tests above, which pin the bytes against the citations rather
than against ourselves.

Not run, because it needs physical hardware:

1. Two `:share` devices on one Wi-Fi network: send a file end to end, then exercise Share
   and Save on the receiver. Proves the whole receive path, and that the new encodings are
   at least self-consistent.
2. `:share` → a Pixel with Quick Share set to "Everyone": the Pixel must **list our device
   by its real name** — the reported bug — then show the Accept sheet with the right
   filename and size, and receive the file.
3. Pixel → `:share` in the same configuration.
4. `adb logcat -s NearbySharing` on the Pixel during 2 and 3. Per writeup §13.6 every log
   string is verbatim, so failures are diagnosable:
   `"Incorrect advertisement format: size (%s) is less than minimum size (%s)."` means the
   endpoint info is still malformed; `"Failed to parse endpoint %s (%s)"`
   (`p000\eafg.java:91`) means BLE discovery still rejects us;
   `"Cannot deserialize WifiLanServiceInfo: EndpointInfo is missing"` means the `n` TXT
   attribute is wrong; `"Failed to parse incoming connection from endpoint %s.
   Disconnecting."` (`p000\each.java:2094`) means `endpoint_info` is not reaching
   `CONNECTION_REQUEST`; `"Incorrect next protocol"` means the cipher;
   `"In readConnectionRequestFrame, expected a CONNECTION_REQUEST v1 OfflineFrame but got a
   %s frame instead"` (`p000\dnsi.java:4067`) means the ordering is still wrong; and
   `"Read an unencrypted (or garbage) frame when we expected an encrypted frame."`
   (`p000\dnhn.java:381`) means the encryption-start point is off by one exchange.

One failure mode is out of our hands: if the Google device has WIFI_LAN discovery disabled
by Phenotype it will find us over BLE and then try to connect over BLE GATT/L2CAP, which
`:share` does not implement. The signature of that case is discovery succeeding while no TCP
connection ever arrives at our `ServerSocket`. A BLE transport is out of scope.

## Unrelated note

`share/SIGNAL_VERIFICATION.md` is entirely about `communicate/**` and
`communicate/src/main/rust/src/signal.rs`. It is in the wrong module. Flagged here rather
than moved, since that is a separate change.
