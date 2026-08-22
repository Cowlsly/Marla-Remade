# FindFamily UWB Tracker — Intended Behaviour (clean-room specification)

## 0. Scope, method and how to read this document

This is an **independent statement of what the tracker firmware is supposed to do**, derived
only from documentation and from vendor reference code. It was written without reading any of
`firmware/findfamily-tracker/src`, `firmware/qorvo-uwb`, or `findfamily/src`, so that it can be
diffed against the implementation to find where the two disagree.

### 0.1 Sources

| Tag | Source |
|---|---|
| **DESIGN** | `c:\Users\Vayun\Documents\code\Modern-Apps\FINDFAMILY_UWB_TRACKER_DESIGN.md` |
| **HW** | `c:\Users\Vayun\Documents\code\Modern-Apps\FINDFAMILY_UWB_TRACKER_HARDWARE.md` |
| **PLAN** | `C:\Users\Vayun\.llms\plans\findfamily_tracker_firmware.plan.md` |
| **QSDK** | Qorvo DW3/QM33 SDK at `C:\qorvo` (headers, vendor sample apps, vendor platform layer) |
| **ZEPHYR** | nRF Connect SDK v3.4.0 at `C:\ncs\v3.4.0` (Zephyr headers, Kconfig, host stack) |

Every normative statement below cites the file and line that justifies it. Vendor sample code
(`C:\qorvo\Src\Apps\Src\fira\*`, `C:\qorvo\Projects\FreeRTOS\QANI\*`) is treated as **normative
documentation of intended API usage** — it is the only description Qorvo ships of the required
call sequence.

### 0.2 Conventions

- **MUST / MUST NOT** — directly supported by a cited source.
- **SHOULD** — strongly implied by a cited source but not stated as a hard rule.
- **OPEN QUESTION** — the documentation is silent, ambiguous, or self-contradictory. These are
  numbered `OQ-n` and indexed in §9. **Do not treat an OPEN QUESTION as a requirement**; treat it
  as something that must be resolved by test or by a source I did not have.
- **TRAP** — a place where a plausible, natural implementation choice is wrong. These are the
  highest-value part of this document; indexed in §10.

### 0.3 Hardware baseline

Bench target is a **Qorvo DWM3001CDK**: DWM3001C module = **nRF52833** (Cortex-M4F, 512 kB flash,
128 kB RAM, **no USB peripheral**) + **DW3110** (HW:5–12). The custom-board BOM in HW §1–§4
specifies an nRF52840 and does **not** describe the bench target (HW:5–12). The DW3110 has a
**single RF port and therefore no on-chip PDoA/AoA** (DESIGN:70–74); the phone measures angle with
its own array, and the tracker only has to be a plain ranging responder.

---

## 1. BLE roles and advertising

### 1.1 Two advertising modes on one state machine

The tracker MUST implement exactly two advertising modes, and MUST NOT run both at once
(PLAN:37–41, HW:145–158):

| Mode | When | PDU type | Contents |
|---|---|---|---|
| **Pairing** | unprovisioned, or after a long button press | **legacy**, connectable | `Flags` + complete list of 128-bit service UUIDs containing `…2f01…` |
| **Beacon** | provisioned | **BLE 5 extended**, connectable | Service Data — 128-bit UUID `…2f00…` carrying `[16B epochId][1B battery%]` |

**Pairing mode MUST stay legacy.** DESIGN:206–207 and HW:156–157 state the reason: the phone
discovers an unprovisioned tracker using *default* `ScanSettings`, which are legacy-only, so an
extended pairing advertisement would be invisible. The AD budget is `Flags (3) + 128-bit service
UUID AD (1 len + 1 type + 16 UUID = 18) = 21` bytes, inside the 31-byte legacy limit
(DESIGN:206, HW:156).

**Beacon mode MUST be extended advertising.** The service-data AD structure costs
`1 (length) + 1 (AD type) + 16 (UUID) + 17 (payload) = 35` bytes on air, against the 31-byte
legacy `AdvData` limit (DESIGN:196–204, HW:152–157, PLAN:9). Zephyr codifies the same two limits:
`BT_GAP_ADV_MAX_ADV_DATA_LEN` is 31 and `BT_GAP_ADV_MAX_EXT_ADV_DATA_LEN` is 1650
(`C:\ncs\v3.4.0\zephyr\include\zephyr\bluetooth\gap.h:140,146`).

The full 16-byte UUID MUST be on air un-shortened, because the finder reads the payload back with
`ScanRecord.getServiceData(ParcelUuid)`, which keys on the full UUID (DESIGN:200–203, PLAN:9).
A 16-bit UUID alias is therefore not an option.

### 1.2 The beacon set MUST be connectable, and that is legal

The beacon advertising set MUST be **connectable**, because a bound tracker has to remain
writable so the owner can hand over per-find UWB parameters on `…2f03…` (DESIGN:203–204,
HW:159–161, PLAN:40).

A connectable **extended** advertising set MAY carry advertising data. Zephyr states the rule
directly: *"When both `BT_LE_ADV_OPT_EXT_ADV` and `BT_LE_ADV_OPT_SCANNABLE` are enabled then
advertising data is ignored and only scan response data is used. When `BT_LE_ADV_OPT_SCANNABLE` is
not enabled then scan response data is ignored and only advertising data is used"*
(`C:\ncs\v3.4.0\zephyr\include\zephyr\bluetooth\bluetooth.h:1475–1478`). So for a
connectable-but-not-scannable extended set, `ad` is the live field and `sd` is ignored.

Consequences that MUST be respected:

1. **The beacon set MUST NOT be scannable.** With extended PDUs, scannable and connectable are
   mutually exclusive: *"When used together with `BT_LE_ADV_OPT_EXT_ADV` then this option cannot
   be used together with the `BT_LE_ADV_OPT_CONN` option"*
   (`bluetooth.h:776–781`). `BT_LE_EXT_ADV_CONN` (`bluetooth.h:1193`) is the correct parameter
   preset; `BT_LE_EXT_ADV_SCAN` (`bluetooth.h:1197`) is not.
2. **There is no scan response.** Everything an Android `ScanFilter` needs MUST be in the
   advertising data. Nothing may be deferred to a scan response.
3. **Zephyr does not insert `Flags` for you.** `adv.c` only *inspects* `BT_DATA_FLAGS` to detect
   limited discoverability (`C:\ncs\v3.4.0\zephyr\subsys\bluetooth\host\adv.c:747–755,1343`); it
   does not synthesise the element. If a `Flags` AD element is wanted it MUST be supplied by the
   application in the `ad` array. The 21-byte pairing figure in DESIGN:206 already assumes this.
4. **Secondary PHY MUST be pinned to 1M**, so the phone needs no coded-PHY scan configuration
   (DESIGN:203, PLAN:41).
5. Finder phones MUST scan with `ScanSettings.Builder().setLegacy(false)` to see the beacon
   (DESIGN:204, PLAN:72). `setLegacy(false)` is documented in PLAN:72 as returning both legacy and
   extended results, so a single scanner can serve both modes.
6. Enabling extended advertising on nRF forces the **MULTIROLE** SoftDevice Controller variant:
   `BT_CTLR_ADV_EXT` appears in the `default BT_LL_SOFTDEVICE_MULTIROLE if (…)` condition
   (`C:\ncs\v3.4.0\nrf\subsys\bluetooth\controller\Kconfig:303–320`). That is a real flash/RAM cost
   on a 512 kB / 128 kB nRF52833 that also has to hold the Qorvo UWB stack.

### 1.3 Refreshing the beacon each epoch

The epoch ID rotates every 900 s (§5), so the advertising data MUST be updated in place once per
epoch. In-place update is permitted here: *"When updating the advertising data while advertising
the advertising data and scan response data length must be smaller or equal to what can be fit in
a single advertising packet. Otherwise the advertiser must be stopped"*
(`bluetooth.h:1490–1493`). 35 bytes fits a single `AUX_ADV_IND`, so the set does not need to be
stopped. The return value of `bt_le_ext_adv_set_data()` MUST still be checked, because the real
extended-advertising data limit is *"defined by the controller"* (`bluetooth.h:1483–1485`).

### 1.4 Transition on successful provisioning

On a successful provisioning write the tracker MUST, in this order (HW:176–178):
persist the blob → **stop the unprovisioned/pairing advertisement** → start the rotating beacon.
The GATT characteristics MUST remain registered for the life of the device even though the
`…2f01…` service is no longer advertised, so the owner can still write UWB parameters to a bound
tracker (HW:159–161).

### 1.5 What is deliberately *not* specified

- **OQ-1** — Nothing in DESIGN/HW/PLAN specifies the beacon advertising interval. HW:203 assumes
  "every 1–2 s" for the power budget and HW:206 says "~1 s beacon interval", but that is a power
  estimate, not a requirement, and no interval is given for pairing mode either.
- **OQ-2** — No source specifies whether the beacon should be motion-gated. HW:194 says
  "Accelerometer INT → wake, refresh beacon, allow ranging" and HW:206 says motion-gating
  "extends" battery life, which is suggestive but not a requirement. The DWM3001CDK has no
  accelerometer wired for this purpose in any cited source.
- **OQ-3** — No source states what the tracker should advertise between "provisioned" and
  "pairing mode re-entered by long press", i.e. whether a long press while provisioned should
  advertise *both* services or switch to pairing-only. HW:193 says long-press → advertise
  unprovisioned service; it does not say what happens to the beacon.

---

## 2. Address types

### 2.1 What a Zephyr peripheral advertises by default

A Zephyr device's default identity is a **random static** address, not a public address:
`bt_id_create()` documents that when `addr` is NULL or `BT_ADDR_LE_ANY`, *"the stack will generate
a new random static address for the identity address"*
(`C:\ncs\v3.4.0\zephyr\include\zephyr\bluetooth\bluetooth.h:466–468`). A public address is only
used if `CONFIG_BT_HCI_SET_PUBLIC_ADDR` is enabled and set before `bt_enable()`
(`bluetooth.h:462–464`).

`CONFIG_BT_PRIVACY` — which would make the device *"use Resolvable Private Addresses (RPAs) by
default"* — is a plain `bool` with no `default y`
(`C:\ncs\v3.4.0\zephyr\subsys\bluetooth\host\Kconfig:537–541`), so it is **off** unless explicitly
enabled. Therefore, absent extra configuration, the tracker advertises a **stable random static
address** — not an RPA.

This matters both ways:

- It is what makes DESIGN:138–139 workable: the phone persists "the tracker's BLE address (needed
  to re-open GATT for a UWB find)". A stable address is required for that to be meaningful.
- **The tracker MUST NOT enable `CONFIG_BT_PRIVACY`.** With RPAs the stored address rotates and
  the owner can never re-open GATT by address. (Note the privacy cost: DESIGN:5.1 wanted "no
  static identifier is ever broadcast" so "the tracker can't be followed by ID"
  (DESIGN:164–166) — a stable random static BLE address defeats that at the link layer even
  though the *payload* rotates. **OQ-4**: no source reconciles the rotating-epoch-ID privacy goal
  with the stable BLE address that owner reconnection requires.)

