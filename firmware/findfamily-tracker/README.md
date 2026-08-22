# FindFamily UWB tracker firmware

Zephyr / nRF Connect SDK firmware for a FindFamily item tracker. Target: **Qorvo
DWM3001CDK** — a DWM3001C module (Nordic **nRF52833** + Qorvo **DW3110**) on a dev kit
with an on-board J-Link OB.

What it does today: binds to the phone over BLE GATT and broadcasts a rotating,
unlinkable epoch id so any FindFamily phone that walks past can report the tracker's
location back to its owner. UWB precision finding is written but **not compiled** — see
[UWB](#uwb-precision-finding) below.

The wire formats here are not local decisions; they mirror the phone. See
`findfamily/src/main/java/com/vayunmathur/findfamily/tracker/TrackerBle.kt` (the contract),
`TrackerProtocol.kt` (epoch ids) and `TrackerUwbKeys.kt` (session material), plus
`FINDFAMILY_UWB_TRACKER_DESIGN.md` and `FINDFAMILY_UWB_TRACKER_HARDWARE.md` at the repo
root. Changing a format means changing it in both places at once.

> This directory is invisible to the repo's builds: `settings.gradle.kts` uses an explicit
> `include()` list, `Cargo.toml` an explicit `members` list, and CI discovers modules by
> looking for `build.gradle.kts`. Nothing here needs excluding anywhere.

---

## Layout

| File | Role |
|---|---|
| `src/ff_tracker.h` | Shared types + the wire-format constants that mirror the phone |
| `src/main.c` | Mode state machine, button handling, once-a-second beacon refresh |
| `src/tracker_store.c` | NVS persistence: user id, secret, time base |
| `src/tracker_epoch.c` | `epochId = HMAC-SHA256(secret, "fftrk1" ‖ u64be(epoch))[..16]`, time base |
| `src/tracker_ble.c` | Two advertising modes + the GATT server |
| `src/tracker_sts.c` | HKDF-SHA256 STS key + UWB address (`CONFIG_FF_TRACKER_UWB`) |
| `src/tracker_uwb.c` | FiRa responder session on the DW3110 (`CONFIG_FF_TRACKER_UWB`) |

Crypto goes through **PSA Crypto** (nrf_security/mbedTLS) rather than anything
hand-rolled, because the epoch id has to be byte-identical to the Kotlin
(`TrackerProtocol.epochId`) and Rust (`tracker_epoch_id`) implementations. A one-byte
disagreement doesn't fail loudly — it just means no sighting ever resolves.

---

## Toolchain

```powershell
winget install NordicSemiconductor.nrfutil
nrfutil install toolchain-manager
nrfutil toolchain-manager install --ncs-version v3.4.0
```

Plus **SEGGER J-Link** (from segger.com) for the on-board probe. Install it even if the
kit already enumerates: without the SEGGER driver the probe comes up as
`BULK interface ... Status: Error` and nothing can flash it.

Then fetch the SDK **outside this repo** (~10 GB; `release.sh` requires a clean tree and
runs `git add .`, so a west workspace inside the repo would be a problem):

```powershell
mkdir C:\ncs\v3.4.0
nrfutil toolchain-manager launch --ncs-version v3.4.0 -- `
  west init -m https://github.com/nrfconnect/sdk-nrf --mr v3.4.0 C:/ncs/v3.4.0
cd C:\ncs\v3.4.0
nrfutil toolchain-manager launch --ncs-version v3.4.0 -- west update --narrow -o=--depth=1
```

## Build and flash

The board is supported upstream as `decawave_dwm3001cdk/nrf52833`, so no custom board
definition is needed. Build from inside the west workspace, with the build directory
outside the repo:

```powershell
cd C:\ncs\v3.4.0
nrfutil toolchain-manager launch --ncs-version v3.4.0 -- `
  west build -b decawave_dwm3001cdk/nrf52833 -d C:/ncs/ff-build `
  <path-to-repo>/firmware/findfamily-tracker --pristine

nrfutil toolchain-manager launch --ncs-version v3.4.0 -- west flash -d C:/ncs/ff-build
```

Logs come out of the on-board J-Link CDC UART at 115200 (COM4 on this machine).

---

## Behaviour

**On boot:** loads persisted state. Provisioned *and* time-synced → beacon mode.
Otherwise → pairing mode, so a fresh board is bindable without touching the button.

**Button** (the kit's Button 1, `sw0`):
- long press (≥ 2 s) → pairing mode for `CONFIG_FF_TRACKER_PAIRING_TIMEOUT_S`, then back to
  beaconing. This does **not** re-bind: a provisioned tracker refuses provisioning writes
  (the anti-theft lock), and nothing unbinds one today — `ff_store_clear()` has no caller.
- short press → mute/unmute the beacon

**Advertising** uses two modes on one state machine, arranged so only the phone's beacon
scanner needed changing:

- **Pairing** — *legacy* connectable advertising of the unprovisioned service
  (`…2f01…`). Flags + a 128-bit UUID is 21 bytes, comfortably inside the legacy budget,
  so `TrackerProvisioner.unprovisioned()` still finds it with default `ScanSettings`.
- **Beacon** — *extended* **non-connectable** advertising of `[16B epochId][1B battery%]`
  as service data under `…2f00…`. This one **cannot** be legacy: the AD structure costs
  `1B len + 1B type + 16B UUID + 17B payload = 35 bytes` against a 31-byte limit, and the
  UUID can't be shortened because the phone looks the service data up by it. Secondary PHY
  stays at 1M so the phone needs no coded-PHY scan config. Connectability is a **third,
  legacy, dataless set** running alongside it, so the owner can still write UWB session
  params after binding — a connectable extended set cannot carry advertising data at all,
  which is why the two are separate. See `beacon_param` in `src/tracker_ble.c`.

**Time.** The board has no battery-backed RTC, but `epochId` needs `unix_seconds / 900`,
so the provisioning blob carries a timestamp and time is kept as
`unix_at_provision + k_uptime_get()` (LFCLK-backed, survives sleep). The current time is
re-persisted to NVS once per epoch, so a reset costs at most one epoch of drift rather
than the whole time base. A tracker with **no** time base refuses to beacon and returns to
pairing mode instead of broadcasting ids nobody is searching for.

The owner only searches `TrackerProtocol.recentEpochIds(back = 8)` ≈ 2.25 h, so a tracker
left unpowered long enough for its persisted time to go stale needs re-provisioning to
resync.

---

## Bench verification without the phone

The log prints the live epoch id every second:

```
<inf> ff_ble: epoch=1985934 id=96592d51... batt=100%
```

Cross-check it against an independent computation from the same secret and timestamp —
`epochId = HMAC-SHA256(secret, "fftrk1" ‖ u64be(epoch))[..16]`. This separates "the
firmware's crypto is wrong" from "the phone isn't hearing the advertisement", which
otherwise look identical.

---

## UWB precision finding

`src/tracker_sts.c` and `src/tracker_uwb.c` build under `CONFIG_FF_TRACKER_UWB`, which
needs the licensed Qorvo **DW3_QM33 SDK**. It is not vendored here; only the glue is, in
`firmware/qorvo-uwb/` (a Zephyr module). Point two environment variables at an extracted
copy and build with the `uwb.conf` fragment:

```powershell
$env:QORVO_SDK_DIR   = "C:/qorvo"        # contains Libs/uwb-stack
$env:QORVO_DRIVER_DIR = "<...>/Drivers/API/Shared/dwt_uwb_driver"

cd C:\ncs\v3.4.0
nrfutil toolchain-manager launch --ncs-version v3.4.0 -- `
  west build -b decawave_dwm3001cdk/nrf52833 -d C:/ncs/ff-uwb-build `
  <repo>/firmware/findfamily-tracker --pristine `
  -- "-DEXTRA_CONF_FILE=uwb.conf" `
     "-DZEPHYR_EXTRA_MODULES=<repo>/firmware/qorvo-uwb"
```

Quote the `-D` arguments: PowerShell otherwise splits `uwb.conf` at the dot.

### State: the stack comes up; ranging does not work yet

Verified on hardware:
- the image builds and links clean and BLE crowd-finding is unaffected — the beacon keeps
  working in the UWB image;
- the DW3110 answers a direct device-id read over SPI with **`0xdeca0302`**, logged at
  startup. That clears the wiring, the `dw3110` devicetree node, chip-select handling and
  the SPI mode;
- `qplatform_init()` now succeeds and the Qorvo stack initialises, so ranging has been
  attempted end to end against a phone. It fails: the phone reports UCI RangingStatus
  `0x21` (RX timeout) on every round for its whole 10 s and then drops the controlee, with
  nothing logged on the tracker side.

That failure is being worked through against `docs/DISCREPANCIES.md`, which orders the
candidates and cites each one. Nothing in the current tree has been re-verified on hardware
since those fixes landed, so treat FiRa interop as unproven.

### What the glue replaces, and why

Qorvo ships Zephyr backends but they cannot be used as they are:

| Vendor file | Problem | Replacement |
|---|---|---|
| `qhal/src/zephyr/qspi.c` | every function returns `QERR_ENOTSUP` — a placeholder | `src/qspi_zephyr.c` |
| `qhal/src/zephyr/qgpio.c` | defines `gpio_is_ready_dt`/`gpio_add_callback_dt`, which Zephyr now provides | `src/qgpio_zephyr.c` |
| `qplatform/qm33_qhal_zephyr/qplatform.c` | treats `spi_config.cs` as a pointer (by value since Zephyr 3.5); requires a `dw35720` node label | `src/qplatform_zephyr.c` |
| `qhal/src/qm33/qpwr.c`, `qhal/src/qrtc.c` | same, or route through `persistent_time`, which is nrfx-only | `src/qhal_extras_zephyr.c` |
| `qosal/src/zephyr/qmalloc.c` | uses libc malloc, which collides with picolibc's in this build | `src/qmalloc_zephyr.c` (k_malloc) |
| `Src/UWB/mcps_crypto.c` | built on Nordic's nrf_crypto, a second crypto provider | `src/mcps_crypto_psa.c` (PSA) |

`include-compat/` additionally papers over qosal being written against Zephyr 3.1: the
pre-3.0 unprefixed include paths, and the internal `log_msg2` logging symbols renamed in
Zephyr 3.2. That directory is the most likely thing to break on an NCS upgrade.

The vendor's DWM3001CDK calibration code (`platform_l1_config.c`) is compiled as-is — it
reads this module's factory antenna delays and crystal trim out of the DW3110's OTP, and
those values are what make ranging accurate.

### Interop is still the open risk

Even once the stack initialises, matching Android's `CONFIG_UNICAST_DS_TWR` against a
hand-configured responder means aligning STS, RFRAME format, and ranging-round and block
timing. Under static STS the timing parameters are configured identically on both ends
rather than negotiated, so `SLOT_DURATION_RSTU`, `BLOCK_DURATION_MS` and
`ROUND_DURATION_SLOTS` in `src/tracker_uwb.c` are the first things to vary if the phone
will not range.
