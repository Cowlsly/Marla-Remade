/*
 * FiRa responder/controlee session on the DW3110, via Qorvo's uwbstack.
 *
 * The phone is the FiRa controller/initiator: it mints the session id, channel,
 * preamble and its own MAC, and drives the ranging rounds. This device is the
 * controlee/responder. Session material is derived, not received — see tracker_sts.c
 * and TrackerUwbKeys.kt — so the 8 bytes written over GATT per find are only address,
 * session id, channel and preamble.
 *
 * Built only when CONFIG_FF_TRACKER_UWB=y, which needs the licensed Qorvo SDK; see
 * README.md and firmware/qorvo-uwb/.
 *
 * Interop caveat: the parameters below are FiRa/Qorvo defaults, which is the best
 * available guess at what Android's `UwbRangingParams.CONFIG_UNICAST_DS_TWR` asks for.
 * Slot duration, block duration and round length are session parameters under static
 * STS — they are configured identically on both ends rather than negotiated — so if
 * they disagree with what the phone picked, ranging simply never converges. Expect to
 * iterate here against a real phone.
 */
#include "ff_tracker.h"

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/byteorder.h>

/* Qorvo uwbstack. */
#include <ff_qorvo_l1_config.h>
#include <l1_config.h>
#include <llhw.h>
#include <net/fira_region_params.h>
#include <qplatform.h>
#include <quwbs/fbs/defs.h>
#include <uwbmac/fira_helper.h>
#include <uwbmac/uwbmac.h>

#include <string.h>

LOG_MODULE_REGISTER(ff_uwb, LOG_LEVEL_INF);

/*
 * The stack calls back from MAC context, so ranging results are only logged here.
 *
 * 8 KB because uwbmac_poll_events runs the whole MAC event loop on this stack — frame
 * assembly, crypto and region callbacks all nest below it. 4 KB tripped the MPU stack
 * guard immediately on the first poll.
 */
#define UWB_THREAD_STACK_SIZE 8192
#define UWB_THREAD_PRIORITY 5

/*
 * Ranging timing. These are the FiRa defaults (the vendor SDK's fira_default_params.h
 * carries the same values, but that header ships with its sample apps rather than the
 * library, so they are spelled out here).
 *
 * Under static STS these are *not* negotiated: the controller and controlee each
 * configure them and they have to agree, so these three constants are the most likely
 * thing to need changing if the phone won't range. 2400 RSTU is 2 ms; 25 slots of that
 * inside a 200 ms block leaves the radio idle most of the time.
 */
#define SLOT_DURATION_RSTU 2400
#define BLOCK_DURATION_MS 200
#define ROUND_DURATION_SLOTS 25

static struct uwbmac_context *uwbmac_ctx;
static struct fira_context fira_ctx;
static uint32_t session_handle;
static bool stack_ready;
static bool session_active;

static K_THREAD_STACK_DEFINE(uwb_thread_stack, UWB_THREAD_STACK_SIZE);
static struct k_thread uwb_thread;
static atomic_t poll_running;

/*
 * Board calibration hook, from the vendor's DWM3001CDK support (built by
 * firmware/qorvo-uwb). It loads this module's factory calibration — antenna delays,
 * crystal trim, TX power — out of the DW3110's OTP. l1_config_init() returns
 * QERR_ENOTSUP without it.
 */
extern struct l1_config_platform_ops l1_config_platform_ops;

/*
 * Bring-up diagnostic from firmware/qorvo-uwb: reads DEV_ID straight over Zephyr's SPI,
 * bypassing the Qorvo driver. A DW3110 answers 0xDECA0302.
 */
uint32_t ff_qorvo_read_dev_id(void);

static void on_fira_notification(enum fira_helper_cb_type cb_type, const void *content,
				 void *user_data)
{
	ARG_UNUSED(user_data);

	switch (cb_type) {
	case FIRA_HELPER_CB_TYPE_TWR_RANGE_NTF: {
		const struct fira_twr_ranging_results *res = content;

		for (int i = 0; i < res->n_measurements; i++) {
			const struct fira_twr_measurements *m = &res->measurements[i];

			/*
			 * The phone computes the arrow from its own multi-antenna array;
			 * the tracker is single-port and has no AoA to report. Logging the
			 * distance is purely so the bench can see the session converge.
			 */
			LOG_INF("range: peer=%04x status=%u distance=%dcm rssi=%u",
				m->short_addr, m->status, m->distance_cm, m->rssi);
		}
		break;
	}
	case FIRA_HELPER_CB_TYPE_SESSION_STATUS_NTF: {
		const struct fbs_helper_session_status_ntf *ntf = content;

		LOG_INF("session %u state=%d reason=%d", ntf->session_handle, ntf->state,
			ntf->reason_code);
		break;
	}
	default:
		LOG_DBG("fira notification type %d", cb_type);
		break;
	}
}

