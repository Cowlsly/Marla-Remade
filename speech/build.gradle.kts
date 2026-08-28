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
        applicationId = "com.vayunmathur.speech"
    }

    androidResources {
        // ORT mmaps the model out of the APK; a deflated asset would have to be inflated to
        // a 40 MB heap buffer first. Storing them uncompressed costs nothing on download size
        // either - int8 weights are already incompressible.
        noCompress += "onnx"
        // Voices are downloaded rather than bundled, so no .maml sits in this APK. The entry
        // is here for the same reason as `onnx`: if a voice is ever bundled, fp16 weights
        // should not be deflated only to be inflated straight back.
        noCompress += "maml"
    }
}
dependencies {
    // Text-to-speech is Piper (VITS) on :library:ml, our own Vulkan compute runtime. Four
    // networks per voice: the text encoder, the flow and the HiFi-GAN vocoder are compiled
    // plans on the GPU, and the stochastic duration predictor runs on the CPU because it is a
    // bin search and a quadratic solve per phoneme. Voices are extracted to disk before use.
    implementation(project(":library:ml"))

    // Speech-to-text is whisper-base int8 ONNX (bundled in assets, see WhisperOnnxEngine).
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
