/**
 * A television app: `common-conventions-app` plus `androidx.tv`.
 *
 * **An additive layer, not a third standalone app plugin.** `common-conventions-wear` is standalone
 * because a watch genuinely needs a different `compileSdk`, a different `minSdk`, a single ABI and
 * `androidx.wear.compose` instead of Material 3. A Google TV box needs none of that: it is an ordinary
 * Android app with a D-pad. Mirroring the Wear plugin would have meant cloning the version read from
 * `version.txt`, the signing configs, the build types, `dependenciesInfo`, the namespace derivation,
 * the launcher-icon extension *together with its per-variant `GenerateLauncherIconTask` wiring and the
 * pinned Material Symbols ref*, the `nativeAbis` extension, the `beforeVariants` block, the lint
 * block, the shared-res source directory and the archive reproducibility tasks - roughly two hundred
 * lines that would then drift from the file they were copied out of. `common-conventions-preview-
 * metadata` is the precedent for a layer that adds one thing and inherits the rest.
 *
 * It also avoids a real regression: the Wear plugin hardcodes `abiFilters.add("arm64-v8a")`, and
 * Google TV hardware runs a 32-bit userspace, so a clone would have produced an APK the onn box
 * refuses to install.
 *
 * `common-conventions-app` is applied here so this plugin stands on its own, but a consumer should
 * still name it in its own `plugins` block: Gradle generates the type-safe accessors for
 * `launcherIcon` and `nativeAbis` from the plugins that build file applies directly, not from ones it
 * inherits.
 */

plugins {
    id("common-conventions-app")
}

// The version catalog is not implicitly in scope in a precompiled script plugin; the app and wear
// conventions reach it exactly this way.
val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    // **Material Design for a ten-foot UI, and the reason this plugin exists at all.** Material 3's
    // components are built for touch: they have no D-pad focus states worth the name, so a TV built on
    // them looks correct in a screenshot and is unusable from a sofa. `androidx.tv` is the same design
    // language with focus as a first-class concern.
    //
    // It resolves against the Compose versions the catalog already pins - `tv-material` asks for
    // compose 1.10 and the BOM here is newer, so Gradle takes the newer and nothing is downgraded.
    implementation(libs.androidx.tv.material)
}
