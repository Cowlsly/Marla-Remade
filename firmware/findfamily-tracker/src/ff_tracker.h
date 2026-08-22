/*
 * FindFamily UWB tracker — internal interfaces.
 *
 * The wire formats here are fixed by the phone side; see
 * findfamily/src/main/java/com/vayunmathur/findfamily/tracker/TrackerBle.kt and
 * TrackerProtocol.kt. Any change has to be made in both places at once.
 */
#ifndef FF_TRACKER_H_
#define FF_TRACKER_H_

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

/* TrackerProtocol.SECRET_LEN */
#define FF_SECRET_LEN 32
/* TrackerProtocol.EPOCH_ID_LEN */
#define FF_EPOCH_ID_LEN 16
/* TrackerProtocol.EPOCH_SECONDS */
#define FF_EPOCH_SECONDS 900U

/* TrackerBle.PROVISION_BLOB_LEN / PROVISION_BLOB_LEN_NO_TIME */
#define FF_PROVISION_BLOB_LEN 48
#define FF_PROVISION_BLOB_LEN_NO_TIME 40

/* TrackerUwbGatt.encodeSessionParams:
 * [2B addr little-endian][4B sessionId BE][1B ch][1B preamble] */
#define FF_UWB_PARAMS_LEN 8

/* Service data payload: [16B epochId][1B battery%] */
#define FF_BEACON_DATA_LEN (FF_EPOCH_ID_LEN + 1)

/* Everything the tracker persists across resets. */
struct ff_tracker_state {
	uint64_t tracker_user_id;
	uint8_t secret[FF_SECRET_LEN];
	bool provisioned;
	/*
	 * Wall-clock seconds at the moment `uptime_at_base_ms` was taken. Zero means
	 * "no time base": the tracker cannot compute an epoch id anyone is looking for
	 * and stays quiet rather than beaconing garbage.
	 */
	uint64_t unix_at_base;
	int64_t uptime_at_base_ms;
};

/* ---- tracker_store.c: NVS-backed persistence ---------------------------- */

int ff_store_init(void);

/* Loads persisted state into `out`. Returns 0 even when nothing is stored yet, in
 * which case `out->provisioned` is false. */
int ff_store_load(struct ff_tracker_state *out);

int ff_store_save_provisioning(uint64_t tracker_user_id, const uint8_t *secret,
			       uint64_t unix_seconds);

/* Re-persists just the time base. Called once per epoch so a reset costs at most one
 * epoch of drift instead of the whole time base. */
int ff_store_save_time(uint64_t unix_seconds);

/* Wipes provisioning so the device can be re-bound. */
int ff_store_clear(void);

/* ---- tracker_epoch.c: rotating beacon id ------------------------------- */

/* Current wall-clock seconds, or 0 if there is no time base yet. */
uint64_t ff_now_unix(const struct ff_tracker_state *st);

/* epoch = unix_seconds / FF_EPOCH_SECONDS */
uint64_t ff_current_epoch(const struct ff_tracker_state *st);

/*
 * epochId = HMAC-SHA256(secret, "fftrk1" || u64_be(epoch))[..16].
 * Mirrors TrackerProtocol.epochId; `out` must hold FF_EPOCH_ID_LEN bytes.
 */
int ff_epoch_id(const uint8_t *secret, uint64_t epoch, uint8_t *out);

/* ---- tracker_ble.c: advertising + GATT --------------------------------- */

enum ff_ble_mode {
	/* Nothing on air: unprovisioned and not pairing, or muted by the user. */
	FF_BLE_MODE_IDLE,
	/* Legacy connectable adv of the unprovisioned service, awaiting a bind. */
	FF_BLE_MODE_PAIRING,
	/* Extended non-connectable adv carrying the rotating epoch id, plus a separate
	 * legacy connectable set so the owner can still write UWB session params. */
	FF_BLE_MODE_BEACON,
};

int ff_ble_init(struct ff_tracker_state *state);

int ff_ble_set_mode(enum ff_ble_mode mode);

enum ff_ble_mode ff_ble_mode(void);

/*
 * GATT writes are handled on the Bluetooth RX thread, whose stack is nowhere near big
 * enough for PSA crypto plus starting an advertisement — doing that work inline faults
 * with a stack overflow. The write callbacks therefore only record what arrived, and the
 * main loop drains it. Both of these return true once per event and clear it.
 */
bool ff_ble_take_provision_event(void);

/* Copies FF_UWB_PARAMS_LEN bytes into `out` when a session-params write is pending. */
bool ff_ble_take_uwb_params(uint8_t *out);

/*
 * True once after a disconnect. Both cases need it: a mode we could not start while the
 * connection slot was taken, and — in beacon mode — the connectable set the controller
 * stopped when it accepted that connection.
 */
bool ff_ble_take_readvertise_event(void);

/*
 * Puts advertising back to what it should be after a disconnect. Not the same as
 * ff_ble_set_mode(): that returns early when the mode is already current, which is exactly
 * the case where the connectable set needs restarting.
 */
int ff_ble_readvertise(void);

/* Recomputes the epoch id and pushes fresh service data into the beacon adv set. */
int ff_ble_refresh_beacon(uint8_t battery_percent);

/* ---- UWB (CONFIG_FF_TRACKER_UWB) --------------------------------------- */

#ifdef CONFIG_FF_TRACKER_UWB
/*
 * HKDF-SHA256 derivations mirroring TrackerUwbKeys.kt. `out` must hold 8 bytes for
 * the STS key and 2 for the address.
 */
int ff_sts_key(const uint8_t *secret, uint32_t session_id, uint8_t *out);
int ff_uwb_address(const uint8_t *secret, uint8_t *out);

struct ff_uwb_params {
	uint8_t peer_address[2]; /* the phone's UWB MAC, from the GATT write */
	uint32_t session_id;
	uint8_t channel;
	uint8_t preamble_index;
};

int ff_uwb_init(void);

/* Starts a FiRa responder/controlee session. Session key and our own address are
 * derived from `secret`, never taken from the wire. */
int ff_uwb_start(const struct ff_uwb_params *params, const uint8_t *secret);

void ff_uwb_stop(void);
#endif /* CONFIG_FF_TRACKER_UWB */

/* Handles a write to the UWB session characteristic. Defined in tracker_uwb.c when
 * CONFIG_FF_TRACKER_UWB is set, and stubbed in tracker_ble.c otherwise. */
int ff_uwb_on_params(const uint8_t *data, size_t len, const uint8_t *secret);

#endif /* FF_TRACKER_H_ */
