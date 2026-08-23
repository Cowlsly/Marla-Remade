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
/*
 * Below the session thread that creates it (6), and below every vendor MAC thread: qosal
 * runs CRITICAL=0..IDLE=6 (qthread.h) and firmware/qorvo-uwb/src/qthread_zephyr.c maps
 * that straight onto K_PRIO_PREEMPT(0..6), so the MAC occupies Zephyr 0..6. At 5 this
 * thread preempted its own creator the instant k_thread_create() returned, which left
 * fira_helper_start_session() — the call immediately after start_polling() — reachable
 * only if the poll loop happened to give the CPU back.
 */
#define UWB_THREAD_PRIORITY 7

/*
 * Ranging timing, and the STS/PHY settings that go with it.
 *
 * These are NOT free choices: under static STS none of it is negotiated, so the controlee
 * has to be configured identically to the controller or it listens on the wrong schedule
 * and never hears a poll. The values below were read off the wire — Android's UWB HAL logs
 * its raw UCI SESSION_SET_APP_CONFIG, and these are the TLVs it sends for
 * RangingParams CONFIG_UNICAST_DS_TWR:
 *
 *   SLOT_DURATION      2400 RSTU (2 ms)
 *   RANGING_DURATION    120 ms   <- block duration
 *   SLOTS_PER_RR          6
 *   HOPPING_MODE          1      <- enabled
 *
 * Guessing these from the FiRa defaults instead (200 ms / 25 slots / no hopping) produced a
 * session where the phone reported UCI status 0x21, RX timeout, on every round.
 */
#define SLOT_DURATION_RSTU 2400
#define BLOCK_DURATION_MS 120
#define ROUND_DURATION_SLOTS 6
#define ROUND_HOPPING true

static struct uwbmac_context *uwbmac_ctx;
static struct fira_context fira_ctx;
static uint32_t session_handle;
static bool stack_ready;
static bool session_active;

static K_THREAD_STACK_DEFINE(uwb_thread_stack, UWB_THREAD_STACK_SIZE);
static struct k_thread uwb_thread;
static atomic_t poll_running;
/* Only ever touched from the session thread, which is the only caller of start/stop. */
static bool poll_thread_created;
/*
 * k_uptime_get_32() at session start, then at every notification. Written from MAC
 * context and read by the session thread, so it goes through atomic_t.
 */
static atomic_t last_activity_ms;

/*
 * Session setup runs on its own thread rather than on the caller's.
 *
 * The fira_helper_* and uwbmac_start calls block, and at least one of them can block
 * indefinitely when the stack is unhappy. Called straight from the main loop — which is
 * also what refreshes the beacon and drives the mode state machine — a stall there took
 * the whole tracker off the air, so crowd-finding died along with ranging. Keeping it on a
 * separate thread means the worst a wedged UWB session can do is lose UWB.
 *
 * 8 KB for the same reason as the poll thread: fira_helper_open, init_session, the 23
 * setters and uwbmac_start all nest MAC work onto this stack, and 4 KB was half what that
 * was measured to need.
 */
#define UWB_SESSION_THREAD_STACK_SIZE 8192
#define UWB_SESSION_THREAD_PRIORITY 6

/*
 * A find is about 10 s of ranging, and the phone drops the GATT link before ranging even
 * starts (TrackerUwbGatt.writeSessionParams disconnects as soon as the write completes),
 * so a BLE disconnect is not a teardown signal — using one would kill the session before
 * the first round. Stop instead once the session has been silent this long: either the
 * find finished, or it never worked. Without this the radio and a stale session stay up
 * until the next params write or a reset.
 */
#define SESSION_IDLE_TIMEOUT_MS 30000

static K_THREAD_STACK_DEFINE(uwb_session_stack, UWB_SESSION_THREAD_STACK_SIZE);
static struct k_thread uwb_session_thread_data;
static K_SEM_DEFINE(session_request, 0, 1);
static struct ff_uwb_params requested_params;
static uint8_t requested_secret[FF_SECRET_LEN];

static void uwb_session_thread(void *a, void *b, void *c);

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

