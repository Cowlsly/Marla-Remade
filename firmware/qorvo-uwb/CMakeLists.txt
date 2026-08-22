# Zephyr module wrapping the licensed Qorvo DW3_QM33 SDK.
#
# The SDK is NOT vendored in this repo — it ships under a Qorvo licence. Point
# QORVO_SDK_DIR at an extracted copy (see README.md). Only the glue lives here:
# a Zephyr SPI backend and the platform instance definitions, because the versions
# the SDK ships are respectively a stub and written against an older Zephyr API.

if(NOT CONFIG_QORVO_UWB)
  return()
endif()

if(NOT QORVO_SDK_DIR)
  set(QORVO_SDK_DIR "$ENV{QORVO_SDK_DIR}")
endif()
if(NOT QORVO_SDK_DIR OR NOT EXISTS "${QORVO_SDK_DIR}/Libs/uwb-stack")
  message(FATAL_ERROR
    "CONFIG_QORVO_UWB=y but the Qorvo SDK was not found.\n"
    "Set -DQORVO_SDK_DIR=<path> or the QORVO_SDK_DIR environment variable to an\n"
    "extracted DW3_QM33_SDK (the directory containing Libs/uwb-stack).\n"
    "Got: '${QORVO_SDK_DIR}'\n"
    "See firmware/qorvo-uwb/README.md.")
endif()

set(UWB_STACK "${QORVO_SDK_DIR}/Libs/uwb-stack")
set(QOSAL "${UWB_STACK}/libs/qosal")
set(QHAL "${UWB_STACK}/libs/qhal")
set(QMATH "${UWB_STACK}/libs/qmath")
set(QPLATFORM "${UWB_STACK}/libs/qplatform")

# The driver ships in the Drivers package, which extracts alongside the SDK zip
# rather than inside it, so allow it to be pointed at separately.
if(NOT QORVO_DRIVER_DIR)
  set(QORVO_DRIVER_DIR "$ENV{QORVO_DRIVER_DIR}")
endif()
if(NOT QORVO_DRIVER_DIR)
  set(QORVO_DRIVER_DIR "${QORVO_SDK_DIR}/Libs/dwt_uwb_driver")
endif()
if(NOT EXISTS "${QORVO_DRIVER_DIR}/deca_device_api.h")
  message(FATAL_ERROR
    "DW3xxx driver not found at '${QORVO_DRIVER_DIR}'. Set -DQORVO_DRIVER_DIR=<path>\n"
    "to the dwt_uwb_driver directory (it contains deca_device_api.h).")
endif()

# Prebuilt FiRa bundle. nRF52833 is a Cortex-M4 with no FPU, hence soft_floating;
# picking the hard-float archive links but faults at the first float call.
file(GLOB FIRA_LIB
  "${QORVO_SDK_DIR}/Libs/uwbstack_libs/delivery/fira/Release/lib/arm-cortex-m4-soft_floating/uwbstack_bundle/libuwbstack_bundle_fira_*.a")
if(NOT FIRA_LIB)
  message(FATAL_ERROR "No arm-cortex-m4-soft_floating uwbstack bundle under ${QORVO_SDK_DIR}")
endif()
list(GET FIRA_LIB 0 FIRA_LIB)
message(STATUS "Qorvo FiRa bundle: ${FIRA_LIB}")

set(FIRA_INC
  "${QORVO_SDK_DIR}/Libs/uwbstack_libs/delivery/fira/Release/include/uwbstack_bundle")

zephyr_include_directories(
  ${FIRA_INC}
  ${CMAKE_CURRENT_LIST_DIR}/include
  ${QOSAL}/include
  ${QOSAL}/include/zephyr
  ${QHAL}/include
  ${QMATH}/include
  ${QPLATFORM}/include
  ${QORVO_DRIVER_DIR}

  # Qorvo's headers predate the <zephyr/...> include prefix (Zephyr 3.0) and still write
  # <kernel.h>, <device.h>, <logging/log.h>. These have to be global rather than scoped
  # to this library because the FindFamily firmware includes uwbmac/fira_helper.h, which
  # pulls the qosal headers in. include-compat/ supplies the two headers that were
  # removed outright rather than renamed.
  ${ZEPHYR_BASE}/include/zephyr
  ${CMAKE_CURRENT_LIST_DIR}/include-compat
)

