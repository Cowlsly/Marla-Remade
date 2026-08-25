plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "picture_as_pdf"
}

android {
    defaultConfig {
        versionCode = 20260825
        versionName = "v2.6.8"
        applicationId = "com.vayunmathur.pdf"
    }
}

androidComponents {
    onVariants { variant ->
        val rustDir = layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        variant.sources.jniLibs?.addStaticSourceDirectory(rustDir)
        // Note: if cargoBuild fails, stale .so could be packaged (audit #16). We rely on
        // cargoBuild task's outputs.file check and the fact that RustNative.kt's per-ABI Exec
        // task validates inputs/outputs. A doFirst delete of stale .so was considered but
        // addStaticSourceDirectory vs addGeneratedSourceDirectory migration is tracked separately.
        // For now, ensure cargoNdkBuild task deletes stale outputs on failure via its own doFirst/doLast
        // in RustNative.kt (see that file for stale .so cleanup logic that should be added there).
    }
}

// Native memory-safe PDF renderer (Rust + lopdf). See pdf/src/main/rust/.
rustNativeLib("pdf_render", "pdf")

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(project(":library:image"))
    implementation(project(":library:ocr"))
}