### 2.2 What the Android client must do

**TRAP-1.** Android's `BluetoothAdapter.getRemoteDevice(String)` produces a `BluetoothDevice` that
assumes a **public** address type. Reconnecting to a random-static peripheral from a stored address
string therefore needs either the `BluetoothDevice` object retained from the `ScanResult`, or
`BluetoothAdapter.getRemoteLeDevice(address, BluetoothDevice.ADDRESS_TYPE_RANDOM)`. PLAN:72 notes
the app's `minSdk = 31`, which is above the API level where `getRemoteLeDevice` is available.

- **OQ-5** — I could not find an authoritative citation for Android's exact behaviour here. An
  internal knowledge search returned nothing on `getRemoteLeDevice` / address-type handling, and no
  Android SDK sources are present on this machine. The failure mode (a connect attempt that times
  out or reports GATT error 133 despite the device being present and connectable) is worth testing
  directly rather than assumed. **No source in DESIGN/HW/PLAN mentions address type at all** — this
  is a genuine gap in the design documents, not just in my research.
- **OQ-6** — No source states whether the phone should store the address at all versus re-scanning
  for the `…2f00…` beacon immediately before a find. Re-scanning would sidestep the whole address
  type problem. DESIGN:139 chose to store.

---

## 3. GATT services, characteristics, and write sizes

### 3.1 UUIDs

From HW:145–188 and DESIGN:196:

| UUID | Kind | Purpose |
|---|---|---|
| `6b1d2f00-4b3a-4c7e-9a10-1f2e3d4c5b6a` | service | **Beacon service** — identifies the crowd-finding advertisement and keys the Service Data AD element. Advertised only, in beacon mode. |
| `6b1d2f01-…` (`…2f01…`) | service | **Binding service** — GATT service holding the two characteristics below. Advertised only while unprovisioned; registered for the life of the device. |
| `6b1d2f02-…` (`…2f02…`) | characteristic, **write** | Provisioning blob (§4). |
| `6b1d2f03-…` (`…2f03…`) | characteristic, **write + notify** | Per-find FiRa parameters (§6.6). |

The base is the same 128-bit UUID with the 3rd–4th nibble pair varying — `…2f00…` through
`…2f03…` (HW:145,159,162,180). The beacon UUID `…2f00…` is a **service UUID used only as the key
of a Service Data AD element**; there is no requirement in any source for a GATT service with that
UUID to exist in the attribute table.

- **OQ-7** — No source specifies the `…2f03…` **notify** semantics: what is notified, when, or
  what the payload is. HW:180 says "write/notify" and DESIGN:218 says the params are delivered over
  a "session characteristic", but nothing says the tracker notifies e.g. "UWB session started" or
  "ranging failed". Given the phone must know when the responder is live before it starts its own
  session, this is a substantive gap (see §6.9 / OQ-19).
- **OQ-8** — No source specifies characteristic **permissions**. The GATT link is explicitly
  described as "unbonded and unencrypted" (DESIGN:92–94, HW:181–182), so the write permission is
  presumably plain `BT_GATT_PERM_WRITE`, but "no encryption required" is inferred from the threat
  model discussion rather than stated.
- **OQ-9** — No source specifies what the tracker should do with a provisioning write **while
  already provisioned**. DESIGN:141 says "Unbind requires the secret (anti-theft lock), so a found
  tracker can't be silently re-bound", which implies a provisioned tracker MUST reject a bare
  re-provisioning write, but no unbind protocol, opcode, or characteristic is defined anywhere.
  As specified, the anti-theft claim in DESIGN:141 has no mechanism behind it.

### 3.2 Write sizes and MTU — the 48-byte problem

The provisioning blob is **48 bytes** (40 accepted for the legacy form) (HW:163–170, PLAN:21).
On a default-configuration Zephyr peripheral, a 48-byte characteristic write **cannot succeed**:

1. `CONFIG_BT_L2CAP_TX_MTU` defaults to **23**, rising to 65 only if `BT_SMP` is enabled
   (`C:\ncs\v3.4.0\zephyr\subsys\bluetooth\host\Kconfig.l2cap:28,40`). With ATT_MTU 23 the maximum
   `ATT_WRITE_REQ` value is **MTU − 3 = 20 bytes**.
2. `CONFIG_BT_ATT_PREPARE_COUNT` defaults to **0**, and its help text says plainly: *"Number of
   buffers available for ATT prepare write, setting this to 0 **disables GATT long/reliable
   writes**"* (`C:\ncs\v3.4.0\zephyr\subsys\bluetooth\host\Kconfig.gatt:25`). All the
   prepare/execute-write handling in the host is compiled out under
   `#if CONFIG_BT_ATT_PREPARE_COUNT > 0`
   (`C:\ncs\v3.4.0\zephyr\subsys\bluetooth\host\att.c:73,153,2229,2500,2859,3153,3256`).

**TRAP-2.** So a naive Zephyr GATT server with default `prj.conf` will reject the 48-byte
provisioning write, and the failure looks like a client-side GATT error rather than anything
obviously MTU-related. Exactly one of the following MUST be true:

- **(a) Large ATT MTU.** `CONFIG_BT_L2CAP_TX_MTU` ≥ 51 on the tracker **and** the Android client
  calls `requestMtu()` and waits for `onMtuChanged` before writing. The negotiated ATT_MTU is the
  minimum of the two sides, so raising it on the tracker alone is not enough. The vendor QANI log
  shows this negotiation happening in practice — the peer requests 293 and the accessory clamps to
  100 (`C:\qorvo\Projects\FreeRTOS\QANI\README.md`, sample output) — which is evidence that a
  ~100-byte MTU is a workable target on this class of device.
- **(b) Long write.** `CONFIG_BT_ATT_PREPARE_COUNT` > 0 on the tracker, and the write handler
  tolerates the long-write path.

Option (a) is simpler and is the one to prefer, because it also removes any dependence on
Android's long-write behaviour (OQ-10).

### 3.3 If long writes are used, the handler MUST handle offsets and flags

Should route (b) be taken, the write callback contract is:

- The callback receives an `offset` and a `flags` bitmask
  (`C:\ncs\v3.4.0\zephyr\include\zephyr\bluetooth\gatt.h:174–200`).
- `BT_GATT_WRITE_FLAG_PREPARE` means *"write callback should only check if the device is
  authorized but no data shall be written"* (`gatt.h:112–118`). It is only delivered at all if the
  attribute carries `BT_GATT_PERM_PREPARE_WRITE` (`gatt.h:84`, `att.c:2262–2264`).
- On `ATT_EXECUTE_WRITE`, Zephyr reassembles the queued fragments itself and calls the write
  callback once with the reassembled buffer and the first fragment's offset
  (`att.c:2440–2470`). The reassembly capacity is `CONFIG_BT_ATT_PREPARE_COUNT * BT_ATT_BUF_SIZE`
  (`att.c:2434`), so `PREPARE_COUNT` MUST be large enough for 48 bytes at the negotiated MTU.
- `BT_GATT_WRITE_FLAG_CMD` distinguishes Write-Without-Response, which produces no error back to
  the client (`gatt.h:120–125`). A provisioning write MUST NOT be a Write Command, because the
  phone needs to know the blob was accepted.

Regardless of route, the handler MUST reject any length that is not exactly 40 or 48
(HW:163–170, PLAN:21) rather than accepting a short prefix, and MUST NOT assume `offset == 0`.

- **OQ-10** — Whether Android's GATT client automatically promotes an over-MTU
  `writeCharacteristic()` to a prepare/execute sequence is not documented in any source I could
  cite, and internal knowledge search found nothing. Do not rely on it.

---

## 4. Provisioning blob

### 4.1 Layout

48 bytes, big-endian (HW:163–170, DESIGN:131–137, PLAN:21):

```
offset  size  field
  0      8    trackerUserId      uint64, big-endian
  8     32    beaconSecret       raw bytes
 40      8    unixSeconds        uint64, big-endian, seconds since Unix epoch
```

The 40-byte form (no timestamp) MUST also be accepted (HW:170, PLAN:21).

**No UWB parameters are in this blob** (DESIGN:135–137). Channel and preamble arrive per-find on
`…2f03…`; the STS key and the tracker's UWB address are derived, never transmitted (§7).

### 4.2 Semantics of the time base

The trailing timestamp is the tracker's **only** source of wall-clock time — there is no
battery-backed RTC (DESIGN:133–136, HW:167–172, PLAN:10). It exists solely because
`epoch = unix_seconds / 900`, and the owner searches a bounded window of recent epochs
(`recentEpochIds(back = 8)` ≈ 2.25 h per PLAN:10). Without a time base the tracker would beacon
IDs outside that window and resolve to nothing.

The firmware MUST therefore:

1. Persist `unixSeconds` at provisioning time (HW:171–172).
2. Compute current time as `unix_at_provision + elapsed`, where `elapsed` comes from a
   sleep-surviving monotonic clock — PLAN:44 names `k_uptime_get()` and notes it is LFCLK-backed
   and survives sleep (HW:171–172, PLAN:44).
3. **Re-persist the current time to NVS once per epoch**, so that a reset costs one epoch of drift
   rather than the whole time base (HW:172, PLAN:44).
4. Persist `trackerUserId`, the 32-byte secret, and a provisioned flag (PLAN:62).

**TRAP-3.** `unixSeconds` is a *point-in-time* value, not a monotonic base. If the firmware
re-persists only `unix_at_provision` and not `unix_at_provision + elapsed`, or if it re-derives
`elapsed` from an uptime counter that resets while the persisted base does not advance, the tracker
silently drifts out of the owner's search window and the failure mode is "no map pin ever appears"
with no error anywhere. PLAN:116 explicitly calls out that a tracker left unpowered for a long
period falls outside the window and needs re-provisioning to resync.

- **OQ-11** — No source specifies whether the tracker should keep beaconing after a reset if the
  time base was never persisted, or with what value. Beaconing a wrong epoch ID is
  indistinguishable from a stranger's tracker to a finder, and would still consume the finder's
  upload budget.
- **OQ-12** — No source specifies the byte-order or units of the battery percentage byte beyond
  "battery%" (HW:148, DESIGN:196). One byte, presumably 0–100. HW:68 says it is fed from a SAADC
  divider; the DWM3001CDK has no such divider described in any cited source, so what the bench
  build should report is undefined.

---

## 5. Crowd finding

### 5.1 The shipped scheme (rotating hash), not the design's Option B

DESIGN §5.1–5.4 describes a per-epoch elliptic-curve scheme, and DESIGN:150–156 says explicitly
that **this is not what shipped** and is retained only as the privacy target to aim for. The
shipped scheme is:

