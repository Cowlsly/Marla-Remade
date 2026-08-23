/*
 * Remaining qhal pieces for Zephyr: transceiver power control, the RTC the stack times
 * ranging blocks against, and flash persistence.
 *
 * The SDK's versions can't be used here:
 *  - qhal/src/qm33/qpwr.c duplicates the SPI config and repeats the `dw35720` node-label
 *    requirement and the `spi_config.cs->gpio` pointer dereference (see
 *    qplatform_zephyr.c).
 *  - qhal/src/qrtc.c and qpwr.c delegate to persistent_time / persistent_config, which
 *    only have nrfx implementations. Those drive an nRF RTC instance directly, which on
 *    Zephyr would contend with the kernel's own timer, so the RTC is implemented on
 *    Zephyr's clock instead.
 */
#include <qerr.h>
#include <qgpio.h>
#include <qirq.h>
#include <qpwr.h>
#include <qrtc.h>
#include <qspi.h>
#include <qtime.h>

#include <deca_device_api.h>

#include <zephyr/drivers/gpio.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>

#include <string.h>

LOG_MODULE_REGISTER(qorvo_qhal, CONFIG_QORVO_UWB_LOG_LEVEL);

/* From qplatform_zephyr.c; carries the chip-select pin used for the wake pulse. */
extern struct qspi_config qm33_qspi_config;

/* Time the DW3000 needs CS held low to wake, and then to become responsive. */
#define WAKEUP_CS_TOGGLE_US 400
#define WAKEUP_DELAY_US 500

/*
 * The DW3000 powers up asleep. dwt_probe() calls the wakeup path during probing, which
 * is what clears this.
 */
static bool uwb_sleeping = true;
static bool lpm_enabled;

/* ---- Transceiver power ------------------------------------------------- */

enum qerr qpwr_uwb_sleep(void)
{
	int key = qirq_lock();

	if (!uwb_sleeping) {
		dwt_entersleep(DWT_DW_IDLE_RC);
		uwb_sleeping = true;
	}
	qirq_unlock(key);
	return QERR_SUCCESS;
}

enum qerr qpwr_uwb_wakeup(void)
{
	int key = qirq_lock();

	if (uwb_sleeping) {
		/*
		 * Waking the DW3000 is a CS pulse, not an SPI transfer: hold CS low long
		 * enough and the part comes up. Zephyr's SPI driver owns this pin, so it
		 * is briefly driven by hand here and left deasserted afterwards.
		 *
		 * Driven through gpio_pin_set_dt rather than qgpio's OUTPUT_LOW/HIGH
		 * flags: the devicetree marks cs-gpios GPIO_ACTIVE_LOW, so Zephyr inverts
		 * logical levels for this pin and "output low" would idle it physically
		 * high — the opposite of the pulse the part needs. Level 1 here means
		 * asserted, i.e. physically low.
		 */
		const struct gpio_dt_spec *cs = qm33_qspi_config.cs_pin.dev;

		if (cs != NULL) {
			(void)gpio_pin_configure_dt(cs, GPIO_OUTPUT_ACTIVE);
			qtime_usleep(WAKEUP_CS_TOGGLE_US);
			(void)gpio_pin_set_dt(cs, 0);
			qtime_usleep(WAKEUP_DELAY_US);
		}
		uwb_sleeping = false;
	}
	qirq_unlock(key);
	return QERR_SUCCESS;
}

bool qpwr_uwb_is_sleeping(void)
{
	return uwb_sleeping;
}

void qpwr_enable_lpm(void)
{
	lpm_enabled = true;
}

void qpwr_disable_lpm(void)
{
	lpm_enabled = false;
}

bool qpwr_is_lpm_enabled(void)
{
	return lpm_enabled;
}

enum qerr qpwr_set_min_inactivity_s4(uint32_t time_ms)
{
	ARG_UNUSED(time_ms);
	return QERR_ENOTSUP;
}

enum qerr qpwr_get_min_inactivity_s4(uint32_t *time_ms)
{
	ARG_UNUSED(time_ms);
	return QERR_ENOTSUP;
}

/* ---- RTC -------------------------------------------------------------- */

