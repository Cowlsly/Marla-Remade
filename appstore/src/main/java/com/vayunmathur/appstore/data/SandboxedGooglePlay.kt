package com.vayunmathur.appstore.data

import com.vayunmathur.appstore.data.grapheneos.GrapheneOSRepo

/**
 * The GrapheneOS "Sandboxed Google Play" bundle: Google's own Play components, installed as
 * ordinary unprivileged user apps rather than as a privileged system blob.
 *
 * These three packages are **not** installable from Google Play, so the store never fetches
 * them through the anonymous-Play path. They come from GrapheneOS's app release server
 * (apps.grapheneos.org), which re-hosts Google's official signed APKs alongside a signed
 * index — see [com.vayunmathur.appstore.data.grapheneos.GrapheneOSRepository], which is what
 * supplies the download URLs, the expected signing certificates and the per-APK hashes.
 * Installing them reuses the store's ordinary verify-then-commit
 * [android.content.pm.PackageInstaller] flow. The gmscompat shim that lets these run without
 * the privileged access the real Play client expects lives in the OS, not here.
 *
 * Order matters at install. Google Services Framework and Play Services provide the
 * accounts, the GSF ID and the provider the store front-end talks to, so they go on before
 * Vending — the same order GrapheneOS installs them in.
 */
object SandboxedGooglePlay {
    /** Google Services Framework — the account/provider layer the rest builds on. */
    const val GSF = "com.google.android.gsf"

    /** Google Play Services — the bulk of the compatibility surface apps call into. */
    const val GMS = "com.google.android.gms"

    /** Google Play Store (Vending) — the store client, installed last. */
    const val VENDING = "com.android.vending"

    /** Stable id for the curated home section, shared by the ViewModel and the home screen. */
    const val SECTION_ID = "sandboxed-google-play"

    /** Human-readable names, shown before the signed index has been fetched. */
    val DISPLAY_NAMES: Map<String, String> = mapOf(
        GSF to "Google Services Framework",
        GMS to "Google Play Services",
        VENDING to "Google Play Store",
    )

    /**
     * Install order: framework and services first, the store that depends on them last.
     * The curated section and the ordered install both read this, so the two never drift.
     */
    val PACKAGES: List<String> = listOf(GSF, GMS, VENDING)

    /**
     * Stand-in listings for the three packages.
     *
     * The home section shows these immediately, before the network is reached. They carry no
     * download: everything needed to install — version, file list, signer digests, per-APK
     * hashes — comes from the signed index, and a row built without it would be a download
     * nothing could be checked against. Until the sync lands, tapping install reports that
     * the source published nothing, which is exactly what has happened.
     */
    fun placeholders(): List<UnifiedApp> = PACKAGES.map { pkg ->
        UnifiedApp(
            packageName = pkg,
            source = AppSource.GRAPHENEOS,
            name = DISPLAY_NAMES[pkg] ?: pkg,
            author = "Google LLC",
            repoUrl = GrapheneOSRepo.BASE_URL,
        )
    }
}
