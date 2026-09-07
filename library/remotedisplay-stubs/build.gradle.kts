plugins {
    id("common-conventions-library")
}

// Compile-only stubs for `com.android.media.remotedisplay.*`, the remote display
// provider API. Unlike `:library:euicc-stubs` and `:library:backup-stubs`, these are
// not hidden framework classes: they are a separate Java *shared library*
// (`com.android.media.remotedisplay`, declared by
// build/make/target/product/generic/Android.bp), so the app must also carry a
// `<uses-library>` tag for the real classes to be on its classpath at runtime. This
// module is depended on with `compileOnly` and must NOT be packaged into any APK.
//
// Only the members `MaRemoteDisplayProvider` touches are declared, following
// euicc-stubs. Volume handling in particular is deliberately absent: nothing here does
// volume control, and every `static final int` in a stub is inlined by javac, so an
// unused constant is a wrong value waiting to be baked into the APK.
