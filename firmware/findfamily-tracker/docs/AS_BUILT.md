# FindFamily UWB tracker — as-built description

Derived **only from source code** (firmware, Android app, and the vendor SDK at `C:\qorvo`
where our code calls into it). No design document, plan, README or intended-behaviour
document was consulted.

Where a comment in the code contradicts what the code does, this document reports **what
the code does** and flags the contradiction inline as `⚠ CONTRADICTION`. Those flags are
the point of the exercise: the prose in this codebase is frequently stale.

Vendor references are to the extracted SDK at `C:\qorvo` and the NCS/Zephyr tree at
`C:\ncs\v3.4.0\zephyr`, which is the tree whose macro spellings the firmware actually
matches.

## Baseline: committed (HEAD) state

Everything in this document describes the **committed** state of the tree. One file is
modified in the working copy:

```
$ git status --short -- firmware findfamily
 M firmware/findfamily-tracker/src/tracker_uwb.c
?? firmware/findfamily-tracker/docs/          (this document)
```

`firmware/findfamily-tracker/src/tracker_uwb.c` at HEAD is **519 lines**; the working copy is
**530 lines**. The difference is a single hunk in `uwb_poll_thread` and is described, and
excluded from the as-built findings, in **§6.3.1**. The uncommitted version is *not* assumed
to be intended. The file is **not** syntactically broken — see §6.3.1 for the checks.

**All `tracker_uwb.c` line numbers in this document are HEAD line numbers.** HEAD lines
1–160 coincide with the working copy; from HEAD line 161 onward the working copy is shifted
+11. No other file has uncommitted changes, so line numbers for every other file are
unambiguous.

One further caveat, which applies to §6.3 in particular: the comment block above
`start_polling()` (`tracker_uwb.c:164-172` at HEAD) has been rewritten repeatedly with
mutually inconsistent claims about when the MAC event loop must be started. It is treated as
unreliable prose throughout and is never used as evidence for a behavioural claim.

---

## 1. Advertising

### 1.1 Advertising sets

Three `bt_le_ext_adv` sets are created once, from `create_adv_sets()`
(`c:\Users\Vayun\Documents\code\Modern-Apps\firmware\findfamily-tracker\src\tracker_ble.c:327-394`),
called at the end of `ff_ble_init()` (`tracker_ble.c:409`). All three use
`.id = BT_ID_DEFAULT`.

| Set | Variable | `options` | Numeric | interval_min | interval_max | AD data |
|---|---|---|---|---|---|---|
| Pairing | `pairing_adv` (`tracker_ble.c:60`) | `BT_LE_ADV_OPT_CONN` (`tracker_ble.c:332`) | `BIT(0)\|BIT(1)` = `0x003` | `BT_GAP_ADV_FAST_INT_MIN_2` = `0x00a0` (160 → 100 ms) | `BT_GAP_ADV_FAST_INT_MAX_2` = `0x00f0` (240 → 150 ms) | `pairing_ad`, 2 fields |
| Beacon | `beacon_adv` (`tracker_ble.c:61`) | `BT_LE_ADV_OPT_EXT_ADV` (`tracker_ble.c:351`) | `BIT(10)` = `0x400` | `BT_GAP_MS_TO_ADV_INTERVAL(CONFIG_FF_TRACKER_BEACON_INTERVAL_MS)` = `BT_GAP_MS_TO_ADV_INTERVAL(1000)` = 1600 (1000 ms) | `BT_GAP_MS_TO_ADV_INTERVAL(1100)` = 1760 (1100 ms) | `beacon_ad`, 2 fields, set at refresh time only |
| Connectable | `connect_adv` (`tracker_ble.c:63`) | `BT_LE_ADV_OPT_CONN` (`tracker_ble.c:364`) | `0x003` | `BT_GAP_ADV_SLOW_INT_MIN` = `0x0640` (1600 → 1000 ms) | `BT_GAP_ADV_SLOW_INT_MAX` = `0x0780` (1920 → 1200 ms) | **none** (`bt_le_ext_adv_set_data` is never called for this set — `tracker_ble.c:387-392`) |

- The beacon set additionally sets `.secondary_max_skip = 0` (`tracker_ble.c:354`). The
  other two leave it at 0 by C initialisation.
- Neither the pairing set nor the connectable set sets `BT_LE_ADV_OPT_EXT_ADV`, so both are
  **legacy** advertising sets created through the extended-advertising API.
- Option constants confirmed in `C:\ncs\v3.4.0\zephyr\include\zephyr\bluetooth\bluetooth.h:724`
  (`BT_LE_ADV_OPT_CONN = BIT(0) | BIT(1)`) and `:803` (`BT_LE_ADV_OPT_EXT_ADV = BIT(10)`).
  Interval constants in `.../include/zephyr/bluetooth/gap.h:62,64,66,68`.
- `BT_LE_ADV_OPT_CODED` is **not** set on the beacon set, and neither is
  `BT_LE_ADV_OPT_SCANNABLE` on any set.

### 1.2 AD field contents

`pairing_ad` (`tracker_ble.c:72-75`) — total **21 bytes**:

| Bytes | AD type | Value |
|---|---|---|
| 3 | `BT_DATA_FLAGS` (`0x01`) | `BT_LE_AD_GENERAL \| BT_LE_AD_NO_BREDR` = `0x02 \| 0x04` = **`0x06`** |
| 18 | `BT_DATA_UUID128_ALL` (`0x07`) | `6b1d2f01-4b3a-4c7e-9a10-1f2e3d4c5b6a`, little-endian on air |

`beacon_ad` (`tracker_ble.c:77-87`) — total **53 bytes**:

| Bytes | AD type | Value |
|---|---|---|
| 18 | `BT_DATA_UUID128_ALL` (`0x07`) | `6b1d2f00-4b3a-4c7e-9a10-1f2e3d4c5b6a` |
| 35 | `BT_DATA_SVC_DATA128` (`0x21`) | `beacon_sd`, 33 bytes = `[16B UUID][16B epochId][1B battery%]` |

- `beacon_sd` is a static 33-byte buffer pre-initialised with the 16-byte beacon UUID
  (`tracker_ble.c:70`); `ff_ble_refresh_beacon()` overwrites bytes 16..31 with the epoch id
  and byte 32 with the battery percent (`tracker_ble.c:432-433`).
- **There is no Flags AD field in the beacon advertisement.** Only the pairing set carries
  Flags.
- AD type values confirmed in `C:\ncs\v3.4.0\zephyr\include\zephyr\bluetooth\assigned_numbers.h:653,659,683,714,715`.

⚠ **CONTRADICTION (advertised size).** `prj.conf:18-19` says the beacon is "35 bytes on
air". 35 bytes is the size of the *service-data structure alone*; the set also carries the
18-byte Service UUID field, so the advertising data is **53 bytes**. The comment block at
`tracker_ble.c:78-84` gets this right ("Costs 18 bytes"), and `TrackerBle.kt:24-31` repeats
the 35-byte figure.

### 1.3 Which sets run in which mode

`ff_ble_set_mode()` (`tracker_ble.c:452-533`). `enum ff_ble_mode` is declared at
`ff_tracker.h:80-87`.

- Entry always assigns `desired_mode = mode` (`tracker_ble.c:456`), then **returns 0
  immediately if `mode == current_mode`** (`tracker_ble.c:458-460`) — no sets are
  (re)started in that case.
- Teardown: if leaving `FF_BLE_MODE_PAIRING`, stop `pairing_adv`; if leaving
  `FF_BLE_MODE_BEACON`, stop `beacon_adv` **and** `connect_adv` (`tracker_ble.c:464-478`).
  `-EALREADY` is tolerated. `current_mode` is then forced to `FF_BLE_MODE_IDLE`
  (`tracker_ble.c:479`).
- `FF_BLE_MODE_IDLE`: nothing started, returns 0 (`tracker_ble.c:482-484`).
- `FF_BLE_MODE_PAIRING`: starts `pairing_adv` only, with `BT_LE_EXT_ADV_START_DEFAULT`
  (= `BT_LE_EXT_ADV_START_PARAM(0, 0)`, i.e. no timeout, unlimited events)
  (`tracker_ble.c:487`).
- `FF_BLE_MODE_BEACON` (`tracker_ble.c:496-530`):
  1. Refuses with `-EPERM` if `!state->provisioned` (`tracker_ble.c:497-500`).
  2. Sets `current_mode = FF_BLE_MODE_BEACON` **before** calling
     `ff_ble_refresh_beacon(100)` (`tracker_ble.c:503-504`) — necessary because
     `ff_ble_refresh_beacon()` no-ops the `bt_le_ext_adv_set_data()` call unless
     `current_mode == FF_BLE_MODE_BEACON` (`tracker_ble.c:442-444`). Note the battery
     percent here is the literal **100**, not `battery_percent()`.
  3. `-EAGAIN` from refresh (no time base) reverts to `FF_BLE_MODE_IDLE` and returns
     (`tracker_ble.c:505-509`).
  4. Starts `beacon_adv`; failure reverts to IDLE and returns the error
     (`tracker_ble.c:514-519`).
  5. Starts `connect_adv`; **failure is logged as a warning and ignored**
     (`tracker_ble.c:525-528`), and the function still returns 0.

⚠ **CONTRADICTION (beacon connectability).** The file header at `tracker_ble.c:12-17` says
the beacon is an "EXTENDED connectable adv … Connectable so the owner can still write UWB
session params after binding", and `ff_tracker.h:85-86` documents `FF_BLE_MODE_BEACON` as
"Extended **connectable** adv carrying the rotating epoch id". The code makes the beacon set
**non-connectable** (`options = BT_LE_ADV_OPT_EXT_ADV` only, `tracker_ble.c:351`) and puts
connectability on a separate dataless legacy set. `TrackerBle.kt:30` also still says
"connectable so the phone can still write UWB session params post-bind". The in-function
comment at `tracker_ble.c:336-348` is the one that matches the code.

⚠ **CONTRADICTION / DEAD CODE (the re-advertise retry).** A whole mechanism exists for
retrying a mode switch that failed for want of a connection slot:
`pending_readvertise` (`tracker_ble.c:96`), set from `on_disconnected()` **only when
`desired_mode != current_mode`** (`tracker_ble.c:139-141`), drained by
`ff_ble_take_readvertise_event()` (`tracker_ble.c:289-292`) and handled in `main()`
(`main.c:228-231`). **Its trigger and its recovery are independently broken, so fixing either
one alone changes nothing.**

*Trigger half* — the event is never set on the path that needs it:
- `ff_ble_set_mode(FF_BLE_MODE_BEACON)` returns **0** even when `connect_adv` fails to start
  (`tracker_ble.c:525-528`), and sets `current_mode = desired_mode = FF_BLE_MODE_BEACON`.
- Therefore on the subsequent disconnect `desired_mode == current_mode` and
  `pending_readvertise` is never set, so `connect_adv` is never retried.
- The same is true after a connection is accepted *while in beacon mode*: the host clears
  `BT_ADV_ENABLED` for a connectable set when it yields a connection
  (`bt_hci_le_adv_set_terminated()`, `C:\ncs\v3.4.0\zephyr\subsys\bluetooth\host\adv.c:2125`,
  clearing at `:2153`), but `current_mode`/`desired_mode` both stay
  `FF_BLE_MODE_BEACON`, so nothing restarts `connect_adv` and no further owner connection is
  possible until a mode change occurs.

*Recovery half* — the handler is a no-op even if the event does arrive. `main()` responds by
calling `ff_ble_set_mode(ff_ble_desired_mode())` (`main.c:230`), and `ff_ble_set_mode()`
assigns `desired_mode = mode` and then returns 0 immediately when `mode == current_mode`
(`tracker_ble.c:456-460`) — before the teardown and before the `switch` that would restart
`beacon_adv`/`connect_adv`. Whenever `connect_adv` is down, `current_mode` is still
`FF_BLE_MODE_BEACON`, so passing `desired_mode` back in always hits that guard and nothing is
restarted.

The two halves check the same `desired_mode == current_mode` condition from opposite ends:
`on_disconnected` refuses to raise the event unless they differ, and `ff_ble_set_mode` refuses
to act unless they differ. There is no state in which the mechanism both fires and does
something. Repairing it requires changing both ends — e.g. tracking per-set enablement rather
than a single mode enum, or forcing a restart path that does not consult `current_mode`.

