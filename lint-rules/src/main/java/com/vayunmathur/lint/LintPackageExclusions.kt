package com.vayunmathur.lint

/**
 * Package prefixes that are exempt from the app-structure lints
 * ([PackageStructureDetector] and [OneComposablePerFileDetector]).
 *
 * Both rules encode APP conventions — the closed canonical root allow-list and
 * one public @Composable per screen file. The prefixes below are NOT apps, so
 * those conventions do not apply and must be skipped by both detectors (kept in
 * one place so the two stay in sync):
 *  - `com.vayunmathur.library`  — shared UI/util library. `library/ui` is a
 *      Material + icon shim that is intentionally many-composables-per-file
 *      (e.g. Icons.kt, MaterialComponents.kt), and its flat package layout is
 *      not the app root structure.
 *  - `com.vayunmathur.e2ee`     — shared end-to-end-encryption library
 *      (`library/e2ee-p2p`); a flat crypto API package, not an app root.
 *  - `com.vayunmathur.sdk`      — shared SDK modules.
 *  - `com.vayunmathur.games`    — games use their own UI structure; the
 *      one-public-composable-per-file splits were deliberately deferred.
 *  - `com.vayunmathur.tools`    — internal tooling, not shipped apps.
 *  - `com.vayunmathur.personal` — personal/scratch modules.
 */
object LintPackageExclusions {

    val EXCLUDED_PREFIXES: List<String> = listOf(
        "com.vayunmathur.library",
        "com.vayunmathur.e2ee",
        "com.vayunmathur.sdk",
        "com.vayunmathur.games",
        "com.vayunmathur.tools",
        "com.vayunmathur.personal",
    )

    /** True if [packageName] is (or is nested under) an excluded module prefix. */
    fun isExcluded(packageName: String): Boolean =
        EXCLUDED_PREFIXES.any { packageName == it || packageName.startsWith("$it.") }
}
