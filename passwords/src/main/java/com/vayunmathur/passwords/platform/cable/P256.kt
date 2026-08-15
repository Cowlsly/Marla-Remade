package com.vayunmathur.passwords.cable

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/**
 * P-256 (secp256r1 / prime256v1) helpers used by the caBLE Noise handshake and EID handling.
 * Uses only the platform JCA (Conscrypt) — no Bouncy Castle.
 *
 * Point encodings follow X9.62: compressed = `02|03 || X` (33 bytes), uncompressed =
 * `04 || X || Y` (65 bytes). ECDH returns the 32-byte big-endian X coordinate of the shared
 * point (Noise `DH` output for P-256).
 */
object P256 {
    const val UNCOMPRESSED_SIZE = 65
    const val COMPRESSED_SIZE = 33
    const val COORD_SIZE = 32
    const val DH_OUTPUT_SIZE = 32

    // secp256r1 domain parameters, obtained from the platform (no BC).
    private val ecSpec: ECParameterSpec = run {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        params.getParameterSpec(ECParameterSpec::class.java)
    }
    private val prime: BigInteger = (ecSpec.curve.field as ECFieldFp).p

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    /** Serializes a public key as the 65-byte uncompressed X9.62 form. */
    fun toUncompressed(publicKey: PublicKey): ByteArray {
        val w = (publicKey as ECPublicKey).w
        return byteArrayOf(0x04) + fixed(w.affineX) + fixed(w.affineY)
    }

    /** Serializes a public key as the 33-byte compressed X9.62 form. */
    fun toCompressed(publicKey: PublicKey): ByteArray {
        val w = (publicKey as ECPublicKey).w
        val prefix = if (w.affineY.testBit(0)) 0x03 else 0x02
        return byteArrayOf(prefix.toByte()) + fixed(w.affineX)
    }

    /** Parses a 33-byte compressed or 65-byte uncompressed point into a [PublicKey]. */
    fun decodePoint(encoded: ByteArray): PublicKey {
        val ecPoint = when (encoded[0].toInt() and 0xFF) {
            0x04 -> {
                require(encoded.size == UNCOMPRESSED_SIZE) { "bad uncompressed point" }
                ECPoint(
                    BigInteger(1, encoded.copyOfRange(1, 1 + COORD_SIZE)),
                    BigInteger(1, encoded.copyOfRange(1 + COORD_SIZE, UNCOMPRESSED_SIZE)),
                )
            }
            0x02, 0x03 -> {
                require(encoded.size == COMPRESSED_SIZE) { "bad compressed point" }
                val x = BigInteger(1, encoded.copyOfRange(1, COMPRESSED_SIZE))
                ECPoint(x, decompressY(x, wantOdd = (encoded[0].toInt() and 1) == 1))
            }
            else -> throw IllegalArgumentException("unsupported point encoding")
        }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ecPoint, ecSpec))
    }

    /**
     * Recover Y from a compressed point: y² = x³ + a·x + b (mod p). secp256r1's p ≡ 3 (mod 4),
     * so y = (rhs)^((p+1)/4) mod p; flip parity to match the requested sign bit.
     */
    private fun decompressY(x: BigInteger, wantOdd: Boolean): BigInteger {
        val a = ecSpec.curve.a
        val b = ecSpec.curve.b
        val rhs = (x.modPow(BigInteger.valueOf(3), prime) + a.multiply(x) + b).mod(prime)
        var y = rhs.modPow((prime + BigInteger.ONE).shiftRight(2), prime)
        if (y.testBit(0) != wantOdd) y = prime.subtract(y)
        return y
    }

    /** Raw ECDH: returns the 32-byte big-endian X coordinate of `priv * pub`. */
    fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray {
        val agreement = javax.crypto.KeyAgreement.getInstance("ECDH")
        agreement.init(priv)
        agreement.doPhase(pub, true)
        return leftPad(agreement.generateSecret(), DH_OUTPUT_SIZE)
    }

    private fun fixed(value: BigInteger): ByteArray = leftPad(value.toByteArray(), COORD_SIZE)

    private fun leftPad(bytes: ByteArray, length: Int): ByteArray = when {
        bytes.size == length -> bytes
        bytes.size > length -> bytes.copyOfRange(bytes.size - length, bytes.size)
        else -> ByteArray(length - bytes.size) + bytes
    }
}