**No host-side auto-resume exists in this Zephyr, so the restart is unconditionally the
application's job.** Stated positively because "the host stops the set on connection" invites
the assumption that the host might put it back. It will not: `adv_resume` and `BT_ADV_PERSIST`
have **zero** occurrences across every `*.c` and `*.h` under
`C:\ncs\v3.4.0\zephyr\subsys\bluetooth\host`. The auto-restart-after-disconnect behaviour that
older Zephyr provided for the legacy `bt_le_adv_start()` API is not present in v3.4.0 and never
applied to extended advertising sets. So once `connect_adv` has yielded one connection, the
only thing that can put it back on air is `ff_ble_set_mode()` being called with a mode
different from `current_mode` — which, per both halves above, does not happen.

The narrative comments that describe this mechanism as load-bearing are
`ff_tracker.h:106-111`, `tracker_ble.c:52-58`, `tracker_ble.c:132-138`, `main.c:221-224`.

⚠ **CONTRADICTION (`-ENOMEM` premise).** `prj.conf:13-16` sets `CONFIG_BT_MAX_CONN=2`
explicitly *so that* starting a connectable advertisement while the binding phone is
attached does not fail with `-ENOMEM`. With two slots the failure the retry mechanism above
exists to handle should not occur at all. Both the fix and the workaround for the same
problem are present simultaneously.

### 1.4 Beacon data refresh cadence

- `main()` calls `ff_ble_refresh_beacon(battery_percent())` on **every** loop pass while
  `tracker_state.provisioned` (`main.c:256-259`), i.e. every `TICK_MS` = **1000 ms**
  (`main.c:30`, `main.c:272`).
- `battery_percent()` is a hard-coded `return 100;` (`main.c:95-98`).
- `ff_ble_refresh_beacon()` (`tracker_ble.c:412-450`):
  - `-EPERM` if not provisioned; `-EAGAIN` if `ff_current_epoch() == 0`.
  - Recomputes the epoch id every call and logs it at `LOG_INF` with the full 16 bytes plus
    `(on air)` / `(NOT advertising)` (`tracker_ble.c:435-440`) — a 1 Hz log line.
  - Calls `bt_le_ext_adv_set_data(beacon_adv, ...)` **only** when
    `current_mode == FF_BLE_MODE_BEACON`. The epoch id itself only changes every 900 s, so
    899 of every 900 of these writes push identical bytes.

### 1.5 Phone-side scan parameters

Beacon scan — `TrackerBeaconScanner.sightings()`
(`C:\Users\Vayun\Documents\code\Modern-Apps\findfamily\src\main\java\com\vayunmathur\findfamily\tracker\TrackerBeaconScanner.kt:29-88`):
- `ScanFilter.Builder().setServiceUuid(ParcelUuid(TrackerBle.SERVICE_UUID))` — matches the
  `0x07` field, not the service data (`:60-62`).
- `ScanSettings.Builder().setScanMode(SCAN_MODE_LOW_POWER).setLegacy(false)` (`:63-70`).
  No `setReportDelay`, no `setPhy`, no `setCallbackType`.
- Payload parsing (`:40-46`): reads `scanRecord.getServiceData(ParcelUuid(SERVICE_UUID))`,
  drops the result if `size < 16`, takes bytes 0..15 as `epochId`, and byte 16 (masked
  `0xFF`) as battery, or `-1` when `size == 16`.

Pairing scan — `TrackerProvisioner.unprovisioned()` (`TrackerProvisioner.kt:38-63`):
- `ScanFilter` on `UNPROVISIONED_SERVICE_UUID` (`:51-53`).
- `ScanSettings.Builder().setScanMode(SCAN_MODE_LOW_LATENCY)` and **no `setLegacy(false)`**
  (`:54-56`), i.e. legacy-only reporting — consistent with the pairing set being a legacy
  advertisement.

Bench equivalent: `tools/bench_provision.py:38-41` filters on
`ad.service_uuids` for the unprovisioned UUID, and `:62-65` reads
`ad.service_data[BEACON_SVC]`.

---

## 2. Address handling

### 2.1 Firmware side

The firmware **never configures its Bluetooth identity**. `ff_ble_init()` calls
`bt_enable(NULL)` (`tracker_ble.c:402`) and nothing else:
- No `settings_load()`, no `settings_subsys_init()`, no `bt_id_create()` anywhere in
  `firmware/` (verified by search).
- `prj.conf` sets `CONFIG_SETTINGS=y` / `CONFIG_SETTINGS_NVS=y` (`prj.conf:59-60`) but does
  **not** set `CONFIG_BT_SETTINGS`, and no code loads settings, so the Bluetooth identity is
  whatever `bt_enable()` derives at boot and is not persisted by the application.
- `CONFIG_BT_PRIVACY` and `CONFIG_BT_SMP` are not set, so there is no RPA rotation, no
  pairing and no bonding. All three advertising sets use `.id = BT_ID_DEFAULT`
  (`tracker_ble.c:331,350,363`).

Consequence for the app: the address the phone sees is a *random* (not public) device
address. Nothing in the firmware pins it explicitly.

### 2.2 Bind path (app obtains the device from a scan)

`TrackerProvisioner.unprovisioned()` emits `result.device` straight from
`ScanCallback.onScanResult` (`TrackerProvisioner.kt:44-46`). That `BluetoothDevice` already
carries the correct address type from the scan record, so `provision()` connects with
`device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)`
(`TrackerProvisioner.kt:150`) and no address type is stated anywhere.

`TrackerBinder.bind()` then persists `device.address` — the **string** form only — via
`store.save(trackerId, secret, privateBundle, device.address)`
(`TrackerBinder.kt:60`, stored under key `ff_tracker_mac_<id>` in `TrackerStore.kt:25,37`).
The address *type* is not stored.

### 2.3 UWB handover path (app rebuilds the device from a string)

`TrackerUwbGatt.writeSessionParams()` reconstructs the device with an explicitly asserted
type:

```kotlin
adapter?.getRemoteLeDevice(bleAddress, BluetoothDevice.ADDRESS_TYPE_RANDOM)
```
(`TrackerUwbGatt.kt:135`)

so the handover path always assumes `ADDRESS_TYPE_RANDOM`, regardless of what the scan
originally reported. Comment at `TrackerUwbGatt.kt:125-134` gives the rationale
(`getRemoteDevice(String)` assumes `ADDRESS_TYPE_PUBLIC` and fails with GATT 147). Connect
is again `TRANSPORT_LE` (`TrackerUwbGatt.kt:204`).

The address comes from `TrackerStore.bleAddress(trackerUserId)`, read in
`UwbSessionManager.beginTrackerFind()` (`UwbSessionManager.kt:328`); a missing secret **or**
address fails the find with "This tracker isn't bound on this device"
(`UwbSessionManager.kt:329-334`).

**Observation (not a comment contradiction, a structural gap).** The stored address is only
ever written at bind time. Because the firmware never persists or pins its own identity
address (§2.1), the app has no path to refresh it — there is no re-resolution by service
UUID on the handover path, and `TrackerStore` has no update-address method. If the
tracker's identity address ever differs from the bind-time one, the UWB handover fails
permanently while crowd-finding (which is scan-based) keeps working.

---

## 3. GATT table

Defined statically by `BT_GATT_SERVICE_DEFINE(ff_tracker_svc, ...)`
(`tracker_ble.c:299-310`). The table is registered unconditionally at build time and is
**not** gated on provisioning state.

| Attribute | UUID | Properties | Permissions |
|---|---|---|---|
| Primary service | `6b1d2f01-4b3a-4c7e-9a10-1f2e3d4c5b6a` (`uuid_prov_svc`, `tracker_ble.c:46`) | — | — |
| Characteristic (provision) | `6b1d2f02-…` (`tracker_ble.c:47`) | `BT_GATT_CHRC_WRITE` | `BT_GATT_PERM_WRITE` |
| Characteristic (UWB session) | `6b1d2f03-…` (`tracker_ble.c:48`) | `BT_GATT_CHRC_WRITE \| BT_GATT_CHRC_NOTIFY` | `BT_GATT_PERM_WRITE` |
| CCC (for the UWB characteristic) | `0x2902` | — | `BT_GATT_PERM_READ \| BT_GATT_PERM_WRITE` |

- Both characteristics have `read` callback `NULL` and no read permission, so they are
  write-only.
- Permissions are plain `BT_GATT_PERM_WRITE`: **no** `_ENCRYPT` or `_AUTHEN` variant, so
  writes are accepted on an unencrypted, unbonded link. (Consistent with the reasoning in
  `tracker_sts.c:5-8` and `TrackerUwbKeys.kt:9-13`.)
- The UWB characteristic declares `NOTIFY` and has a CCC, but **nothing in the firmware ever
  calls `bt_gatt_notify()`** — there is no notify path at all. The declared capability is
  unused.
- The beacon service UUID `6b1d2f00-…` is **only** advertised; it is never registered as a
  GATT service.

### 3.1 `on_provision_write` (`tracker_ble.c:166-243`)

Accepted/rejected, in evaluation order:

1. `flags & BT_GATT_WRITE_FLAG_PREPARE` → **`return 0`** immediately, no data staged
   (`tracker_ble.c:187-189`). Returning 0 (rather than `len`) from the prepare-authorisation
   callback is how Zephyr is told the write is permitted.
2. `state->provisioned` → **reject** `BT_ATT_ERR_WRITE_NOT_PERMITTED` (0x03), with a warning
   that names the likely cause ("misdirected UWB write?") (`tracker_ble.c:197-201`).
3. `offset + len > sizeof(provision_staging)` i.e. `> 48` → **reject**
   `BT_ATT_ERR_INVALID_ATTRIBUTE_LEN` (0x0D) (`tracker_ble.c:203-206`).
4. `memcpy(&provision_staging[offset], data, len)`; `total = offset + len`
   (`tracker_ble.c:207-208`). `provision_staging` is a 48-byte static buffer
   (`tracker_ble.c:103`) and is **never cleared** between attempts.
5. If `total` is neither `48` (`FF_PROVISION_BLOB_LEN`) nor `40`
   (`FF_PROVISION_BLOB_LEN_NO_TIME`) → accept the chunk (`return len`) and wait
   (`tracker_ble.c:210-214`).
6. Otherwise parse and commit (see §4), set `pending_provision`, `return len`
   (`tracker_ble.c:215-242`).

**BUG (as-built, from the code alone).** Step 5 keys the "blob complete" decision purely on
`offset + len`, and 40 is one of the two accepted totals. A 48-byte blob delivered as a long
write in 20-byte chunks produces totals `20`, `40`, `48`. At the second chunk `total == 40`,
so the firmware commits provisioning **with `unix_seconds = 0`** and sets
`state->provisioned = true` (`tracker_ble.c:230`). The third chunk (offset 40, len 8) then
hits guard 2 and is rejected with `BT_ATT_ERR_WRITE_NOT_PERMITTED`, so the client sees the
write fail while the device is left provisioned with no time base — which
`ff_ble_refresh_beacon()` turns into a permanent `-EAGAIN` (`tracker_ble.c:422-426`) and
`main()` turns into a fall-back to pairing mode on next boot (`main.c:205-209`). The long
write path is exactly what `tracker_ble.c:178-186`, `tracker_ble.c:99-103` and
`prj.conf:31-33` are written to support.

This is reachable whenever the MTU negotiation does not succeed: the app requests
`PREFERRED_MTU = 64` (`TrackerProvisioner.kt:182`) and only falls back to service discovery
on the default MTU when `requestMtu` returns false (`TrackerProvisioner.kt:112-115`).

### 3.2 `on_uwb_write` (`tracker_ble.c:245-273`)

`flags` is `ARG_UNUSED` (`tracker_ble.c:249`) — prepare-write is **not** special-cased here,
so a prepared write reaches the length check below with `len == 0` and is rejected. In
evaluation order:

1. Logs `len` and `offset` at `LOG_INF` unconditionally (`tracker_ble.c:252`).
2. `offset != 0` → **reject** `BT_ATT_ERR_INVALID_OFFSET` (0x07) (`tracker_ble.c:254-257`).
3. `len != FF_UWB_PARAMS_LEN` (8) → **reject** `BT_ATT_ERR_INVALID_ATTRIBUTE_LEN` (0x0D)
   (`tracker_ble.c:258-262`).
4. `!state->provisioned` → **reject** `BT_ATT_ERR_WRITE_NOT_PERMITTED` (0x03)
   (`tracker_ble.c:263-268`).
5. Otherwise `memcpy` the 8 bytes into `pending_uwb_params` and set `pending_uwb`;
   `return len` (`tracker_ble.c:270-272`).

### 3.3 Deferral to the main loop

Both handlers only record state; work happens in `main()`. Flags are `atomic_t`
(`tracker_ble.c:94-97`) consumed by `atomic_cas(&flag, 1, 0)`:
- `ff_ble_take_provision_event()` (`tracker_ble.c:275-278`) → `main.c:218`
- `ff_ble_take_uwb_params()` (`tracker_ble.c:280-287`) → `main.c:236`
- `ff_ble_take_readvertise_event()` (`tracker_ble.c:289-292`) → `main.c:228`

