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
        // ncnn model files (the two face models here, OCR via :library:ocr) are
        // bundled in this app's assets and their paths passed to the wrappers;
        // the AAR ships none.
        //
        // The TinyCLIP .onnx is read straight out of the APK by ClipEmbedder, so leave it
        // uncompressed: int8 weights barely deflate, and a compressed asset would have to be
        // inflated into a 24 MB heap buffer before ORT could open it.
        noCompress += "onnx"
        // u2netp.vkml is read straight out of the APK by SubjectSegmenter for the same
        // reason: fp16 weights barely deflate, and a compressed asset would have to be
        // inflated into a 2.2 MB heap buffer before it could be uploaded.
        noCompress += "vkml"
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
    // On-device face detection (SCRFD) and face embedding (MobileFaceNet) run on ncnn
    // via the generalist AAR (BSD-3, no ONNX Runtime / Play Services / MediaPipe). OCR
    // uses it via :library:ocr.
    //
    // Subject segmentation left: U²-Net now runs on :library:ml, our own Vulkan compute
    // runtime. The two face models are transformer-adjacent enough that porting them is a
    // later phase, so ncnn stays for now.
    implementation(libs.ncnn.android)
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