/*
 * Bring-up instrumentation for the DW3110 interrupt (firmware/qorvo-uwb). llhw arms the
 * interrupt inside llhw_init(), before any session exists. If the line is already asserted by
 * the time it is armed, the rising-edge trigger can never fire and the MAC is never told a
 * frame arrived - which is indistinguishable, in a capture, from a radio that heard nothing.
 * Reported at three fixed points rather than periodically, so it cannot flood the capture.
 */
int ff_qorvo_irq_pin_level(void);
void ff_qorvo_gpio_irq_stats(uint32_t *events, uint32_t *arm_calls);
void ff_qorvo_spi_isr_stats(uint32_t *total, uint32_t *in_isr);
void ff_qorvo_timer_stats(uint32_t *starts, uint32_t *stops, uint32_t *expiries,
			  uint32_t *last_us);

static void log_irq_diag(const char *when)
{
	uint32_t events = 0;
	uint32_t arm_calls = 0;
	uint32_t xfers = 0;
	uint32_t xfers_isr = 0;
	uint32_t t_starts = 0;
	uint32_t t_stops = 0;
	uint32_t t_expiries = 0;
	uint32_t t_last_us = 0;

	ff_qorvo_gpio_irq_stats(&events, &arm_calls);
	ff_qorvo_spi_isr_stats(&xfers, &xfers_isr);
	ff_qorvo_timer_stats(&t_starts, &t_stops, &t_expiries, &t_last_us);
	LOG_INF("IRQ diag (%s): line level=%d, armed %u time(s), ISR entries=%u, "
		"spi xfers=%u (%u from ISR)", when, ff_qorvo_irq_pin_level(), arm_calls,
		events, xfers, xfers_isr);
	LOG_INF("IRQ diag (%s): timer starts=%u stops=%u expiries=%u last_us=%u", when,
		t_starts, t_stops, t_expiries, t_last_us);
}

/*
 * Decoders for the two enums that distinguish the failures this bring-up has to tell
 * apart: "never heard anything" (RX_TIMEOUT) from "heard it, STS wrong"
 * (RX_PHY_STS_FAILED) from "the region rejected a parameter" (the ERROR_INVALID_* reason
 * codes). Switches rather than a table so the compiler ties each name to its enumerator.
 * Only the codes a static-STS unicast controlee can produce are listed; anything else
 * still prints as a number.
 */
static const char *ranging_status_name(uint8_t status)
{
	switch (status) {
	case QUWBS_FBS_STATUS_RANGING_SUCCESS:
		return "SUCCESS";
	case QUWBS_FBS_STATUS_RANGING_TX_FAILED:
		return "TX_FAILED";
	case QUWBS_FBS_STATUS_RANGING_RX_TIMEOUT:
		return "RX_TIMEOUT";
	case QUWBS_FBS_STATUS_RANGING_RX_PHY_DEC_FAILED:
		return "RX_PHY_DEC_FAILED";
	case QUWBS_FBS_STATUS_RANGING_RX_PHY_TOA_FAILED:
		return "RX_PHY_TOA_FAILED";
	case QUWBS_FBS_STATUS_RANGING_RX_PHY_STS_FAILED:
		return "RX_PHY_STS_FAILED";
	case QUWBS_FBS_STATUS_RANGING_RX_MAC_DEC_FAILED:
		return "RX_MAC_DEC_FAILED";
	case QUWBS_FBS_STATUS_RANGING_RX_MAC_IE_DEC_FAILED:
		return "RX_MAC_IE_DEC_FAILED";
	case QUWBS_FBS_STATUS_RANGING_RX_MAC_IE_MISSING:
		return "RX_MAC_IE_MISSING";
	case QUWBS_FBS_STATUS_RANGING_INTERNAL_ERROR:
		return "INTERNAL_ERROR";
	default:
		return "?";
	}
}

