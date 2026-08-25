plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "moved_location"
}

android {
    defaultConfig {
        versionCode = 20260825
        versionName = "v2.6.8"
        applicationId = "com.vayunmathur.findfamily"
    }
}

dependencies {

    // HTTP + JSON
    implementation(project(":library:network"))
    implementation(project(":library:e2ee-p2p"))

    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":library:work"))

    implementation(project(":library:image"))

    // Compose-native raster map (replaces maplibre; keeps spatialk coordinate types).
    implementation(project(":library:map"))

    // Public AOSP ranging API (android.ranging.*) is part of the framework
    // on Android 15+ — no third-party library needed. We intentionally avoid
    // androidx.core.uwb because its only backend is GMS-mediated, which fails
    // on GrapheneOS (sandboxed Play Services can't talk to the platform UWB
    // service). All UWB code paths are gated on Build.VERSION.SDK_INT >= 35.
}

// The Compose screenshot renderer draws previews from the compiled `screenshotTest` classes
// directory (the renderer's screenshotProjectClassPath). JVM resources placed under
// src/screenshotTest/resources are NOT otherwise put on that classpath, so the metadata
// previews can't load the bundled CARTO map via getResourceAsStream. Stage those resources
// into the classes output so the previews can read them at render time. Because this asset
// lives only in the screenshotTest source set it ships in neither the dev nor the release APK.
val screenshotTestClasses = layout.buildDirectory.dir(
    "intermediates/built_in_kotlinc/devScreenshotTest/compileDevScreenshotTestKotlin/classes"
)
val stageDevScreenshotTestResources = tasks.register<Copy>("stageDevScreenshotTestResources") {
    from(layout.projectDirectory.dir("src/screenshotTest/resources"))
    into(screenshotTestClasses)
    mustRunAfter("compileDevScreenshotTestKotlin")
    outputs.upToDateWhen { false }
}
tasks.matching {
    it.name == "updateDevScreenshotTest" || it.name == "validateDevScreenshotTest"
}.configureEach {
    dependsOn(stageDevScreenshotTestResources)
}