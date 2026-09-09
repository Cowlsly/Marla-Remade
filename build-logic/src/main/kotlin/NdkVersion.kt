/**
 * The single NDK version used by every module that compiles native code.
 *
 * AGP's `ndkVersion` (apps, wear) and the Rust cross-compile toolchain paths in
 * [rustNativeLib] must name the same installed NDK, or cargo links against a different
 * toolchain than the one AGP builds the rest of the app with.
 */
const val NDK_VERSION = "29.0.14206865"
