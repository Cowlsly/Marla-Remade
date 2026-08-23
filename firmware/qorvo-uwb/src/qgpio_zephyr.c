/*
 * Zephyr GPIO backend for Qorvo's qhal.
 *
 * Replaces qhal/src/zephyr/qgpio.c, which cannot be compiled against Zephyr 4.4: it
 * defines its own `gpio_is_ready_dt` and `gpio_add_callback_dt` helpers, which Zephyr
 * has since added to gpio.h, so the two collide. Renaming them from the command line
 * doesn't help — the rename would hit Zephyr's definitions too.
 *
 * qplatform supplies each pin as a `gpio_dt_spec` in `qgpio.dev` (see
 * qplatform_zephyr.c); `port` and `pin_number` are unused on Zephyr.
 *
 * qgpio's flag bits are NOT the same as Zephyr's despite reading similarly — for
 * instance QGPIO_PULL_UP is BIT(2) where GPIO_PULL_UP is BIT(4) — so every flag is
 * translated explicitly rather than passed through.
 */
#include <qerr.h>
#include <qgpio.h>

#include <zephyr/device.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/atomic.h>

LOG_MODULE_REGISTER(qorvo_qgpio, CONFIG_QORVO_UWB_LOG_LEVEL);

/* The stack only ever registers a callback on the transceiver IRQ line. */
#define MAX_CALLBACKS 2

struct cb_entry {
	const struct gpio_dt_spec *spec;
	struct gpio_callback zephyr_cb;
	qgpio_irq_cb cb;
	void *arg;
	bool used;
};

static struct cb_entry callbacks[MAX_CALLBACKS];

/*
 * The qgpio callback is dispatched from a thread, not from the GPIOTE ISR.
 *
 * The vendor's handler (qplatform_uwb_spi_irq_handler) is `while (irq_high) dwt_isr()`, and
 * dwt_isr() reads SYS_STATUS over SPI. That is legal on Qorvo's nrfx backend, whose
 * qspi_transceive busy-waits on a volatile flag, but not here: Zephyr's spi_transceive()
 * completes via k_sem_take, which cannot wait in ISR context, so every such transfer would
 * fail with -ETIMEDOUT and pend whichever thread happened to be interrupted. qgpio.h permits
 * this — "Considered this called from ISR context unless indicated otherwise on
 * implementation" — so the choice belongs to the backend.
 *
 * The interrupt is deliberately left ARMED while the handler drains. The vendor's loop runs
 * until the line goes low, so an assertion arriving mid-drain is picked up by the loop itself,
 * and a fresh low-to-high transition afterwards produces a new edge and another wakeup.
 * Disabling the trigger around the drain — the usual shape of this refactor — is what would
 * lose an edge. An edge trigger cannot storm while the line stays high, so leaving it armed
 * costs nothing.
 */
static void irq_dispatch_thread(void *a, void *b, void *c);

/*
 * 2048 matches CONFIG_ISR_STACK_SIZE, which is the stack this work ran on until now, so it is
 * a like-for-like size rather than a guess. CONFIG_HW_STACK_PROTECTION is enabled for this
 * board, so an undersized stack faults loudly instead of corrupting a neighbour.
 */
#define IRQ_THREAD_STACK_SIZE 2048

/*
 * Lowest cooperative priority: above every preemptible thread — the Qorvo MAC threads occupy
 * Zephyr 0..6 via qthread_zephyr.c, the poll thread 7, log processing 3 — so MAC work cannot
 * starve the handler it depends on, and cooperative means no timeslice can preempt a drain
 * mid-way. Still below the Bluetooth RX thread at K_PRIO_COOP(CONFIG_BT_RX_PRIO) = -8, so
 * crowd-finding keeps its latency. This is the TRAP-6 tradeoff taken deliberately in BLE's
 * favour.
 */
#define IRQ_THREAD_PRIORITY -1

K_THREAD_DEFINE(qorvo_irq_thread, IRQ_THREAD_STACK_SIZE, irq_dispatch_thread, NULL, NULL, NULL,
		IRQ_THREAD_PRIORITY, 0, 0);

static K_SEM_DEFINE(irq_signal, 0, 1);

/* Which callbacks/pins have fired; a bitmask so one wakeup can serve several. */
static atomic_t irq_pending;

/*
 * Bring-up instrumentation for the transceiver IRQ. llhw arms the DW3110 interrupt
 * (llhw_drv_init -> qplatform_uwb_interrupt_enable) and it is delivered through this file,
 * which until now had no way to report either event: every log statement here is a LOG_ERR,
 * so silence in a capture means "no error", not "armed and delivered".
 *
 * Counters rather than a log line in the callback: logging from the GPIO ISR would perturb
 * the timing being measured, and the arming path is also reached from decamutexon/off around
 * ordinary register access, so it can be hot.
 */
