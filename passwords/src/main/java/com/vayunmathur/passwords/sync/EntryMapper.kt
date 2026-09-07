package com.vayunmathur.passwords.sync

import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.newSyncId
import java.security.MessageDigest
import java.util.Base64

/**
 * The single Password/Passkey <-> kdbx field-map conversion, shared by the one-shot
 * backup format and the bidirectional sync.
 *
 * The mapping must round-trip: `toFields(fromFields(f))` has to be stable, because the
 * merge compares content hashes computed from it on both sides.
 */
object EntryMapper {
    const val FIELD_TYPE = "_Type"
    const val FIELD_SYNC_ID = "_SyncId"
    const val FIELD_MODIFIED = "_Modified"

    private const val FIELD_SIGN_COUNT = "_SignCount"
    private const val FIELD_CREATED = "_Created"
    private const val FIELD_LAST_USED = "_LastUsed"
    private const val FIELD_LINKED_PASSWORD = "_LinkedPassword"

    /**
     * Every key this mapper can emit. Keys outside this set belong to whatever client
     * wrote the vault (Notes, Tags, ...) and are carried through untouched on push.
     */
    val OWNED_KEYS = setOf(
        "Title", "UserName", "Email", "Password", "Notes", "URL", "Websites", "otp", "TOTP Seed",
        FIELD_TYPE, FIELD_SYNC_ID, FIELD_MODIFIED,
        FIELD_SIGN_COUNT, FIELD_CREATED, FIELD_LAST_USED, FIELD_LINKED_PASSWORD,
        "KPEX_PASSKEY_USERNAME", "KPEX_PASSKEY_PRIVATE_KEY_PEM", "KPEX_PASSKEY_CREDENTIAL_ID",
        "KPEX_PASSKEY_USER_HANDLE", "KPEX_PASSKEY_RELYING_PARTY",
    )

    fun isPasskeyEntry(entry: Map<String, String>): Boolean =
        entry.keys.any { it.startsWith("KPEX_PASSKEY_") }

    fun toFields(pw: Password): Map<String, String> = buildMap {
        put("Title", pw.name)
        put("UserName", pw.username)
        put("Email", pw.email)
        put("Password", pw.password)
        put("Notes", pw.note)
        if (pw.websites.isNotEmpty()) {
            put("URL", pw.websites.first())
            if (pw.websites.size > 1) put("Websites", pw.websites.joinToString("\n"))
        }
        pw.totpSecret?.takeIf { it.isNotBlank() }?.let { put("otp", "otpauth://totp/?secret=$it") }
        put(FIELD_TYPE, "password")
        put(FIELD_SYNC_ID, pw.syncId)
        put(FIELD_MODIFIED, pw.updatedAt.toString())
    }

    fun toPassword(entry: Map<String, String>): Password {
        val websites = mutableListOf<String>()
        entry["URL"]?.takeIf { it.isNotEmpty() }?.let { websites.add(it) }
        entry["Websites"]?.split("\n")?.filter { it.isNotBlank() }?.forEach {
            if (it !in websites) websites.add(it)
        }
        val otp = entry["otp"]
        val totpSecret = if (!otp.isNullOrEmpty()) {
            Regex("[?&]secret=([^&]+)").find(otp)?.groupValues?.get(1) ?: otp
        } else {
            entry["TOTP Seed"]?.takeIf { it.isNotBlank() }
        }

        return Password(
            name = entry["Title"].orEmpty(),
            username = entry["UserName"].orEmpty(),
            email = entry["Email"].orEmpty(),
            password = entry["Password"].orEmpty(),
            note = entry["Notes"].orEmpty(),
            websites = websites,
            totpSecret = totpSecret,
            syncId = entry[FIELD_SYNC_ID]?.takeIf { it.isNotBlank() } ?: newSyncId(),
            updatedAt = entry[FIELD_MODIFIED]?.toLongOrNull() ?: System.currentTimeMillis(),
        )
    }

    fun toFields(pk: Passkey): Map<String, String> = buildMap {
        put("Title", pk.rpName)
        put("UserName", pk.userDisplayName)
        put("URL", pk.rpId)
        put(FIELD_TYPE, "passkey")
        put("KPEX_PASSKEY_USERNAME", pk.userName)
        put("KPEX_PASSKEY_PRIVATE_KEY_PEM", Base64.getEncoder().encodeToString(pk.privateKeyBytes))
        put("KPEX_PASSKEY_CREDENTIAL_ID", pk.credentialId)
        put("KPEX_PASSKEY_USER_HANDLE", pk.userId)
        put("KPEX_PASSKEY_RELYING_PARTY", pk.rpId)
        put(FIELD_SIGN_COUNT, pk.signCount.toString())
        put(FIELD_CREATED, pk.creationTime.toString())
        put(FIELD_LAST_USED, pk.lastUsedTime.toString())
        // Only when set. contentHash covers every key but _SyncId/_Modified, so emitting this
        // unconditionally would rewrite the hash of every existing passkey and report them all as
        // changed on the next sync.
        pk.linkedPasswordSyncId?.takeIf { it.isNotBlank() }?.let { put(FIELD_LINKED_PASSWORD, it) }
        put(FIELD_SYNC_ID, pk.syncId)
        put(FIELD_MODIFIED, pk.updatedAt.toString())
    }

    fun toPasskey(entry: Map<String, String>): Passkey {
        val privateKeyB64 = entry["KPEX_PASSKEY_PRIVATE_KEY_PEM"].orEmpty()
        val privateKeyBytes = if (privateKeyB64.isNotEmpty()) {
            runCatching { Base64.getDecoder().decode(privateKeyB64) }.getOrDefault(ByteArray(0))
        } else {
            ByteArray(0)
        }
        val now = System.currentTimeMillis()

        return Passkey(
            rpId = entry["KPEX_PASSKEY_RELYING_PARTY"].orEmpty().ifEmpty { entry["URL"].orEmpty() },
            rpName = entry["Title"].orEmpty(),
            credentialId = entry["KPEX_PASSKEY_CREDENTIAL_ID"].orEmpty(),
            userId = entry["KPEX_PASSKEY_USER_HANDLE"].orEmpty(),
            userName = entry["KPEX_PASSKEY_USERNAME"].orEmpty().ifEmpty { entry["UserName"].orEmpty() },
            userDisplayName = entry["UserName"].orEmpty(),
            privateKeyBytes = privateKeyBytes,
            creationTime = entry[FIELD_CREATED]?.toLongOrNull() ?: now,
            lastUsedTime = entry[FIELD_LAST_USED]?.toLongOrNull() ?: now,
            signCount = entry[FIELD_SIGN_COUNT]?.toIntOrNull() ?: 0,
            linkedPasswordSyncId = entry[FIELD_LINKED_PASSWORD]?.takeIf { it.isNotBlank() },
            syncId = entry[FIELD_SYNC_ID]?.takeIf { it.isNotBlank() } ?: newSyncId(),
            updatedAt = entry[FIELD_MODIFIED]?.toLongOrNull() ?: now,
        )
    }

    /** Content fingerprint ignoring identity and timestamps, so it is comparable across sides. */
    fun contentHash(fields: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.entries
            .filter { it.key != FIELD_SYNC_ID && it.key != FIELD_MODIFIED }
            .sortedBy { it.key }
            .forEach { digest.update("${it.key}=${it.value}\n".toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Match key used to adopt pre-existing vault entries on the very first sync. */
    fun contentKey(pw: Password): String = "pw\u0000${pw.name}\u0000${pw.username}"

    fun contentKey(pk: Passkey): String = "pk\u0000${pk.rpId}\u0000${pk.credentialId}"
}
