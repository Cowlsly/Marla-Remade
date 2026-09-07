package com.vayunmathur.appstore.data

import com.vayunmathur.appstore.data.grapheneos.GrapheneOSRepo

/**
 * The GrapheneOS "Sandboxed Google Play" bundle: Google's own Play components, installed as
 * ordinary unprivileged user apps rather than as a privileged system blob.
 *
 * These packages are **not** installable from Google Play, so the store never fetches them
 * through the anonymous-Play path. They come from GrapheneOS's app release server
 * (apps.grapheneos.org), which re-hosts Google's official signed APKs alongside a signed
 * index — see [com.vayunmathur.appstore.data.grapheneos.GrapheneOSRepository], which is what
 * supplies the download URLs, the expected signing certificates and the per-APK hashes.
 * Installing them reuses the store's ordinary verify-then-commit
 * [android.content.pm.PackageInstaller] flow. The gmscompat shim that lets these run without
 * the privileged access the real Play client expects lives in the OS, not here.
 *
 * Order matters at install: Play Services provides the compatibility surface the store
 * front-end talks to, so it goes on before Vending.
 */
object SandboxedGooglePlay {
    /**
     * Google Services Framework — deliberately **not** installed.
     *
     * GrapheneOS refuses it outright, in PackageInstallerSession:
     * `INSTALL_FAILED_SESSION_INVALID: GSF installation is not allowed`. Two reasons, both
     * from the OS's own comments: gmscompat does not need GSF, and because GSF is
     * preinstalled on real GMS Android other apps trust anything holding its package name
     * without checking a signature — so allowing an arbitrary GSF would be a spoofing hole.
     * GrapheneOS does not ship it as a preinstalled app either.
     *
     * Kept as a constant because the identity is still worth naming: [GsfVersionProvider]
     * reads its version when spoofing Play device info, falling back to a default when — as
     * here — it is absent.
     */
    const val GSF = "com.google.android.gsf"

    /** Google Play Services — the bulk of the compatibility surface apps call into. */
    const val GMS = "com.google.android.gms"

    /** Google Play Store (Vending) — the store client, installed last. */
    const val VENDING = "com.android.vending"

    /** Stable id for the curated home section, shared by the ViewModel and the home screen. */
    const val SECTION_ID = "sandboxed-google-play"

    /** Human-readable names, shown before the signed index has been fetched. */
    val DISPLAY_NAMES: Map<String, String> = mapOf(
        GMS to "Google Play Services",
        VENDING to "Google Play Store",
    )

    /**
     * Install order: services first, the store that depends on them last.
     * The curated section and the ordered install both read this, so the two never drift.
     */
    val PACKAGES: List<String> = listOf(GMS, VENDING)

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
