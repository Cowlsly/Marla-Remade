package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.transport.SignalKeysApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parser vectors for `GET /v2/keys/...`. The wire details worth pinning down: the Kyber key is named
 * `pqPreKey` (not `kyberPreKey`), keys are base64 **without** padding, and a device with no Kyber key
 * must be skipped rather than downgraded to a non-post-quantum session.
 */
class SignalKeysApiTest {
    // Unpadded base64, as the server sends it.
    private val identityKeyB64 = "BXu6QIKVz5MA8gstzfOgRQGqyLqOwNKHL6INkv3IHWMF"
    private val signedPubB64 = "BUkY0I+9+oPgDCn4+Ac6Iu813yvqkDr/ga8DzLxFxuk6"
    private val sigB64 = "AQIDBAUGBwgJCgsMDQ4PEA"
    private val pqPubB64 = "EBESExQVFhcYGRobHB0eHw"

    private fun parse(body: String) = SignalKeysApi.parse(body, "aci", warn = {})

    private fun response(devices: String) =
        """{"identityKey":"$identityKeyB64","devices":[$devices]}"""

    private fun device(id: Int, includePq: Boolean = true, includeOneTime: Boolean = true): String {
        val pq = if (includePq) ""","pqPreKey":{"keyId":77,"publicKey":"$pqPubB64","signature":"$sigB64"}""" else ""
        val oneTime = if (includeOneTime) ""","preKey":{"keyId":55,"publicKey":"$signedPubB64"}""" else ""
        return """{"deviceId":$id,"registrationId":1234,""" +
            """"signedPreKey":{"keyId":66,"publicKey":"$signedPubB64","signature":"$sigB64"}""" +
            "$oneTime$pq}"
    }

    @Test
    fun parsesAFullDeviceBundle() {
        val parsed = parse(response(device(1)))
        assertEquals(1, parsed.size)
        val bundle = parsed.first().bundle
        assertEquals(1, parsed.first().deviceId)
        assertEquals(1234, bundle.registrationId)
        assertEquals(66, bundle.signedPreKeyId)
        assertEquals(55, bundle.preKeyId)
        assertEquals(77, bundle.kyberPreKeyId)
        assertTrue(bundle.identityKey.isNotEmpty())
        assertEquals(16, bundle.kyberPreKeyPublic?.size)
    }

    @Test
    fun skipsADeviceWithNoKyberPreKey() {
        // A non-PQ session would be a silent downgrade, so the device must be dropped entirely.
        assertTrue(parse(response(device(2, includePq = false))).isEmpty())
    }

    @Test
    fun treatsTheOneTimePreKeyAsOptional() {
        val parsed = parse(response(device(1, includeOneTime = false)))
        assertEquals(1, parsed.size)
        assertNull(parsed.first().bundle.preKeyId)
        assertNull(parsed.first().bundle.preKeyPublic)
    }

    @Test
    fun keepsGoodDevicesWhenOneIsUnusable() {
        val parsed = parse(
            response("${device(1)},${device(2, includePq = false)},${device(3)}"),
        )
        assertEquals(listOf(1, 3), parsed.map { it.deviceId })
    }

    @Test
    fun returnsNothingWithoutAnIdentityKey() {
        assertTrue(parse("""{"devices":[${device(1)}]}""").isEmpty())
    }

    @Test
    fun returnsNothingForUnparseableBody() {
        assertTrue(parse("not json").isEmpty())
    }

    @Test
    fun decodesUnpaddedBase64() {
        // sigB64 is 22 chars, needing "==" restored; a strict decoder would reject it.
        assertEquals(16, parse(response(device(1))).first().bundle.signedPreKeySignature.size)
    }
}
