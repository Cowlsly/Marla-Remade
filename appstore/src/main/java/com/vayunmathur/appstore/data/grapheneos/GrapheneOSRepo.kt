package com.vayunmathur.appstore.data.grapheneos

/**
 * Fixed trust anchors for the GrapheneOS Apps source, mirroring GrapheneOS's own client
 * (`Apps/app/build.gradle.kts` and `core/RepoRetriever.kt`).
 *
 * These are the only values this store takes on faith for GrapheneOS: the release-server
 * host and — the actual root of trust — the signify ed25519 public key the repository index
 * must verify against. Everything the index then states (versions, file names, per-APK
 * SHA-256, signing-certificate digests) is trusted only because the signature covered it.
 */
object GrapheneOSRepo {

    const val BASE_URL = "https://apps.grapheneos.org"

    /** signify-format ed25519 public key the index signature is verified against. */
    const val METADATA_PUBKEY = "RWQtZwEu1br1lMh911L3yPOs97cQb9LOks/ALBbqGl21ul695ocWR/ir"

    /** Index schema version. Bumping it is a breaking change GrapheneOS publishes separately. */
    private const val METADATA_VERSION = 1

    /** Signing-key generation. Rotating the key above means bumping this with it. */
    private const val KEY_VERSION = 0

    val METADATA_URL = "$BASE_URL/metadata.$METADATA_VERSION.$KEY_VERSION.sjson"

    /**
     * Anti-rollback floor: the store refuses any index older than this, even on first run
     * when nothing is stored yet. Matches GrapheneOS's own `MIN_TIMESTAMP`.
     */
    const val MIN_TIMESTAMP = 1_770_000_000L

    /** Where a package's APKs live. GrapheneOS serves them gzipped. */
    fun apkUrl(manifestPackageName: String, versionCode: Long, apkName: String): String =
        "$BASE_URL/packages/$manifestPackageName/$versionCode/$apkName.gz"

    fun iconUrl(manifestPackageName: String, iconType: String): String =
        "$BASE_URL/packages/$manifestPackageName/icon.$iconType"
}
