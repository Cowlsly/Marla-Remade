/*
 * FiRa responder/controlee session on the DW3110.
 *
 * NOT COMPILED BY DEFAULT — see CONFIG_FF_TRACKER_UWB in Kconfig and the "UWB" section
 * of README.md. The DW3xxx driver is licensed and not vendored here, so the calls into
 * it below are written against Qorvo's documented API but have never been built or run.
 * Treat every dwt_* call as unverified.
 *
 * Roles: the phone is the controller/initiator (it mints session id, channel, preamble
 * and its own address, and starts ranging); this device is the controlee/responder. The
 * phone's stack uses UwbRangingParams.CONFIG_UNICAST_DS_TWR with static STS, which is
 * what the configuration below aims at:
 *
 *   - double-sided two-way ranging, one initiator and one responder
 *   - static STS, key from ff_sts_key() (FiRa reads those 8 bytes as
 *     [2B VendorID][6B STATIC_STS_IV])
 *   - channel 9, preamble index 10 (UwbController.DEFAULT_CHANNEL / DEFAULT_PREAMBLE)
 *
 * Aligning the ranging-round and block timing with what Android's stack picks is the
 * genuinely uncertain part of this and should be expected to need iteration on real
 * hardware.
 */
#include "ff_tracker.h"

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/byteorder.h>

/* Provided by the Qorvo DW3xxx driver; see README.md for dropping it in. */
#include <deca_device_api.h>
#include <deca_regs.h>

#include <string.h>

LOG_MODULE_REGISTER(ff_uwb, LOG_LEVEL_INF);

#define STS_KEY_LEN 8

static bool session_active;

int ff_uwb_init(void)
{
	/*
	 * Bring up the DW3110 over SPI. The devicetree overlay names the SPI bus, IRQ,
	 * reset and wakeup lines (see boards/ in this directory).
	 */
	if (dwt_probe(NULL) != DWT_SUCCESS) {
		LOG_ERR("dwt_probe failed: DW3110 not responding on SPI");
		return -ENODEV;
	}
	if (dwt_initialise(DWT_DW_INIT) != DWT_SUCCESS) {
		LOG_ERR("dwt_initialise failed");
		return -EIO;
	}
	LOG_INF("DW3110 initialised");
	return 0;
}

int ff_uwb_start(const struct ff_uwb_params *params, const uint8_t *secret)
{
	uint8_t sts_key[STS_KEY_LEN];
	uint8_t own_address[2];
	int rc;

	if (session_active) {
		ff_uwb_stop();
	}

	rc = ff_sts_key(secret, params->session_id, sts_key);
	if (rc) {
		return rc;
	}
	rc = ff_uwb_address(secret, own_address);
	if (rc) {
		return rc;
	}

	LOG_INF("starting FiRa responder: session=%08x ch=%u preamble=%u peer=%02x%02x own=%02x%02x",
		params->session_id, params->channel, params->preamble_index,
		params->peer_address[0], params->peer_address[1],
		own_address[0], own_address[1]);

	/*
	 * Static STS: the 8 derived bytes are the vendor id + IV, and the FiRa layer
	 * expands them into the STS. Both ends must load identical bytes here — this is
	 * where a Kotlin/firmware HKDF mismatch would silently break ranging.
	 */
	dwt_configurestskey((dwt_sts_cp_key_t *)sts_key);
	dwt_configurestsiv((dwt_sts_cp_iv_t *)sts_key);
	dwt_configurestsloadiv();

	{
		dwt_config_t cfg = {
			.chan = params->channel,
			.txPreambLength = DWT_PLEN_128,
			.rxPAC = DWT_PAC8,
			.txCode = params->preamble_index,
			.rxCode = params->preamble_index,
			.sfdType = DWT_SFD_IEEE_4Z,
			.dataRate = DWT_BR_6M8,
			.phrMode = DWT_PHRMODE_STD,
			.phrRate = DWT_PHRRATE_STD,
			.sfdTO = (129 + 8 - 8),
			.stsMode = DWT_STS_MODE_1,
			.stsLength = DWT_STS_LEN_64,
			.pdoaMode = DWT_PDOA_M0,
		};

		if (dwt_configure(&cfg) != DWT_SUCCESS) {
			LOG_ERR("dwt_configure failed");
			return -EIO;
		}
	}

	/* Responder: sit in receive and reply to the initiator's poll. The FiRa MAC
	 * timing (ranging round/block) is driven by the initiator. */
	dwt_setrxaftertxdelay(0);
	dwt_setrxtimeout(0);
	dwt_rxenable(DWT_START_RX_IMMEDIATE);

	session_active = true;
	return 0;
}

void ff_uwb_stop(void)
{
	if (!session_active) {
		return;
	}
	dwt_forcetrxoff();
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
