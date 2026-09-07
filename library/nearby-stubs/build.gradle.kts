plugins {
    id("common-conventions-library")
}

// Compile-only stub for the framework's @SystemApi `android.nearby.NearbyManager`. The real
// class ships in the Connectivity mainline module (`com.android.tethering`) and is present at
// runtime, but `android.nearby` is absent from the public SDK entirely — there is not one
// `android/nearby/*.class` in compileSdk 37's android.jar. So this is depended on with
// `compileOnly` and must NOT be packaged into any APK.
//
// Only the powered-off finding surface is declared. The rest of NearbyManager (scanning,
// broadcasting, offload capability) is deliberately absent: a stub is a compile-time promise
// about the runtime class, and every method here is one more thing that has to stay true.
//
// Signatures are copied from
// packages/modules/Connectivity/nearby/framework/java/android/nearby/NearbyManager.java in the
// MAOS tree. If they drift, the failure is a runtime NoSuchMethodError, not a build error —
// nothing in this repo can check them for us.
