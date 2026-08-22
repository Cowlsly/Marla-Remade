# FindFamily UWB tracker — spec vs. as-built discrepancies

Diff of `docs/INTENDED_BEHAVIOUR.md` (written from documentation only) against
`docs/AS_BUILT.md` (written from source only), with **every claim carried into this report
re-verified against the actual source, the Qorvo SDK at `C:\qorvo`, and the NCS tree at
`C:\ncs\v3.4.0`** before being asserted. Where an input document is wrong, it is corrected
here explicitly (§6).

Immediate goal: FiRa ranging between an Android controller/initiator and this DW3110
controlee/responder, **without regressing BLE crowd-finding, which currently works.**
Ordered blockers first.

## Line numbers used here

`src/tracker_uwb.c` line numbers in this document are **working-tree** (531 lines, i.e.
including the uncommitted `k_sleep` hunk assessed in §5). `AS_BUILT.md` cites HEAD
(`0cdc09026`, 519 lines); for HEAD, subtract 11 from any line ≥ 172. All other files are
identical at HEAD and in the working tree.

## Verification status of each claim class

| Class | How verified |
|---|---|
| Firmware behaviour | Read directly: `src/*.c`, `src/*.h`, `prj.conf`, `uwb.conf`, `Kconfig`, `firmware/qorvo-uwb/src/*.c` |
| Phone behaviour | Read directly: `TrackerUwbGatt.kt`, `TrackerUwbKeys.kt`, `UwbController.kt` |
| Vendor API contracts | Read directly in `C:\qorvo` (`uwbmac.h`, `fira_helper.h`, `fira_region_params.h`, `qthread.h`, `fira_niq.c`, `fira_default_params.h`) |
| Zephyr/NCS defaults | Read directly in `C:\ncs\v3.4.0` (`kernel/Kconfig`, `include/zephyr/kernel.h`, `subsys/logging/*`) |
| Android UCI config | **Given as observed fact** in the task; not re-derived |
| `qplatform_uwb_interrupt_enable()` caller | **Not verifiable** — closed-source `llhw`. Kept as an open item (M5) |

---

# BLOCKERS

## B1. The FiRa session start is unreachable behind a higher-priority poll thread — and there is no evidence it ever ran

**Spec requires** (INTENDED §6.5, verified against `fira_app.c` / `fira_niq.c`):
`uwbmac_start()` **then** `fira_helper_start_session()`, and the session must actually reach
ACTIVE before the controller polls (§6.7 / OQ-19).

**Code does:** `ff_uwb_start()` runs on the session thread at **priority 6**
(`tracker_uwb.c:93`). At `tracker_uwb.c:451` — after `uwbmac_start()` (`:444`) and **before**
`fira_helper_start_session()` (`:454`) — it calls `start_polling()`, which
`k_thread_create(..., UWB_THREAD_PRIORITY, 0, K_NO_WAIT)` a poll thread at **priority 5**
(`:49`, `:188-190`). Priority 5 is numerically lower, i.e. higher priority, than its own
creator, and both are preemptible, so **the new thread preempts the session thread at the
moment of creation**. `fira_helper_start_session()` is only reached if the poll loop yields.

Zephyr time-slicing does not save this: `CONFIG_TIMESLICING` is default `y` with
`CONFIG_TIMESLICE_SIZE` 20 ms (`C:\ncs\v3.4.0\zephyr\kernel\Kconfig:679-692`), but slicing
only rotates threads of **equal** priority.

**Runtime consequence:** the responder session is never started, so the DW3110 is not
listening in the phone's rounds. Android, as controller, transmits and gets nothing back:
**UCI RangingStatus 0x21 (RX timeout), Distance 65535, on every round for 10 s, then drops
the controlee** — precisely the observed symptom. Crowd-finding is unaffected because the
beacon refresh runs on `main` at priority 0 (`main.c:215-272`), which also keeps feeding the
watchdog (`main.c:216`) — hence a silent board with **no reset**, also as observed.

This is ranked first not because it is the most interesting defect but because it is the only
one **proven by the observed run**: none of the seven `printk` markers in `ff_uwb_start`
(`:370, 377, 384, 436, 442, 453, 460`) ever appeared. Had `ff_uwb_start` completed, the
session thread would have returned to `k_sem_take(K_FOREVER)` (`:500`) and released the CPU,
at which point the log thread would have flushed the whole trace. It did not. Therefore
`ff_uwb_start` did not complete, therefore no session was started, therefore every parameter,
STS and endianness theory below is currently **unfalsifiable**.

Two mechanisms are consistent with that, and B1's fix covers both:
1. the poll loop does not yield (the premise of the uncommitted delta, §5); or
2. the session thread is stuck inside a vendor call between `fira_helper_open` (`:371`) and
   `fira_helper_start_session` (`:454`) — the exact hazard `tracker_uwb.c:84-90` was written
   to contain.

A third-party fault is ruled out: an MPU/bus fault would have halted or rebooted the board
(`k_fatal_halt` spins with interrupts off; `CONFIG_RESET_ON_FATAL_ERROR` defaults `y`), and
neither happened. It would also have *printed*: the fatal path calls `LOG_PANIC()` before
halting (`C:\ncs\v3.4.0\zephyr\kernel\fatal.c:42-44`), which flushes the deferred buffer
synchronously, so a fault would have dumped the whole missing trace plus "Halting system"
rather than producing silence. The silence is therefore positive evidence of starvation rather
than of a crash.