```
epoch    = unix_seconds / 900                                        (15-minute rotation)
epochId  = HMAC-SHA256(beaconSecret, "fftrk1" || u64_be(epoch))[0..16]
```

(HW:150–151, DESIGN:153–155, PLAN:63). The advertised payload is `[16B epochId][1B battery%]` as
Service Data under `…2f00…` (HW:147–149, DESIGN:196).

The firmware MUST produce byte-identical output to the Kotlin and Rust implementations; PLAN:42
requires using PSA Crypto (via nrf_security/mbedTLS) for the HMAC for exactly that reason.

Note the ASCII label is `"fftrk1"` with **no separator and no length prefix** before the
big-endian epoch counter (HW:150). Domain separation rests entirely on that fixed 6-byte prefix.

### 5.2 How a finder resolves it

Any FindFamily phone (DESIGN:169–176, PLAN:5):

1. Scans for `…2f00…` service data, with `setLegacy(false)` (§1.2).
2. Builds a `LocationValue` at the **finder's own** GPS.
3. Seals it to the tracker's ML-KEM public bundle, which the **server** serves to finders
   (DESIGN:153–155).
4. Uploads `{ epochId-derived key, ciphertext }` via the tracker opcodes `0x06`–`0x0B`
   (DESIGN:25,31).
5. Rate-limits/dedups per `(id, coarse-time)` and requires decent GPS accuracy (DESIGN:175).
6. Never learns whose tracker it is (DESIGN:176).

The owner recomputes the expected epoch IDs for the last N epochs, fetches matching ciphertexts,
decrypts, and emits a `LocationValue` keyed on the tracker's `userid`, which flows through the
existing pipeline to a map pin with no UI change (DESIGN:186–191).

### 5.3 What the server must do

Server work lives outside this repo, in `location_share_server` (DESIGN:30–32,178–184):

- Opcodes `0x06`–`0x0B`: `TRACKER_REGISTER` / `RESOLVE` / `REPORT_PUT` / `REPORT_GET`
  (DESIGN:31).
- Store `{ id, ciphertext }` with a retention window (DESIGN:183, "e.g. 7 days") and lazy TTL
  purge (DESIGN:31).
- Serve the tracker's ML-KEM public bundle to finders so they can seal reports (DESIGN:153–155).
- Abuse / rate-limit policy (DESIGN:183).

**Accepted privacy weakening:** unlike Option B, the shipped scheme lets the **server link a
rotating id → tracker** (DESIGN:47). This is deliberate and documented, not a defect.

- **OQ-13** — The tracker firmware has no role in the server contract, and nothing in the firmware
  scope depends on it. Included here only because the task asked; the firmware-side requirement is
  simply "emit the right 17 bytes".

---

## 6. UWB / FiRa

The tracker is the FiRa **controlee + responder**; the phone is **controller + initiator**
(DESIGN:48,84–89,190; HW:190–192). This section is derived from the Qorvo SDK headers and the two
vendor reference applications: the CLI FiRa app (`C:\qorvo\Src\Apps\Src\fira\*`) and, more
relevantly, **QANI** — Qorvo's own BLE-provisioned accessory responder
(`C:\qorvo\Projects\FreeRTOS\QANI\Common\src\fira\fira_niq.c`), which is the closest existing
analogue to what this tracker is.

### 6.1 Required initialisation order — stack bring-up

`fira_uwb_mcps_init()` is the vendor's canonical bring-up and the order is not negotiable
(`C:\qorvo\Src\Apps\Src\fira\fira_dw3000.c:21–49`):

```
1. qplatform_init()                          /* MUST be first */
2. l1_config_init(&l1_config_platform_ops)
3. llhw_init()
4. uwbmac_init(&uwbmac_ctx)
```

`qplatform.h:19` states the rule for step 1: *"It should be called prior to any other init of the
UWB stack."* Teardown is the exact reverse (`fira_dw3000.c:42–47,51–56`):
`llhw_deinit() → l1_config_deinit() → qplatform_deinit()`, with `uwbmac_exit()` last
(`fira_app.c:932–936`).

What each step actually does, and why the order is forced:

- **`qplatform_init()`** (`C:\qorvo\Libs\uwb-stack\libs\qplatform\qm33_qhal_common\src\qplatform.c:241–267`):
  disables the UWB IRQ, pulses `RSTn`, opens and configures the SPI **at the slow rate**
  (*"At cold start, SPI communication rate cannot be higher than 7 Mhz"*, `qplatform.c:211–212`),
  installs the IRQ-pin callback, then calls `dwt_probe()` which selects the driver by device ID
  from the list `{ dw3000_driver, dw3720_driver }` (`qplatform.c:61–68,260–266`). `CONFIG_DW3000`
  MUST be defined or the `dw3000_driver` is not in the list at all, and `qplatform.c:50–52` is a
  hard `#error` if neither `CONFIG_DW3000` nor `CONFIG_DW3720` is set.
- **`l1_config_init()`** must come after `qplatform_init()` because loading calibration reads the
  DW3xxx OTP over SPI (see §6.2), which requires a probed device.
- **`llhw_init()`** must come after `l1_config_init()` because it consumes the L1 configuration.
- **`uwbmac_init()`** last.

**RSTn is open-drain.** `qplatform_uwb_reset()` drives the pin **low**, then reconfigures it as
**input** — it never drives it high (`qplatform.c:278–285`). HW:117 says the same
("open-drain reset (don't drive high)"). A devicetree/GPIO configuration that actively drives
`RSTn` high will damage or hang the DW3110.

### 6.2 L1 config / calibration is mandatory, not optional

**TRAP-4.** This is the single most likely cause of "phone transmits, gets RX timeouts" that is
invisible in a code review, because nothing fails: the stack initialises cleanly and the session
goes ACTIVE.

`l1_config_init()` takes a `struct l1_config_platform_ops` whose only member is
`reset_to_default` (`C:\qorvo\Libs\uwb-stack\config_manager\plugins\l1_config\include\l1_config.h:20–28`).
In volatile mode (`CONFIG_L1_CONFIG_VOLATILE`) `l1_config_load()` **always** calls
`l1_config_reset_to_default()`
(`C:\qorvo\Libs\uwb-stack\config_manager\plugins\l1_config\src\l1_config.c:723–726`), which loads
generic defaults and then calls `platform_ops.reset_to_default()` if — and only if — one was
registered (`l1_config.c:653–654`). Pass `NULL` ops, or ops with a null hook, and the radio comes
up with **generic, uncalibrated** RF settings.

The vendor's DWM3001CDK hook does all of the following
(`C:\qorvo\Src\Boards\Src\DWM3001CDK\platform_l1_config.c:57–150`):

| Step | Detail | Cite |
|---|---|---|
| Read OTP revision, sanity-check platform ID is `DW3000` or `DW3001C` | rejects unknown silicon | `:67–75` |
| **Antenna path 0 = TX on RF port 1** | `configure_antenna_path(0, TRANSCEIVER_TX, 1)` | `:77–78` |
| **Antenna path 1 = RX-A on RF port 1** | `configure_antenna_path(1, TRANSCEIVER_RXA, 1)` | `:80–81` |
| Channel-5 antenna delay from OTP `0x1A`, with a DW3001C cal-rev-1 workaround overriding it to `0x3FEE3FEE` | ToF accuracy | `:29–31,83–96` |
| Channel-9 antenna delay from OTP `0x1C` | ToF accuracy | `:98–102` |
| TX power for ch 5 / ch 9 from OTP `0x11` / `0x13`, converted to a **TX power index** via `llhw_convert_tx_power_to_index()` | link budget | `:104–119`, `helper_platform_l1_config.c:50–82` |
| **XTAL trim** from OTP `0x1E` (masked `0x7F`), applied only if non-zero | carrier frequency offset | `:121–127` |
| `pg_count` for both channels from OTP `0x18`, stored on the TX antenna path | pulse shaping | `:129–140` |
| `ant_set0.tx_power_control = 1` (adaptive TX power) | | `:142–143` |

Note the split: `tx_ant_delay = otp & 0xFFFF` goes to **ant_path 0** and
`rx_ant_delay = (otp >> 16) & 0xFFFF` goes to **ant_path 1**
(`platform_l1_config.c:43–55`). Getting that backwards produces a constant range bias.

Two of these are plausible root causes of one-way RX failure specifically:

- **Missing antenna-path configuration** means the driver has no TX/RX port assignment at all.
- **Missing XTAL trim** leaves a clock offset large enough that the peer's receiver may fail to
  acquire. The QANI sample log shows the pg_count path being exercised at runtime
  (`"Run calcbandwidthadj for pg_count 178: pg_delay 53"`,
  `C:\qorvo\Projects\FreeRTOS\QANI\README.md`), i.e. these values are genuinely used, not decorative.

The tracker firmware MUST provide a `reset_to_default` hook equivalent to
`platform_l1_config.c`, and it MUST run after `dwt_probe()` (which is why the order in §6.1
matters) because `qotp_read()` talks to the DW3110 over SPI.

- **OQ-14** — Whether the tracker should use volatile or persistent L1 config is not addressed by
  any source. Persistent mode adds a `CONFIG_SECURE_PARTITIONS_UWB_L1_CONFIG_SIZE` flash partition
  and version-mismatch handling (`l1_config.c:690–711`); volatile mode re-reads OTP every boot,
  which is simpler and always correct. Volatile appears preferable, but this is inference.

### 6.3 The application MUST supply the MAC crypto primitives

**TRAP-5.** The prebuilt uwbstack calls out to `mcps_crypto_*` symbols that the **application**
must define. The public header only declares two of them (`mcps_crypto_init` / `mcps_crypto_deinit`,
`C:\qorvo\Libs\uwbstack_libs\delivery\full\Release\include\uwbstack_bundle\mcps_crypto_platform.h`),
but the vendor's implementation shows the full surface
(`C:\qorvo\Src\UWB\mcps_crypto.c:16–123`):

```
mcps_crypto_init / _deinit / _reinit
mcps_crypto_get_random
mcps_crypto_cmac_aes_128_digest        mcps_crypto_cmac_aes_256_digest
mcps_crypto_aead_aes_ccm_star_128_create / _destroy
mcps_crypto_aead_aes_ccm_star_128_encrypt / _encrypt_inout
mcps_crypto_aead_aes_ccm_star_128_decrypt / _decrypt_inout
mcps_crypto_aes_ecb_128_create_encrypt / _create_decrypt / _encrypt_decrypt / _destroy
```

`AES-CMAC-128` is what FiRa uses to derive the STS from the vUpper64. If any of these are stubbed
out, return errors, or are wired to a different AES mode, the STS the tracker generates will not
match the phone's, and the symptom is exactly the reported one: the phone transmits, the tracker
either never decodes the poll or replies with an STS the phone rejects, and the phone reports
**RX timeouts**. The Qorvo enum even has dedicated status codes for this —
`QUWBS_FBS_STATUS_RANGING_RX_PHY_STS_FAILED` and `..._RX_TIMEOUT`
(`C:\qorvo\Src\Apps\Src\fira\fira_app.c:556,565`) — so if the tracker's own notifications are
being logged, they will distinguish "never heard anything" from "heard it, STS wrong".

