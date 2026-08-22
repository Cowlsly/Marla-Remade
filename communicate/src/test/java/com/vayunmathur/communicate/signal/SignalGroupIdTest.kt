package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.SignalGroups
import com.vayunmathur.communicate.data.signal.SignalProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The group identifier must be *derived* from the master key, never a slice of it. The master key
 * encrypts the group's attributes and membership, so leaking it through a conversation id — which ends
 * up in database keys and log lines — would be handing out the group's contents.
 */
class SignalGroupIdTest {
    private val masterKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun identifierIsThirtyTwoBytes() {
        assertEquals(32, SignalGroups.groupIdentifierBytes(masterKey).size)
    }

    @Test
    fun identifierIsNotDerivedFromTheMasterKeyBytes() {
        val id = SignalGroups.groupIdentifierBytes(masterKey)
        assertFalse(id.contentEquals(masterKey))
        // The old implementation was the hex of the first 8 bytes; make sure that shape is gone.
        val leakedPrefix = SignalGroups.run { masterKey.toHex() }.take(16)
        assertFalse(SignalGroups.groupIdFromMasterKey(masterKey).startsWith(leakedPrefix))
    }

    @Test
    fun identifierIsDeterministic() {
        assertEquals(
            SignalGroups.groupIdFromMasterKey(masterKey),
            SignalGroups.groupIdFromMasterKey(masterKey.copyOf()),
        )
    }

    @Test
    fun differentMasterKeysGiveDifferentIdentifiers() {
        val other = ByteArray(32) { (it + 2).toByte() }
        assertFalse(
            SignalGroups.groupIdFromMasterKey(masterKey) == SignalGroups.groupIdFromMasterKey(other),
        )
    }

    @Test
    fun conversationIdRoundTripsToTheIdentifierBytes() {
        val conversationId = SignalProtocol.toConversationId("", masterKey)
        assertTrue(SignalProtocol.isGroupConversation(conversationId))
        val recovered = SignalProtocol.groupIdentifierOf(conversationId)
        assertTrue(SignalGroups.groupIdentifierBytes(masterKey).contentEquals(recovered))
    }

    @Test
    fun oneToOneConversationIdsAreTheAci() {
        val aci = "3f0e5a2c-1111-2222-3333-444455556666"
        assertEquals(aci, SignalProtocol.toConversationId(aci, null as ByteArray?))
        assertFalse(SignalProtocol.isGroupConversation(aci))
        assertNull(SignalProtocol.groupIdentifierOf(aci))
    }

    @Test
    fun hexRoundTrip() {
        val bytes = ByteArray(32) { (it * 7).toByte() }
        val hex = SignalGroups.run { bytes.toHex() }
        assertEquals(64, hex.length)
        assertTrue(bytes.contentEquals(SignalGroups.hexToBytes(hex)))
    }

    @Test
    fun malformedHexIsRejected() {
        assertNull(SignalGroups.hexToBytes(""))
        assertNull(SignalGroups.hexToBytes("abc"))
        assertNull(SignalGroups.hexToBytes("zz"))
    }
}
