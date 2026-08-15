package com.vayunmathur.backup.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * BIP-0039 mnemonic recovery codes: entropy ⇔ 12-word mnemonic and mnemonic →
 * 512-bit seed (PBKDF2-HMAC-SHA512). This mirrors the recovery-code scheme used by
 * Seedvault so a backup is recoverable from the twelve words alone.
 *
 * Ported concept (BIP-0039 + Seedvault recovery code) — see backup/LICENSE-Seedvault.
 * The 2048-word English wordlist is loaded from the bundled resource
 * `/bip39-english.txt` (canonical Bitcoin BIPs list).
 */
object Bip39 {
    /** BIP-0039 salt prefix for the PBKDF2 seed derivation. */
    private const val SEED_SALT_PREFIX = "mnemonic"
    private const val PBKDF2_ITERATIONS = 2048
    private const val SEED_BITS = 512

    /** 12-word code: 128 bits of entropy + a 4-bit checksum = 132 bits = 12 × 11. */
    const val ENTROPY_BYTES = 16
    const val WORD_COUNT = 12

    val wordList: List<String> by lazy { loadWordList() }
    private val wordIndex: Map<String, Int> by lazy {
        wordList.withIndex().associate { (i, w) -> w to i }
    }

    private fun loadWordList(): List<String> {
        val stream = Bip39::class.java.getResourceAsStream("/bip39-english.txt")
            ?: error("bip39-english.txt not found on classpath")
        val words = stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        require(words.size == 2048) { "BIP-39 wordlist must have 2048 words, got ${words.size}" }
        return words
    }

    /** A fresh random 12-word recovery code. */
    fun generate(): List<String> =
        entropyToMnemonic(ByteArray(ENTROPY_BYTES).also { SecureRandom().nextBytes(it) })

    /** Encodes [entropy] (16/20/24/28/32 bytes) as its BIP-0039 mnemonic. */
    fun entropyToMnemonic(entropy: ByteArray): List<String> {
        require(entropy.size in intArrayOf(16, 20, 24, 28, 32)) {
            "entropy must be 16/20/24/28/32 bytes, got ${entropy.size}"
        }
        val checksumBits = entropy.size * 8 / 32
        val hash = sha256(entropy)
        val bits = BooleanArray(entropy.size * 8 + checksumBits)
        for (i in entropy.indices) for (b in 0 until 8) {
            bits[i * 8 + b] = (entropy[i].toInt() shr (7 - b)) and 1 == 1
        }
        // checksumBits ≤ 8 for all valid sizes, so it fits in hash[0].
        for (b in 0 until checksumBits) {
            bits[entropy.size * 8 + b] = (hash[0].toInt() shr (7 - b)) and 1 == 1
        }
        return (0 until bits.size / 11).map { group ->
            var idx = 0
            for (j in 0 until 11) idx = (idx shl 1) or (if (bits[group * 11 + j]) 1 else 0)
            wordList[idx]
        }
    }

    /** Decodes a mnemonic back to its entropy, verifying the checksum. */
    fun mnemonicToEntropy(mnemonic: List<String>): ByteArray {
        require(mnemonic.isNotEmpty() && mnemonic.size % 3 == 0) {
            "mnemonic length must be a positive multiple of 3"
        }
        val totalBits = mnemonic.size * 11
        val checksumBits = totalBits / 33
        val entropyBits = totalBits - checksumBits
        val bits = BooleanArray(totalBits)
        for ((i, word) in mnemonic.withIndex()) {
            val idx = wordIndex[word] ?: throw IllegalArgumentException("word not in list: $word")
            for (j in 0 until 11) bits[i * 11 + j] = (idx shr (10 - j)) and 1 == 1
        }
        val entropy = ByteArray(entropyBits / 8)
        for (i in entropy.indices) {
            var value = 0
            for (j in 0 until 8) value = (value shl 1) or (if (bits[i * 8 + j]) 1 else 0)
            entropy[i] = value.toByte()
        }
        val hash = sha256(entropy)
        for (b in 0 until checksumBits) {
            val expected = (hash[0].toInt() shr (7 - b)) and 1 == 1
            if (bits[entropyBits + b] != expected) {
                throw IllegalArgumentException("invalid recovery-code checksum")
            }
        }
        return entropy
    }

    /** True if [mnemonic] is a well-formed BIP-0039 code with a valid checksum. */
    fun isValid(mnemonic: List<String>): Boolean =
        runCatching { mnemonicToEntropy(mnemonic) }.isSuccess

    /**
     * Derives the 512-bit BIP-0039 seed from a mnemonic (NFKD-normalized) and an
     * optional passphrase, via PBKDF2-HMAC-SHA512 with 2048 iterations.
     */
    fun mnemonicToSeed(mnemonic: List<String>, passphrase: String = ""): ByteArray {
        val mnemonicStr = Normalizer.normalize(mnemonic.joinToString(" "), Normalizer.Form.NFKD)
        val salt = Normalizer.normalize(SEED_SALT_PREFIX + passphrase, Normalizer.Form.NFKD)
        val spec = PBEKeySpec(
            mnemonicStr.toCharArray(),
            salt.toByteArray(Charsets.UTF_8),
            PBKDF2_ITERATIONS,
            SEED_BITS,
        )
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
