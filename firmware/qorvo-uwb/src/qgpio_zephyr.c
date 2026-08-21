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

enum qerr qgpio_pin_irq_configure(const struct qgpio *qgpio_pin, uint32_t flags)
{
	const struct gpio_dt_spec *spec = spec_of(qgpio_pin);
	int rc;

	if (spec == NULL) {
		return QERR_EINVAL;
	}
	rc = gpio_pin_interrupt_configure_dt(spec, translate_irq(flags));
	if (rc < 0) {
		LOG_ERR("gpio_pin_interrupt_configure_dt: %d", rc);
		return QERR_EIO;
	}
	return QERR_SUCCESS;
}

static void on_gpio_event(const struct device *port, struct gpio_callback *cb, uint32_t pins)
{
	struct cb_entry *entry = CONTAINER_OF(cb, struct cb_entry, zephyr_cb);

	ARG_UNUSED(port);
	ARG_UNUSED(pins);

	if (entry->cb) {
		entry->cb(entry->arg);
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
	return QERR_SUCCESS;
}
