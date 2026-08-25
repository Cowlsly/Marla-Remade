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
        versionCode = 20260825
        versionName = "v2.6.8"
        applicationId = "com.vayunmathur.music"
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)

    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui.compose.material3)

    implementation(project(":library:image"))

    // Casting. `:sdk:cast` owns no sockets and needs no network permission - which is the point,
    // because this app deliberately has no INTERNET permission. `:library:media` is here for the
    // Opus transcoder: every cast audio track is 48 kHz Opus, and most of the library is not.
    implementation(project(":sdk:cast"))
    implementation(project(":library:media"))
}