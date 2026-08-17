plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:music:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "music_note"
}

android {
    defaultConfig {
        versionCode = 20260816
        versionName = "v2.6.7"
        applicationId = "com.vayunmathur.music"
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)

    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(project(":library:image"))
}