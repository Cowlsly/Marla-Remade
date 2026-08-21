/*
 * Platform instance definitions for Qorvo's qplatform on Zephyr.
 *
 * Replaces qplatform/qm33_qhal_zephyr/src/qplatform.c, which cannot be used as
 * shipped: it dereferences `spi_config.cs` as a pointer (it has been a by-value
 * struct since Zephyr 3.5) and hard-#errors unless the devicetree happens to label
 * its UWB node `dw35720` — a DW3572, where this board carries a DW3110.
 *
 * The four externs below are what qm33_qhal_common/src/qplatform.c consumes; see
 * qplatform_internal.h. Everything comes from the devicetree so the pin mapping
 * lives in the board overlay rather than in compile definitions.
 */
#include "qplatform_internal.h"

#include <qgpio.h>
#include <qspi.h>

#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/spi.h>
#include <zephyr/sys/byteorder.h>

#define UWB_NODE DT_NODELABEL(dw3110)

#if !DT_NODE_HAS_STATUS(UWB_NODE, okay)
#error "Enable a 'dw3110' SPI node with irq-gpios and rstn-gpios in the board overlay. \
See firmware/findfamily-tracker/boards/decawave_dwm3001cdk_nrf52833.overlay."
#endif

static const struct gpio_dt_spec rstn_pin_spec = GPIO_DT_SPEC_GET(UWB_NODE, rstn_gpios);
static const struct gpio_dt_spec irq_pin_spec = GPIO_DT_SPEC_GET(UWB_NODE, irq_gpios);
static const struct gpio_dt_spec cs_pin_spec = GPIO_DT_SPEC_GET(DT_BUS(UWB_NODE), cs_gpios);

/*
 * qhal's Zephyr qgpio backend keys entirely off `dev` (a gpio_dt_spec); port and
 * pin_number are unused there and left zero.
 */
const struct qgpio qm33_rstn = {
	.dev = (void *)&rstn_pin_spec,
	.port = 0,
	.pin_number = 0,
};

const struct qgpio qm33_irq = {
	.dev = (void *)&irq_pin_spec,
	.port = 0,
	.pin_number = 0,
};

const struct qspi_instance qm33_qspi_instance = {
	.instance_number = CONFIG_UWB_SPI_INSTANCE,
	.dev = DEVICE_DT_GET(DT_BUS(UWB_NODE)),
};

struct qspi_config qm33_qspi_config = {
	/* SCK/MOSI/MISO are owned by Zephyr's pinctrl, so qhal needs no descriptors. */
	.sck_pin = { .port = 0, .pin_number = 0, .dev = NULL },
	.mosi_pin = { .port = 0, .pin_number = 0, .dev = NULL },
	.miso_pin = { .port = 0, .pin_number = 0, .dev = NULL },
	.cs_pin = { .port = 0, .pin_number = 0, .dev = (void *)&cs_pin_spec },
	/* Slow until the device is identified; the driver raises it afterwards. */
	.freq_hz = CONFIG_SPI_UWB_SLOW_RATE_FREQ,
	.irq_priority = CONFIG_SPI_UWB_IRQ_PRIORITY,
	/*
	 * SPI mode 0. Deliberately not the SDK's QSPI_OP_FLAGS, which sets QSPI_CPOL:
	 * the DW3000 datasheet specifies mode 0 (CPOL=0, CPHA=0), and mode 2 is what
	 * the nrfx backend's flag handling happens to paper over.
	 */
	.op_flags = QSPI_MASTER | QSPI_MSB_FIRST | QSPI_MISO_SINGLE |
		    QSPI_SET_FRAME_LEN(CONFIG_UWB_SPI_FRAME_SIZE),
};

/*
 * Bring-up diagnostic: read the transceiver's DEV_ID register straight through Zephyr's
 * SPI API, bypassing the Qorvo driver entirely. A DW3110 answers 0xDECA0302.
 *
 * This exists because "qplatform_init failed" is ambiguous — it cannot distinguish a
 * miswired bus or a wrong SPI mode from the driver being unhappy about something it read.
 * A correct id here means the bus is fine and the problem is above it.
 *
 * The transaction is a DW3000 short-addressed read: one header byte (bit 7 clear = read,
 * register file 0 offset 0) followed by four bytes clocked out to shift the value in.
 */
uint32_t ff_qorvo_read_dev_id(void)
{
	static const struct spi_dt_spec bus =
		SPI_DT_SPEC_GET(UWB_NODE,
				SPI_WORD_SET(8) | SPI_OP_MODE_MASTER | SPI_TRANSFER_MSB, 0);
	uint8_t tx[5] = { 0x00, 0x00, 0x00, 0x00, 0x00 };
	uint8_t rx[5] = { 0 };
	const struct spi_buf tx_buf = { .buf = tx, .len = sizeof(tx) };
	const struct spi_buf rx_buf = { .buf = rx, .len = sizeof(rx) };
	const struct spi_buf_set tx_set = { .buffers = &tx_buf, .count = 1 };
	const struct spi_buf_set rx_set = { .buffers = &rx_buf, .count = 1 };

	if (spi_transceive_dt(&bus, &tx_set, &rx_set) < 0) {
		return 0;
	}
	return sys_get_le32(&rx[1]);
}