The tracker firmware MUST implement all of these, and they MUST be correct AES-CMAC/CCM*/ECB, not
placeholders. `mcps_crypto_get_random` MUST be a real RNG.

- **OQ-15** — No source states whether these may be backed by PSA Crypto on Zephyr the way the
  epoch HMAC is (PLAN:42). Since PSA offers AES-CMAC and AES-CCM, that should work, but the
  interaction with the BLE stack's own PSA usage (`BT_GATT_CACHING` selects
  `PSA_WANT_ALG_CMAC`, `C:\ncs\v3.4.0\zephyr\subsys\bluetooth\host\Kconfig.gatt`) — in particular
  whether PSA calls are safe from the DW3xxx ISR context described in §6.4 — is undocumented and is
  a real concern.

### 6.4 Where the MAC event loop is pumped, and why it matters

`qplatform_init()` installs `qplatform_uwb_spi_irq_handler` as the DW3xxx IRQ-pin GPIO callback
(`qplatform.c:220–225`), and that handler is:

```c
static void qplatform_uwb_spi_irq_handler(void *arg)
{
    while (qplatform_uwb_read_irq_pin_value())
        dwt_isr();
}
```
(`qplatform.c:153–157`)

So **the entire DW3xxx interrupt service routine — and therefore all MAC frame reception,
transmission scheduling and FiRa region processing — runs from the GPIO interrupt callback**, and
it loops until the IRQ line deasserts. On Zephyr, `qgpio_pin_irq_configure()` installs this via
`gpio_add_callback_dt()` / `gpio_pin_interrupt_configure_dt()`
(`C:\qorvo\Libs\uwb-stack\libs\qhal\src\zephyr\qgpio.c:280–297`), i.e. as an ordinary Zephyr GPIO
callback, which runs in **interrupt context**.

Corroborating this model, `uwbmac.h` warns for report callbacks: *"In embedded application, the
callback might be called from MAC context, large treatments should be deferred"*
(`C:\qorvo\Libs\uwbstack_libs\delivery\full\Release\include\uwbstack_bundle\uwbmac\uwbmac.h:262–263`),
and for the data ops: *"This callback must return quickly and it must not reenter the MAC"*
(`uwbmac.h:150–162`).

**TRAP-6 (the most likely cause of RX timeouts on a BLE+UWB device).** FiRa DS-TWR is
time-slotted: the responder must transmit inside a 2 ms slot at a precise offset from the poll it
just received. If the DW3xxx GPIO ISR is delayed or preempted long enough, the response misses its
slot and the **controller** — the phone — reports an RX timeout, while the tracker looks healthy.
On nRF with a BLE link running, MPSL/the SoftDevice Controller owns the highest interrupt
priorities for RADIO/TIMER0/RTC0, so BLE activity will preempt the UWB ISR. The vendor's own
FreeRTOS projects place the UWB SPI IRQ at priority 3 and GPIOTE at priority 6
(`C:\qorvo\Projects\FreeRTOS\QANI\DWM3001CDK\ProjectDefinition\uwb_stack_llhw.cmake:9,55`) —
i.e. deliberately high, above the general-purpose peripherals.

Therefore the tracker firmware SHOULD:

- give the DW3110 IRQ GPIO a high interrupt priority, above ordinary application work, and MUST
  NOT do heavy work (logging, NVS writes, crypto over the BLE link, GATT notifications) inside the
  ISR path or in anything that can block it;
- consider suppressing BLE activity during a ranging session — or at minimum verify that BLE
  connection events do not overlap ranging rounds. The system is running two radios, and DESIGN:51
  only addresses their *RF* separation, not their *timing* contention.
- **OQ-16** — No source specifies whether BLE should be quiesced during ranging. The design
  requires the GATT link to be usable to *start* a find (DESIGN:203–204) but says nothing about
  whether it should stay connected during it.

Two further unknowns about the event loop:

- **OQ-17** — `qplatform_init()` **disables** the UWB interrupt (`qplatform.c:245–247`) and
  `qplatform_uwb_interrupt_enable()` is **never called anywhere in the visible SDK source** — it is
  declared in `qplatform.h:46` and defined in `qplatform.c:159–169`, and no caller exists in
  `Src/`, `Projects/`, or the open parts of `Libs/uwb-stack/`. It is presumably called from inside
  the closed-source `llhw`. **If it is not, the tracker will never see a single DW3110 interrupt,
  which is a complete and exact explanation of "phone transmits, gets RX timeouts".** This is the
  first thing to check on hardware: probe the IRQ line, or call
  `qplatform_uwb_interrupt_enable()` after `llhw_init()` and see whether behaviour changes.
- **OQ-18** — `uwbmac_poll_events(ctx, timeout_us)` exists but is documented as *"only available
  if you passed a NULL @event_loop_ops to uwbmac_init()"* (`uwbmac.h:323–340`), and
  `uwbmac_init()` in this delivery takes only a context pointer (`uwbmac.h:291`); no
  `event_loop_ops` symbol exists anywhere in the public headers. `uwbmac.h:286–288` says *"Some
  flavors of uwbmac have their own init method in their dedicated headers"*. So the embedded
  flavour appears to run its own internal context and the application pumps nothing — but neither
  vendor sample calls `uwbmac_poll_events()`, and no document states this positively. If the
  implementation is calling `uwbmac_poll_events()` from a thread, or *not* calling it when it
  should, that is worth checking.

Separately: **on Zephyr the `qtimer` HAL is entirely stubbed out.**
`C:\qorvo\Libs\uwb-stack\libs\qhal\src\zephyr\qtimer.c` returns `NULL` from `qtimer_init()` and
`QERR_ENOTSUP` from `qtimer_start/stop/read`. The idle timer that `qplatform_get_idle_timer_config()`
advertises (32768 Hz, 24-bit, an RTC instance per
`C:\qorvo\Libs\uwb-stack\libs\qhal\src\nrfx\qtimer.c:16,50–55`) therefore does not exist under
Zephyr. **OQ-20**: whether `llhw` tolerates a NULL idle timer, or whether low-power/deferred
scheduling silently breaks, is undocumented. Note also that the Zephyr `qplatform` variant
`#error`s unless a devicetree node labelled **`dw35720`** exists
(`C:\qorvo\Libs\uwb-stack\libs\qplatform\qm33_qhal_zephyr\src\qplatform.c:16–18`) — the Zephyr path
was developed for the QM33/DW3572, not the DW3110, which is a reason to treat Zephyr support here
as unproven.

### 6.5 Required initialisation order — per-session bring-up

Both vendor apps use the same sequence
(`C:\qorvo\Src\Apps\Src\fira\fira_app.c:386–491`; `fira_niq.c:103–174,344–369`):

```
 5. uwbmac_set_short_addr(ctx, own_short_addr)          /* MAC-layer HW address filter */
 5b.[QANI only] uwbmac_set_promiscuous_mode(ctx, true)  /* disables HW filtering */
 6. fira_helper_open(&fira_ctx, ctx, ntf_cb, "endless", 0, user_data)
 7. fira_helper_set_scheduler(&fira_ctx)                /* MUST be while MAC is stopped */
 8. fira_prepare_measurement_sequence(ctx, &session, is_report_required)
 9. fira_helper_init_session(&fira_ctx, session_id,
                             QUWBS_FBS_SESSION_TYPE_RANGING_NO_IN_BAND_DATA, &rsp)
    session_handle = rsp.session_handle
10. fira_set_session_parameters(&fira_ctx, session_handle, &session)
11. [controller only] fira_helper_add_controlee(...)     /* NOT for the tracker */
12. uwbmac_start(ctx)                                    /* MUST precede step 13 */
13. fira_helper_start_session(&fira_ctx, session_handle)
```

Hard constraints, each with a citation:

- `fira_helper_open()` *"must be called first"* among the helper calls, and
  `fira_helper_close()` must be called at the end; the helper owns the uwbmac channel, so the
  application MUST NOT call `uwbmac_channel_create` / `uwbmac_channel_release`
  (`fira_helper.h:1439–1457`).
- `fira_helper_set_scheduler()` *"must be called while the UWB MAC is stopped"*
  (`fira_helper.h:1479–1487`). It must therefore precede `uwbmac_start()`.
- `fira_prepare_measurement_sequence()` calls `uwbmac_get_device_info()`
  (`common_fira.c:150–157`), so it needs an initialised uwbmac; and it *mutates
  `session.result_report_config`* (`common_fira.c:170–196`), so it MUST run **before**
  `fira_set_session_parameters()` pushes that field, not after.
- `fira_helper_init_session()` *"must be called first to create and initialize the fira session"*
  and returns a **`session_handle` in `rsp.session_handle`** (`fira_helper.h:1500–1512`,
  `fira_app.c:439–445`).
- `uwbmac_start()` before `fira_helper_start_session()` (`fira_app.c:471–491`,
  `fira_niq.c:355–367`).

**TRAP-7. `session_id` and `session_handle` are different values.** Every
`fira_helper_set_session_*` call, and `start`/`stop`/`deinit`, take the **handle** returned by
`init_session`, not the session ID (`fira_app.c:439–453`, `fira_helper.h:1510–1546`). Passing the
session ID where a handle is expected is a natural mistake, will be accepted by the compiler, and
will make every parameter set silently target nothing.

**Teardown order (as the vendor does it, which is surprising):** `uwbmac_stop()` is called
**before** `fira_helper_stop_session()`, then `deinit_session`, `fira_helper_close`,
`fira_uwb_mcps_deinit`, and finally `uwbmac_exit` (`fira_app.c:493–528,932–936`;
`fira_niq.c:375–403`). `fira_helper_deinit_session()` returning `QERR_EBUSY` is documented as
**success**, meaning an active session was deinited (`fira_helper.h:1536–1546`) — treating it as
an error is wrong.

**OQ-21** — QANI calls `fira_uwb_mcps_init()` *before* the SoftDevice/BLE task is created, with
the comment *"To handle calibration and flash transactions before softdevice is up"*
(`fira_niq.c:420–426`). That is an ordering constraint between UWB stack init and BLE stack init
on nRF5+SoftDevice. Whether the equivalent constraint exists on Zephyr (where flash access is
mediated differently) is undocumented. Given that this tracker's BLE must be up long before any
find happens, the two cannot be ordered the vendor's way, and the consequences are unknown. **This
is a structural difference between this design and the only vendor reference for a
BLE-provisioned responder.**

### 6.6 What the phone sends per find

`…2f03…` carries exactly 8 bytes (HW:179–181, DESIGN:96–97):