**Fix:**
- `src/tracker_uwb.c:49` — `#define UWB_THREAD_PRIORITY 5` → **`7`**. This puts the pump
  below the session thread (6) *and* below every vendor MAC thread: `enum qthread_priority`
  runs `CRITICAL = 0 … IDLE = 6` (`C:\qorvo\Libs\uwb-stack\libs\qosal\include\qthread.h:42-51`)
  and `qthread_zephyr.c:96` maps it order-preservingly onto `K_PRIO_PREEMPT`, so MAC threads
  occupy Zephyr 0..6 and the poll thread currently sits **inside** that band.
- `src/tracker_uwb.c:160` — stop discarding the result; log it once
  (`(void)` → capture and `LOG_INF` on first call, or on change). If it returns an error
  immediately, the pump is unavailable in this delivery (see §6.2) and the thread should be
  deleted rather than tuned.
- Keep the `k_sleep(K_MSEC(1))` at `:171` (§5) — but as defence, not as the mechanism that
  makes the session start work.

## B2. Every diagnostic is destroyed at exactly the moment it is needed

**Spec requires** (INTENDED §12 step 2, and its whole investigation order): decode the
tracker's own `SESSION_STATUS_NTF` reason codes *before* guessing at parameters, because they
distinguish "never heard anything" from "heard it, STS wrong" from "parameter rejected".

**Code does:** `CONFIG_LOG_MODE_DEFERRED=y` (`prj.conf:74`) with no priority override, so the
log processing thread runs at `K_LOWEST_APPLICATION_THREAD_PRIO`
(`C:\ncs\v3.4.0\zephyr\subsys\logging\log_core.c:60-64,1013`) =
`K_LOWEST_THREAD_PRIO − 1` = `CONFIG_NUM_PREEMPT_PRIORITIES − 1` = **14**
(`kernel.h:58-61`, `kernel/Kconfig:52-55`). The two UWB threads are at 5 and 6, `main` at 0
(`kernel/Kconfig:70-73`). Worse, the `printk()` calls in `ff_uwb_start` are **not** an escape
hatch: `CONFIG_LOG_PRINTK` is `default y if PRINTK`
(`C:\ncs\v3.4.0\zephyr\subsys\logging\Kconfig.processing:8-13`), so printk is routed into the
same deferred pipeline and is lost with everything else.

**Runtime consequence:** any CPU-bound or long-running condition in a thread at priority
1..13 makes the board go permanently silent while the watchdog keeps being fed — an
observation that looks like a hang, is not one, and erases the record of what happened. This
is the reason the whole bring-up is currently blind, and it is why B1 could only be inferred
rather than read off a log.

**Severity:** BLOCKER. It does not itself stop ranging, but it blocks the diagnosis of
everything that does, and every experiment below is worthless until it is fixed. It is also
the cheapest fix in this document.

**Fix:** `prj.conf` — add `CONFIG_LOG_MODE_IMMEDIATE=y` for bring-up (replacing
`CONFIG_LOG_MODE_DEFERRED=y` at `prj.conf:74`), or keep deferred and add
`CONFIG_LOG_PROCESS_THREAD_CUSTOM_PRIORITY=y` + `CONFIG_LOG_PROCESS_THREAD_PRIORITY=3`.
Immediate mode is preferable while chasing B1/B3/B4 because it removes the log thread — and
therefore the whole starvation mechanism — from the picture, rather than merely re-ranking it.
Then extend `on_fira_notification` (`tracker_uwb.c:139-145`) to print the reason-code name, not
just the integer.

> **Corrected after implementation review (`fixer`).** An earlier revision justified immediate
> mode on the grounds that it "also survives a subsequent hard fault". That was wrong: deferred
> mode survives one too, because the fatal path calls `LOG_PANIC()` before halting
> (`C:\ncs\v3.4.0\zephyr\kernel\fatal.c:42-44`), which switches the backends to synchronous
> panic mode and flushes what is buffered. The distinction that actually matters here is the
> opposite one: `LOG_PANIC()` only fires on a fatal error, and the observed failure had none —
> the board neither halted nor reset — so nothing ever triggered a flush and the buffered trace
> was simply never emitted. Starvation, not faulting, is what loses the evidence, and only
> immediate mode is immune to it.

## B3. vUpper64 is laid into the vendor struct in the wrong order — guaranteed STS mismatch

**Spec requires** (INTENDED §6.8 / TRAP-11): treat the derived 8 bytes as
`[2B VendorID][6B STATIC_STS_IV]` and lay them into the Qorvo struct the way the vendor's own
BLE-provisioned accessory does.

**Code does:** `memcpy(sp.vupper64, vupper64, sizeof(vupper64))` (`tracker_uwb.c:403`) with
the 8 bytes exactly as HKDF produced them, documented at `:348-352` as
`[2B VendorID][6B STATIC_STS_IV]` (matching `TrackerUwbKeys.kt:27-33` and
`setSessionKeyInfo`).

