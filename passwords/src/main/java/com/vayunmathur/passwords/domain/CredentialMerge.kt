package com.vayunmathur.passwords.domain

import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password

/**
 * A strict partition of the passkeys: each one is attached to exactly one password or is
 * standalone, never both and never neither. The list renders a row per password plus a row per
 * standalone passkey, so a passkey appearing twice would mean duplicate LazyColumn keys.
 */
data class MergeResult(
    val passkeysByPasswordSyncId: Map<String, List<Passkey>>,
    val standalone: List<Passkey>,
)

/**
 * Pairs each passkey with the password it belongs to.
 *
 * An explicit link wins over the automatic rule in both directions. Automatic matching requires
 * both the domain and the username to agree, and only applies when exactly one password is a
 * candidate — anything ambiguous is left standalone for the user to link by hand.
 */
fun mergeCredentials(passwords: List<Password>, passkeys: List<Passkey>): MergeResult {
    val bySyncId = passwords.associateBy { it.syncId }
    val attached = mutableMapOf<String, MutableList<Passkey>>()
    val standalone = mutableListOf<Passkey>()

    for (passkey in passkeys) {
        val target = when (val link = passkey.link()) {
            PasskeyLink.Detached -> null
            // A link can outlive its password, or arrive before it on a device that is still
            // syncing. Show it standalone but leave the link alone so it re-attaches later.
            is PasskeyLink.To -> bySyncId[link.passwordSyncId]
            PasskeyLink.Auto -> passwords.filter { matches(passkey, it) }.singleOrNull()
        }
        if (target == null) standalone.add(passkey)
        else attached.getOrPut(target.syncId) { mutableListOf() }.add(passkey)
    }

    return MergeResult(
        attached.mapValues { (_, list) -> list.sortedWith(compareBy({ it.creationTime }, { it.syncId })) },
        standalone,
    )
}

/** The passkeys shown on [password]'s detail page. Resolves identically to the list. */
fun passkeysFor(password: Password, passwords: List<Password>, passkeys: List<Passkey>): List<Passkey> =
    mergeCredentials(passwords, passkeys).passkeysByPasswordSyncId[password.syncId].orEmpty()

private fun matches(passkey: Passkey, password: Password): Boolean =
    usernameMatches(passkey, password) && domainMatches(passkey, password)

private fun usernameMatches(passkey: Passkey, password: Password): Boolean {
    val user = passkey.userName.trim()
    if (user.isEmpty()) return false
    return user.equals(password.username.trim(), ignoreCase = true) ||
        user.equals(password.email.trim(), ignoreCase = true)
}

private fun domainMatches(passkey: Passkey, password: Password): Boolean {
    val rp = DomainMatch.normalizeSite(passkey.rpId)
    if (rp.isEmpty()) return false
    return password.websites.any { site ->
        !DomainMatch.isAndroidPackageSite(site) &&
            DomainMatch.isSameSiteOrSubdomain(DomainMatch.normalizeSite(site), rp)
    }
}