```
offset  size  field
  0      2    phoneAddr    the controller's 2-byte UWB short address
  2      4    sessionId    uint32, big-endian
  6      1    channel
  7      1    preamble     preamble code index
```

Everything else is either derived (§7) or must be pre-agreed as a constant (§6.7). The rationale
for keeping this minimal is explicit: the GATT link is unbonded, so a stranger nearby could
otherwise write their own STS key and range against someone else's tracker (DESIGN:92–97,
HW:181–184).

- **OQ-22** — `phoneAddr` byte order is **not specified**. `sessionId` is explicitly big-endian
  (HW:180) but the 2-byte address is not. The Qorvo API takes a `uint16_t short_addr`
  (`fira_helper.h:243–245`), and the vendor NI glue builds it from a byte array as
  `AR2U16(x) = ((x[1] << 8) | x[0])` — i.e. **little-endian** (`fira_niq.c:43,186,218,220`),
  matching the on-air 802.15.4 convention. Meanwhile HW:180 puts the rest of the blob in
  big-endian. If the phone writes the address big-endian and the tracker reads it little-endian (or
  vice versa) the addresses will not match, MAC filtering will drop everything, and the phone will
  report RX timeouts. **This is a concrete, high-probability bug candidate.**
- **OQ-23** — Nothing specifies whether the tracker should validate `channel` ∈ {5, 9} and
  `preamble` ∈ {9..12} before use. The stack's own limits are given in §6.7; an out-of-range value
  would be rejected by the region with `..._ERROR_INVALID_PREAMBLE_CODE_INDEX` /
  `..._ERROR_INVALID_CHANNEL_...` reason codes (`fira_app.c:651,697`), but only if the tracker
  surfaces the session-status notification.

### 6.7 Complete FiRa session parameter set for the controlee/responder

Values are the ones a controlee must hold to interoperate with a controller using
`CONFIG_UNICAST_DS_TWR`. The "Source" column says where the value comes from; the
"Must match?" column says whether a mismatch between phone and tracker is fatal.

| Parameter (`struct session_parameters`) | Value | Numeric | Source | Must match? |
|---|---|---|---|---|
| `device_type` | `QUWBS_FBS_DEVICE_TYPE_CONTROLEE` | **0** | `fira_app.c:68`; `quwbs/fbs/defs.h:496–498` (controller = 1) | role, must be opposite |
| `device_role` | `QUWBS_FBS_DEVICE_ROLE_RESPONDER` | **0** | `fira_app.c:69`; `defs.h:510–516` | role, must be opposite |
| `ranging_round_usage` | `DSTWR_DEFERRED` | **2** | `fira_default_params.h`; `fira_region_params.h:147–152` | **exactly** |
| `sts_config` | `FBS_STS_MODE_STATIC` | **0** | `fbs_region_params.h:397–403`; not pushed by `fira_set_session_parameters` → relies on the default | **exactly** |
| `multi_node_mode` | `FIRA_MULTI_NODE_MODE_UNICAST` | **0** | `fira_default_params.h`; `fira_region_params.h:176–185` | **exactly** |
| `schedule_mode` | `FIRA_SCHEDULE_MODE_TIME_SCHEDULED` | **1** | `fira_app.c:83`; `fira_region_params.h:240–247` | **exactly** |
| `rframe_config` | `FIRA_RFRAME_CONFIG_SP3` | **3** | `fira_default_params.h`; `fira_region_params.h:261–265` | **exactly** |
| `sfd_id` | `FIRA_SFD_ID_2` (4z SFD) | **2** | `fira_default_params.h`; `fira_region_params.h:305–309`; BPRF allows only 0 or 2 (`fira_helper.h:348–357`) | **exactly** |
| `prf_mode` | `FIRA_PRF_MODE_BPRF` | **0** | `fira_helper.h:467–478`; only BPRF is supported (`fira_app.c:151–153`) | **exactly** |
| `phr_data_rate` | 850 kbit/s | **0** | `fira_helper.h:371–381`; `fira_app.c:87–88` | **exactly** |
| `psdu_data_rate` | 6.81 Mbps (default) | **0** | `fira_helper.h:358–370`; **not pushed** by `fira_set_session_parameters` | **exactly** |
| `preamble_duration` | 64 symbols (default) | **1** | `fira_helper.h:336–346`; `fira_niq.c:210` sets it with the comment *"FIX The decryption issue"*; **not pushed** | **exactly** |
| `sts_length` | 64 symbols (default) | **1** | `fira_helper.h:521–531`; `fira_niq.c:243`; **not pushed** | **exactly** |
| `number_of_sts_segments` | 1 | **1** | `fira_helper.h:491–505`, documented **[NOT IMPLEMENTED]**; `fira_niq.c:242`; **not pushed** | **exactly** |
| `channel_number` | 9 (5 also legal) | 9 | `FIRA_DEFAULT_CHANNEL_NUMBER` = 9 (`fira_region_params.h:63–64`); only 5 and 9 accepted (`uwb_translate.c:17–27`) | **from `…2f03…`** |
| `preamble_code_index` | 10 | 10 | `FIRA_DEFAULT_PREAMBLE_CODE_INDEX` = 10 (`fira_region_params.h:65`); BPRF range documented as **9–12** in this stack (`fira_region_params.h:103–104`, `uwb_translate.c:178–190`) — note `fira_helper.h:319–328` claims 9–24 for BPRF, see OQ-24 | **from `…2f03…`** |
| `slot_duration_rstu` | 2400 (= 2 ms; 1200 RSTU = 1 ms) | 2400 | **UCI capture** (see §6.7.1); agrees with `fira_default_params.h`; `fira_helper.h:258–261`; CLI rejects < 2400 (`fira_app.c:187–199`) though the capability minimum is 1200 (`fira_region_params.h:85`) | **exactly** |
| `block_duration_ms` | **120** (`RANGING_DURATION`) | 120 | **UCI capture** (see §6.7.1). *Not* the sample-app default of 200. `fira_helper.h:266–269` | **exactly** |
| `round_duration_slots` | **6** (`SLOTS_PER_RR`) | 6 | **UCI capture** (see §6.7.1). *Not* the sample-app default of 25. `fira_helper.h:262–265`; range 1–255 (`fira_app.c:215–228`) | **exactly** |
| `block_stride_length` | 0 | 0 | pushed by `common_fira.c:234`, never set → 0 | **exactly** |
| `round_hopping` | **true** (`HOPPING_MODE = 1`) | 1 | **UCI capture** (see §6.7.1). *Not* the sample-app default of `false`. `fira_helper.h:274–277`. See **OQ-30** — no Qorvo sample exercises hopping. | **exactly** |
| `ranging_round_control` | `0x03` | 0x03 | `FIRA_DEFAULT_RANGING_ROUND_CONTROL` = 0x3; `fira_helper_bool_to_ranging_round_control(true,false)` (`fira_helper.h:2003–2010`) | **exactly**, see OQ-25 |
| `result_report_config` | bit0 (ToF) set; azimuth/elevation/FoM **cleared** for a non-AoA chip | 0x01 | `FIRA_DEFAULT_RESULT_REPORT_CONFIG` = 0x1; `fira_helper.h:2439–2448`; `common_fira.c:189–196` clears the AoA bits when AoA is unsupported | **exactly** |
| `report_rssi` | optional | — | pushed by `common_fira.c:240` | local only |
| `enable_diagnostics` | optional (diagnostics are extremely useful for this bug) | — | `fira_helper.h:511–520`; `common_fira.c:239,254` | local only |
| `short_addr` | tracker's own derived 2-byte UWB address (§7) | — | `fira_helper.h:243–245`; `common_fira.c:242–244` | **must equal what the phone uses as peer address** |
| `destination_short_address[0]` | the **phone's** address, from `…2f03…` | — | `fira_app.c:65–66`; `common_fira.c:246–248` | **must equal the phone's own address** |
| `n_destination_short_address` | **1** | 1 | `fira_app.c:65` — controlee sets 1, controller sets 0 | yes |
| `n_controlees` | **0** | 0 | `fira_app.c:70` — controlee adds no controlees | yes |
| `vupper64[8]` | derived STS key (§7), **byte order per §6.8** | — | `fira_helper.h:382–397`; `common_fira.c:236` | **exactly** |
| `time0_ns` | 0 | 0 | `fira_niq.c:181–183` sets 0 with a comment that NI's ms-based init time does not map onto the stack's absolute UWB-domain time | see OQ-19 |
| `meas_seq` | 1 step, `FIRA_MEASUREMENT_TYPE_RANGE`, `n_measurements = 1`, all antenna sets `0xff` | — | `common_fira.c:180–188` (the `AOA_NOT_SUPPORTED` branch, which is what a DW3110 takes) | local, but required |
| `key_rotation`, `key_rotation_rate`, `sub_session_id` | unused with static STS | 0 | `fira_helper.h:398–418` — these apply to Dynamic/Provisioned STS only | n/a |

#### 6.7.1 Provenance of the timing values, and a correction

**Corrected 2026-08-22 after `compare` supplied a UCI capture.** The first revision of this table
listed `block_duration_ms` = 200, `round_duration_slots` = 25 and `round_hopping` = false, sourced
from `C:\qorvo\Src\Apps\Src\fira\Inc\fira_default_params.h`. **Those numbers were wrong as
requirements.** They are the Qorvo *sample application's* defaults, and this document had no
business presenting them as the values the tracker must hold.

The authoritative values come from a capture of the **`SESSION_SET_APP_CONFIG` the Android
controller actually sends**:

| UCI parameter | Value | → `session_parameters` field |
|---|---|---|
| `SLOT_DURATION` | 2400 RSTU | `slot_duration_rstu` = 2400 |
| `RANGING_DURATION` | 120 ms | `block_duration_ms` = 120 |
| `SLOTS_PER_RR` | 6 | `round_duration_slots` = 6 |
| `HOPPING_MODE` | 1 | `round_hopping` = **true** |

A capture of what the peer programs is strictly better evidence than any documentary inference, and
it directly closes item 2 of §8 for these four fields. Sanity check: 6 slots × 2400 RSTU = 12 ms of
ranging round inside a 120 ms block, which is consistent.

**The methodological error is worth recording, because it is the same class of mistake the rest of
this document warns about.** The table conflated two separate claims in one row: *"this field must
match exactly on both sides"* (true, and the important point) and *"its value is N"* (which for
these four fields was only ever a sample-app default). The "Source" column named
`fira_default_params.h` honestly, but the "Must match?" column said **exactly** next to a number
that nothing had established. Where the Source column for a value in this table names only a Qorvo
`FIRA_DEFAULT_*` macro or a vendor sample app, **read it as "the Qorvo side will do this unless told
otherwise", not as "Android does this"** — the two coincide for the spec-derived PHY fields
(channel 9 / preamble 10 are labelled UCI-spec defaults at `fira_region_params.h:63–65`) but plainly
did not coincide for the timing fields.