static atomic_t irq_events;
static atomic_t irq_arm_calls;

/* Bounded so a hot arming path cannot flood the capture; as TRACE_XFERS does in qspi_zephyr.c. */
#define IRQ_ARM_TRACE_MAX 8
static unsigned int irq_arm_traced;

static const struct gpio_dt_spec *spec_of(const struct qgpio *pin)
{
	if (pin == NULL || pin->dev == NULL) {
		return NULL;
	}
	return (const struct gpio_dt_spec *)pin->dev;
}

static gpio_flags_t translate_config(uint32_t flags)
{
	gpio_flags_t out = 0;

	if (flags & QGPIO_INPUT) {
		out |= GPIO_INPUT;
	}
	if (flags & INTERNAL_QGPIO_OUTPUT) {
		out |= GPIO_OUTPUT;
		/* Initial level, so a reset line never glitches to the wrong state. */
		if (flags & INTERNAL_QGPIO_OUTPUT_INIT_HIGH) {
			out |= GPIO_OUTPUT_INIT_HIGH;
		} else if (flags & INTERNAL_QGPIO_OUTPUT_INIT_LOW) {
			out |= GPIO_OUTPUT_INIT_LOW;
		}
	}
	if (flags & QGPIO_PULL_UP) {
		out |= GPIO_PULL_UP;
	}
	if (flags & QGPIO_PULL_DOWN) {
		out |= GPIO_PULL_DOWN;
	}
	if (flags & QGPIO_SINGLE_ENDED) {
		out |= GPIO_SINGLE_ENDED;
		/* The DW3110's RSTn is open drain: pulled low, released, never driven. */
		out |= (flags & QGPIO_LINE_OPEN_DRAIN) ? GPIO_LINE_OPEN_DRAIN
						       : GPIO_LINE_OPEN_SOURCE;
	}
	return out;
}

static gpio_flags_t translate_irq(uint32_t flags)
{
	if (flags & INTERNAL_QGPIO_INT_DISABLE) {
		return GPIO_INT_DISABLE;
	}
	if (!(flags & INTERNAL_QGPIO_INT_ENABLE)) {
		return GPIO_INT_DISABLE;
	}
	if (flags & INTERNAL_QGPIO_INT_EDGE) {
		const bool low = flags & INTERNAL_QGPIO_INT_LOW;
		const bool high = flags & INTERNAL_QGPIO_INT_HIGH;

		if (low && high) {
			return GPIO_INT_EDGE_BOTH;
		}
		return high ? GPIO_INT_EDGE_RISING : GPIO_INT_EDGE_FALLING;
	}
	return (flags & INTERNAL_QGPIO_INT_HIGH) ? GPIO_INT_LEVEL_HIGH : GPIO_INT_LEVEL_LOW;
}

enum qerr qgpio_pin_configure(const struct qgpio *qgpio_pin, uint32_t flags)
{
	const struct gpio_dt_spec *spec = spec_of(qgpio_pin);
	int rc;

	if (spec == NULL) {
		return QERR_EINVAL;
	}
	if (!gpio_is_ready_dt(spec)) {
		LOG_ERR("GPIO port not ready");
		return QERR_ENODEV;
	}
	rc = gpio_pin_configure_dt(spec, translate_config(flags));
	if (rc < 0) {
		LOG_ERR("gpio_pin_configure_dt: %d", rc);
		return QERR_EIO;
	}
	return QERR_SUCCESS;
}

enum qerr qgpio_pin_write(const struct qgpio *qgpio_pin, uint8_t value)
{
	const struct gpio_dt_spec *spec = spec_of(qgpio_pin);

	if (spec == NULL) {
		return QERR_EINVAL;
	}
	return gpio_pin_set_dt(spec, value ? 1 : 0) < 0 ? QERR_EIO : QERR_SUCCESS;
}

enum qerr qgpio_pin_read(const struct qgpio *qgpio_pin, uint8_t *value)
{
	const struct gpio_dt_spec *spec = spec_of(qgpio_pin);
	int rc;

	if (spec == NULL || value == NULL) {
		return QERR_EINVAL;
	}
	rc = gpio_pin_get_dt(spec);
	if (rc < 0) {
		return QERR_EIO;
	}
	*value = (uint8_t)rc;
	return QERR_SUCCESS;
}