static const char *session_state_name(enum quwbs_fbs_session_state state)
{
	switch (state) {
	case QUWBS_FBS_SESSION_STATE_INIT:
		return "INIT";
	case QUWBS_FBS_SESSION_STATE_DEINIT:
		return "DEINIT";
	case QUWBS_FBS_SESSION_STATE_ACTIVE:
		return "ACTIVE";
	case QUWBS_FBS_SESSION_STATE_IDLE:
		return "IDLE";
	default:
		return "?";
	}
}

static const char *reason_code_name(enum quwbs_fbs_reason_code code)
{
	switch (code) {
	case QUWBS_FBS_REASON_CODE_SUCCESS:
		/* Also SESSION_MANAGEMENT_COMMANDS, which shares the value 0. */
		return "SUCCESS/STATE_CHANGE_REQUESTED";
	case QUWBS_FBS_REASON_CODE_MAX_RANGING_ROUND_RETRY_COUNT_REACHED:
		return "MAX_RANGING_ROUND_RETRY_COUNT_REACHED";
	case QUWBS_FBS_REASON_CODE_MAX_NUMBER_OF_MEASUREMENTS_REACHED:
		return "MAX_NUMBER_OF_MEASUREMENTS_REACHED";
	case QUWBS_FBS_REASON_CODE_ERROR_SLOT_LENGTH_NOT_SUPPORTED:
		return "ERROR_SLOT_LENGTH_NOT_SUPPORTED";
	case QUWBS_FBS_REASON_CODE_ERROR_INSUFFICIENT_SLOTS_PER_RR:
		return "ERROR_INSUFFICIENT_SLOTS_PER_RR";
	case QUWBS_FBS_REASON_CODE_ERROR_MAC_ADDRESS_MODE_NOT_SUPPORTED:
		return "ERROR_MAC_ADDRESS_MODE_NOT_SUPPORTED";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_RANGING_DURATION:
		return "ERROR_INVALID_RANGING_DURATION";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_STS_CONFIG:
		return "ERROR_INVALID_STS_CONFIG";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_RFRAME_CONFIG:
		return "ERROR_INVALID_RFRAME_CONFIG";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_PREAMBLE_CODE_INDEX:
		return "ERROR_INVALID_PREAMBLE_CODE_INDEX";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_SFD_ID:
		return "ERROR_INVALID_SFD_ID";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_PHR_DATA_RATE:
		return "ERROR_INVALID_PHR_DATA_RATE";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_PREAMBLE_DURATION:
		return "ERROR_INVALID_PREAMBLE_DURATION";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_STS_LENGTH:
		return "ERROR_INVALID_STS_LENGTH";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_NUM_OF_STS_SEGMENTS:
		return "ERROR_INVALID_NUM_OF_STS_SEGMENTS";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_DST_ADDRESS_LIST:
		return "ERROR_INVALID_DST_ADDRESS_LIST";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_RESULT_REPORT_CONFIG:
		return "ERROR_INVALID_RESULT_REPORT_CONFIG";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_RANGING_ROUND_CONTROL_CONFIG:
		return "ERROR_INVALID_RANGING_ROUND_CONTROL_CONFIG";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_RANGING_ROUND_USAGE:
		return "ERROR_INVALID_RANGING_ROUND_USAGE";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_MULTI_NODE_MODE:
		return "ERROR_INVALID_MULTI_NODE_MODE";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_SCHEDULE_MODE:
		return "ERROR_INVALID_SCHEDULE_MODE";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_PRF_MODE:
		return "ERROR_INVALID_PRF_MODE";
	case QUWBS_FBS_REASON_CODE_ERROR_INVALID_DEVICE_ROLE:
		return "ERROR_INVALID_DEVICE_ROLE";
	case QUWBS_FBS_REASON_CODE_ERROR_UWB_INITIATION_TIME_EXPIRED:
		return "ERROR_UWB_INITIATION_TIME_EXPIRED";
	case QUWBS_FBS_REASON_CODE_ERROR_DRIVER_DOWN:
		return "ERROR_DRIVER_DOWN";
	case QUWBS_FBS_REASON_CODE_ERROR_NOMEM:
		return "ERROR_NOMEM";
	default:
		return "?";
	}
}

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
			 * Only a measurement that worked counts as the session being
			 * alive (see SESSION_IDLE_TIMEOUT_MS). A responder that is
			 * hearing nothing may still report a round per block with
			 * RX_TIMEOUT, and treating that as activity would keep a session
			 * that has never worked up forever.
			 */
			if (m->status == QUWBS_FBS_STATUS_RANGING_SUCCESS) {
				atomic_set(&last_activity_ms,
					   (atomic_val_t)k_uptime_get_32());
			}

			/*
			 * The phone computes the arrow from its own multi-antenna array;
			 * the tracker is single-port and has no AoA to report. Logging the
			 * distance is purely so the bench can see the session converge.
			 */
			LOG_INF("range: peer=%04x status=%u (%s) distance=%dcm rssi=%u",
				m->short_addr, m->status, ranging_status_name(m->status),
				m->distance_cm, m->rssi);
		}
		break;
	}
	case FIRA_HELPER_CB_TYPE_SESSION_STATUS_NTF: {
		const struct fbs_helper_session_status_ntf *ntf = content;

		/* Not periodic, so it cannot hold the idle timeout off indefinitely. */
		atomic_set(&last_activity_ms, (atomic_val_t)k_uptime_get_32());
		LOG_INF("session %u state=%d (%s) reason=%d (%s)", ntf->session_handle,
			ntf->state, session_state_name(ntf->state), ntf->reason_code,
			reason_code_name(ntf->reason_code));
		break;
	}
	default:
		LOG_DBG("fira notification type %d", cb_type);
		break;
	}
}

