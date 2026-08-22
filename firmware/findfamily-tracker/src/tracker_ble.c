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
 *  - Beacon: EXTENDED NON-connectable adv of [16B epochId][1B battery] as service data.
 *    That AD structure is 18B of overhead + 17B of payload = 35 bytes, past the legacy
 *    limit, and the full 16-byte UUID has to be on air because the phone reads it back
 *    with ScanRecord.getServiceData(ParcelUuid). Secondary PHY is pinned to 1M so the
 *    phone needs no coded-PHY scan configuration. Connectability runs on a third, legacy,
 *    dataless set alongside it — see beacon_param for why it cannot live on the beacon
 *    set itself.
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
/*
 * The mode we want to be in, as distinct from the one we managed to start. Starting a
 * connectable advertisement needs a spare connection slot, so a mode switch requested
 * while a phone is still connected can fail; this lets the main loop retry once the
 * connection drops.
 */
static enum ff_ble_mode desired_mode = FF_BLE_MODE_IDLE;

static struct bt_le_ext_adv *pairing_adv;
static struct bt_le_ext_adv *beacon_adv;
/* Runs alongside beacon_adv so the owner can connect while crowd-finding. */
static struct bt_le_ext_adv *connect_adv;

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
	/*
	 * The service UUID must be advertised as its own AD field, not just implied by the
	 * service data below. Android's ScanFilter.setServiceUuid() matches against the
	 * Service UUID AD types (0x06/0x07) and ignores service data, so a beacon carrying
	 * only 0x21 is invisible to a filtered scan no matter how correct its payload is.
	 * Costs 18 bytes, which extended advertising has room for.
	 */
	BT_DATA_BYTES(BT_DATA_UUID128_ALL, FF_UUID_BEACON_SVC),
	BT_DATA(BT_DATA_SVC_DATA128, beacon_sd, sizeof(beacon_sd)),
};

/*
 * Work handed from the Bluetooth RX thread to the main loop. The RX thread's stack
 * cannot absorb PSA crypto plus bt_le_ext_adv_start(); doing either inline in a write
 * callback faults with a stack overflow.
 */
static atomic_t pending_provision;
static atomic_t pending_uwb;
static atomic_t pending_readvertise;
static uint8_t pending_uwb_params[FF_UWB_PARAMS_LEN];

/*
 * Reassembly buffer for the provisioning blob. A client that hasn't negotiated a larger
 * ATT MTU delivers it as a long write in ~20-byte chunks.
 */
static uint8_t provision_staging[FF_PROVISION_BLOB_LEN];

/* ---- Connection tracking (bring-up visibility) ------------------------- */

static void on_connected(struct bt_conn *conn, uint8_t err)
{
	struct bt_conn_info info;

	if (err) {
		LOG_WRN("connection failed: 0x%02x", err);
		return;
	}
	if (bt_conn_get_info(conn, &info) == 0) {
		LOG_INF("connected (interval=%uus latency=%u timeout=%u)",
			info.le.interval_us, info.le.latency, info.le.timeout);
	} else {
		LOG_INF("connected");
	}
}

static void on_disconnected(struct bt_conn *conn, uint8_t reason)
{
	ARG_UNUSED(conn);
	/*
	 * Worth logging the reason: 0x08 is a supervision timeout (out of range or the
	 * phone stopped responding) and 0x13/0x16 are a deliberate disconnect by peer or
	 * host, which is what a completed or abandoned bind looks like.
	 */
	LOG_INF("disconnected (reason=0x%02x)", reason);

	/*
	 * Always ask the main loop to re-apply advertising, not just when the mode we
	 * wanted differs from the one we got. The controller stops a connectable set when
	 * it yields a connection and Zephyr does not restart it, so after one owner
	 * connection connect_adv is down with both modes still BEACON — and the tracker is
	 * unreachable for every later find, which is what made each bring-up iteration need
	 * a power cycle.
	 */
	atomic_set(&pending_readvertise, 1);
}

BT_CONN_CB_DEFINE(conn_callbacks) = {
	.connected = on_connected,
	.disconnected = on_disconnected,
};

static void on_mtu_updated(struct bt_conn *conn, uint16_t tx, uint16_t rx)
{
	ARG_UNUSED(conn);
	/*
	 * The provisioning blob is FF_PROVISION_BLOB_LEN bytes and an ATT write carries
	 * MTU-3, so anything below 51 means the client has to use a long write.
	 */
	LOG_INF("ATT MTU updated: tx=%u rx=%u (%u needed to write the blob in one go)", tx,
		rx, FF_PROVISION_BLOB_LEN + 3);
}