- **OQ-30** — **`round_hopping = true` is a path no Qorvo sample application exercises.**
  `FIRA_DEFAULT_ROUND_HOPPING` is `false`, and the CLI only enables it via an explicit `-HOP`
  argument (`fira_app.c:281–285`). More pointedly, QANI's `round_duration_slots` computation carries
  the comment *"+1 slot to satisfy corner case when the # of RR is exact the same as the # of slots
  in TWR. **This is fine because hopping is disabled in the NI protocol**"*
  (`fira_niq.c:206`) — i.e. the vendor explicitly relies on hopping being off in the one reference
  that resembles this product. With Android enabling hopping, that assumption does not hold, and
  whether this stack's controlee-side hop-sequence derivation is correct against Android is
  unvalidated by any vendor code. If ranging fails *intermittently* or only after the first few
  blocks rather than never, this is where to look.

**TRAP-8. `session_parameters` MUST be zero-initialised.** `fira_set_session_parameters()`
(`common_fira.c:208–259`) pushes 25 fields including `prf_mode`, `phr_data_rate`, `sfd_id`,
`schedule_mode` and `block_stride_length`, and `fira_set_default_params()` (`fira_app.c:49–99`)
does **not** assign several of them — it relies on the struct living in a zero-initialised static
(`.rconfig`) section (`driver_app_config.c`, `fira_niq.c:101`). A stack-allocated
`struct session_parameters` without a `memset` will push garbage for `prf_mode`,
`block_stride_length`, `priority`, `mac_address_mode`, `link_layer_mode` and more, and the failures
will be timing/PHY-level rather than a clean error return.

**Fields that `fira_set_session_parameters()` does NOT push**, and which therefore keep the FiRa
region's internal defaults regardless of what you put in the struct (`common_fira.c:220–256`):
`sts_config`, `preamble_duration`, `psdu_data_rate`, `sts_length`, `number_of_sts_segments`,
`mac_address_mode`, `priority`, `link_layer_mode`, `mac_fcs_type`, `time0_ns`,
`max_number_of_measurements`, `max_rr_retry`, `termination_count`, `key_rotation*`,
`sub_session_id`, `session_info_ntf_config` and the proximity/AoA-bound fields.

This creates a genuine oddity: **QANI sets `preamble_duration`, `sts_length` and
`number_of_sts_segments` in the struct and then calls the same `fira_set_session_parameters()`
that never transmits them** (`fira_niq.c:210,242–243` vs `common_fira.c:220–256`). The
*"FIX The decryption issue"* comment on `preamble_duration` therefore documents a fix that, on the
face of the code, cannot be taking effect through this path. **OQ-26**: either there is another
setter path, or the region default already equals 64 symbols and the assignment is vestigial. If
the phone ever selects a 32-symbol preamble, this matters.

**TRAP-9. `sts_config` for static STS is `0`, and one of the vendor's own doc comments says
`0x01`.** `struct session_parameters.sts_config` documents *"0x00: Static STS (default)"*
(`fira_helper.h:216–229`) and `enum fbs_sts_mode` has `FBS_STS_MODE_STATIC = 0`
(`fbs_region_params.h:397–403`). But `fira_helper_set_session_sts_config()` documents
*"0x01: Static STS (default). 0x02: Dynamic STS. 0x08: Provisioned STS"*
(`fira_helper.h:2036–2050`) — those are **capability bitmask** values mislabelled as parameter
values. Calling `fira_helper_set_session_sts_config(ctx, h, 0x01)` in the belief that static STS is
`0x01` selects **Dynamic STS**, guaranteeing an STS mismatch and RX failures. The safe course is
what both vendor apps do: **never call this setter at all** and leave `sts_config` at its
zero-initialised default (`common_fira.c:220–256` omits it).

**Address filtering.** `uwbmac_set_short_addr()` sets the **MAC-layer hardware address filter**
and is a *separate* call from `fira_helper_set_session_short_address()`; both vendor apps make
both calls (`fira_app.c:413–414` + `common_fira.c:242–244`; `fira_niq.c:128` + same).
`uwbmac.h:558–567` notes *"HW Filtering is disabled if promiscuous mode is enabled"*.
**TRAP-10:** if the firmware sets only the *session* short address and neither
`uwbmac_set_short_addr()` nor promiscuous mode, the DW3110's hardware filter will discard the
phone's poll frames before the MAC ever sees them — the tracker never responds and the phone
reports RX timeouts, with no error on the tracker side. QANI additionally enables promiscuous mode
(`uwbmac_set_promiscuous_mode(ctx, true)`, `fira_niq.c:125–126`) — note the code comment there says
*"Unset promiscuous to accept only filtered frames"* while passing `true`, i.e. the comment is
wrong and filtering is in fact **disabled**. Enabling promiscuous mode is the more forgiving option
for an accessory and is what the only BLE-provisioned vendor reference does.

**Responder slot index.** There is **no responder-slot-index parameter in this API.** Grepping
`fira_helper.h` finds `slot_index` only as a diagnostic *output* — *"In case of error, slot index
where the error was detected"* (`fira_helper.h:838–843`). For `MULTI_NODE_MODE_UNICAST` there is
exactly one responder, so its slot is implicit in the DS-TWR round structure and no configuration
is needed. **OQ-27**: for one-to-many the ordering would come from the controller's controlee list
(`fira_app.c:457–466`), but that is not this configuration.

- **OQ-24** — The BPRF preamble-code range is stated inconsistently *within the same SDK*:
  `fira_helper.h:319–328` says "9-24: BPRF", while `fira_region_params.h:103–104` says
  `FIRA_PCODE_BPRF_MIN 9` / `FIRA_PCODE_BPRF_MAX 12` with the explicit comment *"Doesn't match
  with MCPS802154_LLHW_BPRF_MAX"*, and `uwb_translate.c:178–190` accepts only 9–12. **Treat 9–12 as
  the real limit.** If the phone selects a preamble index above 12, this stack will reject it.
- **OQ-25** — `ranging_round_control` bit semantics are documented inconsistently.
  `fira_helper.h:287–293` says b1 = *"Control Message is sent in band(1) or not (0, not
  supported)"* and b2 = *"Control Message is sent separately(0) or piggybacked to RIM(1)"*, but
  `fira_helper_bool_to_ranging_round_control(result_report_phase, skip_ranging_control_phase)`
  unconditionally sets b1 and maps its second argument to b2 as *"skip ranging control phase"*
  (`fira_helper.h:1996–2010`). `FIRA_DEFAULT_RANGING_ROUND_CONTROL` is `0x3` with the comment
  *"Result report phase, skip ranging control phase"* — which contradicts `0x3` (b2 clear). The
  value `0x03` is what both vendor apps end up with; whether it means what the comment says is
  unresolved, and it must match the phone.
- **OQ-19** — Nothing states whether the responder must be started before the initiator, or what
  `time0_ns` / UWB initiation time the tracker should use. `fira_niq.c:181–183` sets `time0_ns = 0`
  and notes the NI protocol's ms-based init time does not map onto the stack's absolute
  UWB-domain time. In QANI the accessory starts its session on the *"configure and start"* BLE
  message and the iPhone starts after (`Projects/FreeRTOS/QANI/README.md`), so responder-first is
  the vendor's ordering — but the phone side of this design has no equivalent handshake defined
  (see OQ-7), so the tracker may well be started *after* the phone has already begun polling,
  which would produce exactly the reported symptom for the first several blocks. **This is a
  first-order suspect and it is a design gap, not just an implementation gap.**

### 6.8 vUpper64 composition and byte order

Static STS is keyed by the 8-byte **vUpper64**, which the struct exposes as a union
(`fira_helper.h:382–397`):

```c
union {
    struct {
        uint8_t static_sts_iv[FIRA_STATIC_STS_IV_SIZE]; /* 6 */
        uint8_t vendor_id[FIRA_VENDOR_ID_SIZE];         /* 2 */
    };
    uint8_t vupper64[FIRA_VUPPER64_SIZE];               /* 8 */
};
```

So **in this struct's layout, `vupper64[0..5]` is the STATIC_STS_IV and `vupper64[6..7]` is the
VENDOR_ID** — confirmed by the vendor's own printer, which formats `v[0]..v[5]` as `STATIC_STS_IV`
and `v[6],v[7]` as `VENDOR_ID` (`common_fira.c:122–124`).

**TRAP-11 — the byte-order trap.** Qorvo's NI accessory glue fills the array **fully reversed**
relative to the `VendorId || StaticStsIv` concatenation used out of band
(`fira_niq.c:228–235`):

```c
vupper64[7] = Vendor_ID[0];      vupper64[6] = Vendor_ID[1];
vupper64[5] = Static_STS_IV[0];  vupper64[4] = Static_STS_IV[1];
vupper64[3] = Static_STS_IV[2];  vupper64[2] = Static_STS_IV[3];
vupper64[1] = Static_STS_IV[4];  vupper64[0] = Static_STS_IV[5];
```

Meanwhile PLAN:28 specifies the tracker's key as an 8-byte HKDF output, chosen because *"8 bytes
is exactly what `setSessionKeyInfo` takes … and under FiRa that is `[2B VendorID][6B
STATIC_STS_IV]`"*. If that 8-byte blob is `memcpy`'d straight into `vupper64`, the resulting STS
will **not** match the phone's, because the phone's stack interprets the same 8 bytes in the
opposite order. The observable symptom is precisely one-way RX failure: frames are transmitted with
a scrambled timestamp sequence the peer cannot correlate, so the peer times out, and the tracker's
own diagnostics (if enabled) would show `RX_PHY_STS_FAILED` or `RX_TIMEOUT`
(`fira_app.c:556,565`).

The correct handling is: treat the 8-byte derived value as `[2B VendorID][6B STATIC_STS_IV]`, then
lay it into the Qorvo struct so that `static_sts_iv` and `vendor_id` land in their documented
positions with the byte order the vendor's NI glue uses. **OQ-28**: I cannot cite an authoritative
statement of which of the two orderings is the one Android uses, because I found no citable
documentation for `setSessionKeyInfo`'s byte layout (see §8). Whichever it is, **this is a single
byte-order decision that fully determines whether ranging works, and it must be settled by
experiment (try both) rather than by reasoning.** It is my leading candidate for the reported bug
alongside OQ-17 and OQ-19.

Note also that `fira_helper_set_session_key()` takes a **128- or 256-bit** key
(`fira_helper.h:3595–3605`) and is for Dynamic/Provisioned STS. An 8-byte value is *not* a session
key here — it is the vUpper64. Passing the derived 8 bytes to `fira_helper_set_session_key()` would
be wrong on two counts (wrong API, illegal length).

### 6.9 DWM3001CDK hardware wiring for the DW3110

From the vendor's own DWM3001CDK project definition
(`C:\qorvo\Projects\FreeRTOS\QANI\DWM3001CDK\ProjectDefinition\uwb_stack_llhw.cmake:5–24`):

