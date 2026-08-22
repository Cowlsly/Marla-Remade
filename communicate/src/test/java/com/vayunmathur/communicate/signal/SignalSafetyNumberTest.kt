package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.SignalSafetyNumber
import org.signal.libsignal.protocol.IdentityKeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Safety numbers must be symmetric — both sides show the same digits — and must change when either
 * identity key changes. A number that fails either property is worse than none, because the user would
 * be comparing something meaningless.
 */
class SignalSafetyNumberTest {
    private val aliceAci = "3f0e5a2c-1111-2222-3333-444455556666"
    private val bobAci = "7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff"

    private val aliceKey = IdentityKeyPair.generate().publicKey.serialize()
    private val bobKey = IdentityKeyPair.generate().publicKey.serialize()

    @Test
    fun bothSidesComputeTheSameNumber() {
        val fromAlice = SignalSafetyNumber.compute(aliceAci, aliceKey, bobAci, bobKey)
        val fromBob = SignalSafetyNumber.compute(bobAci, bobKey, aliceAci, aliceKey)
        assertNotNull(fromAlice)
        assertEquals(fromAlice, fromBob)
    }

    @Test
    fun numberIsSixtyDigits() {
        val number = SignalSafetyNumber.compute(aliceAci, aliceKey, bobAci, bobKey)
        assertNotNull(number)
        assertEquals(60, number.length)
        assertTrue(number.all { it.isDigit() })
    }

    @Test
    fun numberChangesWhenTheRemoteKeyChanges() {
        val before = SignalSafetyNumber.compute(aliceAci, aliceKey, bobAci, bobKey)
        val newBobKey = IdentityKeyPair.generate().publicKey.serialize()
        val after = SignalSafetyNumber.compute(aliceAci, aliceKey, bobAci, newBobKey)
        assertNotNull(before)
        assertNotNull(after)
        assertTrue(before != after)
    }

    @Test
    fun numberChangesWhenTheLocalKeyChanges() {
        val before = SignalSafetyNumber.compute(aliceAci, aliceKey, bobAci, bobKey)
        val newAliceKey = IdentityKeyPair.generate().publicKey.serialize()
        val after = SignalSafetyNumber.compute(aliceAci, newAliceKey, bobAci, bobKey)
        assertTrue(before != after)
    }

    @Test
    fun malformedInputsGiveNull() {
        assertNull(SignalSafetyNumber.compute("not-a-uuid", aliceKey, bobAci, bobKey, warn = {}))
        assertNull(SignalSafetyNumber.compute(aliceAci, aliceKey, "not-a-uuid", bobKey, warn = {}))
        assertNull(SignalSafetyNumber.compute(aliceAci, ByteArray(0), bobAci, bobKey, warn = {}))
        assertNull(SignalSafetyNumber.compute(aliceAci, aliceKey, bobAci, ByteArray(5), warn = {}))
    }

    @Test
    fun formatGroupsIntoBlocksOfFive() {
        val formatted = SignalSafetyNumber.format("0".repeat(60))
        assertEquals(12, formatted.split(" ").size)
        assertTrue(formatted.split(" ").all { it.length == 5 })
    }
}
