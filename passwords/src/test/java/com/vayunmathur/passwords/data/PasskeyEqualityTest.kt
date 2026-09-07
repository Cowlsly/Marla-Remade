package com.vayunmathur.passwords.data

import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * `viewModel.passkeys` is a StateFlow, which conflates by equality. If the link column were left
 * out of [Passkey.equals] a link-only change would produce an equal list and the emission would be
 * dropped, so linking and unlinking would silently do nothing in the UI.
 */
class PasskeyEqualityTest {

    @Test fun aLinkOnlyChangeIsNotEqual() {
        val pk = Passkey(id = 1, rpId = "example.com", credentialId = "cred", userId = "user")
        val linked = pk.copy(linkedPasswordSyncId = "0123456789abcdef0123456789abcdef")
        val detached = pk.copy(linkedPasswordSyncId = PASSKEY_LINK_DETACHED)

        assertNotEquals(pk, linked)
        assertNotEquals(pk, detached)
        assertNotEquals(linked, detached)
        assertNotEquals(listOf(pk), listOf(linked))
        assertNotEquals(pk.hashCode(), linked.hashCode())
    }
}