static void uwb_poll_thread(void *a, void *b, void *c)
{
	enum qerr last_r = QERR_SUCCESS;
	bool logged = false;

	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	while (atomic_get(&poll_running)) {
		/* Drives the MAC event loop; notifications are dispatched from here. */
		enum qerr r = uwbmac_poll_events(uwbmac_ctx, 100000);

		if (!logged || r != last_r) {
			/*
			 * uwbmac.h:331-336 documents a non-zero timeout as blocking, but
			 * :328-329 also says the call only exists when uwbmac_init() was
			 * passed a NULL event_loop_ops — a parameter this delivery's
			 * uwbmac_init() does not have. So the return value is the only way
			 * to know whether the pump works here at all; an immediate error
			 * means nothing is dispatching notifications.
			 */
			LOG_INF("uwbmac_poll_events: %d", r);
			last_r = r;
			logged = true;
		}
		/*
		 * Yield unconditionally, in case the call turns out not to block. This is
		 * defence, not the mechanism: session-start liveness comes from this
		 * thread running *below* the session thread (see UWB_THREAD_PRIORITY).
		 * 1 ms against a 120 ms ranging block costs nothing, and the tick here is
		 * 32768 Hz so it really is ~1 ms.
		 */
		k_sleep(K_MSEC(1));
	}
}

/*
 * Started immediately after uwbmac_start(), because fira_helper_start_session() is a
 * request/response over the MAC and needs its events dispatched to complete.
 */
static void start_polling(void)
{
	if (poll_thread_created) {
		/*
		 * A previous stop_polling() could not join it. If it has exited since,
		 * replace it; otherwise re-arm the one that is there, because creating a
		 * second thread over a live k_thread would corrupt the scheduler. A failed
		 * join only says the thread has not terminated — if it is wedged inside
		 * uwbmac_poll_events() this session has no working pump, hence the warning.
		 */
		if (k_thread_join(&uwb_thread, K_NO_WAIT) != 0) {
			LOG_WRN("reusing a poll thread that would not exit; events may not "
				"be dispatched");
			atomic_set(&poll_running, 1);
			return;
		}
		poll_thread_created = false;
	}
	atomic_set(&poll_running, 1);
	k_thread_create(&uwb_thread, uwb_thread_stack, UWB_THREAD_STACK_SIZE,
			uwb_poll_thread, NULL, NULL, NULL, UWB_THREAD_PRIORITY, 0,
			K_NO_WAIT);
	k_thread_name_set(&uwb_thread, "ff_uwb");
	poll_thread_created = true;
}

