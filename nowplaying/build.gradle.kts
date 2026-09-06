plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:nowplaying:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "music_note"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.nowplaying"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))

    // The log-mel front end and the CPU music gate when it lands. No model asset ships from
    // this module: the gate's weights live in :library:ml and gate-dev owns adding them.
    implementation(project(":library:ml"))
}
