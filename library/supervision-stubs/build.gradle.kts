plugins {
    id("common-conventions-library")
}
// Compile-only stubs for the framework's @SystemApi supervision (parental controls) classes,
// `android.app.supervision.*`. They exist at runtime on a MAOS build but are absent from the
// public SDK. Depend on this with `compileOnly`; it must NOT be packaged into any APK.
//
// Only the surface :safefamily actually calls is declared. A stub is a compile-time promise
// about the runtime class, and every member here is one more thing that has to stay true.
//
// Signatures are copied verbatim from
//   frameworks/base/core/java/android/app/supervision/SupervisionAppService.java
//   frameworks/base/core/java/android/app/supervision/SupervisionManager.java
//   frameworks/base/core/java/android/app/supervision/Policy.java
//   frameworks/base/core/java/android/app/supervision/PolicyKey.java
//   frameworks/base/core/java/android/app/supervision/PackageUsagePolicy.java
// in the MAOS tree. Three things here are load-bearing beyond the names:
//
//  1. `SupervisionAppService.onBind` is FINAL upstream. It is final here too. Were it not, a
//     subclass could override it, and overriding a final method is a runtime failure
//     (IncompatibleClassChangeError), not a compile error.
//  2. `Policy.Builder<P, B>` is generic and `PackageUsagePolicy.Builder` does NOT override
//     `build()`. The generics are mirrored exactly so javac emits the same
//     `invokevirtual Policy$Builder.build()Policy` + checkcast that it would against the real
//     SDK. Flattening the hierarchy and giving Builder its own `build()` compiles fine and
//     throws NoSuchMethodError on device.
//  3. The constant VALUES matter as much as the signatures: javac inlines `static final`, so a
//     wrong number or string here is silently baked into the APK. TYPE_ALLOWED/BLOCKED/
//     TIME_LIMIT are 0/1/2 and ACTION_SUPERVISION_APP_SERVICE is the exact action the system's
//     SupervisionAppServiceFinder queries for.