**Verified against the SDK:** the field is a union whose struct arm is
`uint8_t static_sts_iv[6]` **first**, then `uint8_t vendor_id[2]`
(`fira_helper.h:382-397`, with `FIRA_VENDOR_ID_SIZE 2` / `FIRA_STATIC_STS_IV_SIZE 6` /
`FIRA_VUPPER64_SIZE 8` at `fira_region_params.h:18-20`). Qorvo's own NI accessory fills it as
a **full byte reversal** of `VendorID‖StaticStsIv`
(`C:\qorvo\Projects\FreeRTOS\QANI\Common\src\fira\fira_niq.c:228-235`:
`vupper64[7]=Vendor_ID[0] … vupper64[0]=Static_STS_IV[5]`). The firmware's straight `memcpy`
therefore puts the VendorID bytes into `static_sts_iv[0..1]` and `STATIC_STS_IV[4..5]` into
`vendor_id[0..1]`.

**Runtime consequence:** the tracker's static-STS key material differs from the phone's, so
SP3 frames do not correlate in either direction. The phone reports RX timeout on every round;
the tracker logs nothing, because a wrong STS is not an error, exactly as
`tracker_sts.c:10-12` warns. Indistinguishable from B1 from the phone's side — which is why
B1 must be fixed first.

**Fix:** `src/tracker_uwb.c:403` — replace the `memcpy` with a reversal:
`for (int i = 0; i < FIRA_VUPPER64_SIZE; i++) sp.vupper64[i] = vupper64[7 - i];`
and correct the comment at `:348-352`, which currently asserts the layout the struct does not
have. Confidence is high (vendor reference + verified struct layout) but not absolute — the
Android side of the mapping is not citable from anything on this machine — so if ranging still
fails after B1+B3, the straight `memcpy` is the one alternative worth re-testing, and it is a
two-build experiment.

## B4. The short-address byte order is unverified, and the hardware address filter is on

**Spec requires** (INTENDED §6.6 / OQ-22, §6.7 / TRAP-10): the tracker's session
`short_addr` must equal what the phone uses as `peerAddress`, `destination_short_address[0]`
must equal the phone's own address, and `uwbmac_set_short_addr()` must be called or the
DW3110's hardware filter silently discards the polls.

**Code does:** `uwbmac_set_short_addr()` **is** called (`tracker_uwb.c:364`) — TRAP-10's
primary form is not present, and `AS_BUILT` §7.4 is right that both sides agree on the byte
*sequence*. But the firmware additionally commits to a **little-endian** reading of both
2-byte arrays (`sys_get_le16`, `:361-362`) while the phone passes the same bytes to
`UwbAddress.fromBytes()` (`UwbController.kt:163,176`) without any documented byte order, and
nothing in this repo ever converts a `UwbAddress` to an integer. Promiscuous mode is not
enabled.

**Runtime consequence:** if `android.ranging.uwb.UwbAddress` is big-endian, *both* addresses
are byte-swapped relative to the tracker, the hardware filter drops every poll frame before
the MAC sees it, and the phone reports RX timeout with nothing at all on the tracker side.
The phone↔phone path cannot have caught this: it exchanges the same bytes symmetrically, so
the tracker is the first consumer that reinterprets them as an integer.

Supporting (not conclusive) evidence for the firmware being right: Qorvo's NI accessory reads
the OOB address little-endian too — `#define AR2U16(x) ((x[1] << 8) | x[0])`,
`fira_niq.c:43,186,218,220`.

**Fix:** two parts, both cheap.
1. `src/tracker_uwb.c:364` — add `uwbmac_set_promiscuous_mode(uwbmac_ctx, true)` for bring-up
   (`uwbmac.h:581-590`; `fira_niq.c:125-126` does exactly this, and its comment there is
   wrong — passing `true` *disables* filtering). This removes the hardware filter as a
   variable while B3 is being settled.
2. `src/tracker_uwb.c:361-362` — log both interpretations
   (`sys_get_le16` and `sys_get_be16`) alongside the addresses at `:463`, then confirm against
   `RangingSession` logs which one the phone used, and pin it down in `ff_tracker.h:26` so the
   wire format is no longer implicit.

## B5. Exactly one GATT handover per mode change — every later find fails before UWB is reached

**Spec requires** (INTENDED §1.4, §3.1): the GATT characteristics must remain usable for the
life of the device so the owner can write per-find UWB parameters to a bound tracker
(DESIGN:203-204, HW:159-161).

**Code does:** connectability lives on its own legacy set, `connect_adv`, started once inside
`ff_ble_set_mode(BEACON)` (`tracker_ble.c:525-528`). A connectable advertising set stops when
a connection is established — verified in the host: `bt_hci_le_adv_set_terminated()` clears
`BT_ADV_ENABLED` (`C:\ncs\v3.4.0\zephyr\subsys\bluetooth\host\adv.c:2153`) — and **nothing in
this NCS version restarts it**: `adv_resume` / `BT_ADV_PERSIST` do not exist anywhere in
`zephyr/subsys/bluetooth/host` (grepped `*.c` and `*.h`; the auto-resume path older Zephyr had
for the legacy API is gone). Restarting is therefore entirely the application's job. The one
recovery path is `pending_readvertise`, and it is doubly dead:
- it is only set when `desired_mode != current_mode` (`tracker_ble.c:139-141`), and
  `ff_ble_set_mode(BEACON)` returns 0 with `current_mode == desired_mode == BEACON` even when
  `connect_adv` fails (`:525-528`) — and with `CONFIG_BT_MAX_CONN=2` (`prj.conf:16`) that
  failure effectively cannot occur anyway, so the flag is never set in practice;
