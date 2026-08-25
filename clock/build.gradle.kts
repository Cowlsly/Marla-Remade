plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:clock:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "schedule"
}

android {
    defaultConfig {
        versionCode = 20260825
        versionName = "v2.6.8"
        applicationId = "com.vayunmathur.clock"
    }
}

dependencies {
    implementation(project(":library:widgets"))
    implementRoom(libs)
    implementation(project(":library:room"))
}