static const char *trigger_name(gpio_flags_t zephyr_flags)
{
	if (!(zephyr_flags & GPIO_INT_ENABLE)) {
		return "DISABLE";
	}
	if (zephyr_flags & GPIO_INT_EDGE) {
		switch (zephyr_flags & (GPIO_INT_LOW_0 | GPIO_INT_HIGH_1)) {
		case (GPIO_INT_LOW_0 | GPIO_INT_HIGH_1):
			return "EDGE_BOTH";
		case GPIO_INT_HIGH_1:
			return "EDGE_RISING";
		default:
			return "EDGE_FALLING";
		}
	}
	return (zephyr_flags & GPIO_INT_HIGH_1) ? "LEVEL_HIGH" : "LEVEL_LOW";
}

enum qerr qgpio_pin_irq_configure(const struct qgpio *qgpio_pin, uint32_t flags)
{
	const struct gpio_dt_spec *spec = spec_of(qgpio_pin);
	gpio_flags_t zephyr_flags;
	int rc;

	if (spec == NULL) {
		return QERR_EINVAL;
	}
	zephyr_flags = translate_irq(flags);
	rc = gpio_pin_interrupt_configure_dt(spec, zephyr_flags);
	if (rc < 0) {
		LOG_ERR("gpio_pin_interrupt_configure_dt: %d", rc);
		return QERR_EIO;
	}
	atomic_inc(&irq_arm_calls);
	if (irq_arm_traced < IRQ_ARM_TRACE_MAX) {
		irq_arm_traced++;
		/*
		 * The level is read *after* arming, which is the point of logging it: an edge
		 * trigger cannot see a line that is already asserted, so "armed EDGE_RISING,
		 * level already 1" is an interrupt that can never fire again and says nothing.
		 */
		LOG_INF("irq armed %s.%u: qgpio=0x%08x -> %s (0x%08x), level now %d",
			spec->port->name, spec->pin, (unsigned int)flags,
			trigger_name(zephyr_flags), (unsigned int)zephyr_flags,
			gpio_pin_get_dt(spec));
	}
	return QERR_SUCCESS;
}

static void on_gpio_event(const struct device *port, struct gpio_callback *cb, uint32_t pins)
{
	struct cb_entry *entry = CONTAINER_OF(cb, struct cb_entry, zephyr_cb);

	ARG_UNUSED(port);
	ARG_UNUSED(pins);

	/* ISR context: signal and return. Nothing here may touch the bus. */
	atomic_inc(&irq_events);
	atomic_or(&irq_pending, (atomic_val_t)BIT(entry - callbacks));
	k_sem_give(&irq_signal);
}

static void irq_dispatch_thread(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	for (;;) {
		atomic_val_t pending;

		k_sem_take(&irq_signal, K_FOREVER);
		pending = atomic_and(&irq_pending, 0);

		for (int i = 0; i < MAX_CALLBACKS; i++) {
			if ((pending & BIT(i)) && callbacks[i].used && callbacks[i].cb) {
				callbacks[i].cb(callbacks[i].arg);
			}
		}
	}
}

enum qerr qgpio_pin_irq_set_callback(const struct qgpio *qgpio_pin, qgpio_irq_cb cb, void *arg)
{
	const struct gpio_dt_spec *spec = spec_of(qgpio_pin);
	struct cb_entry *entry = NULL;
	int rc;

	if (spec == NULL) {
		return QERR_EINVAL;
	}

	/* Re-registering the same pin replaces its handler rather than leaking a slot. */
	for (int i = 0; i < MAX_CALLBACKS; i++) {
		if (callbacks[i].used && callbacks[i].spec == spec) {
			entry = &callbacks[i];
			break;
		}
	}
	if (entry == NULL) {
		for (int i = 0; i < MAX_CALLBACKS; i++) {
			if (!callbacks[i].used) {
				entry = &callbacks[i];
				break;
			}
		}
	}
	if (entry == NULL) {
		LOG_ERR("no free GPIO callback slot");
		return QERR_ENOMEM;
	}

	if (entry->used) {
		(void)gpio_remove_callback_dt(spec, &entry->zephyr_cb);
	}
	entry->spec = spec;
	entry->cb = cb;
	entry->arg = arg;
	gpio_init_callback(&entry->zephyr_cb, on_gpio_event, BIT(spec->pin));
	rc = gpio_add_callback_dt(spec, &entry->zephyr_cb);
	if (rc < 0) {
		LOG_ERR("gpio_add_callback_dt: %d", rc);
		return QERR_EIO;
	}
	entry->used = true;
	LOG_INF("irq callback registered on %s.%u", spec->port->name, spec->pin);
	return QERR_SUCCESS;
}

void ff_qorvo_gpio_irq_stats(uint32_t *events, uint32_t *arm_calls)
{
	if (events != NULL) {
		*events = (uint32_t)atomic_get(&irq_events);
	}
	if (arm_calls != NULL) {
		*arm_calls = (uint32_t)atomic_get(&irq_arm_calls);
	}
}
