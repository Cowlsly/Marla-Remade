/*
 * MCPS crypto backend on PSA Crypto.
 *
 * The prebuilt FiRa bundle calls out to these for all 802.15.4z frame protection:
 * AES-CMAC key derivation, CCM* frame encryption/authentication, and AES-ECB for
 * header IEs. The SDK's own implementation (Src/UWB/mcps_crypto.c) forwards to a
 * HAL_crypto layer built on Nordic's old nrf_crypto, which would mean pulling a second
 * crypto provider into the image. This talks to PSA directly, which the FindFamily
 * firmware already uses for the epoch-id HMAC and the STS HKDF.
 *
 * Contexts are handed out from static pools: the stack creates a couple per session and
 * this avoids putting the heap on the ranging path.
 */
#include <qerr.h>

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <psa/crypto.h>

#include <stdbool.h>
#include <stdint.h>
#include <string.h>

LOG_MODULE_REGISTER(qorvo_crypto, CONFIG_QORVO_UWB_LOG_LEVEL);

/* 802.15.4 CCM* nonce is 13 bytes. */
#define CCM_NONCE_LEN 13
#define AES_BLOCK_LEN 16

#define MAX_CCM_CTX 4
#define MAX_ECB_CTX 4

struct key_ctx {
	psa_key_id_t key;
	bool used;
	bool encrypt; /* ECB only. */
};

static struct key_ctx ccm_ctxs[MAX_CCM_CTX];
static struct key_ctx ecb_ctxs[MAX_ECB_CTX];

static struct key_ctx *alloc_ctx(struct key_ctx *pool, size_t n)
{
	for (size_t i = 0; i < n; i++) {
		if (!pool[i].used) {
			pool[i].used = true;
			return &pool[i];
		}
	}
	return NULL;
}

static void free_ctx(struct key_ctx *ctx)
{
	if (ctx == NULL || !ctx->used) {
		return;
	}
	(void)psa_destroy_key(ctx->key);
	ctx->key = PSA_KEY_ID_NULL;
	ctx->used = false;
}

enum qerr mcps_crypto_init(void)
{
	/* Idempotent: the FindFamily firmware calls this from main() as well. */
	psa_status_t status = psa_crypto_init();

	if (status != PSA_SUCCESS) {
		LOG_ERR("psa_crypto_init: %d", (int)status);
		return QERR_EIO;
	}
	return QERR_SUCCESS;
}

void mcps_crypto_deinit(void)
{
	for (size_t i = 0; i < MAX_CCM_CTX; i++) {
		free_ctx(&ccm_ctxs[i]);
	}
	for (size_t i = 0; i < MAX_ECB_CTX; i++) {
		free_ctx(&ecb_ctxs[i]);
	}
}

enum qerr mcps_crypto_reinit(void)
{
	return QERR_SUCCESS;
}

uint32_t mcps_crypto_get_random(void)
{
	uint32_t v = 0;

	if (psa_generate_random((uint8_t *)&v, sizeof(v)) != PSA_SUCCESS) {
		/* Only reachable if the RNG driver is missing, which would already have
		 * failed psa_crypto_init. Zero is not a safe nonce, so say so loudly. */
		LOG_ERR("psa_generate_random failed");
	}
	return v;
}

/* ---- AES-CMAC ---------------------------------------------------------- */

static enum qerr cmac_digest(const uint8_t *key, size_t key_bits, const uint8_t *data,
			     unsigned int data_len, uint8_t *out)
{
	psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
	psa_key_id_t key_id = PSA_KEY_ID_NULL;
	psa_status_t status;
	size_t out_len = 0;

	psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_SIGN_MESSAGE);
	psa_set_key_algorithm(&attr, PSA_ALG_CMAC);
	psa_set_key_type(&attr, PSA_KEY_TYPE_AES);
	psa_set_key_bits(&attr, key_bits);

	status = psa_import_key(&attr, key, key_bits / 8, &key_id);
	if (status != PSA_SUCCESS) {
		LOG_ERR("cmac import_key: %d", (int)status);
		return QERR_EIO;
	}
	status = psa_mac_compute(key_id, PSA_ALG_CMAC, data, data_len, out, AES_BLOCK_LEN,
				 &out_len);
	(void)psa_destroy_key(key_id);
	if (status != PSA_SUCCESS) {
		LOG_ERR("psa_mac_compute: %d", (int)status);
		return QERR_EIO;
	}
	return QERR_SUCCESS;
}

enum qerr mcps_crypto_cmac_aes_128_digest(const uint8_t *key, const uint8_t *data,
					  unsigned int data_len, uint8_t *out)
{
	return cmac_digest(key, 128, data, data_len, out);
}

