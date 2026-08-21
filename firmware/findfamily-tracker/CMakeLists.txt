#
# FindFamily UWB tracker firmware (Qorvo DWM3001CDK: nRF52833 + DW3110).
#
# Build:
#   west build -b nrf52833dk/nrf52833 firmware/findfamily-tracker
# See README.md for the DWM3001CDK board notes.
#
cmake_minimum_required(VERSION 3.20.0)

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(findfamily_tracker)

target_sources(app PRIVATE
  src/main.c
  src/tracker_store.c
  src/tracker_epoch.c
  src/tracker_ble.c
)

# UWB is written but not built by default: the FiRa responder session needs the
# licensed Qorvo DW3xxx driver, which isn't vendored here. See README.md.
target_sources_ifdef(CONFIG_FF_TRACKER_UWB app PRIVATE
  src/tracker_sts.c
  src/tracker_uwb.c
)
