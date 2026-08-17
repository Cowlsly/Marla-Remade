plugins {
    id("common-conventions-app")
    // No metadata convention. The viewfinder is the screen, and Layoutlib cannot open a
    // camera, so there is nothing a Compose preview could capture — with no SurfaceRequest
    // the preview falls back to a black Box. The listing images are hand-captured and
    // committed under metadata_data/photos/, which release.sh picks up directly.
}

launcherIcon {
    symbol = "photo_camera"
}

android {
    defaultConfig {
        versionCode = 20260816
        versionName = "v2.6.7"
        applicationId = "com.vayunmathur.camera"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native panorama stitcher + night burst aligner (Rust). See camera/src/main/rust/.
rustNativeLib("camera_stitch", "camera")

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.androidx.exifinterface)
    implementation(libs.zxing.core)
    // On-device portrait segmentation via ncnn (Tencent, BSD-3, CPU-only), forked AAR.
    implementation(libs.ncnn.android)
}