static void uwb_poll_thread(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	while (atomic_get(&poll_running)) {
		/* Drives the MAC event loop; notifications are dispatched from here. */
		(void)uwbmac_poll_events(uwbmac_ctx, 100000);
	}
}

int ff_uwb_init(void)
{
	enum qerr r;

	if (stack_ready) {
		return 0;
	}

	/*
	 * Order matters and is not obvious. qplatform_init() brings up SPI/GPIO and probes
	 * the DW3110, which has to happen first: l1_config_init()'s reset_to_default hook
	 * reads this module's calibration out of the transceiver's OTP, and without a
	 * probed driver that dereferences an unset interface pointer and bus-faults.
	 */
	r = qplatform_init();
	if (r != QERR_SUCCESS) {
		LOG_ERR("qplatform_init: %d", r);
		/*
		 * Read the id straight off the bus to tell a wiring or SPI-mode problem
		 * apart from the driver rejecting what it read, which qplatform_init's
		 * return code alone cannot. Only on the failure path: qplatform_init resets
		 * and wakes the transceiver, so before it runs the part is still asleep and
		 * this reads zero even when everything is fine.
		 */
		LOG_ERR("DEV_ID read directly: 0x%08x (expect 0xdeca0302)",
			ff_qorvo_read_dev_id());
		return -EIO;
	}

	/*
	 * Must precede l1_config_init, which stores its defaults back to this storage.
	 * As shipped it points into the image, where the store would target flash.
	 */
	ff_qorvo_l1_config_use_ram_storage();

	r = l1_config_init(&l1_config_platform_ops);
	if (r != QERR_SUCCESS) {
		LOG_ERR("l1_config_init: %d", r);
		qplatform_deinit();
		return -EIO;
	}
	r = llhw_init();
	if (r != QERR_SUCCESS) {
		LOG_ERR("llhw_init: %d", r);
		l1_config_deinit();
		qplatform_deinit();
		return -EIO;
	}
	r = uwbmac_init(&uwbmac_ctx);
	if (r != QERR_SUCCESS) {
		LOG_ERR("uwbmac_init: %d", r);
		llhw_deinit();
		l1_config_deinit();
		qplatform_deinit();
		return -EIO;
	}

	stack_ready = true;
	LOG_INF("Qorvo uwbstack up; DW3110 identified");
	return 0;
}

/*
 * The MAC event loop is only driven while a session exists. Starting it at init time
 * faulted with a corrupted return address: there is nothing for uwbmac_poll_events to
 * dispatch before uwbmac_start(), and polling an unstarted MAC is not a supported state.
 */
static void start_polling(void)
{
	if (atomic_cas(&poll_running, 0, 1)) {
		k_thread_create(&uwb_thread, uwb_thread_stack, UWB_THREAD_STACK_SIZE,
				uwb_poll_thread, NULL, NULL, NULL, UWB_THREAD_PRIORITY, 0,
				K_NO_WAIT);
		k_thread_name_set(&uwb_thread, "ff_uwb");
	}
}

static void stop_polling(void)
{
	atomic_set(&poll_running, 0);
}

/*
 * Push a filled session_parameters into the session. The stack has no bulk setter —
 * only a bulk *getter* — so each field goes in individually, the same way the vendor
 * SDK's fira_set_session_parameters does it.
 */
#define SET_PARAM(field)                                                                  \
	do {                                                                              \
		enum qerr _r = fira_helper_set_session_##field(&fira_ctx, session_handle, \
							       sp->field);                \
		if (_r != QERR_SUCCESS) {                                                 \
			LOG_ERR("set " #field " failed: %d", _r);                         \
			return -EIO;                                                      \
		}                                                                         \
	} while (0)

