/*
 * Compatibility shim for building Qorvo's uwb-stack against Zephyr 4.4 / NCS 3.4.
 * Force-included into the Qorvo library only (see CMakeLists.txt), never into the
 * FindFamily firmware.
 *
 * The SDK's Zephyr support was written against Zephyr ~3.1 and has two problems this
 * header fixes. This is the piece most likely to break on an NCS upgrade, because it
 * leans on Zephyr internals that carry no stability promise. If a future SDK release
 * ships a qosal built for a current Zephyr, delete this file.
 */
#ifndef FF_QORVO_COMPAT_H_
#define FF_QORVO_COMPAT_H_

/*
 * 1. qosal/src/zephyr/qos.c uses QERR_SUCCESS but only includes "qos.h", which does not
 *    pull in qerr.h. It happens to compile inside the vendor's own build because of
 *    their include ordering.
 */
#include <qerr.h>

/*
 * 2. qosal implements its own zero-copy log-message construction (see
 *    qosal/include/zephyr/qosal_impl.h) directly against Zephyr's internal logging
 *    symbols as they were named in 3.1: log_msg2, z_log_msg2_*, Z_LOG_MSG2_*. Zephyr
 *    3.2 dropped the "2" suffix throughout. The rename was mechanical — the structures
 *    and semantics are unchanged — so aliasing the old spellings onto the current ones
 *    is sufficient. Every name below was checked to exist in Zephyr 4.4's
 *    logging/log_msg.h.
 */
#ifdef CONFIG_LOG

#include <zephyr/logging/log_msg.h>

/* Message type and descriptor. */
#define log_msg2 log_msg
#define log_msg2_desc log_msg_desc

/* Packaging helpers. */
#define Z_LOG_MSG2_ALIGN_OFFSET Z_LOG_MSG_ALIGN_OFFSET
#define Z_LOG_MSG2_CBPRINTF_FLAGS Z_LOG_MSG_CBPRINTF_FLAGS
#define Z_LOG_MSG2_ALIGNED_WLEN Z_LOG_MSG_ALIGNED_WLEN

/* Allocation / finalisation. */
#define z_log_msg2_alloc z_log_msg_alloc
#define z_log_msg2_finalize z_log_msg_finalize
#define z_log_msg2_runtime_vcreate z_log_msg_runtime_vcreate
#define Z_LOG_MSG2_STACK_CREATE Z_LOG_MSG_STACK_CREATE
#define LOG_MSG2_DBG LOG_MSG_DBG

/*
 * Zephyr 3.1 had a Kconfig for the log domain id. It is now a plain constant, 0 meaning
 * the local domain (multi-domain logging gives remote domains non-zero ids).
 */
#ifndef CONFIG_LOG_DOMAIN_ID
#define CONFIG_LOG_DOMAIN_ID 0
#endif

#endif /* CONFIG_LOG */

#endif /* FF_QORVO_COMPAT_H_ */
