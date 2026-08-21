/*
 * Compatibility shim: <zephyr.h> was the catch-all Zephyr header before the
 * <zephyr/...> prefix landed in Zephyr 3.0, and was removed in 3.2. Qorvo's qosal
 * Zephyr backend still includes it.
 *
 * Only reachable from the Qorvo sources — see firmware/qorvo-uwb/CMakeLists.txt, which
 * puts this directory on the include path for that library alone, so nothing in the
 * FindFamily firmware can accidentally depend on the old spelling.
 */
#ifndef FF_COMPAT_ZEPHYR_H_
#define FF_COMPAT_ZEPHYR_H_

#include <zephyr/kernel.h>

#endif /* FF_COMPAT_ZEPHYR_H_ */
