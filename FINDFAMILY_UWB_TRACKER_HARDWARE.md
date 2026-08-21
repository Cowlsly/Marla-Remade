# FindFamily Custom UWB Tracker — Hardware

Companion to `FINDFAMILY_UWB_TRACKER_DESIGN.md`.

> **The bench target is a Qorvo DWM3001CDK, not the custom board below.** The DWM3001C
> module pairs a DW3110 with an **nRF52833**, whereas the BOM and pinmap in this document
> specify a bare **nRF52840**. The two differ in ways that matter if you cross-read them:
> the nRF52833 has 512 kB flash / 128 kB RAM against the nRF52840's 1 MB / 256 kB, and it
> has **no USB peripheral**, so USB-DFU (§3 J1, §4) does not exist on the dev kit — it is
> flashed over the on-board J-Link. Everything in §5 (the firmware contract) applies to
> both; §1–§4 and §6–§10 describe the eventual custom board only. Firmware lives in
> `firmware/findfamily-tracker/`.

Physical design for the DIY
tracker: **bare Qorvo DW3110 (UWB) + bare Nordic nRF52840 (MCU/BLE)**, small
**LiPo + USB-C** charging, with a **piezo buzzer, pairing/mute button, and
accelerometer**. Bare-chip path chosen deliberately — we own all RF and
regulatory work (see §7, §8).

The firmware contract (BLE UUIDs, beacon/provisioning/UWB byte formats, epoch-id
derivation) mirrors the app exactly — see `findfamily/.../tracker/TrackerBle.kt`,
`TrackerProtocol.kt`, `TrackerUwbKeys.kt`, and `TrackerUwbGatt.kt`.

---

## 1. Block diagram

```
                         ┌───────────────────────────────┐
        USB-C  ─────────►│ CC 5.1k ×2 │ ESD │  charger IC │
        (5V, D±)         │            │     │  (BQ25100)  │
             │           └──────┬─────┴──────────┬────────┘
             │ D±                │ VBAT (3.0–4.2V)│ charge
             ▼                   ▼                ▼
        ┌─────────┐      ┌───────────────┐   ┌────────┐
        │nRF52840 │      │ buck-boost     │   │ LiPo   │
        │ USB     │      │ TPS63802 →3.3V │   │100-250 │
        └─────────┘      └──────┬────────┘   │  mAh   │
                                │ 3.3V rail   └────────┘
              ┌─────────────────┼───────────────────────┐
              ▼                 ▼                        ▼
        ┌───────────┐   ┌───────────────┐        ┌──────────────┐
        │ nRF52840  │   │   DW3110      │        │ peripherals  │
        │ (BLE 2.4G)│◄──┤ SPI + IRQ/RST │        │ piezo+PAM8904│
        │  32MHz    │   │  38.4MHz XTAL │        │ LIS2DH12     │
        │  32.768kHz│   │  UWB antenna  │        │ button       │
        │ BLE ant.  │   │  bulk cap     │        └──────────────┘
        └───────────┘   └───────────────┘
```

Two independent RF front-ends: **2.4 GHz** (BLE, off nRF52840) and **6.5/8 GHz**
(UWB, off DW3110). Keep them separated/orthogonal (see §7).

---

## 2. Power tree

LiPo peaks at 4.2 V, above the nRF52840's 3.6 V absolute max, so **neither chip
runs directly off the battery** — a single regulated 3.3 V rail feeds both.

| Stage | Part | Key values / notes |
|---|---|---|
| USB-C sink | USB-C recept. | CC1/CC2 → 5.1 kΩ to GND (advertise sink); ESD array on D+/D-/VBUS |
| Charger | TI **BQ25100** (or MCP73831) | I_charge ≈ 0.5C via ISET resistor; thermal-limited; STAT → GPIO |
| Battery | LiPo pouch **100–250 mAh** | 3.0–4.2 V; size for form factor + UWB burst headroom |
| Regulator | TI **TPS63802** buck-boost | 3.3 V out, ~11 µA Iq, holds 3.3 V from 4.2→2.7 V in; ≥1 A cap |
| Bulk decap | 100–470 µF + 4.7 µF + 100 nF | at DW3110 supply — absorbs UWB TX spikes (~50–130 mA) |
| VBAT sense | 2× 1 MΩ divider + 100 nF | → nRF52840 SAADC (AIN); feeds beacon battery byte |

Why **buck-boost** not buck: a buck can't hold 3.3 V once the LiPo sags below
~3.4 V; UWB must keep working as the battery drains.

nRF52840 uses its **internal DC/DC** (external inductors per Nordic PS) off the
3.3 V rail for best efficiency. DW3110 uses its internal regulators (decoupling +
DC/DC inductor per the DW3000 datasheet / Qorvo DWM3000 reference).

