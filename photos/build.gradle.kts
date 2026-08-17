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
        versionCode = 20260816
        versionName = "v2.6.7"
        applicationId = "com.vayunmathur.photos"
    }
    androidResources {
        // ncnn model files (face/segmentation here, OCR via :library:ocr) are
        // bundled in this app's assets and their paths passed to the wrappers;
        // the AAR ships none.
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
    // Semantic photo search now delegates image/text embedding to the
    // OpenAssistant app via this thin cross-app client (no on-device CLIP).
    implementation(project(":sdk:openassistant"))
}
