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
        versionCode = 20260825
        versionName = "v2.6.8"
        applicationId = "com.vayunmathur.speech"
    }

    androidResources {
        // ORT mmaps the model out of the APK; a deflated asset would have to be inflated to
        // a 40 MB heap buffer first. Storing them uncompressed costs nothing on download size
        // either — int8 weights are already incompressible.
        noCompress += "onnx"
    }
}

dependencies {
    // Text-to-speech (Piper/VITS) runs in the ncnn AAR via com.vayunmathur.ncnn.Vits, which
    // is filesystem-only, so voices are extracted to disk before use.
    implementation(libs.ncnn.android)

    // Speech-to-text is whisper-tiny int8 ONNX (bundled in assets, see WhisperOnnxEngine).
    // It is not ncnn: onnx2ncnn has no DynamicQuantizeLinear/MatMulInteger/ConvInteger
    // support, and the AAR's Whisper expects a six-net decomposition HF does not export.
    //
    // Reduced ORT build (10 MB native, vs 28 MB for the full one). Its operator set is
    // generated from the two bundled .onnx files, so if they are ever re-exported with
    // different ops the session will fail to create — regenerate the AAR, don't work around it.
    implementation(libs.onnxruntime.reduced)

    // Runtime model download (mirror-hosted, SHA-256 pinned) — non-English TTS voices only.
    implementation(project(":library:downloadservice"))
    implementation(libs.androidx.datastore.preferences)
}
