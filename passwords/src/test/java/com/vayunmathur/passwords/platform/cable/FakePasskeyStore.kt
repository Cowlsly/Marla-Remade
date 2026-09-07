package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyStore

/** In-memory [PasskeyStore] for the CTAP tests. */
class FakePasskeyStore(initial: List<Passkey> = emptyList()) : PasskeyStore {
    val store = initial.toMutableList()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    override suspend fun getPasskeysByRpId(rpId: String): List<Passkey> =
        store.filter { it.rpId == rpId }

    override suspend fun getPasskeyByCredentialId(credentialId: String): Passkey? =
        store.firstOrNull { it.credentialId == credentialId }

    /**
     * Emulates Room's autoGenerate. Without this, every freshly created credential arrives with
     * id 0 and would overwrite the previous one instead of being inserted.
     */
    override suspend fun upsertPasskey(passkey: Passkey): Long {
        val row = if (passkey.id == 0L) passkey.copy(id = nextId++) else passkey
        store.removeAll { it.id == row.id }
        store.add(row)
        return row.id
    }
}
