package com.vayunmathur.findfamily.intents

import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** Wire shape of the `DATA` extra; field names must match the caller. */
@Serializable
data class CreateLinkData(val name: String, val expiryMillis: Long)

/**
 * Lets another app (e.g. the messages app) mint a FindFamily location-sharing
 * link. Reuses FindFamily's temporary-link mechanism: generate an RSA keypair
 * AND a PQC bundle, persist a [TemporaryLink] (the background tracking service
 * then publishes encrypted location for it until it expires), and return the
 * same recipient URL the in-app copy button produces. PQC included when available.
 */
@OptIn(InternalSerializationApi::class)
class CreateLinkIntent : AssistantIntent<CreateLinkData, String>(
    serializer<CreateLinkData>(),
    serializer<String>(),
) {
    override suspend fun performCalculation(input: CreateLinkData): String {
        // Links are post-quantum only — with no classic fallback, a PQC keygen failure has to
        // fail the whole call rather than hand back a link that can never publish.
        val pqcPair = Networking.generatePqcKeyPair()
        val link = TemporaryLink(
            name = input.name,
            deleteAt = Clock.System.now() + input.expiryMillis.milliseconds,
            pqcPublicKey = pqcPair.publicBundleB64,
            pqcKey = pqcPair.privateBundleB64,
        )
        FindFamilyRepository.get(this).upsertTemporaryLink(link)
        // The id is generated with the link (newTemporaryLinkId), not handed back by the
        // insert, so read it off the entity — @Upsert returns -1 on the update path.
        // Must match the URL format produced by TemporaryLinkCard in MainPage.
        return "https://findfamily.cc/view/${link.id}#pqc_key=${link.pqcKey}"
    }
}
