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

/* From qplatform_zephyr.c: the devicetree-derived bus spec for the dw3110 node. */
const struct spi_dt_spec *ff_qorvo_spi_spec(void);

struct qspi {
	const struct device *dev;
	struct spi_config cfg;
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

/* Number of opening transfers to hexdump, which is enough to cover the device-id probe. */
#define TRACE_XFERS 4
static unsigned int traced;

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
	const struct spi_dt_spec *spec = ff_qorvo_spi_spec();

	if (spi == NULL || config == NULL || spi->dev == NULL) {
		return QERR_EINVAL;
	}

	/*
	 * Start from the devicetree-derived config rather than assembling one by hand.
	 * A hand-built equivalent clocked out the right bytes but read back all zeros,
	 * even though its frequency, operation word and chip-select all logged identically
	 * to this spec's. Only the clock is taken from qspi_config, because the driver
	 * raises it from the cold-start rate once the device is identified.
	 *
	 * Clamped to the node's spi-max-frequency: the driver asks for its fast rate
	 * unconditionally, and honouring a rate above what the board declares is how you
	 * get a bus that enumerates fine and then fails once traffic gets real.
	 */
	spi->cfg = spec->config;
	spi->cfg.frequency = MIN(config->freq_hz, spec->config.frequency);

	if (config->freq_hz > spec->config.frequency) {
		LOG_INF("qspi_configure: capping %u Hz to the node's %u Hz",
			config->freq_hz, spec->config.frequency);
	}
	LOG_INF("qspi_configure: %u Hz, op=0x%08x (from devicetree spec)",
		spi->cfg.frequency, (unsigned int)spi->cfg.operation);
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

	/*
	 * The first few transfers are the driver probing the device id. Logging them makes
	 * a probe failure diagnosable: dwt_probe() only reports DWT_ERROR, which cannot
	 * distinguish "the bus returned nothing" from "the id matched no known driver".
	 */
	if (traced < TRACE_XFERS) {
		traced++;
		LOG_HEXDUMP_INF(xfer->tx_buf, MIN(xfer->tx_size, 8U), "spi tx");
		LOG_HEXDUMP_INF(xfer->rx_buf, MIN(xfer->rx_size, 8U), "spi rx");
	}

	if (spi->done_cb) {
		spi->done_cb(spi->done_arg);
	}
	return QERR_SUCCESS;
}
