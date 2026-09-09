plugins {
    id("common-conventions-library")
}
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

dependencies {
    implementation(libs.androidx.compose.foundation)

    // For androidx.lifecycle.compose.LocalLifecycleOwner, which drives the frame loop's
    // ON_START/ON_STOP reconciliation. Scoped to this module rather than the convention
    // plugin: one consumer does not justify reconfiguring every module.
    implementation(libs.androidx.lifecycle.runtime.compose)

    // The renderer's HTTP transport, and now also ConnectivityMonitor (package
    // com.vayunmathur.library.util), which drives live online/offline into the renderer.
    // The Rust crate does not open sockets: it calls back through library/jni-http into
    // NativeHttpBridge here, so pmtiles range requests keep library:network's reduced CA
    // bundle and HttpURLConnection-only policy, and the renderer gains no second TLS stack.
    //
    // Deliberately not :library — that would drag Room, DataStore, Navigation 3 and
    // :sdk:games onto this module's compile classpath for one object.
    implementation(project(":library:network"))

    // Own GeoPoint/GeoBounds – this module has no third-party geometry dependency.

    // :library:image is deliberately gone: it existed to fetch and decode CARTO raster
    // PNGs, and there is no raster tile path any more.

    // The on-device screenshot harness (`src/androidTest`), which is the only way to see what
    // the renderer draws: Vulkan needs a real GPU, so the host probes can only measure the CPU
    // pipeline. These are the only androidTest dependencies in the repo; the coordinates live
    // in the version catalog with the rest of the repo's dependencies.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.androidx.compose.foundation)
    // Supplies the bare `ComponentActivity` the harness hosts the map in.
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}

// The Vulkan vector-tile renderer: `library/map/src/main/rust`, on ash.
//
// arm64 only, which is what the seven consumer apps build
// (`common-conventions-app.gradle.kts:131-133`). `libvulkan.so` is on the device, so this
// .so is our own code and nothing else — the whole size argument against bundling Dawn.
//
// `-PemulatorAbi=x86_64` additionally builds x86_64 so the renderer can be run in an
// emulator on an x86_64 host. Vulkan cannot be verified on a build host, and an arm64
// system image on such a host means full-system QEMU translation, so this is the only
// practical way to exercise the GPU path here. Never on by default.
//
// Shaders are GLSL compiled to SPIR-V by the crate's own build.rs, which resolves glslc
// from the NDK and fails the build if it cannot — deliberately unlike
// games/voxels/src/main/rust/build.rs, which writes a 28-byte stub and then draws nothing.
rustNativeLib(
    "map_renderer",
    "map",
    extraAbis = if (providers.gradleProperty("emulatorAbi").orNull == ABI_X86_64) {
        listOf(ABI_X86_64)
    } else {
        emptyList()
    },
)
