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
        // Load-bearing, not an optimisation. `AssetManager.openFd` throws for a deflated entry,
        // and the fd is how Supertonic's ~105 MB of weights reach the GPU without being copied
        // through the Java heap three times - see SupertonicSynthesizer.inAssets.
        noCompress += "maml"
    }
}
dependencies {
    // Text-to-speech is Supertonic 3 on :library:ml, our own Vulkan compute runtime. Four networks
    // for all 31 languages, every one of them on the GPU: the duration predictor, the text encoder,
    // a flow-matching sampler and a ConvNeXt vocoder. The bundle ships in `assets/supertonic/` and
    // is streamed from a file descriptor, which is what `noCompress += "maml"` above is for.
    implementation(project(":library:ml"))
    // Speech-to-text is whisper-base int8 ONNX (bundled in assets, see WhisperOnnxEngine).
    // It is not ncnn: onnx2ncnn has no DynamicQuantizeLinear/MatMulInteger/ConvInteger
    // support, and the AAR's Whisper expects a six-net decomposition HF does not export.
    //
    // Reduced ORT build (10 MB native, vs 28 MB for the full one). Its operator set is
    // generated from the two bundled .onnx files, so if they are ever re-exported with
    // different ops the session will fail to create - regenerate the AAR, don't work around it.
    implementation(libs.onnxruntime.reduced)
    // No `:library:downloadservice` and no DataStore: both models ship in the APK, so this app
    // downloads nothing and stores no preferences. They went when Piper's 1,834 MB of voices did.
}
