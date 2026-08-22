package com.vayunmathur.share.platform.discovery.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Apple manufacturer-data TLV decoder.
 *
 * Pure byte handling, so it runs on the JVM. Worth covering because the decoder is the only
 * logic in the AirDrop probe that can be wrong *silently*: a mis-parsed advert produces a
 * plausible-looking result rather than an error, and the whole point of the probe is that its
 * negative result be trustworthy.
 */
class AppleBeaconScannerTest {

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    /** An 18-byte AirDrop value: 8 zero bytes, version, four 2-byte hashes, terminator. */
    private fun airDropValue(
        version: Int = 0x01,
        appleId: Int = 0xAABB,
        phone: Int = 0xCCDD,
        email: Int = 0x0000,
        email2: Int = 0x0000,
    ): ByteArray = bytes(
        0, 0, 0, 0, 0, 0, 0, 0,
        version,
        appleId shr 8, appleId and 0xFF,
        phone shr 8, phone and 0xFF,
        email shr 8, email and 0xFF,
        email2 shr 8, email2 and 0xFF,
        0,
    )

    @Test
    fun `decodes a single TLV`() {
        val tlvs = parseAppleTlvs(bytes(0x10, 0x02, 0xAA, 0xBB))
        assertEquals(1, tlvs.size)
        assertEquals(0x10, tlvs[0].type)
        assertEquals("NearbyInfo", tlvs[0].name)
        assertEquals("aabb", tlvs[0].value.toHex())
    }

    @Test
    fun `decodes several TLVs in one advert`() {
        // AirDrop is deliberately not first: the scanner must not depend on its position, which
        // is why the scan filter matches the company ID rather than a data prefix.
        val data = bytes(0x10, 0x02, 0x01, 0x02) +
            bytes(APPLE_TLV_AIRDROP, 0x12) + airDropValue() +
            bytes(0x0C, 0x01, 0x09)
        val tlvs = parseAppleTlvs(data)
        assertEquals(listOf(0x10, APPLE_TLV_AIRDROP, 0x0C), tlvs.map { it.type })
        assertEquals(18, tlvs[1].value.size)
    }

    @Test
    fun `stops at a length running past the buffer instead of throwing`() {
        // BLE adverts get truncated by the controller, so a partial trailing record is normal
        // and must not lose the records that already parsed.
        val tlvs = parseAppleTlvs(bytes(0x10, 0x02, 0xAA, 0xBB, 0x05, 0x12, 0x00))
        assertEquals(1, tlvs.size)
        assertEquals(0x10, tlvs[0].type)
    }

    @Test
    fun `ignores a trailing type byte with no length`() {
        val tlvs = parseAppleTlvs(bytes(0x10, 0x02, 0xAA, 0xBB, 0x05))
        assertEquals(1, tlvs.size)
    }

    @Test
    fun `empty data yields no TLVs`() {
        assertTrue(parseAppleTlvs(ByteArray(0)).isEmpty())
    }

    @Test
    fun `zero-length TLV is kept and does not stall the parse`() {
        val tlvs = parseAppleTlvs(bytes(0x0B, 0x00, 0x10, 0x01, 0x07))
        assertEquals(listOf(0x0B, 0x10), tlvs.map { it.type })
        assertEquals(0, tlvs[0].value.size)
    }

    @Test
    fun `parses the AirDrop record fields`() {
        val beacon = AirDropBeacon.parse(airDropValue(version = 0x01))
        requireNotNull(beacon)
        assertEquals(0x01, beacon.version)
        assertEquals("aabb", beacon.appleIdHash.toHex())
        assertEquals("ccdd", beacon.phoneHash.toHex())
        assertEquals("0000", beacon.emailHash.toHex())
    }

    @Test
    fun `populatedHashes omits the zero-filled slots`() {
        val beacon = AirDropBeacon.parse(airDropValue())
        requireNotNull(beacon)
        // Apple zero-fills unused contact slots; reporting them as hashes would invent senders.
        assertEquals(listOf("appleId=aabb", "phone=ccdd"), beacon.populatedHashes())
    }

    @Test
    fun `rejects an AirDrop value that is too short`() {
        assertNull(AirDropBeacon.parse(bytes(0, 0, 0, 0, 0, 0, 0, 0, 1)))
    }

    @Test
    fun `a sighting without an AirDrop TLV is not airdropping`() {
        val sighting = sighting(parseAppleTlvs(bytes(0x10, 0x02, 0x01, 0x02)))
        assertNull(sighting.airDrop)
        assertTrue(!sighting.isAirDropping)
    }

    @Test
    fun `a sighting carrying the AirDrop TLV is airdropping`() {
        val data = bytes(APPLE_TLV_AIRDROP, 0x12) + airDropValue()
        val sighting = sighting(parseAppleTlvs(data))
        assertTrue(sighting.isAirDropping)
        assertEquals(0x01, sighting.airDrop?.version)
    }

    @Test
    fun `sightings compare by TLV content so a rescan is not a new observation`() {
        val data = bytes(0x10, 0x02, 0xAA, 0xBB)
        // Same device, same payload, different RSSI and timestamp — the change detection that
        // gates logging keys on content, so this must compare equal.
        val first = sighting(parseAppleTlvs(data), rssi = -40, seenAtMs = 1)
        val second = sighting(parseAppleTlvs(data), rssi = -70, seenAtMs = 9_999)
        assertEquals(first, second)
    }

    private fun sighting(
        tlvs: List<AppleTlv>,
        rssi: Int = -50,
        seenAtMs: Long = 0,
    ) = AppleBeaconSighting(
        address = "AA:BB:CC:DD:EE:FF",
        rssi = rssi,
        localName = null,
        tlvs = tlvs,
        raw = ByteArray(0),
        seenAtMs = seenAtMs,
    )
}