static void stop_polling(void)
{
	if (!poll_thread_created) {
		return;
	}
	atomic_set(&poll_running, 0);
	/*
	 * The loop can be inside a poll with a 100 ms timeout, so give it room. If it
	 * somehow does not come back, leave the thread object alone and let
	 * start_polling() sort it out; the alternative is creating a second thread over a
	 * live k_thread.
	 */
	if (k_thread_join(&uwb_thread, K_MSEC(500)) != 0) {
		LOG_ERR("poll thread did not exit within 500 ms");
		return;
	}
	poll_thread_created = false;
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
	log_irq_diag("after llhw_init");

	k_thread_create(&uwb_session_thread_data, uwb_session_stack,
			UWB_SESSION_THREAD_STACK_SIZE, uwb_session_thread, NULL, NULL, NULL,
			UWB_SESSION_THREAD_PRIORITY, 0, K_NO_WAIT);
	k_thread_name_set(&uwb_session_thread_data, "ff_uwb_sess");
	return 0;
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
	/*
	 * Zero-init is load-bearing. apply_session_params() never pushes sts_config,
	 * preamble_duration, psdu_data_rate, sts_length, number_of_sts_segments or
	 * enable_diagnostics, so the FiRa region's own defaults govern those and the
	 * struct values are only a starting point. Adding setters for them would change
	 * behaviour silently: fira_helper_set_session_sts_config(.., 0x01) selects Dynamic
	 * STS rather than Static (fira_helper.h:2036-2050), and sp.preamble_duration == 0
	 * is FIRA_PREAMBLE_DURATION_32, not 64.
	 */
	struct session_parameters sp = { 0 };
	struct fbs_session_init_rsp rsp = { 0 };
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

	/* Both are derived from the bind-time secret, never taken from the wire. */
	rc = ff_sts_key(secret, params->session_id, vupper64);
	if (rc) {
		return rc;
	}
	rc = ff_uwb_address(secret, own_address);
	if (rc) {
		return rc;
	}
	/*
	 * Little-endian, i.e. byte 0 is the low byte. That is the 802.15.4 convention and
	 * what the vendor's own BLE-provisioned accessory does with an out-of-band address:
	 * AR2U16(x) = ((x[1] << 8) | x[0]) (QANI fira_niq.c:43,218-220). The phone hands the
	 * same two bytes to UwbAddress.fromBytes() without documenting an order, so the
	 * summary log below prints both readings to settle it against a RangingSession log.
	 */
	own_short_addr = sys_get_le16(own_address);
	peer_short_addr = sys_get_le16(params->peer_address);

	r = uwbmac_set_short_addr(uwbmac_ctx, own_short_addr);
	if (r != QERR_SUCCESS) {
		LOG_ERR("uwbmac_set_short_addr: %d", r);
		return -EIO;
	}
	/*
	 * Passing true *disables* hardware address filtering (uwbmac.h:580-590; the comment
	 * at fira_niq.c:125 says the opposite of what its own argument does). Without it the
	 * DW3110 drops the phone's polls before the MAC sees them if the two ends disagree
	 * about the address byte order, and there is no error on either side to say so.
	 * QANI, the only vendor BLE-provisioned responder, does the same.
	 */
	r = uwbmac_set_promiscuous_mode(uwbmac_ctx, true);
	if (r != QERR_SUCCESS) {
		LOG_ERR("uwbmac_set_promiscuous_mode: %d", r);
		return -EIO;
	}

	/* Per-step markers from here on: any of these vendor calls can be the one that does
	 * not return, and which one it is only shows in what the trace stops after. */
	LOG_INF("fira_helper_open");
	r = fira_helper_open(&fira_ctx, uwbmac_ctx, on_fira_notification, "endless", 0, NULL);
	if (r != QERR_SUCCESS) {
		LOG_ERR("fira_helper_open: %d", r);
		return -EIO;
	}
	/* Must happen while the MAC is stopped. */
	LOG_INF("fira_helper_set_scheduler");
	r = fira_helper_set_scheduler(&fira_ctx);
	if (r != QERR_SUCCESS) {
		LOG_ERR("fira_helper_set_scheduler: %d", r);
		goto close;
	}

	LOG_INF("fira_helper_init_session");
	r = fira_helper_init_session(&fira_ctx, params->session_id,
				     QUWBS_FBS_SESSION_TYPE_RANGING_NO_IN_BAND_DATA, &rsp);
	if (r != QERR_SUCCESS || rsp.status_code != QUWBS_FBS_STATUS_OK) {
		/* The qerr return and the FBS status are separate answers; a session that
		 * was refused reports it in rsp.status_code only. */
		LOG_ERR("fira_helper_init_session: %d (status %d)", r, rsp.status_code);
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
	/*
	 * The derived 8 bytes are [2B VendorID][6B STATIC_STS_IV], the order Android hands
	 * to setSessionKeyInfo. The Qorvo struct holds the opposite layout — a union over
	 * { static_sts_iv[6]; vendor_id[2]; } (fira_helper.h:382-397) — and its own NI
	 * accessory fills the array as a full reversal of VendorID||StaticStsIv
	 * (fira_niq.c:228-235). Follow that: a straight memcpy puts VendorID into
	 * static_sts_iv[0..1] and the STS silently never matches (tracker_sts.c).
	 */
	for (size_t i = 0; i < FIRA_VUPPER64_SIZE; i++) {
		sp.vupper64[i] = vupper64[FIRA_VUPPER64_SIZE - 1 - i];
	}

	/* One initiator, one responder, deferred DS-TWR: the FiRa shape of Android's
	 * CONFIG_UNICAST_DS_TWR. */
	sp.multi_node_mode = FIRA_MULTI_NODE_MODE_UNICAST;
	sp.ranging_round_usage = FIRA_RANGING_ROUND_USAGE_DSTWR_DEFERRED;
	sp.schedule_mode = FIRA_SCHEDULE_MODE_TIME_SCHEDULED;
	sp.rframe_config = FIRA_RFRAME_CONFIG_SP3;
	sp.sfd_id = FIRA_SFD_ID_2;
	sp.prf_mode = FIRA_PRF_MODE_BPRF;
	sp.phr_data_rate = FIRA_PHR_DATA_RATE_850K;
	sp.slot_duration_rstu = SLOT_DURATION_RSTU;
	sp.block_duration_ms = BLOCK_DURATION_MS;
	sp.round_duration_slots = ROUND_DURATION_SLOTS;
	sp.round_hopping = ROUND_HOPPING;
	sp.block_stride_length = 0;
	sp.report_rssi = 1;

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

	LOG_INF("applying session parameters");
	rc = apply_session_params(&sp);
	if (rc) {
		goto deinit;
	}

	LOG_INF("uwbmac_start");
	r = uwbmac_start(uwbmac_ctx);
	if (r != QERR_SUCCESS) {
		LOG_ERR("uwbmac_start: %d", r);
		goto deinit;
	}
	start_polling();

	LOG_INF("starting FiRa session");
	r = fira_helper_start_session(&fira_ctx, session_handle);
	if (r != QERR_SUCCESS) {
		LOG_ERR("fira_helper_start_session: %d", r);
		/* Same order as ff_uwb_stop(): deinit while the pump is up, then take the
		 * pump down while the MAC is still running. */
		(void)fira_helper_deinit_session(&fira_ctx, session_handle);
		stop_polling();
		(void)uwbmac_stop(uwbmac_ctx);
		goto close;
	}
	LOG_INF("FiRa session started");
	log_irq_diag("session started");

	session_active = true;
	atomic_set(&last_activity_ms, (atomic_val_t)k_uptime_get_32());
	LOG_INF("FiRa responder started: session=%08x ch=%u preamble=%u own=%04x peer=%04x "
		"(peer bytes %02x%02x, big-endian reading would be %04x)",
		params->session_id, params->channel, params->preamble_index, own_short_addr,
		peer_short_addr, params->peer_address[0], params->peer_address[1],
		sys_get_be16(params->peer_address));
	return 0;

deinit:
	/* Only reached before uwbmac_start(), so there is no pump to shut down here. */
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
	/* Before teardown, so this reports the state at the end of the ACTIVE window. */
	log_irq_diag("session teardown");
	(void)fira_helper_stop_session(&fira_ctx, session_handle);
	(void)fira_helper_deinit_session(&fira_ctx, session_handle);
	/*
	 * Between those two and uwbmac_stop(): they are request/response over the MAC and
	 * need the pump, while nothing in the vendor API says what uwbmac_poll_events()
	 * does on a *stopped* MAC. Keeping the pump's lifetime inside
	 * [uwbmac_start, uwbmac_stop) means never finding out.
	 */
	stop_polling();
	(void)uwbmac_stop(uwbmac_ctx);
	fira_helper_close(&fira_ctx);
	session_active = false;
	LOG_INF("FiRa session stopped");
}

/*
 * Runs session setup off the main loop; see the note by uwb_session_stack. One request at a
 * time is enough — the phone writes params once per find, and a second write while a session
 * is starting is better dropped than queued behind a possibly-wedged one.
 *
 * Teardown lives here too, so that every fira_helper_* call in this file is made from this
 * one thread and needs no locking.
 */
static void uwb_session_thread(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	while (true) {
		/* Nothing to do until the phone writes params — unless a session is up,
		 * in which case wake up often enough to notice it going quiet. */
		k_timeout_t wait = session_active ? K_MSEC(1000) : K_FOREVER;

		if (k_sem_take(&session_request, wait) == 0) {
			LOG_INF("session request picked up: starting FiRa setup");
			(void)ff_uwb_start(&requested_params, requested_secret);
			continue;
		}
		if (session_active &&
		    (k_uptime_get_32() - (uint32_t)atomic_get(&last_activity_ms)) >
			    (uint32_t)SESSION_IDLE_TIMEOUT_MS) {
			LOG_WRN("no UWB activity for %d ms: stopping the session",
				SESSION_IDLE_TIMEOUT_MS);
			ff_uwb_stop();
		}
	}
}

int ff_uwb_on_params(const uint8_t *data, size_t len, const uint8_t *secret)
{
	if (len != FF_UWB_PARAMS_LEN) {
		return -EINVAL;
	}
	if (!stack_ready) {
		return -EAGAIN;
	}
	/*
	 * Reject what the stack would reject anyway, so an out-of-range value says so here
	 * instead of surfacing as an unexplained session-start failure. Only channels 5 and
	 * 9 exist in this stack, and BPRF preamble indices are 9..12
	 * (FIRA_PCODE_BPRF_MIN/MAX, whose header notes the wider 9..24 range elsewhere in
	 * the SDK does not apply here).
	 */
	if (data[6] != 5U && data[6] != 9U) {
		LOG_WRN("UWB params rejected: channel %u (expected 5 or 9)", data[6]);
		return -EINVAL;
	}
	if (data[7] < FIRA_PCODE_BPRF_MIN || data[7] > FIRA_PCODE_BPRF_MAX) {
		LOG_WRN("UWB params rejected: preamble index %u (expected %d..%d)", data[7],
			FIRA_PCODE_BPRF_MIN, FIRA_PCODE_BPRF_MAX);
		return -EINVAL;
	}

	/* TrackerUwbGatt.encodeSessionParams: [2B addr][4B sessionId BE][1B ch][1B pre] */
	requested_params.peer_address[0] = data[0];
	requested_params.peer_address[1] = data[1];
	requested_params.session_id = sys_get_be32(&data[2]);
	requested_params.channel = data[6];
	requested_params.preamble_index = data[7];
	memcpy(requested_secret, secret, sizeof(requested_secret));

	/* Hand off and return immediately: the caller is the main loop. */
	LOG_INF("UWB session requested: peer=%02x%02x session=%08x ch=%u preamble=%u",
		requested_params.peer_address[0], requested_params.peer_address[1],
		requested_params.session_id, requested_params.channel,
		requested_params.preamble_index);
	k_sem_give(&session_request);
	return 0;
}
