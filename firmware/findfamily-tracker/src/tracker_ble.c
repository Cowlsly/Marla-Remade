/*
 * Advertising (two modes) and the GATT server.
 *
 * Two advertising modes on one state machine, chosen so only the phone's beacon
 * scanner needs a change:
 *
 *  - Pairing: LEGACY connectable adv of the unprovisioned service. Flags + a 128-bit
 *    service UUID is 21 bytes, well inside the 31-byte legacy budget, so
 *    TrackerProvisioner.unprovisioned() keeps working with default (legacy-only)
 *    ScanSettings.
 *
 *  - Beacon: EXTENDED connectable adv of [16B epochId][1B battery] as service data.
 *    That AD structure is 18B of overhead + 17B of payload = 35 bytes, past the legacy
 *    limit, and the full 16-byte UUID has to be on air because the phone reads it back
 *    with ScanRecord.getServiceData(ParcelUuid). Secondary PHY is pinned to 1M so the
 *    phone needs no coded-PHY scan configuration. Connectable so the owner can still
 *    write UWB session params after binding.
 *
 * Both characteristics stay registered under the "unprovisioned" service for the
 * device's whole life; provisioning only stops that service being advertised.
 */
#include "ff_tracker.h"

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/sys/byteorder.h>

#include <string.h>

LOG_MODULE_REGISTER(ff_ble, LOG_LEVEL_INF);

/* TrackerBle UUIDs, little-endian byte order as bt_uuid_128 wants them. */
#define FF_UUID_BEACON_SVC \
	BT_UUID_128_ENCODE(0x6b1d2f00, 0x4b3a, 0x4c7e, 0x9a10, 0x1f2e3d4c5b6a)
#define FF_UUID_PROV_SVC \
	BT_UUID_128_ENCODE(0x6b1d2f01, 0x4b3a, 0x4c7e, 0x9a10, 0x1f2e3d4c5b6a)
#define FF_UUID_PROV_CHR \
	BT_UUID_128_ENCODE(0x6b1d2f02, 0x4b3a, 0x4c7e, 0x9a10, 0x1f2e3d4c5b6a)
#define FF_UUID_UWB_CHR \
	BT_UUID_128_ENCODE(0x6b1d2f03, 0x4b3a, 0x4c7e, 0x9a10, 0x1f2e3d4c5b6a)

static const struct bt_uuid_128 uuid_prov_svc = BT_UUID_INIT_128(FF_UUID_PROV_SVC);
static const struct bt_uuid_128 uuid_prov_chr = BT_UUID_INIT_128(FF_UUID_PROV_CHR);
static const struct bt_uuid_128 uuid_uwb_chr = BT_UUID_INIT_128(FF_UUID_UWB_CHR);

static struct ff_tracker_state *state;
static enum ff_ble_mode current_mode = FF_BLE_MODE_IDLE;

static struct bt_le_ext_adv *pairing_adv;
static struct bt_le_ext_adv *beacon_adv;

/*
 * Service-data AD payload: the 16-byte UUID followed by [16B epochId][1B battery].
 * Held in a static buffer because bt_le_ext_adv_set_data() does not copy — the
 * controller reads it back when it refreshes the advertisement.
 */
static uint8_t beacon_sd[16 + FF_BEACON_DATA_LEN] = { FF_UUID_BEACON_SVC };

static const struct bt_data pairing_ad[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
	BT_DATA_BYTES(BT_DATA_UUID128_ALL, FF_UUID_PROV_SVC),
};

static struct bt_data beacon_ad[] = {
	BT_DATA(BT_DATA_SVC_DATA128, beacon_sd, sizeof(beacon_sd)),
};

/*
 * Work handed from the Bluetooth RX thread to the main loop. The RX thread's stack
 * cannot absorb PSA crypto plus bt_le_ext_adv_start(); doing either inline in a write
 * callback faults with a stack overflow.
 */
static atomic_t pending_provision;
static atomic_t pending_uwb;
static uint8_t pending_uwb_params[FF_UWB_PARAMS_LEN];

/* ---- GATT -------------------------------------------------------------- */

