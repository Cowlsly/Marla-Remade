/*
 * Compatibility shim: <random/rand32.h> was renamed to <zephyr/random/random.h> in
 * Zephyr 3.5 (sys_rand32_get and friends moved with it). Qorvo's qosal qrand.c still
 * includes the old path.
 *
 * See the note in ../zephyr.h about why this is scoped to the Qorvo library.
 */
#ifndef FF_COMPAT_RANDOM_RAND32_H_
#define FF_COMPAT_RANDOM_RAND32_H_

#include <zephyr/random/random.h>

#endif /* FF_COMPAT_RANDOM_RAND32_H_ */
