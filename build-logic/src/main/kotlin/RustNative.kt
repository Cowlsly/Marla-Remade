import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Exec
import org.gradle.internal.os.OperatingSystem
import org.gradle.kotlin.dsl.register
import java.util.Properties

/**
 * Shared build service that caps concurrent cargoBuild executions to 1.
 * This prevents parallel invocations of `rustup toolchain install` triggered
 * implicitly by cargo when rust-toolchain.toml pins 1.97.0, which is not
 * concurrency-safe and produces "Directory not empty (os error 39)",
 * "cargo not applicable", "failed to install component bin/rust-gdb" errors.
 */
abstract class RustToolchainLock : BuildService<BuildServiceParameters.None>

/**
 * An Android ABI paired with the Rust target triple and the NDK clang-wrapper prefix
 * used to cross-compile for it.
 *
 * @param clangPrefix deliberately separate from [triple]: for armeabi-v7a the NDK ships
 *   `armv7a-linux-androideabi<api>-clang` (note the `a`) while the Rust triple is
 *   `armv7-linux-androideabi`, so deriving one from the other silently misses the wrapper.
 */
data class RustAbi(val abiDir: String, val triple: String, val clangPrefix: String)

private val KNOWN_RUST_ABIS = listOf(
    RustAbi(ABI_ARM64, "aarch64-linux-android", "aarch64-linux-android"),
    RustAbi(ABI_ARMV7, "armv7-linux-androideabi", "armv7a-linux-androideabi"),
    // Emulator-only; see [ABI_X86_64]. Never packaged by default.
    RustAbi(ABI_X86_64, "x86_64-linux-android", "x86_64-linux-android"),
)

/**
 * Registers the per-ABI cargo cross-compile of a Rust cdylib into `build/rustJniLibs`,
 * wired ahead of `preBuild`. This consolidates the ~80 lines of NDK-toolchain + cargo +
 * reproducible-build boilerplate that used to be copy-pasted across every native module
 * (pdf, camera, weather, passwords, e2ee-p2p). The caller still registers
 * `build/rustJniLibs` as a jniLibs source dir in its own `android { }` block.
 *
 * Prereq: `rustup target add` for every triple built (see `rust-toolchain.toml`).
 *
 * @param crate      cargo package lib name → produces `lib<crate>.so`
 * @param remapLabel `--remap-path-prefix` label baked in for reproducible builds
 *                   (defaults to [crate]).
 * @param extraAbis  ABIs to build in addition to [ABI_ARM64]. Opt-in per module so only
 *                   the modules that need a second ABI pay for the extra cargo build;
 *                   pass [ABI_ARMV7] for libraries consumed by an app that sets
 *                   `nativeAbis { armv7 = true }`.
 */
