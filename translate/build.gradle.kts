plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:translate:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "translate"
}

android {
    defaultConfig {
        versionCode = 20260816
        versionName = "v2.6.7"
        applicationId = "com.vayunmathur.translate"
    }
}

dependencies {
    // On-device translation runs entirely in the ncnn AAR (SMaLL-100 via the
    // Small100 class); the model files are downloaded at runtime from the mirror.
    implementation(libs.ncnn.android)
    implementation(project(":library:downloadservice"))
    implementation(project(":library:ocr"))
    implementation(project(":library:network"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.compose)
}
