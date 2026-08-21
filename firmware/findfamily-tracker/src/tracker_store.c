/*
 * NVS persistence for the provisioning blob and the time base.
 *
 * A tracker that forgets its secret is a brick — it can only be recovered by
 * re-binding — and one that forgets its time base beacons epoch ids outside the
 * owner's ~2.25 h search window, which looks identical to being out of range. Both
 * therefore live in flash rather than RAM.
 */
#include "ff_tracker.h"

#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/flash.h>
#include <zephyr/kvss/nvs.h>
#include <zephyr/logging/log.h>
#include <zephyr/storage/flash_map.h>

#include <string.h>

LOG_MODULE_REGISTER(ff_store, LOG_LEVEL_INF);

#define NVS_PARTITION		storage_partition
#define NVS_PARTITION_DEVICE	PARTITION_DEVICE(NVS_PARTITION)
#define NVS_PARTITION_OFFSET	PARTITION_OFFSET(NVS_PARTITION)

/* NVS ids. Append only — reusing an id would read back another field's bytes. */
#define ID_USER_ID  1
#define ID_SECRET   2
#define ID_UNIX     3

static struct nvs_fs fs;
static bool initialized;

int ff_store_init(void)
{
	struct flash_pages_info info;
	const struct device *flash_dev = NVS_PARTITION_DEVICE;
	int rc;

	if (initialized) {
		return 0;
	}
	if (!device_is_ready(flash_dev)) {
		LOG_ERR("flash device not ready");
		return -ENODEV;
	}

	fs.flash_device = flash_dev;
	fs.offset = NVS_PARTITION_OFFSET;
	rc = flash_get_page_info_by_offs(flash_dev, fs.offset, &info);
	if (rc) {
		LOG_ERR("flash_get_page_info_by_offs: %d", rc);
		return rc;
	}
	fs.sector_size = info.size;
	fs.sector_count = 3U;

	rc = nvs_mount(&fs);
	if (rc) {
		LOG_ERR("nvs_mount: %d", rc);
		return rc;
	}
	initialized = true;
	return 0;
}

int ff_store_load(struct ff_tracker_state *out)
{
	ssize_t rc;

	if (!initialized) {
		return -EINVAL;
	}
	memset(out, 0, sizeof(*out));

	rc = nvs_read(&fs, ID_SECRET, out->secret, FF_SECRET_LEN);
	if (rc != FF_SECRET_LEN) {
		LOG_INF("no provisioning stored (secret read = %d)", (int)rc);
		return 0;
	}
	rc = nvs_read(&fs, ID_USER_ID, &out->tracker_user_id, sizeof(out->tracker_user_id));
	if (rc != sizeof(out->tracker_user_id)) {
		/* A secret without a user id can't be reported against, so treat the
		 * pair as absent rather than half-provisioned. */
		LOG_WRN("secret present but user id missing; ignoring provisioning");
		memset(out->secret, 0, FF_SECRET_LEN);
		return 0;
	}
	out->provisioned = true;

	rc = nvs_read(&fs, ID_UNIX, &out->unix_at_base, sizeof(out->unix_at_base));
	if (rc == sizeof(out->unix_at_base)) {
		out->uptime_at_base_ms = k_uptime_get();
	} else {
		out->unix_at_base = 0;
		LOG_WRN("no time base stored; beacon stays off until re-provisioned");
	}
	return 0;
}

int ff_store_save_provisioning(uint64_t tracker_user_id, const uint8_t *secret,
			       uint64_t unix_seconds)
{
	ssize_t rc;

	if (!initialized) {
		return -EINVAL;
	}
	rc = nvs_write(&fs, ID_SECRET, secret, FF_SECRET_LEN);
	if (rc < 0) {
		return (int)rc;
	}
	rc = nvs_write(&fs, ID_USER_ID, &tracker_user_id, sizeof(tracker_user_id));
	if (rc < 0) {
		return (int)rc;
	}
	if (unix_seconds != 0U) {
		rc = nvs_write(&fs, ID_UNIX, &unix_seconds, sizeof(unix_seconds));
		if (rc < 0) {
			return (int)rc;
		}
	}
	return 0;
}

int ff_store_save_time(uint64_t unix_seconds)
{
	ssize_t rc;

	if (!initialized) {
		return -EINVAL;
	}
	rc = nvs_write(&fs, ID_UNIX, &unix_seconds, sizeof(unix_seconds));
	return rc < 0 ? (int)rc : 0;
}

int ff_store_clear(void)
{
	if (!initialized) {
		return -EINVAL;
	}
	(void)nvs_delete(&fs, ID_SECRET);
	(void)nvs_delete(&fs, ID_USER_ID);
	(void)nvs_delete(&fs, ID_UNIX);
	return 0;
}
