/*
 * HKDF-SHA256 derivation of the FiRa session material, mirroring
 * findfamily/src/main/java/com/vayunmathur/findfamily/tracker/TrackerUwbKeys.kt.
 *
 * The phone never sends the STS key or our UWB address: the GATT link is unbonded and
 * unencrypted, so anything on it could be supplied by a stranger standing nearby. Both
 * sides derive from the bind-time beacon secret instead, and the per-find write carries
 * only channel/slot params.
 *
 * A mismatch of a single byte against the Kotlin side shows up as a FiRa session that
 * never converges, with nothing in any log to say why — TrackerUwbKeysTest holds the
 * fixed vectors this must reproduce.
 */
#include "ff_tracker.h"

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <psa/crypto.h>

#include <string.h>

LOG_MODULE_REGISTER(ff_sts, LOG_LEVEL_INF);

#define STS_KEY_LEN 8
#define UWB_ADDR_LEN 2

static const char sts_info[] = "com.vayunmathur.findfamily/uwb-sts";
static const char addr_info[] = "com.vayunmathur.findfamily/uwb-addr";

/*
 * Hkdf.kt defaults the salt to 32 zero bytes rather than omitting it. RFC 5869 makes an
 * absent salt equivalent to a zero-filled one of HashLen, so this matches, but PSA needs
 * it passed explicitly.
 */
static const uint8_t zero_salt[32];

static int hkdf_sha256(const uint8_t *ikm, size_t ikm_len, const uint8_t *info,
		       size_t info_len, uint8_t *out, size_t out_len)
{
	psa_status_t status;
	psa_key_derivation_operation_t op = PSA_KEY_DERIVATION_OPERATION_INIT;

	status = psa_key_derivation_setup(&op, PSA_ALG_HKDF(PSA_ALG_SHA_256));
	if (status != PSA_SUCCESS) {
		goto err;
	}
	status = psa_key_derivation_input_bytes(&op, PSA_KEY_DERIVATION_INPUT_SALT,
					       zero_salt, sizeof(zero_salt));
	if (status != PSA_SUCCESS) {
		goto err;
	}
	status = psa_key_derivation_input_bytes(&op, PSA_KEY_DERIVATION_INPUT_SECRET,
					       ikm, ikm_len);
	if (status != PSA_SUCCESS) {
		goto err;
	}
	status = psa_key_derivation_input_bytes(&op, PSA_KEY_DERIVATION_INPUT_INFO,
					       info, info_len);
	if (status != PSA_SUCCESS) {
		goto err;
	}
	status = psa_key_derivation_output_bytes(&op, out, out_len);
	if (status != PSA_SUCCESS) {
		goto err;
	}
	(void)psa_key_derivation_abort(&op);
	return 0;

err:
	LOG_ERR("HKDF failed: %d", (int)status);
	(void)psa_key_derivation_abort(&op);
	return -EIO;
}

int ff_sts_key(const uint8_t *secret, uint32_t session_id, uint8_t *out)
{
	/* info = "…/uwb-sts" || u32_be(sessionId); the trailing NUL is not part of it. */
	uint8_t info[sizeof(sts_info) - 1 + 4];

	memcpy(info, sts_info, sizeof(sts_info) - 1);
	info[sizeof(sts_info) - 1 + 0] = (uint8_t)(session_id >> 24);
	info[sizeof(sts_info) - 1 + 1] = (uint8_t)(session_id >> 16);
	info[sizeof(sts_info) - 1 + 2] = (uint8_t)(session_id >> 8);
	info[sizeof(sts_info) - 1 + 3] = (uint8_t)session_id;

	return hkdf_sha256(secret, FF_SECRET_LEN, info, sizeof(info), out, STS_KEY_LEN);
}

int ff_uwb_address(const uint8_t *secret, uint8_t *out)
{
	int rc = hkdf_sha256(secret, FF_SECRET_LEN, (const uint8_t *)addr_info,
			     sizeof(addr_info) - 1, out, UWB_ADDR_LEN);

	if (rc) {
		return rc;
	}
	/* 0x0000 and 0xFFFF are reserved by FiRa. Nudge exactly as TrackerUwbKeys does,
	 * or the two sides would disagree on the address for those secrets. */
	if ((out[0] == 0x00 && out[1] == 0x00) || (out[0] == 0xFF && out[1] == 0xFF)) {
		out[1] = 0x01;
	}
	return 0;
}
