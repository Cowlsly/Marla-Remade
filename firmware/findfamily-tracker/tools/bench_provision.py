"""
Bench-provision a FindFamily tracker from a PC and verify its beacon.

Stands in for the phone so the firmware's crypto and mode switch can be checked
independently of the app: an epoch id that doesn't match what this script computes is a
firmware bug, whereas a matching id the phone can't see is a scanning problem. Those two
look identical from inside the app.

Not part of the firmware build. Run with a BLE-capable PC:
    python tools/bench_provision.py
"""

import asyncio
import hashlib
import hmac
import struct
import sys
import time

from bleak import BleakClient, BleakScanner

UNPROVISIONED_SVC = "6b1d2f01-4b3a-4c7e-9a10-1f2e3d4c5b6a"
BEACON_SVC = "6b1d2f00-4b3a-4c7e-9a10-1f2e3d4c5b6a"
PROVISION_CHR = "6b1d2f02-4b3a-4c7e-9a10-1f2e3d4c5b6a"

EPOCH_SECONDS = 900
TRACKER_USER_ID = 0x0123456789ABCDEF
SECRET = bytes(range(32))


def epoch_id(secret: bytes, epoch: int) -> bytes:
    """Mirror of TrackerProtocol.epochId / ff_epoch_id."""
    return hmac.new(secret, b"fftrk1" + struct.pack(">Q", epoch), hashlib.sha256).digest()[:16]


async def main() -> int:
    print(f"scanning for an unprovisioned tracker ({UNPROVISIONED_SVC})...")
    device = await BleakScanner.find_device_by_filter(
        lambda d, ad: UNPROVISIONED_SVC.lower() in [u.lower() for u in ad.service_uuids],
        timeout=15.0,
    )
    if device is None:
        print("no tracker in pairing mode found (long-press the button and retry)")
        return 1
    print(f"found {device.address}")

    now = int(time.time())
    blob = struct.pack(">Q", TRACKER_USER_ID) + SECRET + struct.pack(">Q", now)
    assert len(blob) == 48, len(blob)

    async with BleakClient(device) as client:
        print(f"writing {len(blob)}-byte provisioning blob (unix={now})")
        await client.write_gatt_char(PROVISION_CHR, blob, response=True)
    print("write acknowledged")

    expected = epoch_id(SECRET, now // EPOCH_SECONDS)
    print(f"expected epochId = {expected.hex()}")

    print(f"scanning for the beacon ({BEACON_SVC})...")
    seen: dict = {}

    def on_adv(dev, ad):
        data = ad.service_data.get(BEACON_SVC.lower())
        if data:
            seen[dev.address] = data

    scanner = BleakScanner(detection_callback=on_adv)
    await scanner.start()
    await asyncio.sleep(15.0)
    await scanner.stop()

    if not seen:
        print("no beacon heard. The firmware log is the tiebreaker: if it prints an")
        print("epoch id, the firmware is fine and this PC's adapter is not reporting")
        print("extended advertisements (many Windows adapters do not).")
        return 1

    ok = False
    for addr, data in seen.items():
        got, battery = data[:16], data[16] if len(data) > 16 else -1
        match = "MATCH" if got == expected else "MISMATCH"
        print(f"{addr}: epochId={got.hex()} battery={battery}% -> {match}")
        ok = ok or got == expected
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
