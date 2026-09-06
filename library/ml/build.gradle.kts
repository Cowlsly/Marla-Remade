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

// The Vulkan compute ML runtime: `library/ml/src/main/rust`, on ash.
//
// No `externalNativeBuild` and no `ndkVersion`: there is no C++ or CMake in this repo
// (CMake was deliberately removed — see maps/build.gradle.kts:29-31), so cargo does the
// cross-compile and the NDK is only reached for its clang wrappers and glslc.
//
// Shaders are GLSL compute compiled to SPIR-V by the crate's own build.rs, which resolves
// glslc from the NDK and fails the build if it cannot.
// `-PemulatorAbi=x86_64` additionally cross-compiles for the emulator's ABI so the gate can be
// exercised on an x86_64 host. Off unless asked for, so no default or release build is affected.
rustNativeLib(
    "modelrunner",
    "ml",
    extraAbis = if (providers.gradleProperty("emulatorAbi").orNull == ABI_X86_64) {
        listOf(ABI_X86_64)
    } else {
        emptyList()
    },
)