The 8-byte `pending_uwb_params` buffer itself is written by the BT RX thread and read by the
main loop with no lock; only the flag is atomic.

### 3.4 MTU observation callback

`bt_gatt_cb.att_mtu_updated = on_mtu_updated` is registered in `ff_ble_init()`
(`tracker_ble.c:160-162`, `:407`). It only logs, and reports `FF_PROVISION_BLOB_LEN + 3` =
**51** as the MTU needed for a single-op write (`tracker_ble.c:149-158`).

MTU sizing in `prj.conf`: `CONFIG_BT_BUF_ACL_RX_SIZE=69`, `CONFIG_BT_L2CAP_TX_MTU=65`
(`prj.conf:36-37`), `CONFIG_BT_ATT_PREPARE_COUNT=6` (`prj.conf:33`). The app asks for 64
(`TrackerProvisioner.kt:182`).

### 3.5 App-side service/characteristic lookup

Both app paths look the characteristic up **under the unprovisioned service UUID**, which
matches the firmware: `TrackerProvisioner.kt:132-133` and `TrackerUwbGatt.kt:169,175`. Both
use `WRITE_TYPE_DEFAULT` (write-with-response) on API ≥ 33 and the deprecated
`ch.value = …; gatt.writeCharacteristic(ch)` below that (`TrackerProvisioner.kt:163-170`,
`TrackerUwbGatt.kt:182-189`).

---

## 4. Provisioning blob

### 4.1 Bytes the app writes

`TrackerProvisioner.provision()` (`TrackerProvisioner.kt:86-90`) builds
`PROVISION_BLOB_LEN` = `8 + 32 + 8` = **48** bytes (`TrackerBle.kt:71`):

| Offset | Len | Content | Encoding |
|---|---|---|---|
| 0 | 8 | `trackerUserId` | big-endian, via `(trackerUserId.toULong() shr (56 - i*8)).toByte()` (`TrackerProvisioner.kt:87`) |
| 8 | 32 | beacon secret | verbatim, `secret.copyInto(blob, 8)` (`:88`); `require(secret.size == 32)` at `:85` |
| 40 | 8 | `unixSeconds` = `nowMs / 1000` | big-endian (`:89-90`) |

The secret is `SecureRandom`-generated at bind time (`TrackerBinder.kt:52`) and the tracker
id is `Random.nextLong(from = 1, until = Long.MAX_VALUE)` (`TrackerBinder.kt:53`).

The app **only ever writes the 48-byte form**. `PROVISION_BLOB_LEN_NO_TIME` = 40
(`TrackerBle.kt:74`) is a firmware-side accept-only variant with no producer in the app.

### 4.2 Bytes the firmware parses

`on_provision_write` (`tracker_ble.c:215-235`), after the completeness check:

```c
user_id = sys_get_be64(data);                                   /* bytes 0..7   */
if (total == 48) unix_seconds = sys_get_be64(&data[8 + 32]);    /* bytes 40..47 */
ff_store_save_provisioning(user_id, &data[8], unix_seconds);    /* bytes 8..39  */
```

Then in RAM (`tracker_ble.c:228-232`):
- `state->tracker_user_id = user_id`
- `memcpy(state->secret, &data[8], 32)`
- `state->provisioned = true`
- `state->unix_at_base = unix_seconds`
- `state->uptime_at_base_ms = k_uptime_get()`

With `total == 40`, `unix_seconds` stays 0 and a warning is logged: "no timestamp in blob:
epoch ids will not resolve for the owner" (`tracker_ble.c:236-238`).

Return value: `return len` — the length of the **final chunk**, not `total`
(`tracker_ble.c:242`).

### 4.3 Persistence

`ff_store_save_provisioning()` (`tracker_store.c:101-124`) writes three NVS entries in the
`storage_partition`:

| NVS id | Constant | Content | Byte order |
|---|---|---|---|
| 1 | `ID_USER_ID` (`tracker_store.c:27`) | `uint64_t tracker_user_id` | **native** (little-endian) raw struct bytes |
| 2 | `ID_SECRET` (`:28`) | 32 secret bytes | verbatim |
| 3 | `ID_UNIX` (`:29`) | `uint64_t unix_seconds` | **native** (little-endian) |

- Write order is secret, then user id, then (only if `unix_seconds != 0`) time
  (`tracker_store.c:109-122`). A non-zero time is required for `ID_UNIX` to be written at
  all, so a 40-byte provisioning never touches it — leaving any *previously* stored time
  base in flash untouched even though `state->unix_at_base` in RAM has been reset to 0.
- NVS mount parameters: `fs.sector_size` = the flash page size at the partition offset,
  `fs.sector_count = 3U` (`tracker_store.c:55-56`).
- `ff_store_load()` (`tracker_store.c:67-99`) reads secret first; anything other than
  exactly 32 bytes → treated as unprovisioned, returns 0. A secret without a user id →
  secret zeroed and treated as unprovisioned (`:81-88`). `provisioned = true` is only set
  after both succeed (`:89`). A missing `ID_UNIX` leaves `unix_at_base = 0` with a warning
  (`:91-97`).
- `ff_store_clear()` exists (`tracker_store.c:137-146`) and is declared as "Wipes
  provisioning so the device can be re-bound" (`ff_tracker.h:61-62`) but is **never called
  from anywhere** in the firmware. Re-binding relies instead on guard 2 of
  `on_provision_write` being bypassed — which it is not, so an already-provisioned tracker
  cannot in fact be re-provisioned over GATT at all. Long-pressing the button re-enters
  pairing mode (`main.c:242-245`) and advertises the unprovisioned service, but every
  provisioning write is then rejected with `BT_ATT_ERR_WRITE_NOT_PERMITTED`.

⚠ **CONTRADICTION (re-bind).** `main.c:8-11` says a long press exists "so an already-bound
tracker can be re-bound or have its clock resynced", and `main.c:206-208` says pairing mode
is offered so a provisioned-but-timeless tracker "can resync". Neither is achievable:
`on_provision_write` rejects all writes once `state->provisioned` is true
(`tracker_ble.c:197-201`) and nothing ever clears that flag.

### 4.4 Bench producer

`tools/bench_provision.py:48-49` builds the identical 48-byte layout
(`">Q" + 32B secret + ">Q"`) with `assert len(blob) == 48`, and writes it in a single
`write_gatt_char(..., response=True)` (`:53`).

---

## 5. Beacon (rotating id)

### 5.1 Epoch and id derivation (firmware)

`tracker_epoch.c`:
- `ff_now_unix()` (`:22-39`): returns 0 if `unix_at_base == 0`; otherwise
  `unix_at_base + (k_uptime_get() - uptime_at_base_ms) / 1000`, with negative elapsed
  clamped to 0.
- `ff_current_epoch()` (`:41-46`): `now == 0 ? 0 : now / 900` (`FF_EPOCH_SECONDS` = `900U`,
  `ff_tracker.h:20`).
- `ff_epoch_id()` (`:48-102`): PSA HMAC-SHA256 over
  `epoch_domain` = `"fftrk1"` (6 bytes, no NUL — `tracker_epoch.c:20`), then 8 bytes of
  `epoch` big-endian (`:58-60`), key = the 32-byte secret imported as `PSA_KEY_TYPE_HMAC`
  with `PSA_KEY_USAGE_SIGN_MESSAGE` and 256 key bits (`:62-67`). Output truncated to the
  first **16** bytes (`:96-100`). Any PSA failure → `-EIO`.

Phone side, `TrackerProtocol.epochId()` (`TrackerProtocol.kt:54-60`): same domain
(`EPOCH_DOMAIN = "fftrk1"`, `:41`, US-ASCII), same `u64be(epoch)` (`:109-113`), same
`copyOf(16)`. `currentEpoch(nowMs) = (nowMs/1000)/900` (`:46-47`). Bench script matches
(`bench_provision.py:31-33`).

Owner search window: `recentEpochIds(secret, nowMs, back = 8)` returns 9 ids — the current
epoch and 8 previous (`TrackerProtocol.kt:67-74`), i.e. **2.25 hours**.

### 5.2 Exact bytes on air

Service data under `0x21` for UUID `6b1d2f00-4b3a-4c7e-9a10-1f2e3d4c5b6a`:

| Offset in service data | Len | Content |
|---|---|---|
| 0 | 16 | `epochId` = `HMAC-SHA256(secret, "fftrk1" ‖ u64be(epoch))[0..15]` |
| 16 | 1 | battery percent, always **100** (`main.c:97`) |

`FF_BEACON_DATA_LEN` = 17 (`ff_tracker.h:30`).

### 5.3 Time-base re-persistence

`main()` re-persists the time base once per epoch change (`main.c:256-269`): computes
`epoch = ff_current_epoch()`, and if `epoch != 0 && epoch != last_persisted_epoch` calls
`ff_store_save_time(ff_now_unix(&tracker_state))` and updates `last_persisted_epoch`.
`last_persisted_epoch` is a local initialised to 0 (`main.c:166`), so the first pass after
boot always writes once. Worst-case drift after a reset is therefore one 900-second epoch.

---

## 6. UWB

Built only when `CONFIG_FF_TRACKER_UWB=y` (`Kconfig:6-15`, `CMakeLists.txt:22-25`,
`uwb.conf:10`). `CONFIG_FF_TRACKER_UWB` `select`s `QORVO_UWB` (`Kconfig:9`).

### 6.1 `ff_uwb_init()` — exact call order (`tracker_uwb.c:184-249`)

1. Early `return 0` if `stack_ready` (`:188-190`).
2. `qplatform_init()` (`:198`). On failure: log, then log
   `ff_qorvo_read_dev_id()` with the expected `0xdeca0302`, `return -EIO` — **no cleanup
   call** (`:199-211`). `qplatform_init()` is the vendor function that ends in `dwt_probe()`
   (`C:\qorvo\Libs\uwb-stack\libs\qplatform\qm33_qhal_common\src\qplatform.c:266`).
3. `ff_qorvo_l1_config_use_ram_storage()` (`:217`) — repoints the two vendor globals
   `persistent_ram_config` / `persistent_ram_config_hash`.
4. `l1_config_init(&l1_config_platform_ops)` (`:219`). On failure: `qplatform_deinit()`,
   `return -EIO`.
5. `llhw_init()` (`:225`). On failure: `l1_config_deinit()`, `qplatform_deinit()`,
   `return -EIO`.
6. `uwbmac_init(&uwbmac_ctx)` (`:232`). On failure: `llhw_deinit()`, `l1_config_deinit()`,
   `qplatform_deinit()`, `return -EIO`.
7. `stack_ready = true` (`:241`).
8. Create the **session thread** (`:244-247`) — see §6.3. The poll thread is *not* created
   here.

Called from `main()` at `main.c:196-201`; a failure is logged and **execution continues**
(no `return`), so a BLE-only tracker still boots.

### 6.2 `ff_uwb_start()` — exact call order (`tracker_uwb.c:319-462`)

1. `!stack_ready` → `-EAGAIN` (`:330-332`).
2. `session_active` → `ff_uwb_stop()` first (`:333-335`).
3. `ff_sts_key(secret, params->session_id, vupper64)` → 8 bytes (`:342`).
4. `ff_uwb_address(secret, own_address)` → 2 bytes (`:346`).
5. `own_short_addr = sys_get_le16(own_address)`;
   `peer_short_addr = sys_get_le16(params->peer_address)` (`:350-351`) — **little-endian**
   interpretation of both 2-byte arrays.
6. `uwbmac_set_short_addr(uwbmac_ctx, own_short_addr)` (`:353`).
7. `fira_helper_open(&fira_ctx, uwbmac_ctx, on_fira_notification, "endless", 0, NULL)`
   (`:360`) — scheduler name `"endless"`, `region_id` 0, no user data.
8. `fira_helper_set_scheduler(&fira_ctx)` (`:367`).
9. `fira_helper_init_session(&fira_ctx, params->session_id,
   QUWBS_FBS_SESSION_TYPE_RANGING_NO_IN_BAND_DATA /* = 0 */, &rsp)`; `session_handle =
   rsp.session_handle` (`:374-380`).
10. Fill `struct session_parameters sp = { 0 }` (see §6.5).
11. `apply_session_params(&sp)` (`:426`) — 19 scalar setters plus 4 special-cased ones, in
    the order listed in §6.5.