fun Project.rustNativeLib(
    crate: String,
    remapLabel: String = crate,
    extraAbis: List<String> = emptyList(),
) {
    // Serialize cargoBuild across all Rust modules to avoid concurrent rustup installs.
    val rustLock = gradle.sharedServices.registerIfAbsent(
        "rustToolchainLock",
        RustToolchainLock::class.java
    ) {
        maxParallelUsages.set(1)
    }

    val ndkVersionForRust = "29.0.14206865"
    val androidApiLevel = 31

    fun resolveSdkDir(): String =
        System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
                Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir")
            }
            ?: error("Android SDK not found (set ANDROID_HOME or sdk.dir in local.properties)")

    val cargoBin = "${System.getProperty("user.home")}/.cargo/bin"
    val ndkRoot = "${resolveSdkDir()}/ndk/$ndkVersionForRust"
    val hostTag = when {
        OperatingSystem.current().isMacOsX -> "darwin-x86_64"
        OperatingSystem.current().isLinux -> "linux-x86_64"
        OperatingSystem.current().isWindows -> "windows-x86_64"
        else -> error("Unsupported host OS for NDK toolchain")
    }
    val ndkBin = "$ndkRoot/toolchains/llvm/prebuilt/$hostTag/bin"
    val ndkSysroot = "$ndkRoot/toolchains/llvm/prebuilt/$hostTag/sysroot"

    // arm64-v8a always; anything else is opt-in via extraAbis.
    val rustAbis = (listOf(ABI_ARM64) + extraAbis).distinct().map { abiDir ->
        KNOWN_RUST_ABIS.firstOrNull { it.abiDir == abiDir }
            ?: error(
                "rustNativeLib($crate): unknown ABI '$abiDir'. " +
                    "Known: ${KNOWN_RUST_ABIS.joinToString { it.abiDir }}"
            )
    }

    val perAbi = rustAbis.map { abi ->
        val (abiDir, triple) = abi
        tasks.register<Exec>("cargoBuild_${abiDir.replace('-', '_')}") {
            usesService(rustLock)
            description = "Cross-compiles lib$crate for $abiDir."
            workingDir = file("src/main/rust")

            // Windows hosts use the .cmd clang wrappers and .exe tool suffixes; Unix hosts none.
            val isWindows = OperatingSystem.current().isWindows
            val exeExt = if (isWindows) ".exe" else ""
            val clangExt = if (isWindows) ".cmd" else ""
            val clang = "$ndkBin/${abi.clangPrefix}$androidApiLevel-clang$clangExt"
            val clangpp = "$ndkBin/${abi.clangPrefix}$androidApiLevel-clang++$clangExt"
            val linkerVar = "CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER"
            // Per-crate target (may exist from older isolated builds) and workspace root target
            // (current scheme since Cargo.toml workspace unified to root).
            val perCrateSoOut = file("src/main/rust/target/$triple/release/lib$crate.so")
            val workspaceSoOut = rootProject.file("target/$triple/release/lib$crate.so")
            val destSo = layout.buildDirectory.file("rustJniLibs/$abiDir/lib$crate.so").get().asFile

            inputs.dir("src/main/rust/src")
            inputs.file("src/main/rust/Cargo.toml")
            // Root workspace unified (Cargo.toml + Cargo.lock + rust-toolchain.toml)
            inputs.file(rootProject.file("Cargo.toml"))
            inputs.file(rootProject.file("Cargo.lock"))
            inputs.file(rootProject.file("rust-toolchain.toml"))
            // Only rust third_party crates – avoid pulling third_party:nanojson java outputs (was implicit dep failure).
            // jni is a crates.io dependency now, so Cargo.lock above covers it.
            inputs.dir(rootProject.file("third_party/om-file-format-sys/src"))
            inputs.file(rootProject.file("third_party/om-file-format-sys/Cargo.toml"))
            inputs.file(rootProject.file("third_party/om-file-format-sys/build.rs"))
            inputs.dir(rootProject.file("third_party/om-file-format-sys/c"))
            // BetoCore vendored crates (Apache-2.0) — incremental builds track these like om-file-format-sys.
            inputs.dir(rootProject.file("third_party/betocore"))
            outputs.file(destSo)

            val cargoHome = System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
            val rustSrc = file("src/main/rust").absolutePath

            val pathSep = if (isWindows) ";" else ":"
            environment("PATH", "$cargoBin$pathSep${System.getenv("PATH")}")
            // A non-rustup rustc earlier on PATH (e.g. Homebrew's) has no
            // aarch64-linux-android std, so cargo fails with "can't find crate for core".
            // Pin the rustup shim explicitly.
            environment("RUSTC", "$cargoBin/rustc$exeExt")
            // The per-API NDK clang wrapper bakes in --target and the sysroot.
            environment("CC", clang)
            environment("CXX", clangpp)
            environment("AR", "$ndkBin/llvm-ar$exeExt")
            environment("SYSROOT", ndkSysroot)
            environment(linkerVar, clang)
            // Force the system clang for host-side C on Unix; on Windows let cc locate MinGW gcc.
            if (!isWindows) environment("HOST_CC", "/usr/bin/clang")
            // Pre-generated bindings (armv8-only): weather's om-file-format-sys no longer uses bindgen
            // (bindings_android.rs/host checked in), so BINDGEN_EXTRA_CLANG_ARGS is no longer needed for build.
            // Kept as comment for documentation if future C sys crates added.
            // environment("BINDGEN_EXTRA_CLANG_ARGS", "--target=$triple$androidApiLevel --sysroot=$ndkSysroot")
            // Reproducible builds: remap $HOME-specific paths (cargo registry + crate
            // dir) to fixed constants so different machines produce identical .so bytes.
            environment(
                "RUSTFLAGS",
                "--remap-path-prefix=$cargoHome=/cargo --remap-path-prefix=$rustSrc=/$remapLabel",
            )
            environment("CFLAGS", "-ffile-prefix-map=$cargoHome=/cargo -ffile-prefix-map=$rustSrc=/$remapLabel -Wdate-time -Werror=date-time")
            environment("CXXFLAGS", "-ffile-prefix-map=$cargoHome=/cargo -ffile-prefix-map=$rustSrc=/$remapLabel -Wdate-time -Werror=date-time")
            environment("CPPFLAGS", "-ffile-prefix-map=$cargoHome=/cargo -ffile-prefix-map=$rustSrc=/$remapLabel -Wdate-time -Werror=date-time")
            environment("ZERO_AR_DATE", "1")
            // Reproducible builds: respect SOURCE_DATE_EPOCH if set (exported by release.sh / CI)
            // https://reproducible-builds.org/docs/source-date-epoch/
            System.getenv("SOURCE_DATE_EPOCH")?.takeIf { it.isNotBlank() }?.let {
                environment("SOURCE_DATE_EPOCH", it)
            }
            environment("CARGO_INCREMENTAL", "0")

            commandLine("$cargoBin/cargo$exeExt", "build", "--locked", "--release", "--target", triple)

            doLast {
                destSo.parentFile.mkdirs()
                val src = when {
                    perCrateSoOut.exists() -> perCrateSoOut
                    workspaceSoOut.exists() -> workspaceSoOut
                    else -> error("Neither per-crate ${perCrateSoOut.path} nor workspace ${workspaceSoOut.path} exists for $crate")
                }
                src.copyTo(destSo, overwrite = true)
            }
        }
    }

    val cargoNdkBuild = tasks.register("cargoNdkBuild") {
        description = "Builds lib$crate.so for all Android ABIs."
        dependsOn(perAbi)
    }
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(cargoNdkBuild)
    }
}
