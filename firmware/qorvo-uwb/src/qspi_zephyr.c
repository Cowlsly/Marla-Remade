/*
 * Zephyr SPI backend for Qorvo's qhal.
 *
 * The SDK ships qhal/src/zephyr/qspi.c, but every function in it returns
 * QERR_ENOTSUP — it is a placeholder, not an implementation. This is the real one.
 *
 * The DW3000 is only ever driven full-duplex with equal-length buffers: the
 * qplatform layer concatenates the command header and the body into one buffer and
 * passes tx_size == rx_size (see qm33_qhal_common/src/qplatform.c,
 * qplatform_uwb_spi_read/write). So a single spi_transceive with one tx and one rx
 * buffer covers every transfer the stack makes.
 */
#include <qerr.h>
#include <qgpio.h>
#include <qspi.h>

#include <zephyr/device.h>
#include <zephyr/drivers/spi.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>

#include <string.h>

LOG_MODULE_REGISTER(qorvo_qspi, CONFIG_QORVO_UWB_LOG_LEVEL);

struct qspi {
	const struct device *dev;
	struct spi_config cfg;
	struct spi_cs_control cs;
	bool in_use;
	/*
	 * Async completion callback. The stack sets this to opt into async transfers;
	 * we always transfer synchronously and invoke it on completion, which satisfies
	 * the contract (qspi.h: "If NULL, synchronous mode is used") without needing
	 * SPI_ASYNC and its extra buffering.
	 */
	qspi_xfer_cb done_cb;
	void *done_arg;
};

/* One UWB transceiver per board; a static instance avoids needing an allocator here. */
static struct qspi the_spi;

struct qspi *qspi_open(const struct qspi_instance *instance)
{
	const struct device *dev;

	if (instance == NULL || instance->dev == NULL) {
		LOG_ERR("qspi_open: no device in instance");
		return NULL;
	}
	if (the_spi.in_use) {
		LOG_ERR("qspi_open: already open");
		return NULL;
	}
	dev = (const struct device *)instance->dev;
	if (!device_is_ready(dev)) {
		LOG_ERR("qspi_open: SPI device %s not ready", dev->name);
		return NULL;
	}

	memset(&the_spi, 0, sizeof(the_spi));
	the_spi.dev = dev;
	the_spi.in_use = true;
	return &the_spi;
}

enum qerr qspi_close(struct qspi *spi)
{
	if (spi == NULL) {
		return QERR_EINVAL;
	}
	spi->in_use = false;
	spi->dev = NULL;
	return QERR_SUCCESS;
}

enum qerr qspi_configure(struct qspi *spi, const struct qspi_config *config)
{
	spi_operation_t op;

	if (spi == NULL || config == NULL || spi->dev == NULL) {
		return QERR_EINVAL;
	}

	op = SPI_WORD_SET(8) | SPI_OP_MODE_MASTER | SPI_TRANSFER_MSB;
	if (config->op_flags & QSPI_CPOL) {
		op |= SPI_MODE_CPOL;
	}
	if (config->op_flags & QSPI_CPHA) {
		op |= SPI_MODE_CPHA;
	}
	if (config->op_flags & QSPI_LSB_FIRST) {
		op &= ~(spi_operation_t)SPI_TRANSFER_MSB;
		op |= SPI_TRANSFER_LSB;
	}
	if (config->op_flags & QSPI_LOOP) {
		op |= SPI_MODE_LOOP;
	}

	spi->cfg.frequency = config->freq_hz;
	spi->cfg.operation = op;

	/*
	 * qplatform hands us the chip select as a gpio_dt_spec in cs_pin.dev, taken
	 * from the devicetree. Zephyr's SPI driver drives CS itself when given this,
	 * which matters for the DW3000: it needs CS to frame the whole
	 * header-plus-body transfer, not each byte.
	 */
	if (config->cs_pin.dev != NULL) {
		spi->cs.gpio = *(const struct gpio_dt_spec *)config->cs_pin.dev;
		spi->cs.delay = 0;
		spi->cfg.cs = spi->cs;
	}

	LOG_DBG("qspi_configure: %u Hz, op=0x%08x", config->freq_hz, (unsigned int)op);
	return QERR_SUCCESS;
}

enum qerr qspi_irq_set_callback(struct qspi *spi, qspi_xfer_cb handler, void *arg)
{
	if (spi == NULL) {
		return QERR_EINVAL;
	}
	spi->done_cb = handler;
	spi->done_arg = arg;
	return QERR_SUCCESS;
}

enum qerr qspi_transceive(struct qspi *spi, const struct qspi_transfer *xfer)
{
	struct spi_buf tx_buf;
	struct spi_buf rx_buf;
	struct spi_buf_set tx_set = { .buffers = &tx_buf, .count = 1 };
	struct spi_buf_set rx_set = { .buffers = &rx_buf, .count = 1 };
	int rc;

	if (spi == NULL || xfer == NULL || spi->dev == NULL) {
		return QERR_EINVAL;
	}

	tx_buf.buf = xfer->tx_buf;
	tx_buf.len = xfer->tx_size;
	rx_buf.buf = xfer->rx_buf;
	rx_buf.len = xfer->rx_size;

	rc = spi_transceive(spi->dev, &spi->cfg,
			    xfer->tx_buf ? &tx_set : NULL,
			    xfer->rx_buf ? &rx_set : NULL);
	if (rc < 0) {
		LOG_ERR("spi_transceive failed: %d", rc);
		return QERR_EIO;
	}

	if (spi->done_cb) {
		spi->done_cb(spi->done_arg);
	}
	return QERR_SUCCESS;
}
