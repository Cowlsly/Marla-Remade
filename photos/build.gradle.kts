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
        versionCode = 20260825
        versionName = "v2.6.8"
        applicationId = "com.vayunmathur.photos"
    }
    androidResources {
        // ncnn model files (face/segmentation here, OCR via :library:ocr) are
        // bundled in this app's assets and their paths passed to the wrappers;
        // the AAR ships none.
        //
        // The TinyCLIP .onnx is read straight out of the APK by ClipEmbedder, so leave it
        // uncompressed: int8 weights barely deflate, and a compressed asset would have to be
        // inflated into a 24 MB heap buffer before ORT could open it.
        noCompress += "onnx"
    }
    packaging {
        jniLibs {
            pickFirsts.add("**/libc++_shared.so")
        }
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
    // On-device face detection (SCRFD), face embedding (MobileFaceNet) and
    // subject segmentation (U²-Net) run on ncnn via the generalist AAR (BSD-3,
    // no ONNX Runtime / Play Services / MediaPipe). OCR uses it via :library:ocr.
    implementation(libs.ncnn.android)

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