zephyr_library_named(qorvo_uwb)

zephyr_library_sources(
  # Our glue. Three of the SDK's Zephyr backends are unusable as shipped:
  #  - qhal/src/zephyr/qspi.c returns QERR_ENOTSUP from every function (a placeholder).
  #  - qhal/src/zephyr/qtimer.c likewise, which alone makes llhw_init fail with EIO.
  #  - qhal/src/zephyr/qgpio.c redefines gpio_is_ready_dt/gpio_add_callback_dt, which
  #    Zephyr has since added itself.
  src/qspi_zephyr.c
  src/qtimer_zephyr.c
  src/qgpio_zephyr.c
  #  - qplatform/qm33_qhal_zephyr/qplatform.c dereferences spi_config.cs as a pointer
  #    (by value since Zephyr 3.5) and hard-requires a "dw35720" node label.
  #  - qhal/src/qm33/qpwr.c and qhal/src/qrtc.c likewise, or route through
  #    persistent_time, which only has an nrfx implementation.
  # mcps_crypto_psa.c replaces Src/UWB/mcps_crypto.c so the image has one crypto
  # provider rather than PSA plus Nordic's nrf_crypto.
  src/qspi_zephyr.c
  src/qgpio_zephyr.c
  src/qplatform_zephyr.c
  src/qhal_extras_zephyr.c
  src/l1_config_storage_zephyr.c
  src/qmalloc_zephyr.c
  src/qthread_zephyr.c
  src/mcps_crypto_psa.c

  # qosal — Zephyr OS abstraction.
  ${QOSAL}/src/qerr_common.c
  ${QOSAL}/src/qmalloc.c
  ${QOSAL}/src/zephyr/qerr.c
  ${QOSAL}/src/zephyr/qirq.c
  ${QOSAL}/src/zephyr/qlog.c
  ${QOSAL}/src/zephyr/qmsg_queue.c
  ${QOSAL}/src/zephyr/qmutex.c
  ${QOSAL}/src/zephyr/qos.c
  ${QOSAL}/src/zephyr/qpm.c
  ${QOSAL}/src/zephyr/qprofiling.c
  ${QOSAL}/src/zephyr/qrand.c
  ${QOSAL}/src/zephyr/qsemaphore.c
  ${QOSAL}/src/zephyr/qsignal.c
  ${QOSAL}/src/zephyr/qtime.c
  ${QOSAL}/src/zephyr/qtracing.c
  ${QOSAL}/src/zephyr/qworkqueue.c

  # qhal — Zephyr hardware abstraction (qspi.c, qgpio.c and qtimer.c replaced above).
  # OTP access, needed to read this module's factory calibration.
  ${QHAL}/src/qm33/qotp.c

  ${QMATH}/src/qmath.c

  # qplatform — ties the driver to qhal.
  ${QPLATFORM}/qm33_qhal_common/src/qplatform.c
  ${QPLATFORM}/qm33_qhal_common/src/qplatform_deca_impl.c

  # DW3xxx driver, DW3000 family (the DWM3001C carries a DW3110).
  ${QORVO_DRIVER_DIR}/deca_compat.c
  ${QORVO_DRIVER_DIR}/deca_interface.c
  ${QORVO_DRIVER_DIR}/deca_rsl.c
  ${QORVO_DRIVER_DIR}/dw3000/dw3000_device.c

  # Board calibration. l1_config_init() needs a reset_to_default hook that loads this
  # module's factory calibration — antenna delays, crystal trim, TX power — out of the
  # DW3110's OTP. Reusing the vendor's DWM3001CDK implementation rather than
  # reimplementing it, since those values are what make ranging accurate.
  ${QORVO_SDK_DIR}/Src/Boards/Src/Common/helper_platform_l1_config.c
  ${QORVO_SDK_DIR}/Src/Boards/Src/DWM3001CDK/platform_l1_config.c
)