---

## 3. Bill of materials (core)

| Ref | Part | Function |
|---|---|---|
| U1 | Nordic **nRF52840** (aQFN73) | MCU, BLE 2.4 GHz, USB, runs Qorvo FiRa stack + HKDF beacon |
| U2 | Qorvo **DW3110** | UWB (FiRa, 802.15.4z, ch 5/9), SPI peripheral |
| U3 | TI **TPS63802** | 3.3 V buck-boost |
| U4 | TI **BQ25100** | Li-ion charger |
| U5 | ST **LIS2DH12** | 3-axis accel, motion wake (~2 µA) |
| U6 | **PAM8904** | piezo boost driver (loud "find" tone) |
| Y1 | 32 MHz crystal | nRF52840 HF (RF) |
| Y2 | 32.768 kHz crystal | nRF52840 LF (RTC / low-power) |
| Y3 | **38.4 MHz** crystal | DW3110 reference (ToF accuracy — tight tolerance + trim) |
| BZ1 | Piezo transducer | audible find + anti-stalk beep |
| SW1 | Tactile switch | long = pair mode, short = mute |
| ANT1 | 2.4 GHz antenna | PCB IFA or chip (Johanson 2450AT) |
| ANT2 | UWB antenna | ch 9 / 8 GHz (Qorvo DWM3000 reference antenna) |
| J1 | USB-C receptacle | charge + USB-DFU |
| J2 | SWD header (SWDIO/SWDCLK) | programming/debug |
| — | LiPo 100–250 mAh | battery |

Matching networks, decoupling, DC/DC inductors, and ESD per each device's
reference design.

---

## 4. nRF52840 ↔ DW3110 wiring (SPI + control)

DW3110 is an SPI peripheral to the nRF52840 SPIM. Suggested pin map (adjust to
routing):

