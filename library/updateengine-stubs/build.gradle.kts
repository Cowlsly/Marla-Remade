plugins {
    id("common-conventions-library")
}

// Compile-only stubs for the framework's @SystemApi `android.os.UpdateEngine` and
// `android.os.UpdateEngineCallback` — the A/B slot writer that applies an OTA payload.
//
// Both are `@SystemApi`, so they exist at runtime on a MAOS build but are absent from the
// public SDK. Depend on this with `compileOnly`; it must NOT be packaged into any APK.
//
// Only the surface :updater actually calls is declared. A stub is a compile-time promise about
// the runtime class, and every method here is one more thing that has to stay true.
//
// Signatures are copied verbatim from
//   frameworks/base/core/java/android/os/UpdateEngine.java
//   frameworks/base/core/java/android/os/UpdateEngineCallback.java
// in the MAOS tree. If they drift, the failure is a runtime NoSuchMethodError at the moment an
// update is applied — the worst possible time, and nothing in this repo can catch it for us.
// The constant VALUES matter as much as the signatures: javac inlines `static final int`, so a
// wrong number here is silently baked into the APK.