| Signal | nRF52833 pin |
|---|---|
| SPI instance | SPIM**3** |
| SCK | P0.03 |
| MOSI | P0.08 |
| MISO | P0.29 (with MISO pull configured — `NRFX_SPIM_MISO_PULL_CFG=1`, `:34`) |
| CS | P1.06 |
| WAKEUP | P1.19 |
| IRQ | P1.02 |
| RSTn | P0.25 |

Interrupt priorities the vendor chose: UWB SPI IRQ **3**, GPIOTE **6**, SPIM default **7**
(`:9,48,55`). SPI must start slow (`CONFIG_SPI_UWB_SLOW_RATE_FREQ`, default 4 MHz —
`qplatform_internal.h:14–15`) and only move to `CONFIG_SPI_UWB_FAST_RATE_FREQ` after cold start
(`qplatform.c:176–190,211–212`).

Note this pinout differs entirely from HW §4, which is the custom nRF52840 board, not the dev kit
(HW:5–12). **Do not use HW §4's pin map for the DWM3001CDK.**

Under Zephyr, the DW3xxx must appear in devicetree as a node labelled **`dw35720`** on an SPI bus,
with `rstn-gpios` and `irq-gpios` properties, or `qplatform.c` fails to compile
(`C:\qorvo\Libs\uwb-stack\libs\qplatform\qm33_qhal_zephyr\src\qplatform.c:16–28`). The Zephyr
variant defines **no WAKEUP pin** (compare the non-Zephyr variant's `CONFIG_DWT_WU_GPIO_*`,
`uwb_stack_llhw.cmake:18–19`). **OQ-29**: how the DW3110 is woken from deep sleep under the Zephyr
platform layer is undocumented; `dwt_probe_interf.wakeup_device_with_io = qplatform_uwb_wakeup` →
`qpwr_uwb_wakeup()` (`qplatform.c:236–239,258`) and the qpwr implementation for QM33 is at
`Libs\uwb-stack\libs\qhal\src\qm33\qpwr.c`, which I did not trace.

---

## 7. Key derivation

Two values are **derived on both sides** from the 32-byte bind-time `beaconSecret` and are never
transmitted over the air, because the GATT link is unbonded and anything on it could come from a
stranger nearby (DESIGN:92–97, HW:181–187, PLAN:26–31):

```
stsKey  = HKDF-SHA256(beaconSecret,
                      info = "com.vayunmathur.findfamily/uwb-sts" || u32_be(sessionId))[0..8]
uwbAddr = HKDF-SHA256(beaconSecret,
                      info = "com.vayunmathur.findfamily/uwb-addr")[0..2]
```

With, per HW:186–187:

- **salt = 32 zero bytes**;
- HKDF = RFC 5869 extract-then-expand over HMAC-SHA256 (PLAN:31);
- reserved addresses `0x0000` and `0xFFFF` have their **low byte set to `0x01`**.

Mixing `sessionId` into the STS derivation gives every find a distinct IV (PLAN:28). The
`sessionId` is 4 bytes big-endian, matching its wire format on `…2f03…` (HW:180).

The firmware MUST produce byte-identical output to the Kotlin implementation; PLAN:42 requires
PSA Crypto for this, and PLAN:82 requires it be *"byte-identical to `TrackerUwbKeys.kt`"*, with
fixed test vectors in `TrackerUwbKeysTest.kt` (HW:187–188, PLAN:124).

### 7.1 What both sides must agree on

| Item | Agreement mechanism |
|---|---|
| `beaconSecret` | Written once at bind time over GATT (§4); the root of everything. |
| `sessionId` | Sent per find on `…2f03…` (§6.6); feeds the STS derivation. |
| STS key / vUpper64 | Derived independently from `(secret, sessionId)`. **Byte order into the Qorvo struct is unresolved — OQ-28.** |
| Tracker UWB short address | Derived independently from `secret`. The phone uses it as `peerAddress`; the tracker uses it as both its session `short_addr` **and** its `uwbmac_set_short_addr()` MAC filter address. |
| Phone UWB short address | Sent per find on `…2f03…`. **Byte order unspecified — OQ-22.** |
| Channel, preamble | Sent per find on `…2f03…`. |
| Everything in the §6.7 table marked "exactly" | **Hard-coded constants on both sides.** Nothing negotiates them. |

### 7.2 Negotiated vs. must-match-exactly

This is the crux of FiRa OOB interop and worth stating baldly:

**Nothing in a static-STS unicast DS-TWR session is negotiated over the UWB link.** There is no
capability exchange, no parameter negotiation, and (in this configuration) no in-band control
message that would let the controller reconfigure the controlee's PHY or timing. The only things
that flow at run time are the ranging frames themselves. Consequently:

- **Carried out of band per find (3 values):** phone short address, session ID, channel, preamble
  — four fields in eight bytes (§6.6).
- **Derived out of band (2 values):** STS key/vUpper64, tracker short address (§7).
- **Everything else must be identical by construction** — `ranging_round_usage`, `sts_config`,
  `multi_node_mode`, `schedule_mode`, `rframe_config`, `sfd_id`, `prf_mode`, `phr_data_rate`,
  `psdu_data_rate`, `preamble_duration`, `sts_length`, `number_of_sts_segments`,
  `slot_duration_rstu`, `block_duration_ms`, `round_duration_slots`, `round_hopping`,
  `block_stride_length`, `ranging_round_control`, `result_report_config`.

A mismatch in **any** of the 19 fields in that last group produces the same symptom — the peer
transmits and times out — with no diagnostic distinguishing which one. That is why the §6.7 table
enumerates them all with citations rather than only the interesting ones, and why enabling the
tracker's session-status and diagnostics notifications (`fira_helper.h:511–520`,
`common_fira.c:239,254`, `fira_app.c:583–728` for the reason-code strings) is worth doing before
guessing: the region emits specific reason codes such as
`..._ERROR_INVALID_STS_CONFIG`, `..._ERROR_INVALID_RFRAME_CONFIG`,
`..._ERROR_SLOT_LENGTH_NOT_SUPPORTED`, `..._ERROR_INSUFFICIENT_SLOTS_PER_RR`,
`..._ERROR_INVALID_RANGING_DURATION` and `..._ERROR_INVALID_PREAMBLE_CODE_INDEX`
(`fira_app.c:627–660`).

---

## 8. Android-side requirements I could NOT source

The task asked for the Android `CONFIG_UNICAST_DS_TWR` parameter set. **I was unable to obtain
authoritative documentation for it**, and I am deliberately not reconstructing it from memory.

> **Partially superseded 2026-08-22.** `compare` supplied a capture of Android's actual
> `SESSION_SET_APP_CONFIG`, which resolves the four timing fields (`SLOT_DURATION` 2400,
> `RANGING_DURATION` 120, `SLOTS_PER_RR` 6, `HOPPING_MODE` 1) — see §6.7.1, where the table has been
> corrected. `compare` also reports having cross-checked all 19 must-match fields against both sides
> and found them in agreement, which **rules out a parameter mismatch as the cause of the RX
> timeouts**; §12 has been reordered accordingly. The items below that the capture does not cover
> remain open, and a capture of one phone/one Android version is not the same as documentation of
> what the API guarantees.

Specifically, I could not cite:

1. What FiRa session parameters `androidx.core.uwb.RangingParameters.CONFIG_UNICAST_DS_TWR` /
   `android.ranging`'s `UwbRangingParams.CONFIG_UNICAST_DS_TWR` (FiRa "Config ID 1") actually
   implies for ranging round usage, STS config, multi-node mode, rframe config, schedule mode,
   round hopping, or ranging round control. *(The capture shows what one device sent; it does not
   tell us which of these the config ID pins versus which are AOSP defaults that could change.)*
2. AOSP's `FiraOpenSessionParams` defaults for `SLOT_DURATION_RSTU`, `RANGING_INTERVAL_MS`,
   `SLOTS_PER_RANGING_ROUND`, `SFD_ID`, PHR/PSDU data rate, preamble duration, STS length, number
   of STS segments, or MAC address mode. *(First three now observed — §6.7.1. The rest are still
   uncited; note `compare` observes that `phr_data_rate` is assigned the wrong enum
   (`FIRA_PRF_MODE_BPRF`) but that this is cosmetic, since it equals 0, which equals
   `FIRA_PHR_DATA_RATE_850K`, which is what Android defaults to.)*
3. The byte layout and byte order of `setSessionKeyInfo(byte[8])` for STATIC STS, and how it maps
   onto vUpper64. (This is **OQ-28**, the highest-value unknown in this document. `compare`
   independently confirms TRAP-11 and ranks it as a top blocker.)
4. Byte order of `androidx.core.uwb.UwbAddress`'s 2-byte short address (**OQ-22**).
5. Whether the Android controller sends any in-band control message conveying slot assignment, or
   whether Android exposes a responder slot index (**OQ-27**).
6. Whether Android requires the responder to be started first, and any `UWB_INITIATION_TIME`
   requirement (**OQ-19**).
7. Whether Android's GATT client auto-promotes over-MTU writes to long writes (**OQ-10**).
8. Android `ScanFilter` matching semantics for 128-bit service data, and confirmation that
   `setLegacy(false)` is required for extended advertisements. (DESIGN:204 and PLAN:72 assert the
   latter; I have no independent citation.)
9. Android address-type handling on reconnect (**OQ-5**).

**What I did:** searched Meta's internal knowledge base (wikis, Google Docs, Workplace posts,
diffs, tasks) four times with different framings; the only UWB-adjacent internal document is a
hardware feasibility proposal with no API content. No Android SDK is installed on this machine
(`%LOCALAPPDATA%\Android\Sdk` does not exist) and no `androidx.core.uwb` or `android.ranging`
artifact is present in the Gradle cache.