| Signal | nRF52840 | DW3110 | Notes |
|---|---|---|---|
| SPI SCK | P0.04 | SPICLK | up to ~36 MHz |
| SPI MOSI | P0.05 | SPIMOSI | |
| SPI MISO | P0.06 | SPIMISO | |
| SPI CSn | P0.07 | SPICSn | active low |
| IRQ | P0.08 | IRQ | ranging/event interrupt → GPIOTE |
| RSTn | P0.11 | RSTn | open-drain reset (don't drive high) |
| WAKEUP | P0.12 | WAKEUP | wake from deep-sleep before SPI |
| EXTON (opt) | P0.13 | EXTON | power-sequencing/PA enable if used |

Peripherals:

| Signal | nRF52840 | Device | Notes |
|---|---|---|---|
| I²C SDA/SCL | P0.26 / P0.27 | LIS2DH12 | or SPI; keep off RF pins |
| Accel INT1 | P0.14 | LIS2DH12 INT1 | motion wake (GPIOTE) |
| Button | P0.15 | SW1 → GND | internal pull-up; RC debounce |
| Piezo drive | P0.16 (PWM) | PAM8904 | EN + PWM for tone |
| Charger STAT | P0.17 | BQ25100 STAT | optional charge LED/logic |
| VBAT sense | P0.28 / AIN4 | divider | SAADC |
| USB D+/D- | D+/D- (dedicated) | J1 | USB-DFU |
| SWD | SWDIO/SWDCLK (dedicated) | J2 | flashing |
| HF xtal | XC1/XC2 | Y1 32 MHz | |
| LF xtal | P0.00/P0.01 (XL1/XL2) | Y2 32.768 kHz | |

DW3110 38.4 MHz crystal (Y3) on its XTAL pins with trim caps; treat as a
calibrated part (§8).

---

## 5. Firmware ↔ hardware contract (mirror of `TrackerBle`)

The firmware must implement, over these radios:

**BLE advertising (crowd-finding)** — service `6b1d2f00-4b3a-4c7e-9a10-1f2e3d4c5b6a`,
service-data payload:
```
[16B epochId][1B battery%]
```
`epochId = HMAC-SHA256(secret, "fftrk1" || u64_be(epoch))[..16]`,
`epoch = unix_seconds / 900` (15-min rotation). Matches `TrackerProtocol`.

This must be sent as a **BLE 5 extended advertisement**: the AD structure is
`1B length + 1B type + 16B UUID + 17B payload = 35 bytes`, past the 31-byte legacy
limit, and the full UUID cannot be shortened because the phone looks the service data up
by it. Pairing mode below stays legacy (21 bytes) so an unprovisioned tracker is
discoverable with default scan settings. See `src/tracker_ble.c`.

**BLE binding (GATT)** — service `…2f01…` (advertised only while unprovisioned;
the characteristics stay registered for the device's life so the owner can write UWB
params to a bound tracker), characteristics:
- `…2f02…` (write): provisioning blob
  ```
  [8B trackerUserId BE][32B beaconSecret][8B unixSeconds BE]
  ```
  48 bytes; firmware also accepts the 40-byte form without the timestamp. The
  timestamp is the tracker's only source of wall-clock time — there is no
  battery-backed RTC, and without it `unix_seconds / 900` is unknowable, so the
  tracker would beacon ids outside the owner's search window. Firmware keeps time as
  `unix_at_provision + k_uptime_get()` and re-persists it once per epoch so a reset
  costs one epoch of drift rather than the whole time base.

  On success: persist, stop advertising the unprovisioned service, start the
  rotating beacon.
- `…2f03…` (write/notify): per-find FiRa params
  ```
  [2B phoneAddr][4B sessionId BE][1B channel][1B preamble]
  ```
  The STS key **and the tracker's own 2-byte UWB address** are derived on both ends
  from `beaconSecret` via HKDF-SHA256 and are not sent OTA — the GATT link is unbonded,
  so anything on it could come from a stranger nearby:
  ```
  stsKey  = HKDF-SHA256(beaconSecret, info = "com.vayunmathur.findfamily/uwb-sts"  || u32_be(sessionId))[..8]
  uwbAddr = HKDF-SHA256(beaconSecret, info = "com.vayunmathur.findfamily/uwb-addr")[..2]
  ```
  (salt = 32 zero bytes; reserved `0x0000`/`0xFFFF` addresses have their low byte set to
  `0x01`.) Mirrored by `TrackerUwbKeys.kt` and `src/tracker_sts.c`; fixed vectors live in
  `TrackerUwbKeysTest.kt`.

**UWB (precision find)** — DW3110 runs a FiRa **responder/controlee** session
with those params; phone is controller/initiator. See `TrackerUwbGatt.kt`.

Button: long-press → advertise unprovisioned service (pair mode); short-press →
mute the piezo. Accelerometer INT → wake, refresh beacon, allow ranging.

---

## 6. Power budget (rough)

| Mode | Current | Duty | Notes |
|---|---|---|---|
| System sleep (RTC + accel) | ~3–5 µA | most of the time | nRF52840 System OFF/ON + LIS2DH12 |
| BLE beacon (1 adv) | ~5–10 mA for ~2 ms | e.g. every 1–2 s | dominates average when idle |
| UWB ranging | ~50–130 mA | seconds, only during a find | rare; bulk cap smooths peaks |

At a ~1 s beacon interval the average is tens of µA → a 150 mAh LiPo lasts
weeks–months between charges; motion-gating the beacon (accel) extends it. UWB is
negligible to the average because it's infrequent.

---

## 7. RF / PCB guidance (the hard part)

- **4-layer stackup**: L1 signal+RF / L2 solid GND / L3 power / L4 signal.
- 50 Ω controlled impedance for both RF nets; keep UWB traces very short.
- **2.4 GHz (BLE)**: nRF52840 has an integrated balun → pi-match + antenna; copy
  Nordic's reference matching + antenna keep-out exactly.
- **UWB (ch 9 / 8 GHz)**: replicate the Qorvo **DWM3000** matching + antenna
  (that module is a DW3110) — it's your reference layout.
- Place ANT1 and ANT2 on a board edge, **orthogonal**, with ground keep-outs and
  via-stitched grounds to minimize cross-coupling.
- Crystals (32 MHz, 32.768 kHz, 38.4 MHz) close to their pins with guard rings;
  keep the 38.4 MHz UWB reference away from switching noise.

---

## 8. Calibration & regulatory (cost of bare-chip)

- **Per-unit UWB calibration**: antenna delay + crystal trim, per Qorvo's
  procedure, for accurate ranging. (Modules ship calibrated; bare parts don't.)
- **Regulatory**: FCC/CE intentional-radiator testing for **both** radios; UWB
  has its own emission mask. Budget for a test lab.

---

## 9. Mechanical

~30×30 mm 4-layer PCB; LiPo stacked underneath; USB-C on an edge; sound port over
the piezo; button on top; keychain loop in a corner.

---

## 10. Open items

- Exact DW3110 regulator config (LDO vs internal DC/DC + inductor) per datasheet.
- Antenna vendor/part selection + matching component values (tune with a VNA).
- Loudness target for the piezo (drives PAM8904 boost voltage / transducer choice).
- Whether to add a MAX17048 fuel gauge for accurate battery % vs the SAADC divider.
- Charge-current + safety (NTC thermistor on the LiPo → charger TS pin).