12. `uwbmac_start(uwbmac_ctx)` (`:433`).
13. `start_polling()` (`:440`) — creates the poll thread **after** `uwbmac_start` and
    **before** `fira_helper_start_session`.
14. `fira_helper_start_session(&fira_ctx, session_handle)` (`:443`). On failure:
    `uwbmac_stop()` then the `deinit` path.
15. `session_active = true`, log the session summary, `return 0` (`:451-455`).

Failure paths: `deinit:` → `fira_helper_deinit_session()` then falls through to `close:` →
`fira_helper_close()`, returning `-EIO` (`:457-461`).

Progress is traced with `printk()` at `:359, 366, 373, 425, 431, 442, 449`. These are **not**
a bypass of the logging subsystem: `CONFIG_LOG_PRINTK` is `default y if PRINTK`
(`C:\ncs\v3.4.0\zephyr\subsys\logging\Kconfig.processing:8-13`) and `CONFIG_PRINTK` is
`default y` (`.../subsys/debug/Kconfig:145-147`), neither of which `prj.conf` or `uwb.conf`
overrides — so printk output is redirected into the log subsystem and, with
`CONFIG_LOG_MODE_DEFERRED=y` (`prj.conf:74`), is deferred through the same pipeline and the
same priority-14 processing thread as every `LOG_INF`. If that thread is starved these seven
markers are lost along with everything else (see §6.3 and §8.4).

`ff_uwb_stop()` (`:464-475`): no-op unless `session_active`; then
`fira_helper_stop_session`, `fira_helper_deinit_session`, `uwbmac_stop`,
`fira_helper_close`, `session_active = false`. It is called from exactly one place
(`:334`) — the top of `ff_uwb_start()`. **Nothing stops a session on BLE disconnect, on
ranging failure, or on a timeout**; a started session runs until the next params write or a
reset.

### 6.3 Threads

| Thread | Entry | Stack object | Size | Priority | Created at |
|---|---|---|---|---|---|
| `ff_uwb` (poll) | `uwb_poll_thread` (`tracker_uwb.c:152-162`) | `uwb_thread_stack` (`:79`) | `UWB_THREAD_STACK_SIZE` = **8192** (`:48`) | `UWB_THREAD_PRIORITY` = **5** (`:49`) | `start_polling()` (`:174-182`), from `ff_uwb_start` after `uwbmac_start` |
| `ff_uwb_sess` (session setup) | `uwb_session_thread` (`:482-493`) | `uwb_session_stack` (`:95`) | `UWB_SESSION_THREAD_STACK_SIZE` = **4096** (`:92`) | `UWB_SESSION_THREAD_PRIORITY` = **6** (`:93`) | `ff_uwb_init()` (`:244-246`) |
| `main` | `main()` | Zephyr main stack | `CONFIG_MAIN_STACK_SIZE=4096` (`prj.conf:6`) | Zephyr default (0) | kernel |
| Qorvo MAC threads (0..4) | vendor | `k_thread_stack_alloc()` (`qthread_zephyr.c:88`) | `MAX(requested, MIN_STACK_SIZE=2048)` (`:37,68`) | `K_PRIO_PREEMPT((int)qthread_priority)` (`:96`), i.e. Zephyr 0..6 | vendor, via `qthread_create` |

Both application threads are created with `K_NO_WAIT` and no options, and both are given
names (`k_thread_name_set`, `:180`, `:247`).

- Poll thread body (**at HEAD**): `while (atomic_get(&poll_running)) {
  (void)uwbmac_poll_events(uwbmac_ctx, 100000); }` (`:158-161`) — an unthrottled loop with no
  sleep and no yield. `poll_running` is set once by `atomic_cas(&poll_running, 0, 1)` in
  `start_polling()` (`:176`) and is **never cleared anywhere**, so the thread never exits —
  including after `ff_uwb_stop()` has called `uwbmac_stop()`. The working tree adds a 1 ms
  sleep to this loop; see §6.3.1.
- Session thread body: `k_sem_take(&session_request, K_FOREVER)` then
  `ff_uwb_start(&requested_params, requested_secret)` (`:488-492`). `session_request` is
  `K_SEM_DEFINE(session_request, 0, 1)` (`:97`) — max count 1, so a second request arriving
  while one is in flight is coalesced, not queued.
- `requested_params` (`:98`) and `requested_secret` (`:99`) are plain statics written by
  `ff_uwb_on_params()` on the main thread and read by the session thread with no lock.

**Whether the HEAD loop actually spins depends on a vendor contract the code does not
restate.** The vendor header documents `uwbmac_poll_events(ctx, timeout_us)` with
`timeout_us > 0` as blocking: "Passing a value greated [sic] than 0 will make the function
block until the timeout is reached when there is no pending event"
(`C:\qorvo\Libs\uwbstack_libs\delivery\fira\Release\include\uwbstack_bundle\uwbmac\uwbmac.h:335-336`).
Taking the vendor at its word, the HEAD loop blocks up to 100 ms per iteration and does not
starve anything. The working-tree change (§6.3.1) is written on the opposite assumption.