enum qerr mcps_crypto_cmac_aes_256_digest(const uint8_t *key, const uint8_t *data,
					  unsigned int data_len, uint8_t *out)
{
	return cmac_digest(key, 256, data, data_len, out);
}

/* ---- AES-CCM* ---------------------------------------------------------- */

int mcps_crypto_aead_aes_ccm_star_128_create(void **ccm_star_ctx, const uint8_t *key)
{
	psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
	struct key_ctx *ctx;
	psa_status_t status;

	if (ccm_star_ctx == NULL || key == NULL) {
		return QERR_EINVAL;
	}
	ctx = alloc_ctx(ccm_ctxs, MAX_CCM_CTX);
	if (ctx == NULL) {
		LOG_ERR("out of CCM contexts");
		return QERR_ENOMEM;
	}

	psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_ENCRYPT | PSA_KEY_USAGE_DECRYPT);
	/* The MAC length varies per frame, so permit any tag from 4 bytes up rather than
	 * pinning one length in the key policy. */
	psa_set_key_algorithm(&attr, PSA_ALG_AEAD_WITH_AT_LEAST_THIS_LENGTH_TAG(PSA_ALG_CCM, 4));
	psa_set_key_type(&attr, PSA_KEY_TYPE_AES);
	psa_set_key_bits(&attr, 128);

	status = psa_import_key(&attr, key, 16, &ctx->key);
	if (status != PSA_SUCCESS) {
		LOG_ERR("ccm import_key: %d", (int)status);
		ctx->used = false;
		return QERR_EIO;
	}
	*ccm_star_ctx = ctx;
	return QERR_SUCCESS;
}

void mcps_crypto_aead_aes_ccm_star_128_destroy(void *ctx)
{
	free_ctx((struct key_ctx *)ctx);
}

/*
 * CCM* keeps the MAC separate from the ciphertext, whereas psa_aead_encrypt returns them
 * concatenated, so the multipart API is used to get the tag on its own.
 *
 * mac_len == 0 (CCM* security level "encryption only", where CCM degenerates to plain
 * AES-CTR) is not implemented: PSA's CCM requires a tag of at least 4 bytes. FiRa STS
 * frames use a 64-bit MIC, so this should not be reached — and an explicit error is
 * better than silently mis-encrypting a frame.
 */
static int ccm_crypt(void *ctx_, const uint8_t *nonce, const uint8_t *header,
		     unsigned int header_len, const uint8_t *in, unsigned int data_len,
		     uint8_t *out, uint8_t *mac, unsigned int mac_len, bool encrypt)
{
	struct key_ctx *ctx = ctx_;
	psa_aead_operation_t op = PSA_AEAD_OPERATION_INIT;
	psa_algorithm_t alg;
	psa_status_t status;
	size_t out_len = 0;
	size_t tag_len = 0;

	if (ctx == NULL || !ctx->used || nonce == NULL) {
		return QERR_EINVAL;
	}
	if (mac_len == 0) {
		LOG_ERR("CCM* with a zero-length MIC is not supported");
		return QERR_ENOTSUP;
	}

	alg = PSA_ALG_AEAD_WITH_SHORTENED_TAG(PSA_ALG_CCM, mac_len);
	status = encrypt ? psa_aead_encrypt_setup(&op, ctx->key, alg)
			 : psa_aead_decrypt_setup(&op, ctx->key, alg);
	if (status != PSA_SUCCESS) {
		goto err;
	}
	status = psa_aead_set_lengths(&op, header_len, data_len);
	if (status != PSA_SUCCESS) {
		goto err;
	}
	status = psa_aead_set_nonce(&op, nonce, CCM_NONCE_LEN);
	if (status != PSA_SUCCESS) {
		goto err;
	}
	if (header_len) {
		status = psa_aead_update_ad(&op, header, header_len);
		if (status != PSA_SUCCESS) {
			goto err;
		}
	}
	if (data_len) {
		status = psa_aead_update(&op, in, data_len, out, data_len, &out_len);
		if (status != PSA_SUCCESS) {
			goto err;
		}
	}
	if (encrypt) {
		status = psa_aead_finish(&op, NULL, 0, &out_len, mac, mac_len, &tag_len);
	} else {
		status = psa_aead_verify(&op, NULL, 0, &out_len, mac, mac_len);
	}
	if (status != PSA_SUCCESS) {
		goto err;
	}
	return QERR_SUCCESS;

err:
	/* A decrypt failure here is usually an authentication failure, which on the
	 * ranging path means the peer's STS key doesn't match ours. */
	LOG_ERR("CCM* %s failed: %d", encrypt ? "encrypt" : "decrypt", (int)status);
	(void)psa_aead_abort(&op);
	return QERR_EIO;
}