static ssize_t on_provision_write(struct bt_conn *conn, const struct bt_gatt_attr *attr,
				  const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
	const uint8_t *data = buf;
	uint64_t user_id;
	uint64_t unix_seconds = 0U;
	int rc;

	ARG_UNUSED(conn);
	ARG_UNUSED(attr);
	ARG_UNUSED(flags);

	if (offset != 0U) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
	}
	if (len != FF_PROVISION_BLOB_LEN && len != FF_PROVISION_BLOB_LEN_NO_TIME) {
		LOG_WRN("provisioning write of %u bytes rejected (want %d or %d)", len,
			FF_PROVISION_BLOB_LEN_NO_TIME, FF_PROVISION_BLOB_LEN);
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}

	user_id = sys_get_be64(data);
	if (len == FF_PROVISION_BLOB_LEN) {
		unix_seconds = sys_get_be64(&data[8 + FF_SECRET_LEN]);
	}

	rc = ff_store_save_provisioning(user_id, &data[8], unix_seconds);
	if (rc) {
		LOG_ERR("persisting provisioning failed: %d", rc);
		return BT_GATT_ERR(BT_ATT_ERR_UNLIKELY);
	}

	state->tracker_user_id = user_id;
	memcpy(state->secret, &data[8], FF_SECRET_LEN);
	state->provisioned = true;
	state->unix_at_base = unix_seconds;
	state->uptime_at_base_ms = k_uptime_get();

	LOG_INF("provisioned: userId=%llu, %u-byte blob, unix=%llu", user_id, len,
		unix_seconds);
	if (unix_seconds == 0U) {
		LOG_WRN("no timestamp in blob: epoch ids will not resolve for the owner");
	}

	/* The main loop stops offering to be claimed and starts crowd-finding. */
	atomic_set(&pending_provision, 1);
	return len;
}

static ssize_t on_uwb_write(struct bt_conn *conn, const struct bt_gatt_attr *attr,
			    const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(attr);
	ARG_UNUSED(flags);

	if (offset != 0U) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
	}
	if (len != FF_UWB_PARAMS_LEN) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}
	if (!state->provisioned) {
		/* Without the secret there is no session key, so there is nothing to
		 * start — and an unprovisioned tracker has no owner to be ranged by. */
		return BT_GATT_ERR(BT_ATT_ERR_WRITE_NOT_PERMITTED);
	}

	/* Deriving the STS key is PSA work; hand it to the main loop. */
	memcpy(pending_uwb_params, buf, FF_UWB_PARAMS_LEN);
	atomic_set(&pending_uwb, 1);
	return len;
}

bool ff_ble_take_provision_event(void)
{
	return atomic_cas(&pending_provision, 1, 0);
}

bool ff_ble_take_uwb_params(uint8_t *out)
{
	if (!atomic_cas(&pending_uwb, 1, 0)) {
		return false;
	}
	memcpy(out, pending_uwb_params, FF_UWB_PARAMS_LEN);
	return true;
}

