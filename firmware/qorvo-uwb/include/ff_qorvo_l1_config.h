/*
 * Copyright (c) 2026 Vayun Mathur
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef FF_QORVO_L1_CONFIG_H_
#define FF_QORVO_L1_CONFIG_H_

/**
 * Point l1_config's storage at RAM buffers instead of the read-only sections the prebuilt
 * bundle places in the image.
 *
 * Must be called before l1_config_init(), which writes to that storage: on a device whose
 * stored configuration doesn't match its checksum — always the case here, since the
 * shipped section is uninitialised — it resets to defaults and stores the result. Left
 * pointing at the image, that store targets flash and fails.
 *
 * See src/l1_config_storage_zephyr.c for why this is done at runtime rather than in the
 * linker script.
 */
void ff_qorvo_l1_config_use_ram_storage(void);

#endif /* FF_QORVO_L1_CONFIG_H_ */
