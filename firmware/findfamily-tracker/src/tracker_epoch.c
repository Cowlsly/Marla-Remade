/*
 * Rotating beacon id and the tracker's notion of wall-clock time.
 *
 * The id must be byte-identical to TrackerProtocol.epochId (Kotlin) and
 * tracker_epoch_id (Rust), or the owner's recomputed ids never match what finders
 * upload and a sighting silently resolves to nothing. Hence PSA HMAC-SHA256 here
 * rather than any hand-rolled hashing.
 */
#include "ff_tracker.h"

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <psa/crypto.h>

#include <string.h>

LOG_MODULE_REGISTER(ff_epoch, LOG_LEVEL_INF);

/* TrackerProtocol.EPOCH_DOMAIN */
static const uint8_t epoch_domain[6] = { 'f', 'f', 't', 'r', 'k', '1' };

uint64_t ff_now_unix(const struct ff_tracker_state *st)
{
	int64_t elapsed_ms;

	if (st->unix_at_base == 0U) {
		return 0U;
	}
	/*
	 * k_uptime_get() is LFCLK-backed and keeps counting through sleep, so this
	 * holds across the idle periods between beacons. It does reset to 0 on reboot,
	 * which is why the caller re-persists the time base once per epoch.
	 */
	elapsed_ms = k_uptime_get() - st->uptime_at_base_ms;
	if (elapsed_ms < 0) {
		elapsed_ms = 0;
	}
	return st->unix_at_base + (uint64_t)(elapsed_ms / 1000);
}

uint64_t ff_current_epoch(const struct ff_tracker_state *st)
{
	uint64_t now = ff_now_unix(st);

	return now == 0U ? 0U : now / FF_EPOCH_SECONDS;
}

int ff_epoch_id(const uint8_t *secret, uint64_t epoch, uint8_t *out)
{
	psa_status_t status;
	psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
	psa_key_id_t key = PSA_KEY_ID_NULL;
	psa_mac_operation_t op = PSA_MAC_OPERATION_INIT;
	uint8_t mac[32];
	uint8_t epoch_be[8];
	size_t mac_len = 0;

	for (int i = 0; i < 8; i++) {
		epoch_be[i] = (uint8_t)(epoch >> (56 - i * 8));
	}

	psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_SIGN_MESSAGE);
	psa_set_key_algorithm(&attr, PSA_ALG_HMAC(PSA_ALG_SHA_256));
	psa_set_key_type(&attr, PSA_KEY_TYPE_HMAC);
	psa_set_key_bits(&attr, FF_SECRET_LEN * 8);

	status = psa_import_key(&attr, secret, FF_SECRET_LEN, &key);
	if (status != PSA_SUCCESS) {
		LOG_ERR("psa_import_key: %d", (int)status);
		return -EIO;
	}

	status = psa_mac_sign_setup(&op, key, PSA_ALG_HMAC(PSA_ALG_SHA_256));
	if (status != PSA_SUCCESS) {
		goto out;
	}
	status = psa_mac_update(&op, epoch_domain, sizeof(epoch_domain));
	if (status != PSA_SUCCESS) {
		goto out;
	}
	status = psa_mac_update(&op, epoch_be, sizeof(epoch_be));
	if (status != PSA_SUCCESS) {
		goto out;
	}
	status = psa_mac_sign_finish(&op, mac, sizeof(mac), &mac_len);

out:
	if (status != PSA_SUCCESS) {
		LOG_ERR("HMAC failed: %d", (int)status);
		(void)psa_mac_abort(&op);
		(void)psa_destroy_key(key);
		return -EIO;
	}
	(void)psa_destroy_key(key);

	if (mac_len < FF_EPOCH_ID_LEN) {
		return -EIO;
	}
	/* Truncate to 16 bytes, exactly as the Kotlin and Rust sides do. */
	memcpy(out, mac, FF_EPOCH_ID_LEN);
	return 0;
}
