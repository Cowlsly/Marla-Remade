package com.vayunmathur.passwords.domain

import com.vayunmathur.passwords.data.PASSKEY_LINK_DETACHED
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CredentialMergeTest {

    private val mail = Password(
        id = 1,
        name = "Example Mail",
        email = "user@example.com",
        websites = listOf("mail.example.com"),
        syncId = "pw-mail",
    )
    private val bank = Password(
        id = 2,
        name = "Example Bank",
        username = "user@example.com",
        websites = listOf("bank.example.com"),
        syncId = "pw-bank",
    )

    private fun passkey(
        id: Long = 1,
        rpId: String = "mail.example.com",
        userName: String = "user@example.com",
        link: String? = null,
        creationTime: Long = 0,
    ) = Passkey(
        id = id,
        rpId = rpId,
        userName = userName,
        linkedPasswordSyncId = link,
        creationTime = creationTime,
        syncId = "pk-$id",
    )

    @Test fun attachesOnDomainAndUsername() {
        val pk = passkey()
        val result = mergeCredentials(listOf(mail, bank), listOf(pk))
        assertEquals(listOf(pk), result.passkeysByPasswordSyncId["pw-mail"])
        assertTrue(result.standalone.isEmpty())
    }

    @Test fun parentRpIdReachesASubdomainEntry() {
        val result = mergeCredentials(listOf(mail), listOf(passkey(rpId = "example.com")))
        assertEquals(1, result.passkeysByPasswordSyncId["pw-mail"]?.size)
    }

    @Test fun twoCandidatePasswordsLeaveItStandalone() {
        // rpId example.com is a parent of both mail. and bank.example.com, and the username is on
        // both entries.
        val pk = passkey(rpId = "example.com")
        val result = mergeCredentials(listOf(mail, bank), listOf(pk))
        assertEquals(listOf(pk), result.standalone)
        assertTrue(result.passkeysByPasswordSyncId.isEmpty())
    }

    @Test fun domainWithoutUsernameDoesNotAttach() {
        val result = mergeCredentials(listOf(mail), listOf(passkey(userName = "someone@else.com")))
        assertEquals(1, result.standalone.size)
    }

    @Test fun usernameWithoutDomainDoesNotAttach() {
        val result = mergeCredentials(listOf(mail), listOf(passkey(rpId = "unrelated.test")))
        assertEquals(1, result.standalone.size)
    }

    @Test fun blankUsernameNeverAutoAttaches() {
        val result = mergeCredentials(listOf(mail), listOf(passkey(userName = "")))
        assertEquals(1, result.standalone.size)
    }

    @Test fun androidPackageWebsitesAreIgnored() {
        val app = mail.copy(websites = listOf("com.example.mail"), syncId = "pw-app")
        val result = mergeCredentials(listOf(app), listOf(passkey(rpId = "com.example.mail")))
        assertEquals(1, result.standalone.size)
    }

    @Test fun explicitLinkBeatsTheRule() {
        val pk = passkey(rpId = "totally.unrelated", userName = "nobody", link = "pw-bank")
        val result = mergeCredentials(listOf(mail, bank), listOf(pk))
        assertEquals(listOf(pk), result.passkeysByPasswordSyncId["pw-bank"])
    }

    @Test fun detachedStaysStandaloneDespiteAPerfectMatch() {
        val pk = passkey(link = PASSKEY_LINK_DETACHED)
        val result = mergeCredentials(listOf(mail), listOf(pk))
        assertEquals(listOf(pk), result.standalone)
    }

    /** A device can hold the passkey before the linked password has synced across. */
    @Test fun linkToAMissingPasswordIsStandaloneButPreserved() {
        val pk = passkey(link = "pw-not-here-yet")
        val result = mergeCredentials(listOf(mail, bank), listOf(pk))
        assertEquals(listOf(pk), result.standalone)
        assertEquals("pw-not-here-yet", result.standalone.single().linkedPasswordSyncId)
    }

    @Test fun onePasswordCanHoldSeveralPasskeys() {
        val phone = passkey(id = 1, creationTime = 200)
        val laptop = passkey(id = 2, creationTime = 100)
        val result = mergeCredentials(listOf(mail), listOf(phone, laptop))
        // Oldest first, so the order does not jump around between recompositions.
        assertEquals(listOf(laptop, phone), result.passkeysByPasswordSyncId["pw-mail"])
    }

    /** Duplicate LazyColumn keys would crash the list, so the split has to be exact. */
    @Test fun everyPasskeyAppearsExactlyOnce() {
        val all = listOf(
            passkey(id = 1),
            passkey(id = 2, rpId = "example.com"),
            passkey(id = 3, link = PASSKEY_LINK_DETACHED),
            passkey(id = 4, link = "pw-bank"),
            passkey(id = 5, rpId = "nowhere.test"),
        )
        val result = mergeCredentials(listOf(mail, bank), all)
        val seen = result.passkeysByPasswordSyncId.values.flatten() + result.standalone
        assertEquals(all.size, seen.size)
        assertEquals(all.map { it.id }.toSet(), seen.map { it.id }.toSet())
    }

    @Test fun passkeysForAgreesWithTheMerge() {
        val pk = passkey()
        assertEquals(listOf(pk), passkeysFor(mail, listOf(mail, bank), listOf(pk)))
        assertTrue(passkeysFor(bank, listOf(mail, bank), listOf(pk)).isEmpty())
    }
}
