//! Compiles the GLSL in `shaders/` to SPIR-V with the NDK's `glslc`.
//!
//! # This deliberately does not do what `games/voxels/build.rs` does
//!
//! That build script (`build.rs:94-106`) resolves `glslc` from two hardcoded
//! absolute paths under one developer's home directory, and when it finds neither
//! it calls `minimal_vert_spv()` / `minimal_frag_spv()` — writing a **28-byte stub**
//! consisting of a SPIR-V header and a single `OpCapability`. A build that takes
//! that path succeeds, installs, runs, and draws nothing at all, with no warning
//! anywhere. The failure surfaces as "the screen is black" long after the build.
//!
//! So this script resolves the compiler from the environment for the *host*
//! platform it is running on, and [`panic!`]s if it cannot find it or if a shader
//! fails to compile. A missing shader compiler must stop the build.
//!
//! SPIR-V is `include_bytes!`d rather than shipped as an asset: the shaders are
//! part of the renderer, and an asset can be missing at run time while a linked
//! `&[u8]` cannot.

use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;

fn main() {
    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    let shader_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"))
        .join("shaders");

    let glslc = find_glslc();
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=shaders");

    let mut compiled = 0;
    let mut entries: Vec<PathBuf> = std::fs::read_dir(&shader_dir)
        .unwrap_or_else(|e| panic!("cannot read {}: {e}", shader_dir.display()))
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| {
            matches!(
                p.extension().and_then(|e| e.to_str()),
                Some("vert") | Some("frag") | Some("comp")
            )
        })
        .collect();
    // Sorted so the build is reproducible and a diff of the log is stable.
    entries.sort();

    for shader in &entries {
        let name = shader.file_name().expect("shader file name");
        println!("cargo:rerun-if-changed={}", shader.display());
        let target = out_dir.join(format!("{}.spv", name.to_string_lossy()));

        let output = Command::new(&glslc)
            .arg("--target-env=vulkan1.1")
            .arg("-O")
            .arg("-o")
            .arg(&target)
            .arg(shader)
            .output()
            .unwrap_or_else(|e| panic!("cannot run {}: {e}", glslc.display()));

        if !output.status.success() {
            panic!(
                "glslc failed on {}:\n{}{}",
                shader.display(),
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr),
            );
        }
        // A zero-exit that wrote nothing usable is the silent-stub case, caught here
        // rather than at run time on a black screen.
        let written = std::fs::metadata(&target).map(|m| m.len()).unwrap_or(0);
        if written < MIN_SPIRV_BYTES {
            panic!(
                "glslc produced {written} bytes for {}; a real SPIR-V module is at least \
                 {MIN_SPIRV_BYTES} bytes",
                shader.display(),
            );
        }
        compiled += 1;
    }

    if compiled == 0 {
        panic!("no shaders found in {}", shader_dir.display());
    }
}

/// A SPIR-V module is a five-word header plus at least a few instructions.
const MIN_SPIRV_BYTES: u64 = 64;

/// `glslc` for the **host** platform, from the NDK.
///
/// Cargo runs this script on the build machine while cross-compiling for Android,
/// so the host triple is what matters, not the target. Searched in order:
///
/// 1. `GLSLC` — an explicit override, for a CI image that puts it elsewhere.
/// 2. `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT` — set directly by some CI setups.
/// 3. `ANDROID_HOME` / `ANDROID_SDK_ROOT`, then the highest installed `ndk/*`.
/// 4. `sdk.dir` from the repo's `local.properties`, which is what a dev box has.
/// 5. `glslc` on `PATH`.
fn find_glslc() -> PathBuf {
    if let Some(explicit) = env::var_os("GLSLC").map(PathBuf::from) {
        if explicit.is_file() {
            return explicit;
        }
        panic!("GLSLC is set to {explicit:?} but that is not a file");
    }

    let host = host_dir();
    let executable = if cfg!(windows) { "glslc.exe" } else { "glslc" };

    let mut ndks: Vec<PathBuf> = Vec::new();
    for key in ["ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"] {
        if let Some(dir) = env::var_os(key) {
            ndks.push(PathBuf::from(dir));
        }
    }
    let mut sdks: Vec<PathBuf> = Vec::new();
    for key in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        if let Some(dir) = env::var_os(key) {
            sdks.push(PathBuf::from(dir));
        }
    }
    if let Some(dir) = sdk_dir_from_local_properties() {
        sdks.push(dir);
    }
    for sdk in &sdks {
        // Highest version first, so a box with several NDKs uses the newest.
        let mut installed: Vec<PathBuf> = std::fs::read_dir(sdk.join("ndk"))
            .into_iter()
            .flatten()
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| p.is_dir())
            .collect();
        installed.sort();
        installed.reverse();
        ndks.extend(installed);
    }

    for ndk in &ndks {
        let candidate = ndk.join("shader-tools").join(&host).join(executable);
        if candidate.is_file() {
            return candidate;
        }
    }

    if Command::new(executable).arg("--version").output().map(|o| o.status.success()).unwrap_or(false) {
        return PathBuf::from(executable);
    }

    panic!(
        "glslc not found. Looked for ndk/*/shader-tools/{host}/{executable} under \
         {sdks:?} and {ndks:?}, then on PATH. Install the NDK, or set GLSLC. The map \
         renderer cannot draw without shaders, so this build stops here rather than \
         producing an app with an empty map.",
    );
}

/// The NDK's prebuilt-tool directory name for the host we are running on.
fn host_dir() -> String {
    let os = if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "darwin"
    } else {
        "linux"
    };
    // The NDK ships x86_64 host tools even on arm64 macOS, where they run under
    // Rosetta, so the host CPU is deliberately not consulted.
    format!("{os}-x86_64")
}

/// `sdk.dir` out of the repo root's `local.properties`.
///
/// Walks up from the crate rather than assuming a depth, so moving the crate does
/// not silently break shader compilation.
fn sdk_dir_from_local_properties() -> Option<PathBuf> {
    let mut dir: &Path = &PathBuf::from(env::var("CARGO_MANIFEST_DIR").ok()?);
    loop {
        let candidate = dir.join("local.properties");
        if candidate.is_file() {
            let text = std::fs::read_to_string(&candidate).ok()?;
            for line in text.lines() {
                let line = line.trim();
                if let Some(value) = line.strip_prefix("sdk.dir=") {
                    // Gradle properties escape the Windows separator.
                    return Some(PathBuf::from(value.replace("\\\\", "\\").replace("\\:", ":")));
                }
            }
            return None;
        }
        dir = dir.parent()?;
    }
}
