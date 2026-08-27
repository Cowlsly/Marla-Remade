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
rustNativeLib("modelrunner", "ml")