BT_GATT_SERVICE_DEFINE(ff_tracker_svc,
	BT_GATT_PRIMARY_SERVICE(&uuid_prov_svc),
	BT_GATT_CHARACTERISTIC(&uuid_prov_chr.uuid,
			       BT_GATT_CHRC_WRITE,
			       BT_GATT_PERM_WRITE,
			       NULL, on_provision_write, NULL),
	BT_GATT_CHARACTERISTIC(&uuid_uwb_chr.uuid,
			       BT_GATT_CHRC_WRITE | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_WRITE,
			       NULL, on_uwb_write, NULL),
	BT_GATT_CCC(NULL, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

#ifndef CONFIG_FF_TRACKER_UWB
int ff_uwb_on_params(const uint8_t *data, size_t len, const uint8_t *secret)
{
	ARG_UNUSED(data);
	ARG_UNUSED(len);
	ARG_UNUSED(secret);
	/* Accepted and logged so the phone-side GATT write can be exercised on a
	 * BLE-only image; ranging itself needs CONFIG_FF_TRACKER_UWB. */
	LOG_INF("UWB session params received but CONFIG_FF_TRACKER_UWB=n; ignoring");
	return 0;
}
#endif

/* ---- Advertising ------------------------------------------------------- */

static int create_adv_sets(void)
{
	/* Legacy connectable+scannable, so a default-settings scanner sees it. */
	const struct bt_le_adv_param pairing_param = {
		.id = BT_ID_DEFAULT,
		.options = BT_LE_ADV_OPT_CONN,
		.interval_min = BT_GAP_ADV_FAST_INT_MIN_2,
		.interval_max = BT_GAP_ADV_FAST_INT_MAX_2,
	};
	/*
	 * Extended connectable. BT_LE_ADV_OPT_EXT_ADV without OPT_CODED pins the
	 * secondary PHY to 1M, so a phone scanning with setLegacy(false) and the default
	 * PHY sees it. BT_LE_ADV_OPT_SCANNABLE is intentionally absent: extended
	 * advertisements can be connectable or scannable, not both, and connectable is
	 * what lets the owner write UWB params.
	 */
	const struct bt_le_adv_param beacon_param = {
		.id = BT_ID_DEFAULT,
		.options = BT_LE_ADV_OPT_EXT_ADV | BT_LE_ADV_OPT_CONN,
		.interval_min = BT_GAP_MS_TO_ADV_INTERVAL(CONFIG_FF_TRACKER_BEACON_INTERVAL_MS),
		.interval_max = BT_GAP_MS_TO_ADV_INTERVAL(CONFIG_FF_TRACKER_BEACON_INTERVAL_MS + 100),
		.secondary_max_skip = 0,
	};
	int rc;

	rc = bt_le_ext_adv_create(&pairing_param, NULL, &pairing_adv);
	if (rc) {
		LOG_ERR("creating pairing adv set: %d", rc);
		return rc;
	}
	rc = bt_le_ext_adv_set_data(pairing_adv, pairing_ad, ARRAY_SIZE(pairing_ad), NULL, 0);
	if (rc) {
		LOG_ERR("setting pairing adv data: %d", rc);
		return rc;
	}

	rc = bt_le_ext_adv_create(&beacon_param, NULL, &beacon_adv);
	if (rc) {
		LOG_ERR("creating beacon adv set: %d", rc);
		return rc;
	}
	return 0;
}

int ff_ble_init(struct ff_tracker_state *st)
{
	int rc;

	state = st;

	rc = bt_enable(NULL);
	if (rc) {
		LOG_ERR("bt_enable: %d", rc);
		return rc;
	}
	LOG_INF("Bluetooth initialised");
	return create_adv_sets();
}

int ff_ble_refresh_beacon(uint8_t battery_percent)
{
	uint8_t epoch_id[FF_EPOCH_ID_LEN];
	uint64_t epoch;
	int rc;

	if (!state->provisioned) {
		return -EPERM;
	}
	epoch = ff_current_epoch(state);
	if (epoch == 0U) {
		/* No time base: any id we produced would fall outside the owner's search
		 * window, so stay quiet rather than fill the air with unresolvable ids. */
		return -EAGAIN;
	}
	rc = ff_epoch_id(state->secret, epoch, epoch_id);
	if (rc) {
		return rc;
	}

	memcpy(&beacon_sd[16], epoch_id, FF_EPOCH_ID_LEN);
	beacon_sd[16 + FF_EPOCH_ID_LEN] = battery_percent;

	LOG_INF("epoch=%llu id=%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x batt=%u%%",
		epoch, epoch_id[0], epoch_id[1], epoch_id[2], epoch_id[3], epoch_id[4],
		epoch_id[5], epoch_id[6], epoch_id[7], epoch_id[8], epoch_id[9],
		epoch_id[10], epoch_id[11], epoch_id[12], epoch_id[13], epoch_id[14],
		epoch_id[15], battery_percent);

	if (current_mode != FF_BLE_MODE_BEACON) {
		return 0;
	}
	rc = bt_le_ext_adv_set_data(beacon_adv, beacon_ad, ARRAY_SIZE(beacon_ad), NULL, 0);
	if (rc) {
		LOG_ERR("refreshing beacon adv data: %d", rc);
	}
	return rc;
}

int ff_ble_set_mode(enum ff_ble_mode mode)
{
	int rc;

	if (mode == current_mode) {
		return 0;
	}

	/* Stopping a set that isn't running returns -EALREADY, which is not a failure
	 * here — mode changes can arrive from the button and from a GATT write. */
	if (current_mode == FF_BLE_MODE_PAIRING) {
		rc = bt_le_ext_adv_stop(pairing_adv);
		if (rc && rc != -EALREADY) {
			LOG_WRN("stopping pairing adv: %d", rc);
		}
	} else if (current_mode == FF_BLE_MODE_BEACON) {
		rc = bt_le_ext_adv_stop(beacon_adv);
		if (rc && rc != -EALREADY) {
			LOG_WRN("stopping beacon adv: %d", rc);
		}
	}
	current_mode = FF_BLE_MODE_IDLE;

	switch (mode) {
	case FF_BLE_MODE_IDLE:
		LOG_INF("advertising off");
		return 0;

	case FF_BLE_MODE_PAIRING:
		rc = bt_le_ext_adv_start(pairing_adv, BT_LE_EXT_ADV_START_DEFAULT);
		if (rc) {
			LOG_ERR("starting pairing adv: %d", rc);
			return rc;
		}
		current_mode = FF_BLE_MODE_PAIRING;
		LOG_INF("pairing mode: advertising unprovisioned service (legacy)");
		return 0;

	case FF_BLE_MODE_BEACON:
		if (!state->provisioned) {
			LOG_WRN("cannot beacon: not provisioned");
			return -EPERM;
		}
		/* Fill in service data before the first start, or the controller would
		 * advertise a zeroed epoch id for one interval. */
		current_mode = FF_BLE_MODE_BEACON;
		rc = ff_ble_refresh_beacon(100);
		if (rc == -EAGAIN) {
			current_mode = FF_BLE_MODE_IDLE;
			LOG_WRN("cannot beacon: no time base (re-provision to sync time)");
			return rc;
		}
		if (rc) {
			current_mode = FF_BLE_MODE_IDLE;
			return rc;
		}
		rc = bt_le_ext_adv_start(beacon_adv, BT_LE_EXT_ADV_START_DEFAULT);
		if (rc) {
			current_mode = FF_BLE_MODE_IDLE;
			LOG_ERR("starting beacon adv: %d", rc);
			return rc;
		}
		LOG_INF("beacon mode: advertising rotating epoch id (extended)");
		return 0;
	}
	return -EINVAL;
}

enum ff_ble_mode ff_ble_mode(void)
{
	return current_mode;
}