- even if it were set, `main.c:228-231` calls `ff_ble_set_mode(ff_ble_desired_mode())`, which
  early-returns at `tracker_ble.c:458` because `mode == current_mode`.

**Runtime consequence:** `TrackerUwbGatt.writeSessionParams` disconnects as soon as the write
completes (`TrackerUwbGatt.kt:143-149`), so after the very first find the tracker is no longer
connectable. The second and every subsequent find fails at "service not found"/connect
timeout, never reaching UWB at all. In bring-up terms: **every iteration needs a power cycle**,
which is why this is a blocker rather than a MAJOR. Crowd-finding is unaffected (the beacon
set keeps running), so this does not regress what works today.

**Fix:** `src/tracker_ble.c:123-142` — in `on_disconnected`, set `pending_readvertise`
unconditionally when `current_mode == FF_BLE_MODE_BEACON`, and add a
`restart_connectable_adv()` that starts `connect_adv` directly (tolerating `-EALREADY`)
instead of routing through `ff_ble_set_mode`, whose `mode == current_mode` short-circuit makes
it unusable for this. `AS_BUILT` §1.3 identified this; the second half of the mechanism (the
early return) is added here.

---

# MAJOR

## M1. The session thread has half the stack the author determined this work needs

`uwb_session_thread` runs the whole `ff_uwb_start` path — `fira_helper_open`,
`init_session`, 23 setters, `uwbmac_start`, `fira_helper_start_session` — on **4096** bytes
(`tracker_uwb.c:92`), while the comment at `:44-47` records that the *same* nested MAC work
needed 8192 on the poll thread because "4 KB tripped the MPU stack guard immediately on the
first poll". Consequence: an MPU fault during session setup, which on this configuration
halts or reboots rather than logging. Not what happened in the observed run (no reset), but
it is the next failure to appear once B1 lets the path run further.
**Fix:** `tracker_uwb.c:92` — `4096` → `8192`, and re-check RAM (README records 47 %).

## M2. Nothing ever stops a UWB session, and the poll thread outlives the MAC

`ff_uwb_stop()` is called from exactly one place, the top of `ff_uwb_start()`
(`tracker_uwb.c:344-346`); nothing stops ranging on BLE disconnect, on ranging failure or on
a timeout. `poll_running` is set once (`:187`) and **never cleared**, so after
`ff_uwb_stop()`'s `uwbmac_stop()` (`:482`) the poll thread keeps calling
`uwbmac_poll_events()` on a stopped MAC, and a later `start_polling()` is a no-op. Consequence:
the radio and session stay up indefinitely after the phone has finished (battery, and a stale
session that the next find must tear down first), plus a reachable
poll-a-stopped-MAC state whose behaviour nothing in this repo specifies.
**Fix:** clear `poll_running` in `ff_uwb_stop()` (`tracker_uwb.c:475-486`) and join the poll
thread **after** the `uwbmac_stop()` call, not before — `fira_helper_stop_session()` and
`fira_helper_deinit_session()` still need the pump alive to complete. Trigger the stop from a
**bounded idle timeout** (no ranging notification for N seconds, checked on the session
thread), **not** from the BLE disconnect.

> **Corrected after implementation review (`fixer`).** An earlier revision of this item
> suggested calling `ff_uwb_stop()` from `on_disconnected` (`tracker_ble.c:123`). That would
> have been actively harmful: `TrackerUwbGatt.writeSessionParams` disconnects as soon as the
> write completes — `finish()` calls `gatt.disconnect()`/`close()` (`TrackerUwbGatt.kt:143-149`)
> — and `startRanging` only calls `ctrl.stream(...)` afterwards (`:93-106`), so the GATT link
> is **always** down before the first ranging round. A disconnect-triggered teardown would
> stop the session before ranging began, every time. The same
> disconnect-then-range ordering is cited in B5 above, and this item contradicted it; the idle
> timeout was always the sound half of the fix.

## M3. Long-press pairing mode is a dead end that takes the tracker off the crowd network

`main.c:9-11` and README:91 both document long press → pairing mode "so an already-bound
tracker can be re-bound or have its clock resynced". None of that works:
- `on_provision_write` refuses every write while `state->provisioned`
  (`tracker_ble.c:197-201`), so a re-bind is impossible;
