package com.vayunmathur.backup.domain.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BIP-0039 standard test vectors (Trezor reference vectors, passphrase "TREZOR")
 * plus round-trip and checksum-validation checks for [Bip39].
 */
class Bip39Test {
    private data class Vector(val entropyHex: String, val mnemonic: String, val seedHex: String)

    private val vectors = listOf(
        Vector(
            "00000000000000000000000000000000",
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
        ),
        Vector(
            "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
            "2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6fa457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607",
        ),
        Vector(
            "80808080808080808080808080808080",
            "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
            "d71de856f81a8acc65e6fc851a38d4d7ec216fd0796d0a6827a3ad6ed5511a30fa280f12eb2e47ed2ac03b5c462a0358d18d69fe4f985ec81778c1b370b652a8",
        ),
        Vector(
            "ffffffffffffffffffffffffffffffff",
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            "ac27495480225222079d7be181583751e86f571027b0497b5b5d11218e0a8a13332572917f0f8e5a589620c6f15b11c61dee327651a14c34e18231052e48c069",
        ),
    )

    @Test
    fun wordListHas2048Words() {
        assertEquals(2048, Bip39.wordList.size)
        assertEquals("abandon", Bip39.wordList.first())
        assertEquals("zoo", Bip39.wordList.last())
    }

    @Test
    fun entropyToMnemonicMatchesVectors() {
        for (v in vectors) {
            assertEquals(v.mnemonic, Bip39.entropyToMnemonic(hex(v.entropyHex)).joinToString(" "))
        }
    }

    @Test
    fun mnemonicToEntropyMatchesVectors() {
        for (v in vectors) {
            assertContentEquals(hex(v.entropyHex), Bip39.mnemonicToEntropy(v.mnemonic.split(" ")))
        }
    }

    @Test
    fun mnemonicToSeedMatchesVectors() {
        for (v in vectors) {
            assertEquals(
                v.seedHex,
                Bip39.mnemonicToSeed(v.mnemonic.split(" "), "TREZOR").toHex(),
            )
        }
    }

    @Test
    fun generateProducesValidTwelveWordCode() {
        val code = Bip39.generate()
        assertEquals(Bip39.WORD_COUNT, code.size)
        assertTrue(Bip39.isValid(code))
        // Round-trips through entropy.
        assertContentEquals(
            Bip39.mnemonicToEntropy(code),
            Bip39.mnemonicToEntropy(Bip39.entropyToMnemonic(Bip39.mnemonicToEntropy(code))),
        )
    }

    @Test
    fun invalidChecksumIsRejected() {
        // Valid words, but not a valid checksum combination.
        val bad = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"
        assertFalse(Bip39.isValid(bad.split(" ")))
        assertFailsWith<IllegalArgumentException> { Bip39.mnemonicToEntropy(bad.split(" ")) }
    }

    @Test
    fun unknownWordIsRejected() {
        val bad = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon notaword"
        assertFailsWith<IllegalArgumentException> { Bip39.mnemonicToEntropy(bad.split(" ")) }
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