/*
 * The stack uses this to convert between its own device time units and wall time when
 * scheduling ranging blocks, so it needs to be monotonic and reasonably fine-grained
 * rather than accurate in absolute terms. Zephyr's uptime is LFCLK-backed and keeps
 * counting through sleep, which is what matters.
 */
int64_t qrtc_get_us(void)
{
	return (int64_t)k_ticks_to_us_floor64(k_uptime_ticks());
}

void qrtc_resync_rtc_systime(int64_t *rtc_us, uint32_t *systime)
{
	if (rtc_us) {
		*rtc_us = qrtc_get_us();
	}
	if (systime) {
		/*
		 * llhw stores this as the anchor tying the DW3110's own timestamp counter to
		 * its DTU timeline (llhw_resync_dtu_systime keeps it, llhw_systime_to_dtu
		 * converts against it). Reporting a constant here does not mean "unused": it
		 * offsets every hardware timestamp by the whole real counter value, and since
		 * the session is time-scheduled, every RX window the MAC computes then refers
		 * to a fiction. The vendor reads the counter for exactly this reason
		 * (qhal/src/nrfx/persistent_time.c, persistent_time_resync_rtc_systime).
		 *
		 * Correlation quality. The two reads are adjacent but NOT atomic, and cannot be
		 * made so here:
		 *  - qrtc_get_us() is tick-granular, so rtc_us alone carries up to one tick,
		 *    30.5 us at 32768 Hz.
		 *  - this read is a blocking SPI transfer. The caller pends on the SPIM
		 *    completion, so the thread YIELDS between the pair, and anything runnable
		 *    then runs: BLE RX (cooperative -8), the transceiver dispatch thread (-1),
		 *    log processing (3). The window is bounded by other work, not by the
		 *    transfer.
		 * At 15.6 DTU/us the best case (tick + transfer, ~50 us) is ~800 DTU; with
		 * preemption it can reach milliseconds, so tens of thousands of DTU.
		 *
		 * Nothing available here closes that. irq_lock() deadlocks, since it masks the
		 * very SPIM completion this read waits on. k_sched_lock() does not help either:
		 * it prevents preemption but not the switch that blocking itself causes. Only a
		 * polling SPI path would, which is what the vendor's nrfx backend has (it
		 * busy-waits on a volatile flag) and ours deliberately does not.
		 *
		 * Tolerable because llhw re-anchors on every MCPS op and every wakeup, so a bad
		 * sample is replaced rather than accumulated, and because the error this
		 * replaces was the entire value of the counter. If ranging proves intermittent
		 * rather than broken, suspect this first.
		 */
		*systime = dwt_readsystimestamphi32();
	}
}

void qrtc_update_rtc_systime(int64_t updated_rtc_us, uint32_t updated_systime)
{
	ARG_UNUSED(updated_rtc_us);
	ARG_UNUSED(updated_systime);
}

/* ---- "Flash" ---------------------------------------------------------- */

enum qerr qflash_write(uint32_t dst_addr, void *src_addr, uint32_t size)
{
	const uint32_t ram_start = CONFIG_SRAM_BASE_ADDRESS;
	const uint32_t ram_end = CONFIG_SRAM_BASE_ADDRESS + (CONFIG_SRAM_SIZE * 1024);

	if (src_addr == NULL || size == 0U) {
		return QERR_EINVAL;
	}

	/*
	 * l1_config is the only caller, storing its configuration and that config's
	 * SHA-256 to what it believes is persistent memory. Both live in .data here (see
	 * l1_config_storage.ld), so this is a plain copy and the store/load round-trip is
	 * consistent within a boot. It is deliberately not persistent: the calibration
	 * that matters is re-read from the DW3110's OTP by reset_to_default on every boot.
	 *
	 * The bounds check is the point of this function — if a future SDK moves those
	 * sections back into flash, this must fail loudly rather than write into the
	 * address space where the code lives.
	 */
	if (dst_addr < ram_start || (dst_addr + size) > ram_end) {
		LOG_ERR("qflash_write to 0x%08x (%u bytes) is outside RAM; refusing",
			dst_addr, size);
		return QERR_ENOTSUP;
	}

	memcpy((void *)dst_addr, src_addr, size);
	return QERR_SUCCESS;
}
