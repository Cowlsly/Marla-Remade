/*
 * FindFamily UWB tracker — main state machine.
 *
 * On boot: load persisted provisioning. Provisioned and time-synced -> beacon.
 * Otherwise -> pairing mode, so a brand-new board is bindable straight out of the box
 * without needing the button.
 *
 * Button (the kit's Button 1):
 *   long press  (>= 2 s) -> pairing mode, so an already-bound tracker can be re-bound
 *                           or have its clock resynced
 *   short press          -> toggle the beacon off/on ("mute"), for taking it on a plane
 *                           or debugging whether a sighting really came from this board
 *
 * The beacon id and battery percent are refreshed once a second and logged, so the
 * board can be verified on the bench against an independently computed epoch id
 * without involving the phone at all.
 */
#include "ff_tracker.h"

#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/logging/log.h>
#include <psa/crypto.h>

LOG_MODULE_REGISTER(ff_main, LOG_LEVEL_INF);

#define LONG_PRESS_MS 2000
#define TICK_MS 1000

static struct ff_tracker_state tracker_state;

/* ---- Button ------------------------------------------------------------ */

static const struct gpio_dt_spec button =
	GPIO_DT_SPEC_GET_OR(DT_ALIAS(sw0), gpios, { 0 });
static struct gpio_callback button_cb;

static int64_t press_started_ms;
static atomic_t pending_short_press;
static atomic_t pending_long_press;

static void button_changed(const struct device *dev, struct gpio_callback *cb,
			   uint32_t pins)
{
	ARG_UNUSED(dev);
	ARG_UNUSED(cb);
	ARG_UNUSED(pins);

	if (gpio_pin_get_dt(&button) == 1) {
		press_started_ms = k_uptime_get();
		return;
	}
	if (press_started_ms == 0) {
		return;
	}
	if (k_uptime_get() - press_started_ms >= LONG_PRESS_MS) {
		atomic_set(&pending_long_press, 1);
	} else {
		atomic_set(&pending_short_press, 1);
	}
	press_started_ms = 0;
}

static int button_init(void)
{
	int rc;

	if (!gpio_is_ready_dt(&button)) {
		LOG_WRN("no sw0 button on this board; pairing mode is boot-only");
		return -ENODEV;
	}
	rc = gpio_pin_configure_dt(&button, GPIO_INPUT);
	if (rc) {
		return rc;
	}
	rc = gpio_pin_interrupt_configure_dt(&button, GPIO_INT_EDGE_BOTH);
	if (rc) {
		return rc;
	}
	gpio_init_callback(&button_cb, button_changed, BIT(button.pin));
	return gpio_add_callback(button.port, &button_cb);
}

/* ---- Battery ---------------------------------------------------------- */

/*
 * The DWM3001CDK is USB-powered on the bench, so there is no meaningful cell voltage
 * to read through the kit's SAADC wiring. Report a constant until a battery-backed
 * carrier exists; the advertisement byte and the phone-side parsing are exercised
 * either way. Sampling VDD here instead would just report the regulator, which reads
 * as a permanently full battery and is more misleading than an obvious placeholder.
 */
static uint8_t battery_percent(void)
{
	return 100;
}

/* ---- Main ------------------------------------------------------------- */

int main(void)
{
	psa_status_t status;
	uint64_t last_persisted_epoch = 0;
	int rc;

	LOG_INF("FindFamily tracker starting");

	status = psa_crypto_init();
	if (status != PSA_SUCCESS) {
		LOG_ERR("psa_crypto_init: %d", (int)status);
		return 0;
	}

	rc = ff_store_init();
	if (rc) {
		LOG_ERR("store init failed: %d", rc);
		return 0;
	}
	rc = ff_store_load(&tracker_state);
	if (rc) {
		LOG_ERR("store load failed: %d", rc);
	}

	rc = ff_ble_init(&tracker_state);
	if (rc) {
		LOG_ERR("ble init failed: %d", rc);
		return 0;
	}

	(void)button_init();

#ifdef CONFIG_FF_TRACKER_UWB
	rc = ff_uwb_init();
	if (rc) {
		LOG_ERR("uwb init failed: %d", rc);
	}
#endif

	if (tracker_state.provisioned) {
		LOG_INF("provisioned as userId=%llu", tracker_state.tracker_user_id);
		if (ff_ble_set_mode(FF_BLE_MODE_BEACON) != 0) {
			/* Provisioned but unusable — almost always a missing time base
			 * after a reset. Offer to be re-bound so it can resync. */
			(void)ff_ble_set_mode(FF_BLE_MODE_PAIRING);
		}
	} else {
		LOG_INF("unprovisioned; entering pairing mode");
		(void)ff_ble_set_mode(FF_BLE_MODE_PAIRING);
	}

	while (true) {
		if (ff_ble_take_provision_event()) {
			LOG_INF("provisioning accepted: switching to beacon mode");
			if (ff_ble_set_mode(FF_BLE_MODE_BEACON) != 0) {
				(void)ff_ble_set_mode(FF_BLE_MODE_PAIRING);
			}
		}

		{
			uint8_t uwb_params[FF_UWB_PARAMS_LEN];

			if (ff_ble_take_uwb_params(uwb_params)) {
				(void)ff_uwb_on_params(uwb_params, sizeof(uwb_params),
						       tracker_state.secret);
			}
		}

		if (atomic_cas(&pending_long_press, 1, 0)) {
			LOG_INF("long press: entering pairing mode");
			(void)ff_ble_set_mode(FF_BLE_MODE_PAIRING);
		}
		if (atomic_cas(&pending_short_press, 1, 0)) {
			if (ff_ble_mode() == FF_BLE_MODE_BEACON) {
				LOG_INF("short press: muting beacon");
				(void)ff_ble_set_mode(FF_BLE_MODE_IDLE);
			} else if (tracker_state.provisioned) {
				LOG_INF("short press: unmuting beacon");
				(void)ff_ble_set_mode(FF_BLE_MODE_BEACON);
			}
		}

		if (tracker_state.provisioned) {
			uint64_t epoch = ff_current_epoch(&tracker_state);

			(void)ff_ble_refresh_beacon(battery_percent());

			/*
			 * Re-persist the time base once per epoch. k_uptime_get() restarts
			 * at 0 on reset, so without this a reset would lose the time base
			 * entirely; with it, the worst case is one epoch of drift.
			 */
			if (epoch != 0U && epoch != last_persisted_epoch) {
				(void)ff_store_save_time(ff_now_unix(&tracker_state));
				last_persisted_epoch = epoch;
			}
		}

		k_msleep(TICK_MS);
	}
	return 0;
}
