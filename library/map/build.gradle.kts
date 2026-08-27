plugins {
    id("common-conventions-library")
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

    // The renderer's HTTP transport. The Rust crate does not open sockets: it calls back
    // through library/jni-http into NativeHttpBridge here, so pmtiles range requests keep
    // library:network's reduced CA bundle and HttpURLConnection-only policy, and the
    // renderer gains no second TLS stack.
    implementation(project(":library:network"))

    // Own GeoPoint/GeoBounds – no spatialk exposure for non-maplibre apps.
    // :maps gets spatialk transitively via maplibre-compose where required.
    // spatialk dependency fully removed from :library:map after migration.

    // :library:image is deliberately gone: it existed to fetch and decode CARTO raster
    // PNGs, and there is no raster tile path any more.
}

// The Vulkan vector-tile renderer: `library/map/src/main/rust`, on ash.
//
// arm64 only, which is what the five consumer apps build
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