zephyr_library_include_directories(
  ${QPLATFORM}/qm33_qhal_common/include
  ${CMAKE_CURRENT_LIST_DIR}/src
  ${QORVO_DRIVER_DIR}/dw3000
  ${QORVO_SDK_DIR}/Src/Boards/Src/Common
  ${QORVO_SDK_DIR}/Src/Boards/Src/Common_OTP
  ${QORVO_SDK_DIR}/Src/Boards/Inc
)

zephyr_library_compile_definitions(
  CONFIG_DW3000=1
  # Selects the idle-timer configuration in qm33_qhal_common/qplatform.c, which
  # #errors without a platform. The nRF branch is right for nRF52833; Zephyr owns
  # the timer instance via qhal's qtimer, so the "RTC1 is FreeRTOS" comment there
  # does not apply to us.
  CONFIG_QPLATFORM_IMPL_NRF=1
  CONFIG_UWB_SPI_INSTANCE=3
  CONFIG_UWB_SPI_FRAME_SIZE=8
  CONFIG_SPI_UWB_IRQ_PRIORITY=3
  CONFIG_SPI_UWB_SLOW_RATE_FREQ=${CONFIG_QORVO_UWB_SPI_FREQ_SLOW_HZ}
  CONFIG_SPI_UWB_FAST_RATE_FREQ=${CONFIG_QORVO_UWB_SPI_FREQ_FAST_HZ}
  CONFIG_QLOG_LEVEL=${CONFIG_QORVO_UWB_QLOG_LEVEL}
  # Number of GPIO callback slots in our qgpio backend's static table; the stack only
  # registers one, on the transceiver IRQ.
  CONFIG_QHAL_MAX_GPIO_CALLBACKS=2
)

# Vendor code is not warning-clean under Zephyr's flags, and it is not ours to fix.
zephyr_library_compile_options(
  # See the header: fixes qos.c's missing qerr.h include, and maps qosal's Zephyr 3.1
  # log_msg2 internals onto their current names.
  -include ${CMAKE_CURRENT_LIST_DIR}/include-compat/ff_qorvo_compat.h
  -Wno-unused-function
  -Wno-unused-variable
  -Wno-unused-but-set-variable
  -Wno-sign-compare
  -Wno-implicit-fallthrough
  -Wno-missing-field-initializers
)

# The FiRa bundle's l1_config sections are placed in rodata purely to avoid orphan-section
# warnings; src/l1_config_storage_zephyr.c redirects the accessors to a RAM buffer.
zephyr_linker_sources(RODATA l1_config_storage.ld)

zephyr_link_libraries(${FIRA_LIB})

# picolibc's malloc family (nano-malloc.o, nano-free.o) ends up on the link line
# alongside Zephyr's lib/libc/common allocator, and both define malloc/free. Neither is
# reached from our code — qosal's allocations go to k_malloc via src/qmalloc_zephyr.c —
# and only Zephyr's own picolibc glue references them, so one of picolibc's malloc-family
# objects is being pulled for an internal symbol rather than for malloc itself.
#
# Both implementations are functional and Zephyr's is encountered first, so the choice is
# deterministic. This is a blunt instrument though: it silences duplicate symbols for the
# whole image, so if a genuine symbol clash appears later this is the first thing to
# suspect. The real fix is to find what pulls nano-malloc.o (start with
# `-Wl,--print-map` and `-Wl,-y,__malloc_free_list`) and drop that reference.
zephyr_link_libraries(-Wl,--allow-multiple-definition)
