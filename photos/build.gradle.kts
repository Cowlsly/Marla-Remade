plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:photos:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "photo_library"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.photos"
    }
    androidResources {
        // The TinyCLIP .onnx is read straight out of the APK by ClipEmbedder, so leave it
        // uncompressed: int8 weights barely deflate, and a compressed asset would have to be
        // inflated into a 24 MB heap buffer before ORT could open it.
        noCompress += "onnx"
        // The three .maml files are read straight out of the APK for the same reason: fp16
        // weights barely deflate, and a compressed asset would have to be inflated into a
        // heap buffer — 6.6 MB for the face embedder — before it could be uploaded.
        noCompress += "maml"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native pixel filters (Rust). See photos/src/main/rust/.
rustNativeLib("photos_fx", "photos")

dependencies {
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.fragment.ktx)
    implementation(project(":library:map"))
    implementation(project(":library:image"))
    implementation(libs.androidx.exifinterface)
    // No ncnn anywhere in this APK. Face detection (SCRFD 500M), face embedding
    // (MobileFaceNet), subject segmentation (U²-Net portable) and PP-OCRv5 text
    // recognition all run on :library:ml, our own Vulkan compute runtime.
    implementation(project(":library:ml"))

    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:ink"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)


    implementation(project(":library:widgets"))
    implementation(project(":library:biometric"))
    implementation(project(":library:ocr"))

    // Semantic photo search runs on-device again: TinyCLIP int8 ships in assets/clip/ and is
    // executed by ClipEmbedder, so search no longer needs the OpenAssistant app installed.
    //
    // Reduced ORT build (10 MB native, vs 28 MB for the full one). Its operator set is
    // generated from the bundled model_int8.onnx, so swapping in a different CLIP export can
    // fail at session creation — regenerate the AAR rather than falling back to the full build.
    implementation(libs.onnxruntime.reduced)
}
