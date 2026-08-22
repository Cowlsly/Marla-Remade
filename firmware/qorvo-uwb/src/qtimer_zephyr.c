/*
 * Zephyr timer backend for Qorvo's qhal.
 *
 * Replaces qhal/src/zephyr/qtimer.c, which like its qspi.c sibling is a placeholder:
 * qtimer_init() returns NULL and every other entry point returns QERR_ENOTSUP. llhw_init()
 * sets up an idle timer through this, so with the stub in place the whole UWB stack fails
 * to initialise with QERR_EIO.
 *
 * Built on k_timer rather than the counter API. The stack uses this to schedule wakeups
 * around ranging blocks, which are milliseconds apart, so tick granularity (30.5 us at the
 * default 32768 Hz) is far finer than needed and avoids depending on a particular hardware
 * timer instance being free — the nRF's are already claimed by the kernel and the
 * Bluetooth controller.
 *
 * `qtimer_id`, and the requested frequency and width in qtimer_config, are therefore
 * ignored: they describe a hardware timer instance this implementation does not allocate.
 */
#include <qerr.h>
#include <qtimer.h>

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>

LOG_MODULE_REGISTER(qorvo_qtimer, CONFIG_QORVO_UWB_LOG_LEVEL);

/* The stack allocates one idle timer; a second slot leaves room without a heap. */
#define MAX_TIMERS 2

struct qtimer {
	struct k_timer timer;
	qtimer_cb cb;
	void *arg;
	int64_t started_ticks;
	bool used;
};

static struct qtimer timers[MAX_TIMERS];

static void on_expiry(struct k_timer *t)
{
	struct qtimer *q = CONTAINER_OF(t, struct qtimer, timer);

	if (q->cb) {
		q->cb(q->arg);
	}
}

struct qtimer *qtimer_init(uint8_t qtimer_id, const struct qtimer_config *config,
			   qtimer_cb handler, void *arg)
{
	ARG_UNUSED(qtimer_id);
	ARG_UNUSED(config);

	for (int i = 0; i < MAX_TIMERS; i++) {
		if (timers[i].used) {
			continue;
		}
		timers[i].cb = handler;
		timers[i].arg = arg;
		timers[i].started_ticks = 0;
		timers[i].used = true;
		k_timer_init(&timers[i].timer, on_expiry, NULL);
		return &timers[i];
	}
	LOG_ERR("out of qtimer slots");
	return NULL;
}

enum qerr qtimer_start(const struct qtimer *timer, uint32_t us, bool periodic)
{
	/* The interface is const, but starting a timer necessarily mutates it. */
	struct qtimer *q = (struct qtimer *)timer;

	if (q == NULL || !q->used) {
		return QERR_EINVAL;
	}
	q->started_ticks = k_uptime_ticks();
	/*
	 * Note the inverted sense in qtimer.h: it documents `periodic` as "true for a
	 * one-shot timer, false for a cyclic timer". Going by the parameter name rather
	 * than that comment, since the name is what callers read — periodic means repeat.
	 */
	k_timer_start(&q->timer, K_USEC(us), periodic ? K_USEC(us) : K_NO_WAIT);
	return QERR_SUCCESS;
}

enum qerr qtimer_stop(const struct qtimer *timer)
{
	struct qtimer *q = (struct qtimer *)timer;

	if (q == NULL || !q->used) {
		return QERR_EINVAL;
	}
	k_timer_stop(&q->timer);
	return QERR_SUCCESS;
}

enum qerr qtimer_read(const struct qtimer *timer, uint32_t *us)
{
	struct qtimer *q = (struct qtimer *)timer;

	if (q == NULL || !q->used || us == NULL) {
		return QERR_EINVAL;
	}
	*us = (uint32_t)k_ticks_to_us_floor64(k_uptime_ticks() - q->started_ticks);
	return QERR_SUCCESS;
}