**Where the authoritative answers live:** the FiRa Consortium UCI Generic Technical Specification
(which `fira_region_params.h:98–102` cites by name — *"v2.0.0_0.9r2, Section 8.2 Device
Configuration Parameters"* — and which `fira_region_params.h:36–38` cites as *"UCI spec
v2.0.0_0.9r11::Table 46"*), AOSP `packages/modules/Uwb`, and the `androidx.core.uwb` reference
docs. The §6.7 table is the **Qorvo-side** requirement set; it needs to be cross-checked field by
field against whatever Android actually programs, and any disagreement is a bug.

A useful shortcut: the defaults in the §6.7 table (channel 9, preamble 10, slot 2400 RSTU, block
200 ms, 25 slots/round, SP3, SFD 2, DS-TWR deferred, unicast, static STS, no hopping) are the
Qorvo/FiRa *spec* defaults, not arbitrary Qorvo choices —
`fira_region_params.h:63–65` labels channel 9 / preamble 10 as *"Default channel and preamble code
in UCI spec v2.0.0"*. They are therefore a reasonable prior for what Android will pick, but a prior
is not a citation.

---

## 9. Index of OPEN QUESTIONS

| # | Question | § |
|---|---|---|
| OQ-1 | Beacon and pairing advertising intervals are unspecified. | 1.5 |
| OQ-2 | Whether the beacon should be motion-gated. | 1.5 |
| OQ-3 | What a provisioned tracker advertises after a long-press back into pairing mode. | 1.5 |
| OQ-4 | Rotating epoch IDs vs. a stable random-static BLE address — the link-layer identifier defeats the payload-level privacy goal. | 2.1 |
| OQ-5 | Android address-type handling when reconnecting by stored address; no source mentions address type at all. | 2.2 |
| OQ-6 | Store the BLE address vs. re-scan for the beacon before a find. | 2.2 |
| OQ-7 | `…2f03…` notify semantics: what is notified, when, with what payload. | 3.1 |
| OQ-8 | GATT characteristic permissions (encryption/authentication) are never stated. | 3.1 |
| OQ-9 | Behaviour on re-provisioning an already-bound tracker; no unbind protocol exists despite the anti-theft claim. | 3.1 |
| OQ-10 | Whether Android auto-promotes over-MTU writes to long writes. | 3.2 |
| OQ-11 | Whether to beacon at all when the time base was lost. | 4.2 |
| OQ-12 | Battery-percent byte units, and what the DWM3001CDK should report with no divider. | 4.2 |
| OQ-13 | (informational) Firmware has no stake in the server contract. | 5.3 |
| OQ-14 | Volatile vs. persistent L1 config. | 6.2 |
| OQ-15 | Whether `mcps_crypto_*` may be PSA-backed, and whether PSA is safe from the DW3xxx ISR context. | 6.3 |
| OQ-16 | Whether BLE should be quiesced during a ranging session. | 6.4 |
| **OQ-17** | **`qplatform_uwb_interrupt_enable()` has no visible caller. If nothing enables the DW3110 IRQ, the tracker sees no interrupts at all.** | 6.4 |
| OQ-18 | Whether the application must pump `uwbmac_poll_events()` in this delivery. | 6.4 |
| **OQ-19** | **Responder-vs-initiator start order and UWB initiation time; the design has no start handshake, so the tracker may start after the phone is already polling.** | 6.7 |
| OQ-20 | Zephyr `qtimer` is stubbed; effect of a missing idle timer on `llhw` is unknown. | 6.4 |
| OQ-21 | Vendor requires UWB stack init before the BLE stack; this design cannot honour that. | 6.5 |
| **OQ-22** | **`phoneAddr` byte order on `…2f03…` is unspecified; the rest of the blob is big-endian but the Qorvo/802.15.4 convention is little-endian.** | 6.6 |
| OQ-23 | Whether the tracker validates channel/preamble from `…2f03…`. | 6.6 |
| OQ-24 | BPRF preamble range: 9–24 vs 9–12, contradictory within the SDK. Treat 9–12 as real. | 6.7 |
| OQ-25 | `ranging_round_control` bit semantics are self-contradictory in the SDK. | 6.7 |
| OQ-26 | QANI sets `preamble_duration`/`sts_length`/`sts_segments` that `fira_set_session_parameters()` never pushes. | 6.7 |
| OQ-27 | Responder slot index does not exist in this API; not needed for unicast. | 6.7 |
| **OQ-28** | **vUpper64 byte order between the derived 8-byte key and the Qorvo struct. Single most likely root cause. Must be settled by trying both orderings.** | 6.8 |
| OQ-29 | DW3110 deep-sleep wake path under the Zephyr platform layer. | 6.9 |
| OQ-30 | `round_hopping = true` (which Android uses) is exercised by no Qorvo sample, and QANI explicitly relies on hopping being **off**. | 6.7.1 |

---

## 10. Index of TRAPs — where a natural implementation goes wrong

| # | Trap | § |
|---|---|---|
| TRAP-1 | Android `getRemoteDevice(String)` assumes a public address; the tracker advertises random static. | 2.2 |
| TRAP-2 | 48-byte GATT write is impossible at Zephyr defaults: `BT_L2CAP_TX_MTU` = 23 and `BT_ATT_PREPARE_COUNT` = 0 disables long writes. | 3.2 |
| TRAP-3 | Re-persisting the provisioning timestamp rather than `provision_time + elapsed` silently drifts out of the owner's search window. | 4.2 |
| TRAP-4 | Omitting the `l1_config_platform_ops.reset_to_default` OTP hook leaves the radio uncalibrated (no antenna paths, no XTAL trim, no TX power index) with **no error anywhere**. | 6.2 |
| TRAP-5 | The ~16 `mcps_crypto_*` primitives are application-supplied; stubs or wrong AES modes produce STS mismatch, i.e. RX timeouts. | 6.3 |
| TRAP-6 | The whole DW3xxx ISR and MAC run in GPIO interrupt context; BLE preemption can push the responder's TX out of its 2 ms slot. | 6.4 |
| TRAP-7 | `session_id` ≠ `session_handle`; every setter takes the handle from `init_session`'s response. | 6.5 |
| TRAP-8 | `struct session_parameters` must be zero-initialised; the vendor relies on static zero-init for ~8 fields it never assigns. | 6.7 |
| TRAP-9 | Static STS is `sts_config = 0`; one vendor doc comment says `0x01`, which actually selects **Dynamic** STS. Do not call that setter. | 6.7 |
| TRAP-10 | Setting only the *session* short address, without `uwbmac_set_short_addr()` or promiscuous mode, leaves the hardware address filter discarding the phone's polls. | 6.7 |
| TRAP-11 | vUpper64 is laid out **reversed** by Qorvo's NI glue relative to `VendorId‖StaticStsIv`; a straight `memcpy` of the derived 8 bytes yields a mismatched STS. | 6.8 |

---

## 11. Contradictions found in the project's own documents

Recorded because they are latent bug sources, not because they need resolving here.

1. **Who chooses the STS key.** DESIGN:84–89 says the phone *"picks the session params … and hands
   them to the tracker"*, and PLAN:12 flags that DESIGN previously said this explicitly included
   the STS key. DESIGN:92–97 and HW:181–187 both say the STS key is **derived, not sent**. The
   derived reading is the correct one; DESIGN:84–89's "the phone picks the params" must be read as
   excluding the STS key and the tracker's UWB address.
2. **Which crowd-finding scheme shipped.** DESIGN §1 (line 47) and §5.1–5.4 describe rotating
   per-epoch EC keys ("Option B"); DESIGN:150–156 says that is **not** what shipped and the
   rotating-hash + ML-KEM scheme in §5.5 is. §5 of this document follows the shipped scheme.
   PLAN:12 records this same contradiction as unresolved.
3. **DESIGN §6 says the per-find handoff carries "only channel/slot"** (DESIGN:216–219) while
   HW:179–181 defines it as `[2B phoneAddr][4B sessionId][1B channel][1B preamble]`. HW is more
   specific and consistent with DESIGN:96–97; follow HW.
4. **HW §4's pin map is for the wrong board.** HW §1–§4 specifies a bare nRF52840 custom board;
   the bench target is the DWM3001CDK (nRF52833), and HW:5–12 says so explicitly. The DW3110 pin
   assignments that actually apply are in §6.9 of this document, taken from Qorvo's own
   DWM3001CDK project definition. Using HW §4's table would wire SPI to the wrong pins entirely.
5. **DESIGN:36–38 and PLAN:23,46 say the UWB firmware is staged behind `CONFIG_FF_TRACKER_UWB`
   (default `n`) and "never compiled"** because the licensed Qorvo driver was unavailable. The SDK
   is now present at `C:\qorvo`, so that premise no longer holds, but no document has been updated
   to say what the flag's state should now be, or whether the staged code was ever compiled before
   it was first run against hardware. PLAN:139 is explicit that UWB ranging was **not verifiable**
   at the time of writing and that success would not be claimed on the basis of compiling.

---

## 12. Suggested order of investigation for the reported bug

The reported symptom is: **the phone transmits and gets RX timeouts.** That means the phone's
receiver never gets a valid, correlatable response in the expected slot. Ordered by
(probability × cheapness to check):

1. **Is the tracker seeing DW3110 interrupts at all?** OQ-17 / TRAP-6. Check whether anything calls
   `qplatform_uwb_interrupt_enable()` after `llhw_init()`. If not, nothing downstream can work.
   Cheapest possible check and a complete explanation of the symptom.
2. **Enable the tracker's own notifications before guessing.** `fira_helper_open`'s notification
   callback delivers `FIRA_HELPER_CB_TYPE_SESSION_STATUS_NTF` (state + reason code) and
   `..._TWR_RANGE_NTF` (per-measurement status). The reason-code and status strings in
   `fira_app.c:546–728` distinguish "never heard anything" (`RX_TIMEOUT`) from "heard it, STS wrong"
   (`RX_PHY_STS_FAILED`) from "parameter rejected" (`ERROR_INVALID_STS_CONFIG`,
   `ERROR_INVALID_RFRAME_CONFIG`, `ERROR_SLOT_LENGTH_NOT_SUPPORTED`, …). This single step collapses
   most of the search space and should precede any parameter fiddling.
3. **Address filtering.** TRAP-10 / OQ-22. Verify `uwbmac_set_short_addr()` is called with the
   derived address, and try `uwbmac_set_promiscuous_mode(ctx, true)` as the vendor's BLE-provisioned
   reference does. Independently, try both byte orders for `phoneAddr` from `…2f03…`.
4. **vUpper64 byte order.** TRAP-11 / OQ-28. Try both orderings. Two builds, one experiment.
5. **L1 config / OTP calibration.** TRAP-4. Verify a `reset_to_default` hook exists, runs after
   `dwt_probe()`, and configures antenna paths, antenna delays, TX power index, XTAL trim and
   pg_count. Missing XTAL trim in particular can prevent the peer from acquiring.
6. **`mcps_crypto_*` completeness.** TRAP-5. Confirm all ~16 symbols are real AES-CMAC/CCM*/ECB.
7. **Start order.** OQ-19. Confirm the tracker's session reaches `ACTIVE` before the phone starts
   polling, and add whatever handshake OQ-7 is missing if it does not.
8. **Zero-initialisation of `session_parameters`.** TRAP-8.
9. **Field-by-field diff of the §6.7 table against what the phone programs.** `compare` reports
   having done this against a UCI capture and found all 19 must-match fields in agreement, so
   **this is ruled out** — keep it on the list only to re-verify after any parameter change. It was
   also the step that caught a real error in §6.7's own timing values (see §6.7.1).
10. **`round_hopping` behaviour.** OQ-30. Android enables hopping; no Qorvo sample does, and QANI's
    code comments explicitly assume it is off. Suspect this specifically if ranging fails
    intermittently, or works for the first blocks and then stops, rather than never working.
11. **Timing contention with BLE.** TRAP-6 / OQ-16. Last, because it is the hardest to change, but
    if ranging works intermittently or only with BLE disconnected, this is the answer.