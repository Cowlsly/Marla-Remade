plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:speech:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "record_voice_over"
}

android {
    defaultConfig {
        versionCode = 20260816
        versionName = "v2.6.7"
        applicationId = "com.vayunmathur.speech"
    }
}

dependencies {
    // Both offline speech engines run in the ncnn AAR: speech-to-text via
    // com.vayunmathur.ncnn.Whisper and text-to-speech (Piper/VITS) via
    // com.vayunmathur.ncnn.Vits. Their models are downloaded at runtime from the mirror
    // (WhisperModel.FILES, PiperModel.FILES) and loaded from the filesystem by path —
    // only native code ships in the APK.
    implementation(libs.ncnn.android)

    // Runtime model download (mirror-hosted, SHA-256 pinned) — same infra as Translate.
    implementation(project(":library:downloadservice"))
    implementation(libs.androidx.datastore.preferences)
}