static struct bt_gatt_cb gatt_callbacks = {
	.att_mtu_updated = on_mtu_updated,
};

/* ---- GATT -------------------------------------------------------------- */

static ssize_t on_provision_write(struct bt_conn *conn, const struct bt_gatt_attr *attr,
				  const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
	const uint8_t *data = buf;
	uint64_t user_id;
	uint64_t unix_seconds = 0U;
	uint16_t total;
	int rc;

	ARG_UNUSED(conn);
	ARG_UNUSED(attr);

	/*
	 * The blob is 48 bytes, well past the 20 usable bytes of a default 23-byte ATT
	 * MTU, so a client that hasn't negotiated a larger MTU sends it as a long write:
	 * several Prepare Writes at increasing offsets, then one Execute Write. Zephyr
	 * reassembles contiguous fragments itself and calls this once with the first
	 * fragment's offset (att.c exec_write_reassemble), but a client is also free to
	 * write at explicit offsets, so chunks are staged here either way.
	 *
	 * The PREPARE flag only ever arrives for an attribute carrying
	 * BT_GATT_PERM_PREPARE_WRITE (att.c:2262), which this one does not; handling it is
	 * kept so that adding the permission cannot silently commit a half blob.
	 */
	if (flags & BT_GATT_WRITE_FLAG_PREPARE) {
		return 0;
	}

	/*
	 * Provisioning is only meaningful while unprovisioned. Refusing it once bound also
	 * surfaces a misdirected write: an 8-byte UWB session-params write landing here
	 * instead of on the UWB characteristic would otherwise be silently accepted as a
	 * partial chunk of a long write, and reported to the phone as success.
	 */
	if (state->provisioned) {
		LOG_WRN("provisioning write of %u bytes at offset %u refused: already "
			"provisioned (misdirected UWB write?)", len, offset);
		return BT_GATT_ERR(BT_ATT_ERR_WRITE_NOT_PERMITTED);
	}

	if (offset + len > sizeof(provision_staging)) {
		LOG_WRN("provisioning write past end of blob (offset=%u len=%u)", offset, len);
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}
	memcpy(&provision_staging[offset], data, len);
	total = offset + len;

	/*
	 * 40 counts as a complete blob only when it arrived in one piece. A 48-byte blob
	 * written at offsets 0/20/40 passes through total == 40 on its way, and treating
	 * that as complete committed provisioning with unix_seconds = 0, set `provisioned`,
	 * and then rejected its own final chunk at the guard above — leaving a tracker with
	 * no time base that can never beacon and can never be re-provisioned. Nothing can
	 * tell the two apart mid-write, so the 40-byte form has to arrive whole; every
	 * producer sends it that way (TrackerBle, tools/bench_provision.py), and it fits a
	 * single write at the MTU this build asks for.
	 */
	if (total != FF_PROVISION_BLOB_LEN &&
	    !(offset == 0U && len == FF_PROVISION_BLOB_LEN_NO_TIME)) {
		/* A partial chunk: acknowledge it and wait for the rest. */
		LOG_DBG("provisioning chunk: offset=%u len=%u", offset, len);
		return len;
	}
	data = provision_staging;

	user_id = sys_get_be64(data);
	if (total == FF_PROVISION_BLOB_LEN) {
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

	LOG_INF("provisioned: userId=%llu, %u-byte blob, unix=%llu", user_id, total,
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

	LOG_INF("UWB params write: len=%u offset=%u", len, offset);

	if (offset != 0U) {
		LOG_WRN("UWB params write rejected: offset=%u (expected 0)", offset);
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
	}
	if (len != FF_UWB_PARAMS_LEN) {
		LOG_WRN("UWB params write rejected: %u bytes (expected %d)", len,
			FF_UWB_PARAMS_LEN);
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}
	if (!state->provisioned) {
		/* Without the secret there is no session key, so there is nothing to
		 * start - and an unprovisioned tracker has no owner to be ranged by. */
		LOG_WRN("UWB params write rejected: not provisioned");
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

bool ff_ble_take_readvertise_event(void)
{
	return atomic_cas(&pending_readvertise, 1, 0);
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
	 * Beacon data set: extended, NON-connectable.
	 *
	 * BT_LE_ADV_OPT_EXT_ADV without OPT_CODED pins the secondary PHY to 1M, so a phone
	 * scanning with setLegacy(false) on the default PHY sees it.
	 *
	 * Deliberately NOT connectable. A connectable extended advertising set cannot carry
	 * advertising data at all in BLE 5 — ask for both and the controller keeps the data
	 * and quietly drops connectability, so the tracker beacons perfectly while being
	 * impossible to connect to. That cost an evening: the phone timed out with GATT
	 * status 147 while Android's stale service cache kept reporting success from a
	 * previous session. Connectability now lives on its own set below.
	 */
	const struct bt_le_adv_param beacon_param = {
		.id = BT_ID_DEFAULT,
		.options = BT_LE_ADV_OPT_EXT_ADV,
		.interval_min = BT_GAP_MS_TO_ADV_INTERVAL(CONFIG_FF_TRACKER_BEACON_INTERVAL_MS),
		.interval_max = BT_GAP_MS_TO_ADV_INTERVAL(CONFIG_FF_TRACKER_BEACON_INTERVAL_MS + 100),
		.secondary_max_skip = 0,
	};
	/*
	 * Connectable set, run alongside the beacon so the owner can still write UWB session
	 * params while crowd-finding. Legacy connectable and carries no data: finders locate
	 * the tracker by the beacon's rotating id, and the owner already knows its address,
	 * so advertising anything identifiable here would only leak a stable identifier.
	 */
	const struct bt_le_adv_param connect_param = {
		.id = BT_ID_DEFAULT,
		.options = BT_LE_ADV_OPT_CONN,
		.interval_min = BT_GAP_ADV_SLOW_INT_MIN,
		.interval_max = BT_GAP_ADV_SLOW_INT_MAX,
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

	/* No data on this one, deliberately — see connect_param. */
	rc = bt_le_ext_adv_create(&connect_param, NULL, &connect_adv);
	if (rc) {
		LOG_ERR("creating connectable adv set: %d", rc);
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
	bt_gatt_cb_register(&gatt_callbacks);
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

	LOG_INF("epoch=%llu id=%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x batt=%u%% %s",
		epoch, epoch_id[0], epoch_id[1], epoch_id[2], epoch_id[3], epoch_id[4],
		epoch_id[5], epoch_id[6], epoch_id[7], epoch_id[8], epoch_id[9],
		epoch_id[10], epoch_id[11], epoch_id[12], epoch_id[13], epoch_id[14],
		epoch_id[15], battery_percent,
		current_mode == FF_BLE_MODE_BEACON ? "(on air)" : "(NOT advertising)");

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

	desired_mode = mode;

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
		rc = bt_le_ext_adv_stop(connect_adv);
		if (rc && rc != -EALREADY) {
			LOG_WRN("stopping connectable adv: %d", rc);
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
			LOG_WRN("cannot beacon: no time base");
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
		/*
		 * Connectability is a separate set. Failing to start it is not fatal — the
		 * tracker is still findable, it just can't be ranged until the next attempt
		 * — so log and carry on rather than dropping out of beacon mode.
		 */
		rc = bt_le_ext_adv_start(connect_adv, BT_LE_EXT_ADV_START_DEFAULT);
		if (rc) {
			LOG_WRN("starting connectable adv: %d (UWB handover unavailable)", rc);
		}
		LOG_INF("beacon mode: rotating epoch id (extended) + connectable set");
		return 0;
	}
	return -EINVAL;
}

enum ff_ble_mode ff_ble_mode(void)
{
	return current_mode;
}

int ff_ble_readvertise(void)
{
	struct bt_le_ext_adv *adv;
	int rc;

	if (desired_mode != current_mode) {
		/* The mode we wanted never started — usually beacon mode attempted while
		 * the binding phone still held the connection slot. */
		return ff_ble_set_mode(desired_mode);
	}
	/*
	 * Otherwise the mode is already current, so ff_ble_set_mode() would return early
	 * and restart nothing — yet the connectable set of that mode is down, because the
	 * controller stops a connectable advertisement when it yields a connection and
	 * nothing in the host restarts an extended-API set. Left alone, the tracker takes
	 * exactly one connection per mode change: one UWB handover, or one attempt at a
	 * bind.
	 */
	if (current_mode == FF_BLE_MODE_BEACON) {
		adv = connect_adv;
	} else if (current_mode == FF_BLE_MODE_PAIRING) {
		adv = pairing_adv;
	} else {
		return 0;
	}

	/* -EALREADY means it is still running, which is not a failure. */
	rc = bt_le_ext_adv_start(adv, BT_LE_EXT_ADV_START_DEFAULT);
	if (rc && rc != -EALREADY) {
		LOG_WRN("restarting connectable adv: %d (not reachable until the next mode "
			"change)", rc);
		return rc;
	}
	return 0;
}
