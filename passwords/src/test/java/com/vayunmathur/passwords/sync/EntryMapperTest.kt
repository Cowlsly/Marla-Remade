package com.vayunmathur.passwords.sync

import com.vayunmathur.passwords.data.PASSKEY_LINK_DETACHED
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The merge compares hashes derived from this mapping, so it has to round-trip exactly. */
class EntryMapperTest {

    @Test fun passwordRoundTripsApartFromRowId() {
        val pw = Password(
            id = 7,
            name = "GitHub",
            username = "octocat",
            password = "s3cr3t!",
            totpSecret = "JBSWY3DPEHPK3PXP",
            websites = listOf("https://github.com", "https://gist.github.com"),
            syncId = "abc123",
            updatedAt = 1_700_000_000_000,
        )

        val back = EntryMapper.toPassword(EntryMapper.toFields(pw))

        assertEquals(0L, back.id)
        assertEquals(pw.copy(id = 0), back)
    }

    @Test fun passwordWithoutWebsitesOrTotpRoundTrips() {
        val pw = Password(name = "Bare", username = "u", password = "p", syncId = "s", updatedAt = 5)
        assertEquals(pw, EntryMapper.toPassword(EntryMapper.toFields(pw)))
    }

    @Test fun passkeyRoundTripsApartFromRowId() {
        val pk = Passkey(
            id = 3,
            rpId = "acme.example",
            rpName = "Acme",
            credentialId = "cred-123",
            userId = "user-abc",
            userName = "alice",
            userDisplayName = "Alice Anderson",
            privateKeyBytes = byteArrayOf(1, 2, 3, 4, -5),
            creationTime = 111,
            lastUsedTime = 222,
            signCount = 9,
            syncId = "pk-sync",
            updatedAt = 333,
        )

        val back = EntryMapper.toPasskey(EntryMapper.toFields(pk))

        assertEquals(0L, back.id)
        assertEquals(pk.rpName, back.rpName)
        assertEquals(pk.userName, back.userName)
        assertEquals(pk.userDisplayName, back.userDisplayName)
        assertEquals(pk.creationTime, back.creationTime)
        assertEquals(pk.lastUsedTime, back.lastUsedTime)
        assertEquals(pk.signCount, back.signCount)
        assertEquals(pk.syncId, back.syncId)
        assertEquals(pk.updatedAt, back.updatedAt)
        assertContentEquals(pk.privateKeyBytes, back.privateKeyBytes)
        assertNull(back.linkedPasswordSyncId)
    }

    @Test fun everyLinkStateRoundTrips() {
        for (link in listOf(null, PASSKEY_LINK_DETACHED, "0123456789abcdef0123456789abcdef")) {
            val pk = Passkey(rpId = "acme.example", credentialId = "c", linkedPasswordSyncId = link)
            val fields = EntryMapper.toFields(pk)
            assertEquals(link, EntryMapper.toPasskey(fields).linkedPasswordSyncId)
            assertEquals(fields, EntryMapper.toFields(EntryMapper.toPasskey(fields).copy(
                syncId = pk.syncId,
                updatedAt = pk.updatedAt,
            )))
        }
    }

    /**
     * contentHash covers every key but _SyncId/_Modified, so emitting the link field for unlinked
     * passkeys would rewrite the hash of every existing one and report them all as changed on the
     * next sync.
     */
    @Test fun unlinkedPasskeysHashAsThoughTheFieldDidNotExist() {
        val pk = Passkey(rpId = "acme.example", credentialId = "c", userId = "u")
        val fields = EntryMapper.toFields(pk)

        assertTrue("_LinkedPassword" !in fields)
        assertEquals(
            EntryMapper.contentHash(fields),
            EntryMapper.contentHash(fields.filterKeys { it != "_LinkedPassword" }),
        )
        assertNotEquals(
            EntryMapper.contentHash(fields),
            EntryMapper.contentHash(EntryMapper.toFields(pk.copy(linkedPasswordSyncId = "abc"))),
        )
        assertTrue("_LinkedPassword" in EntryMapper.OWNED_KEYS)
    }

    @Test fun passkeyEntriesAreDetected() {
        assertTrue(EntryMapper.isPasskeyEntry(EntryMapper.toFields(Passkey())))
        assertTrue(!EntryMapper.isPasskeyEntry(EntryMapper.toFields(Password())))
    }

    @Test fun contentHashIgnoresIdentityAndTimestamp() {
        val pw = Password(name = "A", username = "u", password = "p", syncId = "one", updatedAt = 1)
        val moved = pw.copy(syncId = "two", updatedAt = 999, id = 42)

        assertEquals(
            EntryMapper.contentHash(EntryMapper.toFields(pw)),
            EntryMapper.contentHash(EntryMapper.toFields(moved)),
        )
        assertNotEquals(
            EntryMapper.contentHash(EntryMapper.toFields(pw)),
            EntryMapper.contentHash(EntryMapper.toFields(pw.copy(password = "other"))),
        )
    }

    @Test fun otpauthUriAndTotpSeedBothYieldTheSecret() {
        assertEquals(
            "JBSWY3DPEHPK3PXP",
            EntryMapper.toPassword(mapOf("otp" to "otpauth://totp/Foo?secret=JBSWY3DPEHPK3PXP&issuer=Foo")).totpSecret,
        )
        assertEquals(
            "SEEDVALUE",
            EntryMapper.toPassword(mapOf("TOTP Seed" to "SEEDVALUE")).totpSecret,
        )
        assertNull(EntryMapper.toPassword(mapOf("Title" to "x")).totpSecret)
    }
}