- `ff_store_clear()` exists (`tracker_store.c:137`, declared "Wipes provisioning so the device
  can be re-bound", `ff_tracker.h:61-62`) and is called from **nowhere**;
- `CONFIG_FF_TRACKER_PAIRING_TIMEOUT_S` (`Kconfig:26-33`) promises a fallback to the previous
  mode and is referenced **nowhere**, so pairing mode never ends.

Consequence: one accidental long press stops the beacon and keeps it stopped — crowd-finding
is off until a short press or a reboot — while the pairing advertisement it puts on air can
never be acted on. The spec is not innocent here either: INTENDED OQ-9 records that DESIGN:141
claims an anti-theft lock with no unbind mechanism defined anywhere, so the refusal at
`tracker_ble.c:197` is the *only* implemented half of a two-half design.
**Fix (smallest coherent one):** implement the timeout in `main.c:215-272` (track pairing entry
time, revert to `ff_ble_desired_mode()`'s previous value after
`CONFIG_FF_TRACKER_PAIRING_TIMEOUT_S`), and either delete the Kconfig symbol and the
long-press re-bind claim, or define an unbind that requires proof of the secret and calls
`ff_store_clear()`. Do not simply relax `tracker_ble.c:197` — that would remove the only
anti-theft behaviour that exists.

## M4. No failure on the tracker side is ever visible to the phone

The `…2f03…` characteristic declares `BT_GATT_CHRC_NOTIFY` (`tracker_ble.c:305-309`) and a
CCC (`:309`), and nothing ever notifies. `on_uwb_write` acknowledges the write
(`:270-272`) long before the session is attempted; `ff_uwb_start`'s return is discarded by
`uwb_session_thread` (`tracker_uwb.c:502`); `main.c:236-239` discards `ff_uwb_on_params`'s
return; and `fira_helper_init_session`'s `rsp.status_code` is never read
(`tracker_uwb.c:385-391`). Consequence: the phone starts its 10 s of polling with no idea
whether a responder exists — INTENDED OQ-7/OQ-19's "design gap, not just an implementation
gap", and the reason the observed failure produced no actionable information on either side.
**Fix:** notify a one-byte status on `…2f03…` from the session thread after
`fira_helper_start_session` succeeds or fails, and have `TrackerUwbGatt.startRanging`
(`TrackerUwbGatt.kt:93-96`) wait for it before calling `ctrl.stream(...)`. This also fixes the
responder-first ordering question (OQ-19) rather than leaving it to luck.

## M5. Nothing in this tree enables the DW3110 interrupt (INTENDED OQ-17), and it cannot be ruled out from here

`qplatform_init()` disables the UWB IRQ, and `qplatform_uwb_interrupt_enable()` has no visible
caller in the SDK; our glue does not call it either (checked all of
`firmware/qorvo-uwb/src/*.c` — `qplatform_zephyr.c` only supplies the `qm33_rstn`/`qm33_irq`/
SPI externs). If `llhw` does not call it internally, the tracker sees no DW3110 interrupts at
all and can never respond. Kept as MAJOR rather than a blocker because it is unverifiable
statically and because B1 already explains the observed run; it becomes the leading suspect
the moment B1/B2 are fixed and the trace still shows no `SESSION_STATUS_NTF`.
**Fix (diagnostic):** after `llhw_init()` (`tracker_uwb.c:236-242`), log the IRQ pin state and
try an explicit `qplatform_uwb_interrupt_enable()`; if behaviour changes, keep it.

## M6. `round_hopping = true` is a Qorvo code path no vendor sample exercises

**Spec requires** (INTENDED OQ-30, added after this diff was raised): `round_hopping` must
match, and Android sets `HOPPING_MODE=1`.

**Code does:** the right thing — `ROUND_HOPPING true` (`tracker_uwb.c:71`), matching the
phone. The problem is not the value; it is that **the Qorvo side of it is unvalidated**.
`FIRA_DEFAULT_ROUND_HOPPING` is `false`, the CLI only enables hopping behind an explicit
`-HOP` argument (`fira_app.c:281-285`), and QANI — the only vendor BLE-provisioned responder —
computes `round_duration_slots = 1 + Round_Duration_RSTU / Slot_Duration_RSTU` with the
comment *"+1 slot to satisfy corner case when the # of RR is exact the same as the # of slots
in TWR. **This is fine because hopping is disabled in the NI protocol**"*
(`fira_niq.c:206`, verified). So the vendor's own accessory reference explicitly depends on
hopping being off, and this stack's controlee-side hop-sequence derivation is exercised by no
sample code.

**Runtime consequence:** if the controlee's hop sequence diverges from the controller's, the
responder listens on the wrong channel/slot for some blocks and not others.

**Why this is MAJOR and not a blocker:** the observed failure is 100 % from the first round to
the last (RX timeout on *every* round for 10 s), which is the signature of "no responder" or
"wrong key", not of a hop-sequence disagreement — a wrong hop sequence would produce
intermittent success or a failure that starts after the first block. Promote this immediately
if, after B1–B4, ranging works for the first block(s) and then stops.
**Fix (if reached):** temporarily set `ROUND_HOPPING false` (`tracker_uwb.c:71`) *and* have the
phone pass a non-hopping config, purely to isolate; do not ship the mismatch, since Android
enables hopping and nothing negotiates it.

## M7. Diagnostics are configured off in the one place they would pay for themselves

`sp.enable_diagnostics = false` (`tracker_uwb.c:420`) and, since `apply_session_params`
(`:277-326`) never pushes it, it is not even sent. `report_rssi = 1` is pushed (`:299`,
`:419`). INTENDED §6.7 lists diagnostics as "extremely useful for this bug".
**Fix:** set `true` and add a `SET_PARAM(enable_diagnostics)` (or the explicit setter) while
bringing up; revert before shipping.

---

# MINOR

## m1. `phr_data_rate` is assigned from the wrong enum family

`sp.phr_data_rate = FIRA_PRF_MODE_BPRF;` (`tracker_uwb.c:413`) — a copy of the line above it.
Verified harmless *today*: `FIRA_PRF_MODE_BPRF == 0` and `FIRA_PHR_DATA_RATE_850K == 0`
(`fira_region_params.h:277-281, 347-350`), and 850 kbit/s is what Android's config leaves at
default. It is still a type confusion sitting on one of the 19 must-match fields, so it will
break silently the day either enum is reordered or the phone selects 6M81.
**Fix:** `tracker_uwb.c:413` → `sp.phr_data_rate = FIRA_PHR_DATA_RATE_850K;`

## m2. Five must-match fields ride on zero-initialisation with nothing asserting them

`sts_config`, `preamble_duration`, `psdu_data_rate`, `sts_length` and
`number_of_sts_segments` are left at the `= { 0 }` value (`tracker_uwb.c:332`) and never
pushed. That is the correct approach for `sts_config` — INTENDED TRAP-9 is right that
`fira_helper_set_session_sts_config(…, 0x01)` would select **Dynamic** STS, and the firmware
correctly never calls it — but note `sp.preamble_duration` is left at `0`, which is
`FIRA_PREAMBLE_DURATION_32` (`fira_region_params.h:288-291`), not 64. It only works because
the value is never transmitted and the region default governs.
**Fix:** comment the reliance at `tracker_uwb.c:332` naming the five fields, so a later
"tidy-up" that adds setters cannot silently select 32-symbol preambles or Dynamic STS.

## m3. `channel` and `preamble` from GATT are used unvalidated

`ff_uwb_on_params` (`tracker_uwb.c:506-530`) accepts any byte. INTENDED OQ-23/OQ-24: this
stack accepts only channel 5/9 and BPRF preamble 9..12. Harmless today (the phone sends 9/10,
`UwbController.kt:462,464`), but an out-of-range value currently surfaces only as an
unexplained session-start failure.
**Fix:** reject outside `{5, 9}` / `9..12` in `ff_uwb_on_params` with a distinct log line.

## m4. `uwbmac_poll_events()`'s return value is discarded

`(void)uwbmac_poll_events(...)` (`tracker_uwb.c:160`). This single value settles the
disagreement between the two input documents about whether the pump blocks (§6.2), and it is
thrown away.
**Fix:** log it once. Covered by B1's fix list; listed separately because it is worth doing
even if nothing else changes.

## m5. Three `return 0` paths in `main()` exit before the watchdog is armed

`psa_crypto_init`, `ff_store_init` and `ff_ble_init` failures return from `main()`
(`main.c:171-191`) before `watchdog_init()` (`:194`), leaving a live board that is
permanently silent with no self-recovery — the exact scenario `main.c:104-109` says the
watchdog exists to prevent.
**Fix:** arm the watchdog first, or `sys_reboot()` on those paths (`CONFIG_REBOOT=y` is
already set, `prj.conf:85`, and `sys_reboot` is never called).

## m6. Reserved-address nudge is asymmetric on the phone side

`TrackerUwbKeys.kt:56-58` checks both `0x0000` and `0xFFFF`; `tracker_sts.c:99-101` matches
it exactly. But `UwbController.openController()`/`openControlee()` check only `0x0000`
(`UwbController.kt:88-91, 115-118`) despite the comment above them, so the phone can hand the
tracker `0xFFFF` as its own address. 1-in-65536, phone-side, but it is a real
`destination_short_address` that FiRa reserves.
**Fix:** `UwbController.kt:88-91` and `:115-118` — add the `0xFFFF` case.

## m7. README and DESIGN describe a state the hardware has moved past

README:156-171 says `qplatform_init()` returns `QERR_EADDRNOTAVAIL` and "**No ranging has been
attempted**"; DESIGN:36-38 says the UWB path is "staged behind `CONFIG_FF_TRACKER_UWB`
(default `n`) and never compiled". Both are now false: the stack initialises, the DW3110
answers `0xdeca0302`, and ranging has been attempted end to end. Anyone reading either
document first will mis-order their debugging.
**Fix:** update README §"State" and DESIGN §"Implementation status"/§8 once B1–B3 land.

---

# COSMETIC

- **c1.** `tracker_uwb.c:13-18` says the timing parameters "are FiRa/Qorvo defaults, which is
  the best available guess"; `:52-67`, 34 lines later, says they "were read off the wire" from
  Android's UCI log and that the defaults produced status 0x21. Mutually exclusive accounts of
  the same four constants in one file header. The second is the one the hardware evidence
  supports — delete the first. (`AS_BUILT` §6.5 flagged this; it is cosmetic only because the
  *values* are right.)
- **c2.** README:100-105 describes the beacon as "extended **connectable** advertising", while
  the implementation deliberately splits connectability onto a separate legacy set and
  documents why at `tracker_ble.c:336-348`. DESIGN:196-204 has the same stale claim. Since the
  split is the whole reason B5 exists, the docs should say so.
- **c3.** `battery_percent()` returns a constant 100 (`main.c:95-98`), matching INTENDED OQ-12,
  with the reasoning written down. Leave it; it is a documented placeholder, not a defect.
- **c4.** `meas_seq` antenna sets are `0xff` assigned into `int8_t` fields (`tracker_uwb.c:430-434`),
  i.e. `-1`. This matches the vendor's "driver default" convention; noted only because
  `AS_BUILT` §6.5 flags the sign conversion.

---

# 5. Assessment of the uncommitted 1 ms `k_sleep`

`tracker_uwb.c:161-171` (11 added lines: a 10-line comment plus `k_sleep(K_MSEC(1));`).

**Is yielding there correct? Yes.** A preemptible thread at priority 5 that never blocks
starves priorities 6..14 indefinitely, including the session thread that created it (B1) and
the log thread at 14 (B2). Some yield is required for the current thread layout to be sound at
all.

**Is 1 ms appropriate given a 120 ms ranging block? Yes, comfortably.** Slot-precise work does
not happen here: the DW3xxx ISR runs from the GPIO interrupt callback and loops until the IRQ
line deasserts (INTENDED §6.4, `qplatform.c:153-157`), so what the poll loop dispatches is
deferred notification delivery, not TX scheduling. 1 ms is ~0.8 % of a block and ~0.04 % of a
2400 RSTU slot; the added dispatch latency is invisible. On this target the tick is 32768 Hz
so `K_MSEC(1)` really is ~1 ms rather than being rounded up to a tick boundary — worth
re-checking `CONFIG_SYS_CLOCK_TICKS_PER_SEC` if the board config is ever changed, since at
100 Hz this would silently become 10 ms.

**Does it explain log output stopping without a watchdog reset? It explains it exactly — but
its stated premise is contradicted by the vendor header, and the fix does not depend on the
premise being right.**
- The mechanism it describes is real and verified: log thread at 14, UWB threads at 5/6, main
  at 0 feeding an 8 s watchdog every 1 s (`main.c:113,216`), `printk` deferred as well
  (`CONFIG_LOG_PRINTK` default `y`). Output stopping dead with no reset is precisely that
  signature. **This part of the comment is correct and is the most valuable thing in the
  delta.**
- Its premise — that `uwbmac_poll_events` "returns immediately when there is nothing to
  dispatch" — contradicts `uwbmac.h:331-336`: *"Passing a value greated than 0 will make the
  function block until the timeout is reached when there is no pending event."* `AS_BUILT`
  §6.3 is right to flag this. It is not settled, though: the same header says at `:328-329`
  that the function "is only available if you passed a NULL `event_loop_ops` to
  `uwbmac_init()`", and no `event_loop_ops` symbol exists anywhere in this delivery, so
  whether the call is functional-and-blocking or returns an error immediately in *this* build
  is unknown. One logged return value decides it (m4).

**Verdict: keep it, and do not stop there.** It is necessary-but-insufficient. Even with the
sleep, the poll thread still outranks the session thread that creates it, so session-start
liveness depends on a sleep rather than on the priority order — and the poll thread still sits
inside the vendor MAC threads' 0..6 priority band. Land the sleep together with
`UWB_THREAD_PRIORITY` 5 → 7 (B1) and the logging change (B2). Rated **correct fix, wrong
justification, incomplete scope** — not a defect to revert.

---

# 6. Corrections to the two input documents

Both documents are unusually careful and most of what follows is a refinement rather than an
error. These are the places where one of them would mislead a reader.

**Status:** all three items below were raised with the authors by message rather than decided
unilaterally, and both authors have since amended their documents. The disagreements are
recorded here anyway, because the reasoning is what justifies the blocker ordering above.

## 6.1 `INTENDED_BEHAVIOUR.md` §6.7 — three of the "must match exactly" values were wrong

The table gives `block_duration_ms` **200**, `round_duration_slots` **25** and `round_hopping`
**false**. Those are the *vendor sample application's* defaults — verified at
`C:\qorvo\Src\Apps\Src\fira\Inc\fira_default_params.h`
(`FIRA_DEFAULT_BLOCK_DURATION_MS 200`, `FIRA_DEFAULT_ROUND_DURATION_SLOTS 25`,
`FIRA_DEFAULT_ROUND_HOPPING false`) — not what Android programs. The observed UCI
`SESSION_SET_APP_CONFIG` is `RANGING_DURATION=120`, `SLOTS_PER_RR=6`, `HOPPING_MODE=1`,
`SLOT_DURATION=2400`. **The firmware's `120 / 6 / true` (`tracker_uwb.c:69-71`) is correct and
the spec's numbers would break a working configuration.** The document is honest about this
risk (§8 says the table is "the Qorvo-side requirement set … a prior is not a citation"), but
the table itself does not carry the caveat, and it is exactly the kind of table someone
copies. Everything else in §6.7 held up against the headers.

**Resolved:** `spec` accepted this and rewrote the three rows against the UCI capture, added
§6.7.1 recording the provenance, and — usefully — turned the correction into a new finding,
**OQ-30**, that `round_hopping = true` is a vendor-untested path. That is carried into this
report as **M6**, and it is a better outcome than the correction itself.

## 6.2 `AS_BUILT.md` §6.3 — right about the header, incomplete about the consequence

The vendor-header quote is accurate and the delta's comment does contradict it. But the
conclusion "the HEAD loop blocks up to 100 ms per iteration and does not starve anything" rests
on a contract the same header undercuts two paragraphs earlier (`:328-329`, no
`event_loop_ops` symbol exists — which is `INTENDED` OQ-18). More importantly, the priority
defect in B1 does not depend on the answer: a thread created at priority 5 from a priority-6
thread preempts its creator immediately regardless of what it then does, so the ordering
`start_polling()` → `fira_helper_start_session()` is unsound either way.

**Resolved:** `asbuilt` accepted this and rewrote §6.3/§6.3.1 to state that the header settles
neither possibility, and to record the preemption relationship and that the delta is
"load-bearing for liveness" in the non-blocking case. The two documents and this one now agree.

## 6.3 `AS_BUILT.md` §10 summary — "`pending_readvertise` is never set" is a shorthand

The body (§1.3) states it precisely: set only when `desired_mode != current_mode`. The full
mechanism, added here, is that **both** halves are broken — the trigger effectively cannot
fire (`CONFIG_BT_MAX_CONN=2` removes the `-ENOMEM` case that would make the modes differ) and
the recovery is a no-op if it does (`ff_ble_set_mode` returns 0 early when
`mode == current_mode`, `tracker_ble.c:458`). Fixing only the trigger would not restore
connectability. See B5.

**Resolved:** `asbuilt` §1.3 (lines 144-148) already carried the decisive case — a connection
accepted *while in beacon mode* stops `connect_adv` with both modes still `BEACON` — so the
disagreement was only with the §10 summary's shorthand. The early-return half
(`tracker_ble.c:458`) is this document's addition and is what makes the fix in B5 bigger than
one line.

## 6.4 Both documents, on "re-binding is impossible"

`AS_BUILT` reports the code fact (nothing clears `provisioned`; `ff_store_clear()` unused);
`INTENDED` OQ-9 reports the documentation fact (DESIGN:141 claims an anti-theft lock with no
unbind protocol defined). Neither on its own conveys the runtime consequence, which is what
makes it MAJOR: a long press stops the beacon, so the tracker leaves the crowd network, and
the pairing advertisement it enters can never be acted upon and never times out. See M3.

---

# 7. Checked and found consistent — do not "fix" these

Recorded so the fix pass does not churn code that is already right.

| Item | Status |
|---|---|
| All 19 "must match exactly" FiRa fields vs. Android's observed UCI config | **All 19 match.** Verified enumerator values in `fira_region_params.h`: `DSTWR_DEFERRED`=2, `UNICAST`=0, `TIME_SCHEDULED`=1, `SP3`=3, `SFD_ID_2`=2, `BPRF`=0, `PHR_DATA_RATE_850K`=0; and `fira_helper_bool_to_ranging_round_control(true,false)` = `BIT(0)|BIT(1)` = **0x03** = Android's `RANGING_ROUND_CONTROL=3` (`fira_helper.h:2003-2010`), `bool_to_result_report_config(true,false,false,false)` = **0x01** = Android's `RESULT_REPORT_CONFIG=1`. **A parameter-value mismatch is not the bug.** The one field whose *value* matches but whose *implementation* is unvalidated is `round_hopping` — see M6 |
| Stack bring-up order `qplatform_init` → `l1_config_init` → `llhw_init` → `uwbmac_init` | Matches INTENDED §6.1 exactly (`tracker_uwb.c:209-250`), with correct reverse-order cleanup on each failure |
| Per-session order: `set_short_addr` → `open` → `set_scheduler` → `init_session` → params → `uwbmac_start` → `start_session` | Matches INTENDED §6.5 (`tracker_uwb.c:364-454`); `set_scheduler` is before `uwbmac_start` as required |
| `session_id` vs `session_handle` (TRAP-7) | Correct: `rsp.session_handle` is used for every setter and for start/stop/deinit |
| `session_parameters` zero-init (TRAP-8) | Correct: `= { 0 }` at `tracker_uwb.c:332` |
| Never calling `fira_helper_set_session_sts_config` (TRAP-9) | Correct — the setter that would select Dynamic STS is not called |
| `mcps_crypto_*` completeness (TRAP-5) | All ~16 symbols implemented on PSA in `firmware/qorvo-uwb/src/mcps_crypto_psa.c` (CMAC-128/256, CCM* create/destroy/encrypt/decrypt/inout, ECB, `get_random`) |
| `l1_config` OTP calibration hook (TRAP-4) | Vendor `DWM3001CDK/platform_l1_config.c` + `Common/helper_platform_l1_config.c` are compiled (`firmware/qorvo-uwb/CMakeLists.txt:140-141`) and `l1_config_init(&l1_config_platform_ops)` is passed real ops |
| `uwbmac_set_short_addr` present (TRAP-10, primary form) | Called at `tracker_uwb.c:364` |
| HKDF derivation vs `TrackerUwbKeys.kt` | Info strings, 32-zero-byte salt, `u32_be(sessionId)`, 8/2-byte outputs and the `0x0000`/`0xFFFF` nudge all match (`tracker_sts.c:27-103` vs `TrackerUwbKeys.kt:23-67`) |
| Two-radio BLE design (extended beacon + legacy pairing + separate connectable set) | Sound, and the reason it is split is documented; **do not merge the sets** while fixing B5 |
| Watchdog covering `main` only | Intentional and documented (`main.c:102-112`); it is what kept crowd-finding alive through the observed failure |

---

# 8. Recommended order of work

1. **B2** (logging) — one Kconfig line; without it nothing below can be verified.
2. **B1** (poll thread priority 5 → 7, keep the `k_sleep`, log the poll return) — then re-run
   and confirm the seven `printk` markers and a `SESSION_STATUS_NTF` appear.
3. If the session now reaches ACTIVE and the phone still times out: **B3** (reverse
   `vupper64`), then **B4** (promiscuous mode + address byte order), in that order — B3 is one
   line and has vendor precedent; B4 is a two-build experiment.
4. **M5** if there is still no `SESSION_STATUS_NTF` at all after B1/B2 — that would mean no
   DW3110 interrupts. **M6** instead if ranging starts and then stops, rather than never
   working — that would mean the hop sequence.
5. **B5** and **M1** before any longer bench session, so iterations stop needing a power cycle
   and the session path stops running on half the stack it needs.
6. **M2/M3/M4/M7**, then the MINORs.