int mcps_crypto_aead_aes_ccm_star_128_encrypt(void *ctx, const uint8_t *nonce,
					      const uint8_t *header, unsigned int header_len,
					      uint8_t *data, unsigned int data_len, uint8_t *mac,
					      unsigned int mac_len)
{
	/* In place: PSA permits in == out for CCM. */
	return ccm_crypt(ctx, nonce, header, header_len, data, data_len, data, mac, mac_len,
			 true);
}

int mcps_crypto_aead_aes_ccm_star_128_encrypt_inout(void *ctx, const uint8_t *nonce,
						    const uint8_t *header,
						    unsigned int header_len, uint8_t *data,
						    unsigned int data_len, uint8_t *out,
						    uint8_t *mac, unsigned int mac_len)
{
	return ccm_crypt(ctx, nonce, header, header_len, data, data_len, out, mac, mac_len,
			 true);
}

int mcps_crypto_aead_aes_ccm_star_128_decrypt(void *ctx, const uint8_t *nonce,
					      const uint8_t *header, unsigned int header_len,
					      uint8_t *data, unsigned int data_len, uint8_t *mac,
					      unsigned int mac_len)
{
	return ccm_crypt(ctx, nonce, header, header_len, data, data_len, data, mac, mac_len,
			 false);
}

int mcps_crypto_aead_aes_ccm_star_128_decrypt_inout(void *ctx, const uint8_t *nonce,
						    const uint8_t *header,
						    unsigned int header_len, uint8_t *data,
						    unsigned int data_len, uint8_t *out,
						    uint8_t *mac, unsigned int mac_len)
{
	return ccm_crypt(ctx, nonce, header, header_len, data, data_len, out, mac, mac_len,
			 false);
}

/* ---- AES-ECB ----------------------------------------------------------- */

static int ecb_create(void **ecb_ctx, const uint8_t *key, bool encrypt)
{
	psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
	struct key_ctx *ctx;
	psa_status_t status;

	if (ecb_ctx == NULL || key == NULL) {
		return QERR_EINVAL;
	}
	ctx = alloc_ctx(ecb_ctxs, MAX_ECB_CTX);
	if (ctx == NULL) {
		LOG_ERR("out of ECB contexts");
		return QERR_ENOMEM;
	}
	ctx->encrypt = encrypt;

	psa_set_key_usage_flags(&attr, encrypt ? PSA_KEY_USAGE_ENCRYPT : PSA_KEY_USAGE_DECRYPT);
	psa_set_key_algorithm(&attr, PSA_ALG_ECB_NO_PADDING);
	psa_set_key_type(&attr, PSA_KEY_TYPE_AES);
	psa_set_key_bits(&attr, 128);

	status = psa_import_key(&attr, key, 16, &ctx->key);
	if (status != PSA_SUCCESS) {
		LOG_ERR("ecb import_key: %d", (int)status);
		ctx->used = false;
		return QERR_EIO;
	}
	*ecb_ctx = ctx;
	return QERR_SUCCESS;
}

int mcps_crypto_aes_ecb_128_create_encrypt(void **ecb_ctx, const uint8_t *key)
{
	return ecb_create(ecb_ctx, key, true);
}

int mcps_crypto_aes_ecb_128_create_decrypt(void **ecb_ctx, const uint8_t *key)
{
	return ecb_create(ecb_ctx, key, false);
}

void mcps_crypto_aes_ecb_128_destroy(void *ctx)
{
	free_ctx((struct key_ctx *)ctx);
}

int mcps_crypto_aes_ecb_128_encrypt_decrypt(void *ctx_, const uint8_t *data,
					    unsigned int data_len, uint8_t *out)
{
	struct key_ctx *ctx = ctx_;
	psa_status_t status;
	size_t out_len = 0;

	if (ctx == NULL || !ctx->used || data == NULL || out == NULL) {
		return QERR_EINVAL;
	}
	if (data_len % AES_BLOCK_LEN) {
		return QERR_EINVAL;
	}
	/* ECB has no IV, so psa_cipher_* neither prepends nor consumes one and the
	 * output is the same length as the input. */
	status = ctx->encrypt ? psa_cipher_encrypt(ctx->key, PSA_ALG_ECB_NO_PADDING, data,
						   data_len, out, data_len, &out_len)
			      : psa_cipher_decrypt(ctx->key, PSA_ALG_ECB_NO_PADDING, data,
						   data_len, out, data_len, &out_len);
	if (status != PSA_SUCCESS) {
		LOG_ERR("psa_cipher_%s: %d", ctx->encrypt ? "encrypt" : "decrypt",
			(int)status);
		return QERR_EIO;
	}
	return QERR_SUCCESS;
}