static int apply_session_params(const struct session_parameters *sp)
{
	enum qerr r;

	SET_PARAM(channel_number);
	SET_PARAM(preamble_code_index);
	SET_PARAM(sfd_id);
	SET_PARAM(phr_data_rate);
	SET_PARAM(prf_mode);
	SET_PARAM(device_type);
	SET_PARAM(device_role);
	SET_PARAM(multi_node_mode);
	SET_PARAM(rframe_config);
	SET_PARAM(slot_duration_rstu);
	SET_PARAM(block_duration_ms);
	SET_PARAM(round_duration_slots);
	SET_PARAM(ranging_round_usage);
	SET_PARAM(round_hopping);
	SET_PARAM(block_stride_length);
	SET_PARAM(schedule_mode);
	SET_PARAM(result_report_config);
	SET_PARAM(ranging_round_control);
	SET_PARAM(report_rssi);

	r = fira_helper_set_session_vupper64(&fira_ctx, session_handle,
					     (uint8_t *)sp->vupper64);
	if (r != QERR_SUCCESS) {
		LOG_ERR("set vupper64 failed: %d", r);
		return -EIO;
	}
	r = fira_helper_set_session_short_address(&fira_ctx, session_handle, sp->short_addr);
	if (r != QERR_SUCCESS) {
		LOG_ERR("set short_address failed: %d", r);
		return -EIO;
	}
	r = fira_helper_set_session_destination_short_addresses(
		&fira_ctx, session_handle, sp->n_destination_short_address,
		(uint16_t *)sp->destination_short_address);
	if (r != QERR_SUCCESS) {
		LOG_ERR("set destination_short_addresses failed: %d", r);
		return -EIO;
	}
	r = fira_helper_set_session_measurement_sequence(&fira_ctx, session_handle,
							 &sp->meas_seq);
	if (r != QERR_SUCCESS) {
		LOG_ERR("set measurement_sequence failed: %d", r);
		return -EIO;
	}
	return 0;
}

#undef SET_PARAM

