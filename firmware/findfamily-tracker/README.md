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
- long press (≥ 2 s) → pairing mode, to re-bind or to resync the clock
- short press → mute/unmute the beacon

**Advertising** uses two modes on one state machine, arranged so only the phone's beacon
scanner needed changing:

- **Pairing** — *legacy* connectable advertising of the unprovisioned service
  (`…2f01…`). Flags + a 128-bit UUID is 21 bytes, comfortably inside the legacy budget,
  so `TrackerProvisioner.unprovisioned()` still finds it with default `ScanSettings`.
- **Beacon** — *extended* connectable advertising of `[16B epochId][1B battery%]` as
  service data under `…2f00…`. This one **cannot** be legacy: the AD structure costs
  `1B len + 1B type + 16B UUID + 17B payload = 35 bytes` against a 31-byte limit, and the
  UUID can't be shortened because the phone looks the service data up by it. Secondary PHY
  stays at 1M so the phone needs no coded-PHY scan config, and it stays connectable so the
  owner can still write UWB session params after binding.

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

`src/tracker_sts.c` and `src/tracker_uwb.c` are **guarded by `CONFIG_FF_TRACKER_UWB`,
default `n`, and have never been compiled.** They call into Qorvo's DW3xxx driver, which
is licensed and not vendored here. To enable:

1. Obtain the Qorvo DW3xxx driver / FiRa stack and add it as a Zephyr module (a `west.yml`
   entry or `ZEPHYR_EXTRA_MODULES`), so `deca_device_api.h` is on the include path.
2. Add a devicetree overlay binding the driver to `spi3` and the module's IRQ/RST/WAKEUP
   lines. The board's `spi3` node already exists for the DW3000.
3. Build with `-DCONFIG_FF_TRACKER_UWB=y`.

Expect this to need iteration. The uncertain part isn't the key derivation — that has
fixed vectors on both sides — it's matching Android's `CONFIG_UNICAST_DS_TWR` against a
hand-configured DW3110 responder: STS mode, RFRAME/packet format, and ranging-round and
block timing all have to line up. That is exactly why it is staged behind BLE rather than
blocking it.
