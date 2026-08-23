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
#include <zephyr/sys/atomic.h>

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

/*
 * Bring-up instrumentation. llhw drives its ranging schedule off this timer, so the wait
 * duration it asks for is a number we can read: if the MAC arms for something absurd, the
 * fault is in the time base rather than the radio.
 *
 * Counters are unbounded but the log trace is capped, because a working schedule would arm
 * this every block and flood the capture. Capping alone is not enough though - last time a
 * cap hid exactly the calls that mattered - so the counters and the last requested interval
 * are reported separately and survive past the cap.
 */
#define QTIMER_TRACE_MAX 16
static unsigned int call_traced;
static unsigned int expiry_traced;
static atomic_t starts;
static atomic_t stops;
static atomic_t expiries;
/* Single aligned 32-bit accesses; diagnostic only, deliberately not synchronised. */
static volatile uint32_t last_us;
static volatile bool last_periodic;

void ff_qorvo_timer_stats(uint32_t *starts_out, uint32_t *stops_out, uint32_t *expiries_out,
			  uint32_t *last_us_out)
{
	if (starts_out != NULL) {
		*starts_out = (uint32_t)atomic_get(&starts);
	}
	if (stops_out != NULL) {
		*stops_out = (uint32_t)atomic_get(&stops);
	}
	if (expiries_out != NULL) {
		*expiries_out = (uint32_t)atomic_get(&expiries);
	}
	if (last_us_out != NULL) {
		*last_us_out = last_us;
	}
}

static void on_expiry(struct k_timer *t)
{
	struct qtimer *q = CONTAINER_OF(t, struct qtimer, timer);

	atomic_inc(&expiries);
	if (expiry_traced < QTIMER_TRACE_MAX) {
		expiry_traced++;
		LOG_INF("qtimer expiry");
	}
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
	atomic_inc(&starts);
	last_us = us;
	last_periodic = periodic;
	if (call_traced < QTIMER_TRACE_MAX) {
		call_traced++;
		LOG_INF("qtimer_start: us=%u (%u ms), periodic=%d", us, us / 1000U,
			(int)periodic);
	}
	/*
	 * Note the inverted sense in qtimer.h: it documents `periodic` as "true for a
	 * one-shot timer, false for a cyclic timer". Going by the parameter name rather
	 * than that comment, since the name is what callers read - periodic means repeat.
	 * Settled since: the vendor's own nrfx backend re-arms when the flag is true and
	 * disables when it is false (qhal/src/nrfx/qrtc_share.c), so the comment is wrong
	 * and the name is right. llhw passes false, i.e. one-shot, and re-arms each interval.
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
	atomic_inc(&stops);
	if (call_traced < QTIMER_TRACE_MAX) {
		call_traced++;
		LOG_INF("qtimer_stop");
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
