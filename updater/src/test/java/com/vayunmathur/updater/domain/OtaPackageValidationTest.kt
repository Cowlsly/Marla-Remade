package com.vayunmathur.updater.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These cover the checks that run AFTER `RecoverySystem.verifyPackage` has passed — i.e. on a
 * package we really did sign. Each one is a way a correctly signed package can still be the
 * wrong thing to write to a system partition, so each gets a test.
 */
class OtaPackageValidationTest {

    private val expected = OtaPackageValidation.Expected(
        buildDateUtcSeconds = 1_788_644_317L,
        targetBuild = "2026090700",
        device = "shiba",
        currentBuild = "2026090500",
        currentFingerprint = "Modern/shiba/shiba:16/2026090500/user/release-keys",
    )

    private fun full(
        timestamp: Long? = 1_788_644_317L,
        build: String? = "2026090700",
        device: String? = "shiba",
        serialNo: String? = null,
        otaType: String? = "AB",
        preBuild: String? = null,
        preFingerprint: String? = null,
        streaming: List<String> = listOf("payload.bin:1234:5678", "payload_properties.txt:9:10"),
    ) = OtaPackageMetadata(
        postTimestamp = timestamp,
        postBuildIncremental = build,
        preDevice = device,
        serialNo = serialNo,
        otaType = otaType,
        preBuildIncremental = preBuild,
        preBuildFingerprint = preFingerprint,
        streamingPropertyFiles = streaming,
    )

    @Test
    fun `a matching full package is valid`() {
        val r = OtaPackageValidation.validate(full(), expected)
        assertEquals(OtaPackageValidation.Result.Valid(1234L, incremental = false), r)
    }

    @Test
    fun `a matching incremental is valid and reported as incremental`() {
        val r = OtaPackageValidation.validate(
            full(preBuild = "2026090500", preFingerprint = expected.currentFingerprint),
            expected,
        )
        assertEquals(OtaPackageValidation.Result.Valid(1234L, incremental = true), r)
    }

    /** The server said one build and the artifact is another: broken publish or swapped file. */
    @Test
    fun `rejects a timestamp that disagrees with the server`() {
        val r = OtaPackageValidation.validate(full(timestamp = 1_788_000_000L), expected)
        assertIs<OtaPackageValidation.Result.Rejected>(r)
        assertTrue(r.reason.contains("timestamp"))
    }

    @Test
    fun `rejects a build that disagrees with the server`() {
        val r = OtaPackageValidation.validate(full(build = "2026090800"), expected)
        assertIs<OtaPackageValidation.Result.Rejected>(r)
    }

    /** A signed package for another Pixel would still flash happily without this check. */
    @Test
    fun `rejects a package for another device`() {
        val r = OtaPackageValidation.validate(full(device = "husky"), expected)
        assertIs<OtaPackageValidation.Result.Rejected>(r)
        assertTrue(r.reason.contains("husky"))
    }

    @Test
    fun `rejects a serialno constraint`() {
        val r = OtaPackageValidation.validate(full(serialNo = "ABC123"), expected)
        assertIs<OtaPackageValidation.Result.Rejected>(r)
        assertTrue(r.reason.contains("serialno"))
    }

    @Test
    fun `rejects a non-AB package`() {
        val r = OtaPackageValidation.validate(full(otaType = "BLOCK"), expected)
        assertIs<OtaPackageValidation.Result.Rejected>(r)
    }

    /**
     * The dangerous one. An incremental is a patch against one exact source; applied to a
     * different starting point it produces a corrupt system that may still boot far enough to
     * matter.
     */
    @Test
    fun `rejects an incremental that patches from a different build`() {
        val r = OtaPackageValidation.validate(full(preBuild = "2026080100"), expected)
        assertIs<OtaPackageValidation.Result.Rejected>(r)
        assertTrue(r.reason.contains("patches from"))
    }

    @Test
    fun `rejects an incremental whose source fingerprint differs`() {
        val r = OtaPackageValidation.validate(
            full(preBuild = "2026090500", preFingerprint = "someone/else:16/x/release-keys"),
            expected,
        )
        assertIs<OtaPackageValidation.Result.Rejected>(r)
        assertTrue(r.reason.contains("fingerprint"))
    }

    @Test
    fun `rejects a package with no payload offset`() {
        val r = OtaPackageValidation.validate(
            full(streaming = listOf("payload_properties.txt:9:10")),
            expected,
        )
        assertIs<OtaPackageValidation.Result.Rejected>(r)
        assertTrue(r.reason.contains("payload offset"))
    }
}

class OtaPackageMetadataTest {

    private val real = """
        ota-required-cache=0
        ota-streaming-property-files=payload.bin:1234:99999,payload_properties.txt:5:60
        ota-type=AB
        post-build=Modern/shiba/shiba:16/2026090700/user/release-keys
        post-build-incremental=2026090700
        post-sdk-level=36
        post-security-patch-level=2026-09-05
        post-timestamp=1788644317
        pre-device=shiba
    """.trimIndent()

    @Test
    fun `parses a real full-package metadata block`() {
        val m = OtaPackageMetadata.parse(real.lineSequence())
        assertEquals(1_788_644_317L, m.postTimestamp)
        assertEquals("2026090700", m.postBuildIncremental)
        assertEquals("shiba", m.preDevice)
        assertEquals("AB", m.otaType)
        assertNull(m.serialNo)
        assertNull(m.preBuildIncremental)
        assertEquals(1234L, m.payloadOffset())
    }

    /**
     * A fingerprint contains '=' in some builds, and splitting without a limit would truncate
     * it — silently turning a mismatched source into a matching one.
     */
    @Test
    fun `does not truncate a value containing an equals sign`() {
        val m = OtaPackageMetadata.parse(sequenceOf("pre-build=a/b:16/x=y/release-keys"))
        assertEquals("a/b:16/x=y/release-keys", m.preBuildFingerprint)
    }

    @Test
    fun `ignores lines that are not key=value`() {
        val m = OtaPackageMetadata.parse(sequenceOf("", "garbage", "ota-type=AB"))
        assertEquals("AB", m.otaType)
    }

    @Test
    fun `payload offset is null when payload_bin is absent`() {
        val m = OtaPackageMetadata.parse(
            sequenceOf("ota-streaming-property-files=payload_properties.txt:5:60"),
        )
        assertNull(m.payloadOffset())
    }
}