**Poll thread runs across `uwbmac_stop()`.** `ff_uwb_stop()` calls `uwbmac_stop()` (`:471`)
without clearing `poll_running`, so after any stop the poll thread keeps calling
`uwbmac_poll_events()` on a stopped MAC. The comment above `start_polling()` (`:164-172`)
asserts this is a firmware-wedging condition ("Too early — before `uwbmac_start()` — and
`uwbmac_poll_events()` hangs on a MAC that is not running"). That prose is **not reliable**
— that comment block has been rewritten repeatedly with mutually inconsistent claims about
the start window — and the vendor header documents no such precondition. What is verifiable
from the code alone: nothing sequences the poll thread against `uwbmac_stop()`, so polling a
stopped MAC is reachable, and the behaviour in that state is unspecified by anything in this
repository.

**The poll thread preempts its own creator, and the session start depends on it yielding.**
This is a property of the priority numbers alone, independent of how `uwbmac_poll_events`
behaves:

- `ff_uwb_start()` is called from exactly one place, `uwb_session_thread` (`:491`), which runs
  at `UWB_SESSION_THREAD_PRIORITY` = **6** (`:93`).
- `start_polling()` (`:440`, inside `ff_uwb_start`) creates the poll thread at
  `UWB_THREAD_PRIORITY` = **5** (`:49`) with `K_NO_WAIT` (`:177-179`).
- In Zephyr a lower number is a *higher* priority, so the new thread is immediately runnable
  at a higher priority than its creator and preempts it at the `k_thread_create()` call.
- `fira_helper_start_session()` is the very next call (`:443`) and is therefore **reached only
  once the poll loop yields or blocks**.

The comment at `:438-439` states that the session start "needs its events pumped to
complete", i.e. the ordering is deliberate — but it does not note that the ordering is
enforced by preemption and that forward progress now depends on the poll loop giving the CPU
back. Consequences:

- If `uwbmac_poll_events` blocks (as the vendor header documents), the creator resumes on the
  first iteration and the session starts normally.
- If it returns immediately, then **at HEAD** — where the loop body contains nothing but the
  poll call, with no sleep and no yield (`:158-161`) — the priority-5 loop never releases the
  CPU, the priority-6 session thread never resumes, and `fira_helper_start_session()` is
  never called at all. That is a hard liveness failure of the session start, not merely
  starvation of lower-priority work.
- In that second case the observable symptom is specific and worth recording: a spinning
  priority-5 thread starves the session thread (6) and the log processing thread (**14**) but
  **not** the main thread, which runs at priority 0 and only sleeps. So logging would stop
  dead while the main loop kept looping and feeding the watchdog — no log output and no reset.
  This matches the symptom the uncommitted comment describes from observation (§6.3.1), which
  is circumstantial evidence that the non-blocking behaviour is the real one in this build.

The priority numbers behind that, all from `C:\ncs\v3.4.0\zephyr` for this configuration:

| Thread | Priority | Derivation |
|---|---|---|
| `main` | **0** | `CONFIG_MAIN_THREAD_PRIORITY` `default 0` (`kernel/Kconfig:70-73`; the `-2 if !PREEMPT_ENABLED` branch does not apply) |
| `ff_uwb` (poll) | **5** | `UWB_THREAD_PRIORITY` (`tracker_uwb.c:49`) |
| `ff_uwb_sess` | **6** | `UWB_SESSION_THREAD_PRIORITY` (`tracker_uwb.c:93`) |
| log processing | **14** | `CONFIG_LOG_PROCESS_THREAD_CUSTOM_PRIORITY` is not set, so `log_core.c:63` uses `K_LOWEST_APPLICATION_THREAD_PRIO` at `:1013`; that is `K_LOWEST_THREAD_PRIO - 1` = `CONFIG_NUM_PREEMPT_PRIORITIES - 1` (`kernel.h:58,61`) with `NUM_PREEMPT_PRIORITIES` `default 15` (`kernel/Kconfig:52-55`) |

**Timeslicing does not rescue this.** `CONFIG_TIMESLICING` is `default y` with
`CONFIG_TIMESLICE_SIZE` 20 ms (`kernel/Kconfig:679-692`), but it "enables time slicing between
preemptible threads of **equal priority**" — so it never rotates a priority-5 thread out in
favour of a priority-6 or priority-14 one. Nothing in the configuration bounds how long the
poll thread can hold the CPU.

Either way the priority relationship is a defect: liveness of the session start should not
depend on the poll loop's internal behaviour. Making the poll thread lower priority than its
creator (7 or below) would remove the dependency.

**The blocking question is not settled by the vendor header.** The same header adds that
`uwbmac_poll_events()` "is only available if you passed a NULL @event_loop_ops to
`uwbmac_init()`" (`uwbmac.h:328-329`). No `event_loop_ops` parameter or type exists anywhere
in this delivery — the string occurs only in that one doc comment — and `uwbmac_init()` here
takes a single argument, `enum qerr uwbmac_init(struct uwbmac_context **context)`
(`uwbmac.h:291`). The documented precondition therefore cannot be evaluated against this API,
so the header establishes neither that the call blocks in this build nor that it is available
at all rather than returning an error immediately. Both possibilities are covered above.

#### 6.3.1 Uncommitted delta in the working tree — NOT part of the as-built baseline

> `firmware/findfamily-tracker/src/tracker_uwb.c` is the one modified file in the working
> tree (`git status --short`: ` M`). The baseline described everywhere else in this document
> is **HEAD** — commit `0cdc09026`, file blob `c65114cf6`, **519 lines**. The working-tree
> file is **530 lines**. The diff is a **single hunk** inserted into the `uwb_poll_thread`
> loop body immediately after the `uwbmac_poll_events()` call: 11 added lines, being a 10-line
> block comment plus the single statement `k_sleep(K_MSEC(1));`. Nothing else in the file
> differs.
>
> **The file is not syntactically broken.** The inserted block is a balanced comment plus one
> complete statement inside an existing brace block; comment delimiters (26/26), braces
> (42/42) and parentheses (179/179) all balance across the working-tree file.
>
> **Line-number effect:** HEAD lines 1–160 are unchanged; every line from HEAD 161 onward is
> shifted **+11** in the working tree. All `tracker_uwb.c` citations in this document are
> **HEAD** line numbers.
>
> **Behavioural effect of the delta:** the poll loop gains an unconditional
> `k_sleep(K_MSEC(1))` per iteration, which makes it yield the CPU every pass. This is not
> necessarily cosmetic. Because the poll thread (priority 5) preempts the session thread
> (priority 6) that created it, and `fira_helper_start_session()` sits immediately after
> `start_polling()` in `ff_uwb_start()` — see the preemption analysis in §6.3 — the effect
> depends on how `uwbmac_poll_events` behaves:
>
> - If it blocks as the vendor header documents, the delta adds up to 1 ms of dispatch latency
>   per event and nothing else.
> - If it returns immediately, the delta is **load-bearing for liveness**: without it the
>   priority-5 loop never yields, the session thread never resumes, and
>   `fira_helper_start_session()` is never reached. The sleep is then what allows the FiRa
>   session to start at all.
>
> So this uncommitted hunk cannot be assumed to be a no-op or a stylistic tweak. Which of the
> two cases holds is not resolvable from the sources (§6.3), and I have not run the firmware.
>
> **The delta's own comment contradicts the vendor header.** It states "The timeout argument
> suggests this blocks, but it returns immediately when there is nothing to dispatch, so
> without a sleep this is a tight loop that starves every lower-priority thread — including
> the log processing thread", which is the direct opposite of `uwbmac.h:335-336` quoted above.
> Only one of the two can be true, and the change is written against the one the vendor
> documentation contradicts. Because this comment exists only in the uncommitted delta, it is
> recorded here rather than among the as-built findings in §10.

### 6.4 Where the MAC event loop is pumped

Only in `uwb_poll_thread` (`tracker_uwb.c:160`), on the `ff_uwb` thread at Zephyr priority 5.
`uwbmac_init()` is called with a single argument (`:232`) because that is the only form this
delivery offers (`uwbmac.h:291`), so `uwbmac_poll_events()` is the only pump available — note
that the header's stated precondition for it referencing `event_loop_ops` cannot be evaluated
against this API at all (see §6.3). Notifications are therefore dispatched from this thread,
including `on_fira_notification`
(`:117-150`), which only logs:
- `FIRA_HELPER_CB_TYPE_TWR_RANGE_NTF`: iterates `res->n_measurements` and logs
  `short_addr`, `status`, `distance_cm`, `rssi` per measurement (`:123-138`). No ranging
  result is surfaced to the rest of the firmware — no callback, no queue, no flag.
- `FIRA_HELPER_CB_TYPE_SESSION_STATUS_NTF`: logs `session_handle`, `state`, `reason_code`
  (`:139-145`).
- everything else: `LOG_DBG` (`:146-148`).

### 6.5 FiRa session parameters actually set

Assigned into `sp` in `ff_uwb_start()` (`tracker_uwb.c:383-423`):

| Field | Value assigned | Numeric (from vendor headers) |
|---|---|---|
| `device_type` | `QUWBS_FBS_DEVICE_TYPE_CONTROLEE` | **0** |
| `device_role` | `QUWBS_FBS_DEVICE_ROLE_RESPONDER` | **0** |
| `channel_number` | `params->channel` (from GATT) | 9 as sent by the app |
| `preamble_code_index` | `params->preamble_index` (from GATT) | 10 as sent by the app |
| `short_addr` | `own_short_addr` = `sys_get_le16(derived 2 bytes)` | derived |
| `n_destination_short_address` | `1` | 1 |
| `destination_short_address[0]` | `peer_short_addr` = `sys_get_le16(GATT bytes 0..1)` | from phone |
| `vupper64[8]` | the 8-byte derived STS key, `memcpy` | derived |
| `multi_node_mode` | `FIRA_MULTI_NODE_MODE_UNICAST` | **0** |
| `ranging_round_usage` | `FIRA_RANGING_ROUND_USAGE_DSTWR_DEFERRED` | **2** |
| `schedule_mode` | `FIRA_SCHEDULE_MODE_TIME_SCHEDULED` | **1** |
| `rframe_config` | `FIRA_RFRAME_CONFIG_SP3` | **3** |
| `sfd_id` | `FIRA_SFD_ID_2` | **2** |
| `prf_mode` | `FIRA_PRF_MODE_BPRF` | **0** |
| `phr_data_rate` | `FIRA_PRF_MODE_BPRF` | **0** (= 850 kbit/s) — see flag below |
| `slot_duration_rstu` | `SLOT_DURATION_RSTU` (`:68`) | **2400** |
| `block_duration_ms` | `BLOCK_DURATION_MS` (`:69`) | **120** |
| `round_duration_slots` | `ROUND_DURATION_SLOTS` (`:70`) | **6** |
| `round_hopping` | `ROUND_HOPPING` (`:71`) | **true** |
| `block_stride_length` | `0` | 0 |
| `report_rssi` | `1` | 1 |
| `enable_diagnostics` | `false` | false — **never pushed to the session** |
| `result_report_config` | `fira_helper_bool_to_result_report_config(true, false, false, false)` | **0x01** (ToF only) |
| `ranging_round_control` | `fira_helper_bool_to_ranging_round_control(true, false)` | **0x03** (`BIT(0)` result-report phase + unconditional `BIT(1)`) |
| `meas_seq.n_steps` | `1` | 1 |
| `meas_seq.steps[0].type` | `FIRA_MEASUREMENT_TYPE_RANGE` | **0** |
| `meas_seq.steps[0].n_measurements` | `1` | 1 |
| `meas_seq.steps[0].rx_ant_set_nonranging` | `0xff` into `int8_t` | **−1** |
| `meas_seq.steps[0].rx_ant_sets_ranging[0..1]` | `0xff` into `int8_t` | **−1, −1** |
| `meas_seq.steps[0].tx_ant_set_nonranging` | `0xff` into `int8_t` | **−1** |
| `meas_seq.steps[0].tx_ant_set_ranging` | `0xff` into `int8_t` | **−1** |

Bitfield helpers verified at
`.../uwbmac/fira_helper.h:2439-2447` (`result_report_config`) and `:2003-2009`
(`ranging_round_control`; note `BIT(1)` is set unconditionally by the vendor helper).

Push order in `apply_session_params()` (`tracker_uwb.c:266-315`) — 19 `SET_PARAM` calls,
each aborting the whole start with `-EIO` on any non-`QERR_SUCCESS`:
`channel_number`, `preamble_code_index`, `sfd_id`, `phr_data_rate`, `prf_mode`,
`device_type`, `device_role`, `multi_node_mode`, `rframe_config`, `slot_duration_rstu`,
`block_duration_ms`, `round_duration_slots`, `ranging_round_usage`, `round_hopping`,
`block_stride_length`, `schedule_mode`, `result_report_config`, `ranging_round_control`,
`report_rssi`; then `fira_helper_set_session_vupper64`,
`fira_helper_set_session_short_address`,
`fira_helper_set_session_destination_short_addresses`,
`fira_helper_set_session_measurement_sequence`.

Fields **left at the `= {0}` default and never pushed**: `sts_config` (0 = Static STS),
`psdu_data_rate`, `preamble_duration`, `key_rotation`, `key_rotation_rate`,
`sub_session_id`, `link_layer_mode`, `mac_fcs_type`, `cap_size_min`, `cap_size_max`,
`number_of_sts_segments`, `priority`, `mac_address_mode`, `time0_ns`,
`max_number_of_measurements`, `max_rr_retry`, plus `enable_diagnostics` and
`diags_frame_reports_fields`. Static STS is therefore relied on as the struct's zero value
and is never explicitly configured on the session.

⚠ **CONTRADICTION (`phr_data_rate` type confusion).** `tracker_uwb.c:402` assigns
`sp.phr_data_rate = FIRA_PRF_MODE_BPRF;` — a `fira_prf_mode` value in a PHR-data-rate field.
The vendor documents `phr_data_rate` as `0 = 850 kbit/s, 1 = 6.81 Mbit/s`
(`fira_helper.h:2541-2545`, `:371-379`). `FIRA_PRF_MODE_BPRF == 0`
`FIRA_PRF_MODE_BPRF == 0`
(`fira_region_params.h:278`), so the assignment happens to produce 850 kbit/s — the correct
default — but the enum used is the wrong one, and the value is a copy-paste of the preceding
line (`:401`).

⚠ **CONTRADICTION (where the timing values came from).** `tracker_uwb.c:13-18` says "the
parameters below are FiRa/Qorvo **defaults**, which is the best available guess at what
Android's `UwbRangingParams.CONFIG_UNICAST_DS_TWR` asks for … Expect to iterate here against
a real phone." `tracker_uwb.c:52-67`, 34 lines later, says the values "were read off the
wire — Android's UWB HAL logs its raw UCI SESSION_SET_APP_CONFIG, and these are the TLVs it
sends", and that "Guessing these from the FiRa defaults instead (200 ms / 25 slots / no
hopping) produced a session where the phone reported UCI status 0x21". These two accounts
of the provenance of `SLOT_DURATION_RSTU`/`BLOCK_DURATION_MS`/`ROUND_DURATION_SLOTS`/
`ROUND_HOPPING` are mutually exclusive.

⚠ **CONTRADICTION (vUpper64 layout).** `tracker_uwb.c:337-341` asserts "vUpper64 is exactly
`[2B VendorID][6B STATIC_STS_IV]`". In the vendor struct, `vupper64` is a union with
`{ uint8_t static_sts_iv[6]; uint8_t vendor_id[2]; }` in that order
(`fira_helper.h:381-397`), i.e. the in-memory layout is `[6B STATIC_STS_IV][2B VendorID]`.
The 8 bytes are passed through verbatim (`tracker_uwb.c:290-295`), so whichever end has the
convention backwards produces a mismatched STS with no diagnostic — the exact failure mode
`tracker_sts.c:10-12` warns about.

⚠ **RISK (vendor annotates the measurement-sequence setter as out of scope).**
`fira_helper_set_session_measurement_sequence()` carries the `|NSQM33|` annotation in the
vendor header (`fira_helper.h:2531-2543`), the same annotation the header applies throughout
to things it flags as not applicable to QM33 (e.g. `FIRA_PRF_MODE_HPRF`,
`FIRA_MULTI_NODE_MODE_MANY_TO_MANY`, all DL-TDoA entry points). `apply_session_params()`
treats any non-success return from it as fatal (`tracker_uwb.c:308-313`), so if it is
unsupported on this part the entire session start fails at the last of 23 setter calls.
I could not find a legend for `|NSQM33|` in the SDK, so this is flagged as a risk rather
than asserted.

**Unchecked status code.** `fira_helper_init_session()` fills
`struct fbs_session_init_rsp { enum quwbs_fbs_status status_code; uint32_t session_handle; }`
(`.../uwbmac/fbs_helper.h`). `tracker_uwb.c:374-380` checks only the `enum qerr` return and
uses `rsp.session_handle` unconditionally; `rsp.status_code` is never read.

### 6.6 `ff_uwb_on_params()` — GATT payload decode (`tracker_uwb.c:495-519`)

1. `len != 8` → `-EINVAL`; `!stack_ready` → `-EAGAIN` (`:497-502`).
2. Decode (`:505-509`):
   - `peer_address[0] = data[0]`, `peer_address[1] = data[1]` (copied in wire order)
   - `session_id = sys_get_be32(&data[2])`
   - `channel = data[6]`
   - `preamble_index = data[7]`
3. `memcpy(requested_secret, secret, 32)` (`:510`).
4. Log, `k_sem_give(&session_request)`, `return 0` (`:513-518`).

Producer: `TrackerUwbGatt.encodeSessionParams()` (`TrackerUwbGatt.kt:54-65`) — 8 bytes,
`[2B localAddress][4B sessionId big-endian][1B channel][1B preamble]`, with
`require(localAddress.size == 2)`. Values come from `UwbController.openController()`:
`localAddress` = 2 random bytes, `sessionId` = `Random.nextInt()`, `channelNumber` =
`DEFAULT_CHANNEL` = **9**, `preambleIndex` = `DEFAULT_PREAMBLE` = **10**
(`UwbController.kt:88-99, 462, 464`).

The BLE-only build has a stub `ff_uwb_on_params()` in `tracker_ble.c:312-323` that logs and
returns 0.

### 6.7 Phone-side session

`UwbSessionManager.beginTrackerFind()` (`UwbSessionManager.kt:324-345`) → a **fresh**
`UwbController` per find → `TrackerUwbGatt.startRanging()` (`TrackerUwbGatt.kt:77-107`):
GATT write first, then `ctrl.stream(role = Initiator, localAddress = info.localAddress,
peerAddress = TrackerUwbKeys.uwbAddress(secret), sessionId = info.sessionId, sessionKey =
TrackerUwbKeys.stsKey(secret, info.sessionId), channelNumber, preambleIndex)`.

Note that `UwbController.openController()` also mints `sessionKey = Random.nextBytes(8)`
(`UwbController.kt:97`), which the tracker path **discards** in favour of the derived key
(`TrackerUwbGatt.kt:103`).

`UwbController.stream()` builds `UwbRangingParams.Builder(sessionId,
UwbRangingParams.CONFIG_UNICAST_DS_TWR, localUwbAddress, peerUwbAddress)` with
`setComplexChannel(channel, preambleIndex)`, `setSessionKeyInfo(sessionKey)`,
`setRangingUpdateRate(RawRangingDevice.UPDATE_RATE_FREQUENT)` and **no** `setSlotDuration`
(`UwbController.kt:189-212`). `SessionConfig` is chosen by a degrade ladder — tier 2 =
AoA + `ANTENNA_MODE_DIRECTIONAL` + sensor fusion, tier 1 = AoA + sensor fusion, tier 0 =
AoA only — retried down on `onOpenFailed(reason == 3)` (`UwbController.kt:227-267`,
`:395-438`). Trackers never receive a CANCEL envelope (`UwbSessionManager.kt:508-514`).

**Slot duration asymmetry (from the code, both sides).** The firmware pins
`slot_duration_rstu = 2400` (`tracker_uwb.c:403`) while the phone deliberately does not call
`setSlotDuration` and lets the radio default (`UwbController.kt:208-211`). Under static STS
neither end negotiates this, per `tracker_uwb.c:53-56`.

---

## 7. Key derivation

### 7.1 HKDF primitive

Firmware: `hkdf_sha256()` (`tracker_sts.c:37-73`) — PSA
`PSA_ALG_HKDF(PSA_ALG_SHA_256)`, inputs supplied in the order **SALT, SECRET, INFO**
(`:47-61`), salt = `static const uint8_t zero_salt[32]` (`:35`, i.e. 32 zero bytes).

App: `Hkdf.derive(ikm, info, length, salt = ByteArray(32))`
(`C:\Users\Vayun\Documents\code\Modern-Apps\library\src\main\java\com\vayunmathur\library\util\Hkdf.kt:18-33`)
— textbook RFC 5869 extract-then-expand over HMAC-SHA256 with a default 32-byte zero salt.
These agree.

### 7.2 STS key (8 bytes)

| | Firmware | App |
|---|---|---|
| info string | `"com.vayunmathur.findfamily/uwb-sts"` (`tracker_sts.c:27`) | `STS_INFO` same literal (`TrackerUwbKeys.kt:23`) |
| info suffix | `u32be(session_id)`, 4 bytes (`tracker_sts.c:81-84`) | `u32be(sessionId)` (`TrackerUwbKeys.kt:38,62-67`) |
| NUL included? | **No** — `sizeof(sts_info) - 1` (`tracker_sts.c:78,80`) | No — `toByteArray(US_ASCII)` |
| output length | `STS_KEY_LEN` = **8** (`tracker_sts.c:24,86`) | `STS_KEY_LEN` = **8** (`TrackerUwbKeys.kt:21`) |
| IKM | the 32-byte secret | the 32-byte secret (`require(size == 32)`) |

Info is 34 ASCII bytes + 4 = **38 bytes** total. Byte-for-byte identical on both sides.

The firmware writes these 8 bytes into a `uint8_t vupper64[FIRA_VUPPER64_SIZE]` buffer;
`FIRA_VUPPER64_SIZE = FIRA_VENDOR_ID_SIZE(2) + FIRA_STATIC_STS_IV_SIZE(6) = 8`
(`fira_region_params.h:15-20`), so the sizes match exactly. The app passes the same 8 bytes
to `setSessionKeyInfo()` (`UwbController.kt:201`).

### 7.3 UWB short address (2 bytes)

| | Firmware | App |
|---|---|---|
| info string | `"com.vayunmathur.findfamily/uwb-addr"` (`tracker_sts.c:28`) | `ADDR_INFO` same literal (`TrackerUwbKeys.kt:24`) |
| NUL included? | **No** — `sizeof(addr_info) - 1` (`tracker_sts.c:92`) | No |
| output length | `UWB_ADDR_LEN` = **2** (`tracker_sts.c:25`) | 2 (`TrackerUwbKeys.kt:55`) |
| reserved-address nudge | if `{0x00,0x00}` **or** `{0xFF,0xFF}` → `out[1] = 0x01` (`tracker_sts.c:99-101`) | identical condition and action (`TrackerUwbKeys.kt:56-58`) |

Both sides therefore produce `0x00,0x01` from an all-zero derivation and `0xFF,0x01` from an
all-ones one. Byte-for-byte identical.

### 7.4 Endianness of the address conversion

- **Firmware:** the derived 2 bytes are converted to the FiRa 16-bit short address with
  `sys_get_le16(own_address)` (`tracker_uwb.c:350`), i.e. `out[0]` is the **least**
  significant byte. The peer address from the GATT write is converted the same way
  (`tracker_uwb.c:351`).
- **App:** the derived 2 bytes go straight into `UwbAddress.fromBytes(peerAddress)`
  (`UwbController.kt:176`), and the phone's own 2 random bytes into
  `UwbAddress.fromBytes(localAddress)` (`:163`) and, unchanged, onto the wire as GATT bytes
  0..1 (`TrackerUwbGatt.kt:57`).

The two sides therefore agree on the *byte sequence* but the firmware additionally commits
to a little-endian reading of it. Whether that matches `android.ranging.uwb.UwbAddress`'s
own convention cannot be established from this repository — no code here converts a
`UwbAddress` to or from an integer. This is a genuine open interop question, not a comment
contradiction.

⚠ **CONTRADICTION (reserved-address handling).** `TrackerUwbKeys.kt:48-49` says the reserved
addresses are nudged "the same way `UwbController.openController` handles its random
address". `UwbController.openController()` checks **only** `0x0000`
(`UwbController.kt:88-91`), not `0xFFFF`, despite its own comment saying "Avoid the reserved
FiRa addresses 0x00/0xFF" (`:86-87`). `openControlee()` has the same one-sided check
(`:115-118`). So the phone can pick `0xFFFF` as its own address while the tracker's
derivation cannot.

### 7.5 Epoch-id key usage

The same 32-byte secret is used as the HMAC key for epoch ids (`tracker_epoch.c:67`) and as
the HKDF IKM for both UWB derivations (`tracker_sts.c:86,91`). There is no per-purpose
sub-key split above the HKDF `info` label.

---

## 8. Watchdog and failure handling

### 8.1 Watchdog

`watchdog_init()` (`main.c:118-154`), called unconditionally from `main()` (`main.c:194`):

- `struct wdt_timeout_cfg`: `.window = { .min = 0U, .max = WDT_TIMEOUT_MS }` with
  `WDT_TIMEOUT_MS` = **8000** (`main.c:113,121`), `.callback = NULL` (`:124`),
  `.flags = WDT_FLAG_RESET_SOC` (`:125`).
- Device: `DEVICE_DT_GET_OR_NULL(DT_ALIAS(watchdog0))` (`:129`). If absent or not ready:
  `LOG_WRN("no watchdog available; a hang will not self-recover")`, `wdt = NULL`, return
  (`:130-134`) — boot continues unprotected.
- `wdt_install_timeout()` failure → warn, `wdt = NULL`, return (`:136-141`).
- `wdt_setup(wdt, WDT_OPT_PAUSE_HALTED_BY_DBG)` failure → warn, `wdt = NULL`, return
  (`:147-152`).
- `watchdog_feed()` (`:156-161`) is a no-op unless both `wdt != NULL` and
  `wdt_channel >= 0`; the `wdt_feed()` return value is discarded.
- Fed once per loop pass, at the very top (`main.c:216`), i.e. every ~1000 ms against an
  8000 ms window. `CONFIG_WATCHDOG=y` (`prj.conf:68`).

**Coverage note.** The main loop only ever sleeps (`k_msleep(TICK_MS)`) and does bounded
work, and both UWB threads run at *lower* Zephyr priority (5, 6) than main (0). So the
watchdog covers a hang *of the main thread* but a wedged UWB thread does not stop the feed —
which is precisely the scenario `main.c:104-109` describes as the motivation. (The
uncommitted working-tree comment in `uwb_poll_thread` makes the same observation — see
§6.3.1 — but it is not part of the HEAD baseline.)

### 8.2 Boot failure paths (`main()`, `main.c:163-213`)

| Failure | Handling |
|---|---|
| `psa_crypto_init()` != `PSA_SUCCESS` | `LOG_ERR`, **`return 0` from `main()`** (`:171-175`) — the thread exits; no advertising, no watchdog |
| `ff_store_init()` != 0 | `LOG_ERR`, **`return 0`** (`:177-181`) |
| `ff_store_load()` != 0 | `LOG_ERR` only, **continues** with whatever `tracker_state` holds (`:182-185`) |
| `ff_ble_init()` != 0 | `LOG_ERR`, **`return 0`** (`:187-191`) |
| `button_init()` | return value discarded (`:193`); on a board without `sw0` it warns and returns `-ENODEV` (`main.c:70-73`) |
| `watchdog_init()` | no return value; degrades silently (§8.1) |
| `ff_uwb_init()` != 0 | `LOG_ERR` only, **continues** (`:196-201`) |
| provisioned, `ff_ble_set_mode(BEACON)` fails | falls back to `ff_ble_set_mode(FF_BLE_MODE_PAIRING)`, return discarded (`:203-209`) |
| not provisioned | `ff_ble_set_mode(FF_BLE_MODE_PAIRING)`, return discarded (`:210-213`) |

Note that the three `return 0` paths exit `main()` *before* the watchdog is armed, so those
failures leave the board alive but permanently silent with no self-recovery.

### 8.3 Runtime failure paths

- `ff_ble_take_provision_event()` → `ff_ble_set_mode(BEACON)` failure is only logged as "beacon
  will start once the phone disconnects" (`main.c:218-226`) — see §1.3 for why that retry does
  not happen.
- `ff_ble_take_uwb_params()` → `ff_uwb_on_params()` return value **discarded**
  (`main.c:236-239`).
- `ff_ble_refresh_beacon()` return value **discarded** (`main.c:259`), so a persistent
  `-EAGAIN`/`-EIO` is invisible except in the log line the function itself emits.
- `ff_store_save_time()` return value **discarded** (`main.c:267`).
- Button: long press → pairing mode; short press → mute (`BEACON`→`IDLE`) or unmute
  (`IDLE`/`PAIRING`→`BEACON`, only if provisioned) (`main.c:242-254`). Return values
  discarded. `LONG_PRESS_MS` = **2000** (`main.c:29`). Edge detection is
  `GPIO_INT_EDGE_BOTH` on `DT_ALIAS(sw0)` configured `GPIO_INPUT`
  (`main.c:74-83`); a release with `press_started_ms == 0` is ignored (`main.c:55-57`).
- `on_provision_write` persistence failure → `BT_GATT_ERR(BT_ATT_ERR_UNLIKELY)` (0x0E) and
  **no** RAM state update (`tracker_ble.c:222-226`).
- `ff_uwb_start()` failures are consumed by `uwb_session_thread` with `(void)`
  (`tracker_uwb.c:491`); the phone is not told, because the GATT write was already
  acknowledged from the write callback long before the session was attempted.
- There is **no pairing-mode timeout.** `CONFIG_FF_TRACKER_PAIRING_TIMEOUT_S`
  (default 120, range 10..600) is declared at `Kconfig:26-33` and is referenced **nowhere**
  in the sources. Pairing mode, once entered, advertises the unprovisioned service
  indefinitely.

⚠ **CONTRADICTION (pairing-mode window).** `Kconfig:31-33` states the pairing advertisement
"runs … before falling back to the previous mode. Bounds the window in which anything nearby
could claim an already-bound tracker." No such fallback exists in the code and the symbol is
unused.

### 8.4 Logging configuration (`prj.conf:70-83`)

`CONFIG_LOG=y`, `CONFIG_LOG_MODE_DEFERRED=y`, `CONFIG_LOG_BUFFER_SIZE=4096`, plus a second
backend: `CONFIG_USE_SEGGER_RTT=y`, `CONFIG_LOG_BACKEND_RTT=y`,
`CONFIG_SEGGER_RTT_BUFFER_SIZE_UP=4096`. `CONFIG_REBOOT=y` is set (`prj.conf:85`) but
`sys_reboot()` is never called.

Two consequences of deferred mode that matter for diagnosing the failures in this document:

- **All log output is produced by one thread at priority 14.** Derivation in the table in
  §6.3. Anything that monopolises a higher-priority thread stops all logging without stopping
  the main loop or the watchdog feed.
- **`printk()` is not an escape hatch from that.** `CONFIG_LOG_PRINTK` is `default y if
  PRINTK` (`C:\ncs\v3.4.0\zephyr\subsys\logging\Kconfig.processing:8-13`, help text: "printk
  messages are redirected to the logging subsystem") and `CONFIG_PRINTK` is `default y`
  (`.../subsys/debug/Kconfig:145-147`); neither `prj.conf` nor `uwb.conf` sets either symbol.
  So the seven `printk()` progress markers in `ff_uwb_start()` (§6.2) go through the same
  deferred buffer and the same priority-14 thread as every `LOG_INF`, and are lost in exactly
  the scenarios they would be most useful for. They read as a deliberate bypass and are not
  one. Getting synchronous output would need `CONFIG_LOG_MODE_IMMEDIATE`, or
  `CONFIG_LOG_PRINTK=n` to send printk straight to the console.

---

## 9. Vendor glue layer (`firmware/qorvo-uwb`) — as built

Included because §6 depends on it and several of its comments do not match its code.

### 9.1 What is replaced and why (per the code)

`CMakeLists.txt:78-142` compiles our own `qspi`, `qtimer`, `qgpio`, `qplatform`,
`qhal_extras`, `l1_config_storage`, `qmalloc`, `qthread` and `mcps_crypto` backends alongside
the vendor's `qosal` Zephyr sources, `qotp.c`, `qmath.c`, `qplatform` common,
`deca_compat.c`/`deca_interface.c`/`deca_rsl.c`/`dw3000_device.c`, and the DWM3001CDK
`platform_l1_config.c`. It links a prebuilt `arm-cortex-m4-soft_floating` FiRa bundle
(`:46-52`) and adds `-Wl,--allow-multiple-definition` image-wide (`:201`).

The stub claim checks out: `C:\qorvo\Libs\uwb-stack\libs\qhal\src\zephyr\{qspi.c,qtimer.c}`
contain 7 `QERR_ENOTSUP` returns between them.

**Duplicated sources.** `src/qspi_zephyr.c` and `src/qgpio_zephyr.c` are each listed twice
in the same `zephyr_library_sources()` call (`CMakeLists.txt:84,86` and `:93,94`). Harmless
(CMake de-duplicates a target's source list) but it is a leftover.

**Compile definition with no effect.** `CONFIG_QHAL_MAX_GPIO_CALLBACKS=2`
(`CMakeLists.txt:166-168`) is described as "Number of GPIO callback slots in our qgpio
backend's static table". `qgpio_zephyr.c:27` hard-codes `#define MAX_CALLBACKS 2` and never
consults the definition.

**`op_flags` has no effect.** `qplatform_zephyr.c:64-71` sets
`op_flags = QSPI_MASTER | QSPI_MSB_FIRST | QSPI_MISO_SINGLE | QSPI_SET_FRAME_LEN(8)` with a
7-line comment justifying SPI mode 0 over the SDK's `QSPI_CPOL`. In the vendor header
`QSPI_MASTER = BIT_TO_0(0)`, `QSPI_MSB_FIRST = BIT_TO_0(4)` and `BIT_TO_0(n) = (0ul << n)`
(`C:\qorvo\Libs\uwb-stack\libs\qhal\include\qspi.h:50,108` and the `BIT_TO_0` definition), so
the expression evaluates to `8 << 5` = **0x100**. More importantly `qspi_configure()`
overwrites the whole operation word from the devicetree spec and only takes `freq_hz` from
the `qspi_config` (`qspi_zephyr.c:103-104`), so `op_flags` is never read. The documented
mode-0 decision is enacted by the devicetree, not by this field.

### 9.2 SPI backend (`qspi_zephyr.c`)

- One static instance (`:44`); `qspi_open()` rejects a second open (`:58-61`).
- `qspi_configure()`: `spi->cfg = spec->config; spi->cfg.frequency = MIN(config->freq_hz,
  spec->config.frequency)` (`:103-104`) — clamped to the node's `spi-max-frequency`.
- `qspi_transceive()`: a single full-duplex `spi_transceive()` with one tx and one rx buffer,
  passing `NULL` for a set whose buffer pointer is NULL (`:142-144`); then invokes
  `done_cb` synchronously if set (`:163-165`). `LOG_HEXDUMP_DBG` on the first
  `TRACE_XFERS = 4` transfers (`:47,157-161`) — passed `xfer->tx_buf`/`xfer->rx_buf` without
  the NULL checks the `spi_transceive` call itself makes.
- `ff_qorvo_read_dev_id()` (`qplatform_zephyr.c:100-113`): 5-byte full-duplex transfer with
  an all-zero tx (header byte `0x00` = read, file 0 offset 0), returning
  `sys_get_le32(&rx[1])`; expects `0xDECA0302`.

**Frequency numbers.** `CONFIG_QORVO_UWB_SPI_FREQ_SLOW_HZ` = 4 000 000,
`CONFIG_QORVO_UWB_SPI_FREQ_FAST_HZ` = 8 000 000 (`Kconfig:25-39`); the `dw3110` node's
`spi-max-frequency` = 8 000 000 (overlay `:25`). The fast rate equals the node ceiling, so
the clamp at `qspi_zephyr.c:104` never actually clamps.

⚠ **CONTRADICTION (SPI rate before INIT).** The overlay comment says "8 MHz is the DW3000's
safe rate until it leaves INIT state" (overlay `:23-24`). `Kconfig:29-30` says "The DW3000
requires **<= 7 MHz** until it has left INIT state … 4 MHz is what the vendor SDK uses", and
the code starts at 4 MHz (`qplatform_zephyr.c:62`). The overlay's 8 MHz is the *ceiling*, not
the pre-INIT rate, and 8 MHz would violate the limit `Kconfig` states.

### 9.3 GPIO backend (`qgpio_zephyr.c`)

- Flag translation is explicit, per-bit (`:47-96`). The claim at `:12-14` that
  `QGPIO_PULL_UP` is `BIT(2)` where Zephyr's is `BIT(4)` is **correct**
  (`C:\qorvo\Libs\uwb-stack\libs\qhal\include\qgpio.h:98`).
- `translate_irq()` defaults to `GPIO_INT_DISABLE` unless `INTERNAL_QGPIO_INT_ENABLE` is set
  (`:83-85`); edge with both LOW and HIGH → `GPIO_INT_EDGE_BOTH`, else rising if HIGH else
  falling (`:86-94`); level otherwise (`:95`).
- Callback table: 2 slots (`:27,37`), re-registration on the same `gpio_dt_spec` replaces the
  handler (`:183-204`).
- **Silent no-ops:** the vendor's `qplatform_init()` configures `qm33_qspi_config.sck_pin` and
  `.mosi_pin` as `QGPIO_OUTPUT_HIGH` before reset
  (`C:\qorvo\Libs\uwb-stack\libs\qplatform\qm33_qhal_common\src\qplatform.c:182-183`), but
  `qplatform_zephyr.c:57-59` sets `.dev = NULL` for those pins, so `spec_of()` returns NULL
  and `qgpio_pin_configure()` returns `QERR_EINVAL` (`qgpio_zephyr.c:100-105`) — a return the
  vendor ignores. Those pin-idling steps do nothing on this port.

### 9.4 Timer backend (`qtimer_zephyr.c`)

- 2 slots (`:27,37`), built on `k_timer`; `qtimer_id` and `qtimer_config` are `ARG_UNUSED`
  (`:51-52`).
- `qtimer_start()`: `k_timer_start(&q->timer, K_USEC(us), periodic ? K_USEC(us) :
  K_NO_WAIT)` (`:83`) — i.e. `periodic == true` means **repeat**.
- `qtimer_read()` returns microseconds since the last `qtimer_start` (`:105`).

⚠ **CONTRADICTION (deliberate, and documented as such).** The vendor header states
"`@periodic`: true for a one-shot timer, false for a cyclic timer"
(`C:\qorvo\Libs\uwb-stack\libs\qhal\include\qtimer.h:72`). Our implementation inverts that
and says so at `qtimer_zephyr.c:78-82` ("Going by the parameter name rather than that
comment"). This is a knowing divergence from the vendor contract, so if the prebuilt bundle
follows its own header, every timer this backend creates has the wrong repeat behaviour. It
is flagged here because the choice is unverifiable from headers alone and is load-bearing
for `llhw_init`'s idle timer.

### 9.5 Thread backend (`qthread_zephyr.c`)

- 4 static slots (`:30,45`); the caller's stack buffer is **ignored** and
  `k_thread_stack_alloc(MAX(stack_size, 2048), 0)` is used instead (`:68,88`).
- Priority mapping: `K_PRIO_PREEMPT((int)prio)` (`:96`), where `qthread_priority` runs
  `CRITICAL = 0 … IDLE = 6`
  (`C:\qorvo\Libs\uwb-stack\libs\qosal\include\qthread.h:42-50`). The mapping is
  **order-preserving** — both scales run highest-priority-first from 0 — so relative ordering
  among MAC threads is preserved. What it does not do is offset the range: a MAC thread
  requesting `QTHREAD_PRIORITY_CRITICAL` lands at Zephyr preemptible priority **0**, the same
  priority as the main thread, and `QTHREAD_PRIORITY_IDLE` lands at **6**, the same as
  `ff_uwb_sess`. The qosal numbers are used verbatim as Zephyr priority numbers, so MAC
  threads interleave with the application's own threads rather than sitting below them.
- Requires `CONFIG_DYNAMIC_THREAD`, `CONFIG_DYNAMIC_THREAD_ALLOC`,
  `CONFIG_DYNAMIC_THREAD_PREFER_ALLOC` (`uwb.conf:26-28`).

⚠ **CONTRADICTION (why `qmalloc` aligns).** `qmalloc_zephyr.c:49-56` justifies
`k_aligned_alloc(32, ROUND_UP(size, 32))` on the grounds that "`qthread_create()` requires
its caller to supply the thread stack, and the prebuilt uwbmac allocates those through here
… so a MAC thread came up with a garbage return address". `qthread_zephyr.c:10-14,83-88`
exists specifically to *ignore* those buffers, and `uwb.conf:22-25` says the same. The stated
reason for the alignment is therefore obsolete; the alignment itself is harmless.

### 9.6 Allocator (`qmalloc_zephyr.c`)

`qmalloc_internal` → `k_aligned_alloc(32, ROUND_UP(size, 32))` (`:24,57`);
`qrealloc_internal` → plain `k_realloc` (`:68`), which drops the alignment;
`qfree_internal` → `k_free` (`:73`). `allocation_quotas[0] = ~0U` with optional
`CONFIG_MEM_QUOTA_ID1..4` entries that are not configured (`:31-45`), so the stack runs with
a single unbounded quota bounded only by `CONFIG_HEAP_MEM_POOL_SIZE` — 4096 in `prj.conf:50`,
raised to **24576** by `uwb.conf:20`.

### 9.7 `l1_config` storage (`l1_config_storage_zephyr.c`, `l1_config_storage.ld`)

- Two static RAM buffers: `l1_config_storage[2048]` and `l1_config_hash_storage[32]`, both
  `__aligned(8)` (`:48-53`).
- `ff_qorvo_l1_config_use_ram_storage()` assigns the vendor globals `persistent_ram_config`
  and `persistent_ram_config_hash` (`:57-58`). Verified effective: the vendor accessors
  simply return those globals
  (`C:\qorvo\Libs\uwb-stack\config_manager\plugins\l1_config\src\l1_config_custom.c:61-73`),
  and `l1_config_store_to_persistent_memory()` writes through them via `qflash_write`
  (`:112-130` of that file), 32 bytes of SHA-256 for the hash — matching
  `L1_CONFIG_HASH_SIZE = 32`.
- `qflash_write()` (`qhal_extras_zephyr.c:159-187`) bounds-checks
  `[dst_addr, dst_addr+size)` against `[CONFIG_SRAM_BASE_ADDRESS,
  CONFIG_SRAM_BASE_ADDRESS + CONFIG_SRAM_SIZE*1024)` and returns `QERR_ENOTSUP` otherwise,
  then `memcpy`s.

⚠ **CONTRADICTION (three descriptions of one mechanism).**
- `l1_config_storage_zephyr.c:14-19`: "Overriding the accessors doesn't work either, because
  l1_config calls them from inside the same translation unit … What does work: … repointing
  [two ordinary global pointers]". **This matches the code and the vendor source.**
- `l1_config_storage.ld:9-10`: "src/l1_config_storage_zephyr.c **overrides the accessors**
  l1_config reads them through" — the opposite of what that file says it does.
- `qhal_extras_zephyr.c:171-173`: "Both live in **`.data`** here (see l1_config_storage.ld)".
  The `.ld` places the vendor sections in **rodata** (`CMakeLists.txt:184-186`,
  `l1_config_storage.ld:9,15-18`), and the buffers actually used are zero-initialised statics
  (`.bss`), not `.data`.
- The 1004-byte `sizeof(struct l1_config)` figure at `l1_config_storage_zephyr.c:43-46` could
  not be verified from the headers available (`struct l1_config` is only defined in
  `l1_config_internal.h`).

### 9.8 Crypto backend (`mcps_crypto_psa.c`)

- CMAC: `psa_mac_compute(PSA_ALG_CMAC)` with a 16-byte output buffer; 128-bit and 256-bit
  entry points share `cmac_digest()` (`:104-142`).
- CCM*: 4-entry static context pool (`:30,39`), key policy
  `PSA_ALG_AEAD_WITH_AT_LEAST_THIS_LENGTH_TAG(PSA_ALG_CCM, 4)` (`:164`), 128-bit AES.
  `ccm_crypt()` uses the multipart API with `CCM_NONCE_LEN = 13` (`:27,221`) and
  `PSA_ALG_AEAD_WITH_SHORTENED_TAG(PSA_ALG_CCM, mac_len)` (`:211`). `mac_len == 0` is an
  explicit `QERR_ENOTSUP` (`:206-209`).
- ECB: 4-entry pool, `PSA_ALG_ECB_NO_PADDING`, 128-bit; input length must be a multiple of
  16 (`:352-354`).
- `mcps_crypto_get_random()` returns `psa_generate_random()` output and **returns 0 on
  failure** after logging (`:90-100`) — the failure is not propagated to the caller.
- Required PSA algorithms are pulled in by `Kconfig:11-16` of the module.

⚠ **CONTRADICTION (Qorvo log-level scale).** `firmware/qorvo-uwb/Kconfig:46` documents
`CONFIG_QORVO_UWB_QLOG_LEVEL` as "0=off 1=error **2=info** 3=debug 4=trace" and defaults it
to 2. The vendor header defines `QLOG_LEVEL_NONE 0, ERR 1, **WARN 2**, INFO 3, DEBUG 4`
(`C:\qorvo\Libs\uwb-stack\libs\qosal\include\qlog.h:15-19`). The default of 2 is therefore
**warning**, not info, and levels 3/4 are info/debug rather than debug/trace.

### 9.9 Compatibility shims

- `include-compat/zephyr.h` → `<zephyr/kernel.h>`; `include-compat/random/rand32.h` →
  `<zephyr/random/random.h>`.
- `ff_qorvo_compat.h` is force-included into the Qorvo library only
  (`CMakeLists.txt:172-175`) and aliases the Zephyr 3.1 `log_msg2`/`z_log_msg2_*`/
  `Z_LOG_MSG2_*` spellings onto their current names, plus `CONFIG_LOG_DOMAIN_ID` = 0
  (`ff_qorvo_compat.h:35-56`).

⚠ **CONTRADICTION (shim scoping).** `include-compat/zephyr.h:6-8` says the directory is on
the include path "for that library alone, so nothing in the FindFamily firmware can
accidentally depend on the old spelling", and `random/rand32.h:6` repeats it.
`CMakeLists.txt:57-74` puts `include-compat` (and `${ZEPHYR_BASE}/include/zephyr`) into
`zephyr_include_directories()`, which is **global**, and the comment right there says so
explicitly: "These have to be global rather than scoped to this library because the
FindFamily firmware includes uwbmac/fira_helper.h". The scoping the shim headers claim does
not exist.

### 9.10 Devicetree

`boards/decawave_dwm3001cdk_nrf52833.overlay` enables `&spi3` and adds `dw3110@0`:

| Property | Value | Line |
|---|---|---|
| `compatible` | `"qorvo,dw3110"` | `:21` |
| `reg` | `<0>` | `:22` |
| `spi-max-frequency` | `<8000000>` | `:25` |
| `irq-gpios` | `<&gpio1 2 (GPIO_ACTIVE_HIGH \| GPIO_PULL_DOWN)>` — P1.02 | `:26` |
| `rstn-gpios` | `<&gpio0 25 GPIO_OPEN_DRAIN>` — P0.25, **not** `GPIO_ACTIVE_LOW` | `:35` |
| `wakeup-gpios` | `<&gpio1 19 GPIO_ACTIVE_HIGH>` — P1.19 | `:36` |

`qplatform_zephyr.c` reads the node with `DT_NODELABEL(dw3110)` and `#error`s if it is not
`okay` (`:23-28`), taking `rstn`/`irq` from the node and `cs` from `DT_BUS(UWB_NODE)`
(`:30-32`). `wakeup-gpios` is declared `required: false` in the binding and is **not read by
any code** — the wake path uses a chip-select pulse instead
(`qhal_extras_zephyr.c:60-88`: `GPIO_OUTPUT_ACTIVE`, `qtime_usleep(400)`,
`gpio_pin_set_dt(cs, 0)`, `qtime_usleep(500)`).

The `rstn` polarity reasoning at overlay `:27-34` is consistent with the vendor code:
`qplatform_uwb_reset()` asserts with `QGPIO_OUTPUT_LOW` then releases with `QGPIO_INPUT`
(`C:\qorvo\Libs\uwb-stack\libs\qplatform\qm33_qhal_common\src\qplatform.c:280-282`), and
`translate_config()` maps `INTERNAL_QGPIO_OUTPUT_INIT_LOW` → `GPIO_OUTPUT_INIT_LOW`
(`qgpio_zephyr.c:59-60`), a logical level — so omitting `GPIO_ACTIVE_LOW` does make logical
and physical low agree.

⚠ **CONTRADICTION (documented board).** `CMakeLists.txt:5` gives the build command as
`west build -b nrf52833dk/nrf52833 firmware/findfamily-tracker`, a board for which no overlay
exists (so no `dw3110` node, so the UWB build `#error`s). `uwb.conf:4` uses
`-b decawave_dwm3001cdk/nrf52833`, which is the board the overlay filename matches.

---

## 10. Summary of internal inconsistencies

Ordered by how much they change observable behaviour.

**Behavioural**

1. **40-byte premature commit** (§3.1) — a 48-byte provisioning blob delivered as a long
   write commits at the 40-byte boundary with `unix_seconds = 0` and then rejects its own
   final chunk. `tracker_ble.c:210-214` vs. `tracker_ble.c:197-201`.
2. **Re-binding is impossible** (§4.3) — `ff_store_clear()` is never called and
   `on_provision_write` rejects everything once provisioned, contradicting `main.c:8-11` and
   `main.c:206-208`.
3. **`connect_adv` is never (re)started after a failure or a connection** (§1.3) — the whole
   `pending_readvertise` mechanism is dead, and its **trigger and recovery are independently
   broken**, so fixing either alone changes nothing. Trigger: `ff_ble_set_mode(BEACON)` returns
   0 even when `connect_adv` fails (`tracker_ble.c:525-528`), leaving
   `desired_mode == current_mode`, so `on_disconnected` never raises the event
   (`:139-141`). Recovery: the handler calls `ff_ble_set_mode(ff_ble_desired_mode())`
   (`main.c:230`), which returns 0 at the `mode == current_mode` guard (`:456-460`) before any
   restart. Both ends test the same condition from opposite directions, so there is no state in
   which the mechanism fires *and* does something. Zephyr v3.4.0 has no host-side auto-resume
   (`adv_resume`/`BT_ADV_PERSIST`: zero occurrences in `bluetooth/host`), so nothing else
   revives the set either.
4. **No pairing-mode timeout** (§8.3) — `CONFIG_FF_TRACKER_PAIRING_TIMEOUT_S` is declared and
   never referenced, contradicting `Kconfig:31-33`.
5. **`ff_uwb_stop()` leaves the poll thread polling a stopped MAC** (§6.3) — `poll_running`
   is never cleared, so nothing sequences the poll thread against `uwbmac_stop()`. What
   happens in that state is unspecified by the vendor header; the `start_polling()` comment
   calls it firmware-wedging but that prose is unreliable (§6.3).
6. **The poll thread preempts its own creator and the session start depends on it yielding**
   (§6.3) — `ff_uwb_start()` runs on the priority-6 session thread (`tracker_uwb.c:491`) and
   creates the poll thread at priority 5 with `K_NO_WAIT` (`:177-179`, `:440`), so
   `fira_helper_start_session()` (`:443`) is reached only once the poll loop yields or blocks.
   At HEAD the loop body has no sleep and no yield (`:158-161`), so if
   `uwbmac_poll_events` does not block, the session start is never reached at all — and the
   resulting symptom (logging dead, main loop alive and feeding the watchdog) is exactly what
   a spinning priority-5 thread produces. Defect regardless of which way the vendor call
   behaves; liveness should not depend on it.
7. **BLE identity address is never persisted or pinned** (§2.1/§2.3) — no `settings_load()`,
   no `CONFIG_BT_SETTINGS`, while the app stores the bind-time address string forever and
   hard-asserts `ADDRESS_TYPE_RANDOM`.
8. **UWB session teardown has no trigger** (§6.2) — nothing stops a session on disconnect,
   error, or timeout.

**Prose contradicting code**

9. Beacon documented as connectable in three places (`tracker_ble.c:12-17`,
   `ff_tracker.h:85-86`, `TrackerBle.kt:30`); the code makes it non-connectable (§1.3).
10. Two mutually exclusive accounts of where the ranging timing constants came from —
    "FiRa/Qorvo defaults … best available guess" (`tracker_uwb.c:13-18`) vs. "read off the
    wire" from Android HAL UCI logs (`tracker_uwb.c:52-67`) (§6.5).
11. `uwbmac_poll_events` is claimed non-blocking by the **uncommitted** working-tree comment
    while the vendor header says it blocks for the timeout (`uwbmac.h:335-336`). Recorded in
    §6.3.1, not counted as an as-built finding — that comment does not exist at HEAD. Note
    also that the header's own availability precondition for this call cannot be evaluated
    against this delivery's `uwbmac_init()` signature (§6.3), so the header does not settle
    the question either way.
12. `qmalloc_zephyr.c:49-56` justifies its alignment by a mechanism that
    `qthread_zephyr.c:83-88` exists to bypass (§9.5).
13. `l1_config_storage.ld:9-10` says accessors are overridden;
    `l1_config_storage_zephyr.c:14-19` says that specifically does not work. And
    `qhal_extras_zephyr.c:171-173` says the buffers live in `.data`; they are `.bss` and the
    vendor sections are in rodata (§9.7).
14. `zephyr.h:6-8` and `random/rand32.h:6` claim the compat include path is scoped to the
    Qorvo library; `CMakeLists.txt:67-74` makes it global and says so (§9.9).
15. `qorvo-uwb/Kconfig:46` states the wrong Qorvo log-level scale — the default of 2 is WARN,
    not INFO (`qlog.h:15-19`) (§9.8).
16. `qtimer_zephyr.c:78-83` knowingly inverts the vendor's documented `periodic` sense
    (`qtimer.h:72`) (§9.4).
17. Overlay `:23-24` says 8 MHz is the pre-INIT-safe SPI rate; `Kconfig:29-30` says the limit
    is 7 MHz and the code uses 4 MHz (§9.2).
18. `TrackerUwbKeys.kt:48-49` says the reserved-address nudge matches
    `UwbController.openController`, which only checks `0x0000`, not `0xFFFF`
    (`UwbController.kt:88-91`) (§7.3).
19. `prj.conf:18-19` and `TrackerBle.kt:24-31` state the beacon is 35 bytes on air; it is
    53 (§1.2).
20. `prj.conf:13-16` sets `CONFIG_BT_MAX_CONN=2` to prevent the `-ENOMEM` that the retry
    mechanism in §1.3 exists to recover from — both the fix and the workaround are present.
21. `CMakeLists.txt:5` documents a board with no overlay (§9.10).

**Latent / type issues**

22. `sp.phr_data_rate = FIRA_PRF_MODE_BPRF` — wrong enum, coincidentally the right value
    (§6.5).
23. `vupper64` byte order: code comment says `[2B VendorID][6B IV]`, the vendor union is
    `[6B IV][2B VendorID]` (§6.5).
24. `fira_helper_set_session_measurement_sequence()` is `|NSQM33|`-annotated yet treated as
    fatal (§6.5).
25. `rsp.status_code` from `fira_helper_init_session()` is never checked (§6.5).
26. `sts_config` (Static STS) is relied on as a struct zero value and never pushed to the
    session (§6.5).
27. The UWB characteristic declares `NOTIFY` and has a CCC; no code ever notifies (§3).
28. `mcps_crypto_get_random()` returns 0 on RNG failure without signalling it (§9.8).
29. `qplatform_init()`'s pre-reset idling of SCK/MOSI silently no-ops because those
    `qgpio.dev` pointers are NULL (§9.3).
30. `CONFIG_QHAL_MAX_GPIO_CALLBACKS` is defined but not consulted (§9.1).
31. `src/qspi_zephyr.c` and `src/qgpio_zephyr.c` are each listed twice in
    `zephyr_library_sources()` (§9.1).
32. `battery_percent()` is a hard-coded 100 (`main.c:95-98`), and
    `ff_ble_set_mode(BEACON)` passes a separate hard-coded 100 (`tracker_ble.c:504`).
33. `-Wl,--allow-multiple-definition` is applied image-wide (`CMakeLists.txt:201`), which the
    comment there acknowledges is blunt.
34. Firmware slot duration is pinned to 2400 RSTU while the phone deliberately leaves
    `setSlotDuration` unset (§6.7); under static STS neither end negotiates it.
35. The seven `printk()` markers in `ff_uwb_start()` read as a bypass of the log subsystem but
    are routed through it: `CONFIG_LOG_PRINTK` is `default y if PRINTK` and the build is
    `CONFIG_LOG_MODE_DEFERRED`, so they are deferred through the same priority-14 thread as
    every `LOG_INF` and are lost whenever it is starved (§6.2, §8.4).