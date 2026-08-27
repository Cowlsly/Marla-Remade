import org.gradle.api.provider.Property

/** arm64-v8a: every phone/tablet target and the Apple-Silicon emulator. */
const val ABI_ARM64 = "arm64-v8a"

/** armeabi-v7a: 32-bit-userspace devices, notably Google TV boxes. */
const val ABI_ARMV7 = "armeabi-v7a"

/**
 * 64-bit x86, which no shipping device uses — it is what the Android emulator runs on an
 * x86_64 host.
 *
 * No app should package this: `arm64-v8a` covers every phone and tablet, and on Apple
 * Silicon the emulator is arm64 too. It exists so a native module can be *tested* in an
 * emulator on an x86_64 host, where the alternative is a full-system QEMU translation of an
 * arm64 image. Opt in per module through `rustNativeLib`'s `extraAbis`, and gate it on a
 * Gradle property so release builds never carry it.
 */
const val ABI_X86_64 = "x86_64"

/**
 * Per-app ABI opt-in, consumed by `common-conventions-app`. Apps stay arm64-only
 * unless they declare otherwise:
 *
 * ```
 * nativeAbis { armv7 = true }
 * ```
 */
interface NativeAbisExtension {
    /**
     * Also package [ABI_ARMV7]. Needed by apps that must run on 32-bit-userspace
     * devices: the onn 4K Streaming Box and most Google TV hardware report only
     * `armeabi-v7a,armeabi` in `ro.product.cpu.abilist`, so an arm64-only APK is
     * rejected at install with "Could not find build of variant which supports
     * density N and an ABI in armeabi-v7a, armeabi".
     *
     * Native (Rust) dependencies must opt in separately via `rustNativeLib`'s
     * `extraAbis`, since `abiFilters` only filters what was built - it cannot
     * conjure an .so for an ABI that was never cross-compiled.
     */
    val armv7: Property<Boolean>
}
