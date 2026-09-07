package com.vayunmathur.findfamily.tracker

import com.vayunmathur.library.util.DataStoreUtils

/**
 * The keys needed to read a device's powered-off sightings, held per device userid.
 *
 * ## Who holds what, and why it matters
 * A powered-off phone cannot decrypt anything — it is off, and by the time it is back on you
 * have already found it. So keeping the private key only on the device that beacons would make
 * the whole feature useless. The AOSP javadoc anticipates this: reports are readable by "the
 * device owner **or someone that the device owner shared the key with**".
 *
 * So there are two cases, and this store handles both identically:
 *  - **your own device**, whose keys you keep so you can look up its sightings from your tablet
 *    or after it comes back;
 *  - **a family member's device**, whose keys they sent you over the existing end-to-end
 *    encrypted peer channel when they armed it, so you can go and look for their lost phone.
 *
 * Handing someone your powered-off private key lets them locate that device for as long as the
 * key lives, so the beacon half must only ever distribute it to peers the user has explicitly
 * chosen — it is not something to sync to everyone by default.
 *
 * The secret is the same per-device value the rest of the crowd network uses; it is here rather
 * than in [TrackerStore] because [TrackerStore] is keyed on tracker `User` rows and these are
 * ordinary people.
 * ## What is stored, and why the name matters
 * The key half is the device's **raw ML-KEM private DER** — the `ff_pqcKemPriv` blob from the
 * identity mirror — and deliberately NOT the composite `[4B kemPrivLen][kemPriv][dsaPriv]` that
 * the word "bundle" means everywhere else in this package. A powered-off phone reuses its own
 * existing identity, whereas a tracker's identity is minted on its behalf and is composite.
 * These accessors are named [kemPrivateKey] rather than `privateBundle` because calling it a
 * bundle already caused one silent bug: the value was fed to
 * [TrackerProtocol.kemPrivFromPrivateBundle], which read the DER's leading SEQUENCE header as a
 * length field and failed every decrypt. Both shapes are `ByteArray`, so nothing but the name
 * stops the next caller repeating it. Decrypt this with `Pqc.decrypt` directly.
 *
 * The set of owned trackers is simply the `User` rows with `kind == UserKind.TRACKER`, so
 * no separate index is kept here.
 */
class PoweredOffKeyStore(private val ds: DataStoreUtils) {

    /**
     * Stores (or replaces) the beacon secret and the raw ML-KEM private DER for [userId].
     * [kemPrivateKey] must be the raw key, not a composite bundle — see the class KDoc.
     */
    suspend fun save(userId: Long, secret: ByteArray, kemPrivateKey: ByteArray) {
        ds.setByteArray(secretKey(userId), secret)
        ds.setByteArray(privKey(userId), kemPrivateKey)
    }

    suspend fun secret(userId: Long): ByteArray? = ds.getByteArrayAwait(secretKey(userId))

    /** The raw ML-KEM private DER, ready to hand straight to `Pqc.decrypt`. */
    suspend fun kemPrivateKey(userId: Long): ByteArray? = ds.getByteArrayAwait(privKey(userId))

    /** True when both keys are present, i.e. this device's sightings are readable here. */
    suspend fun canRead(userId: Long): Boolean = secret(userId) != null && kemPrivateKey(userId) != null

    private fun secretKey(id: Long) = "ff_pof_secret_$id"
    private fun privKey(id: Long) = "ff_pof_priv_$id"
}