int ff_uwb_start(const struct ff_uwb_params *params, const uint8_t *secret)
{
	struct session_parameters sp = { 0 };
	struct fbs_session_init_rsp rsp;
	uint8_t vupper64[FIRA_VUPPER64_SIZE];
	uint8_t own_address[2];
	uint16_t own_short_addr;
	uint16_t peer_short_addr;
	enum qerr r;
	int rc;

	if (!stack_ready) {
		return -EAGAIN;
	}
	if (session_active) {
		ff_uwb_stop();
	}

	/*
	 * vUpper64 is exactly [2B VendorID][6B STATIC_STS_IV], which is the same 8 bytes
	 * Android hands to UwbRangingParams.setSessionKeyInfo. So the derived STS key is
	 * used verbatim on both sides with no reinterpretation.
	 */
	rc = ff_sts_key(secret, params->session_id, vupper64);
	if (rc) {
		return rc;
	}
	rc = ff_uwb_address(secret, own_address);
	if (rc) {
		return rc;
	}
	own_short_addr = sys_get_le16(own_address);
	peer_short_addr = sys_get_le16(params->peer_address);

	r = uwbmac_set_short_addr(uwbmac_ctx, own_short_addr);
	if (r != QERR_SUCCESS) {
		LOG_ERR("uwbmac_set_short_addr: %d", r);
		return -EIO;
	}

	r = fira_helper_open(&fira_ctx, uwbmac_ctx, on_fira_notification, "endless", 0, NULL);
	if (r != QERR_SUCCESS) {
		LOG_ERR("fira_helper_open: %d", r);
		return -EIO;
	}
	/* Must happen while the MAC is stopped. */
	r = fira_helper_set_scheduler(&fira_ctx);
	if (r != QERR_SUCCESS) {
		LOG_ERR("fira_helper_set_scheduler: %d", r);
		goto close;
	}

	r = fira_helper_init_session(&fira_ctx, params->session_id,
				     QUWBS_FBS_SESSION_TYPE_RANGING_NO_IN_BAND_DATA, &rsp);
	if (r != QERR_SUCCESS) {
		LOG_ERR("fira_helper_init_session: %d", r);
		goto close;
	}
	session_handle = rsp.session_handle;

	/* Roles: the phone controls and initiates, we respond. */
	sp.device_type = QUWBS_FBS_DEVICE_TYPE_CONTROLEE;
	sp.device_role = QUWBS_FBS_DEVICE_ROLE_RESPONDER;

	/* From the phone, per find. */
	sp.channel_number = params->channel;
	sp.preamble_code_index = params->preamble_index;
	sp.short_addr = own_short_addr;
	sp.n_destination_short_address = 1;
	sp.destination_short_address[0] = peer_short_addr;
	memcpy(sp.vupper64, vupper64, sizeof(vupper64));

	/* One initiator, one responder, deferred DS-TWR: the FiRa shape of Android's
	 * CONFIG_UNICAST_DS_TWR. */
	sp.multi_node_mode = FIRA_MULTI_NODE_MODE_UNICAST;
	sp.ranging_round_usage = FIRA_RANGING_ROUND_USAGE_DSTWR_DEFERRED;
	sp.schedule_mode = FIRA_SCHEDULE_MODE_TIME_SCHEDULED;
	sp.rframe_config = FIRA_RFRAME_CONFIG_SP3;
	sp.sfd_id = FIRA_SFD_ID_2;
	sp.prf_mode = FIRA_PRF_MODE_BPRF;
	sp.phr_data_rate = FIRA_PRF_MODE_BPRF;
	sp.slot_duration_rstu = SLOT_DURATION_RSTU;
	sp.block_duration_ms = BLOCK_DURATION_MS;
	sp.round_duration_slots = ROUND_DURATION_SLOTS;
	sp.round_hopping = false;
	sp.block_stride_length = 0;
	sp.report_rssi = 1;
	sp.enable_diagnostics = false;

	/* Report time-of-flight; no AoA, the DW3110 has a single RF port. */
	sp.result_report_config = fira_helper_bool_to_result_report_config(true, false, false, false);
	sp.ranging_round_control = fira_helper_bool_to_ranging_round_control(true, false);

	/* Single range-only measurement step, all antenna sets left to the driver. */
	sp.meas_seq.n_steps = 1;
	sp.meas_seq.steps[0].type = FIRA_MEASUREMENT_TYPE_RANGE;
	sp.meas_seq.steps[0].n_measurements = 1;
	sp.meas_seq.steps[0].rx_ant_set_nonranging = 0xff;
	sp.meas_seq.steps[0].rx_ant_sets_ranging[0] = 0xff;
	sp.meas_seq.steps[0].rx_ant_sets_ranging[1] = 0xff;
	sp.meas_seq.steps[0].tx_ant_set_nonranging = 0xff;
	sp.meas_seq.steps[0].tx_ant_set_ranging = 0xff;

	rc = apply_session_params(&sp);
	if (rc) {
		goto deinit;
	}

	r = uwbmac_start(uwbmac_ctx);
	if (r != QERR_SUCCESS) {
		LOG_ERR("uwbmac_start: %d", r);
		goto deinit;
	}
	r = fira_helper_start_session(&fira_ctx, session_handle);
	if (r != QERR_SUCCESS) {
		LOG_ERR("fira_helper_start_session: %d", r);
		uwbmac_stop(uwbmac_ctx);
		goto deinit;
	}

	session_active = true;
	start_polling();
	LOG_INF("FiRa responder started: session=%08x ch=%u preamble=%u own=%04x peer=%04x",
		params->session_id, params->channel, params->preamble_index, own_short_addr,
		peer_short_addr);
	return 0;

deinit:
	(void)fira_helper_deinit_session(&fira_ctx, session_handle);
close:
	fira_helper_close(&fira_ctx);
	return -EIO;
}

void ff_uwb_stop(void)
{
	if (!session_active) {
		return;
	}
	stop_polling();
	(void)fira_helper_stop_session(&fira_ctx, session_handle);
	(void)fira_helper_deinit_session(&fira_ctx, session_handle);
	(void)uwbmac_stop(uwbmac_ctx);
	fira_helper_close(&fira_ctx);
	session_active = false;
	LOG_INF("FiRa session stopped");
}

int ff_uwb_on_params(const uint8_t *data, size_t len, const uint8_t *secret)
{
	struct ff_uwb_params params;

	if (len != FF_UWB_PARAMS_LEN) {
		return -EINVAL;
	}
	/* TrackerUwbGatt.encodeSessionParams: [2B addr][4B sessionId BE][1B ch][1B pre] */
	params.peer_address[0] = data[0];
	params.peer_address[1] = data[1];
	params.session_id = sys_get_be32(&data[2]);
	params.channel = data[6];
	params.preamble_index = data[7];

	return ff_uwb_start(&params, secret);
}
