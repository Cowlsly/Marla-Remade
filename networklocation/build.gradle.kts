plugins {
    id("common-conventions-app")
    alias(libs.plugins.protobuf)
}

launcherIcon {
    symbol = "my_location"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.networklocation"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native code for this module, loaded as libnetworklocation.so: RANSAC + EM multilateration
// device-position estimation over the cached gs-loc beacon fixes, AND the offline geocoder
// search over geocoder.geodb. Both live in networklocation/src/main/rust/.
rustNativeLib("networklocation")

// The offline databases (geocoder.geodb ~1-1.5 GB, wifi.wpsdb up to ~2 GB, cells.wpsdb far
// smaller) are NOT bundled. Shipping them as assets put a 3.5 GB APK in the MAOS system image,
// which pushed the factory install zip past fastboot's 4 GiB zip limit. They are downloaded to
// device-protected storage on request instead — see OfflineDatabases.kt. Until they arrive every
// lookup misses, which the readers already treat as "no data": beacon lookups fall back to
// gs-loc, and the geocoder reports itself unavailable.

dependencies {
    // Compile-only stubs for the framework's unbundled provider API (com.android.location.provider).
    // Provided at runtime by <uses-library>; must NOT be packaged.
    compileOnly(project(":library:locationprovider"))
    // Apple gs-loc request/response wire format (proto/apple_wps.proto).
    implementation(libs.protobuf.javalite)
    // Beacon-location cache (in-memory TimedLruCache in front of a Room table).
    // No SQLCipher here — the cache holds only public beacon coordinates, so it uses
    // AndroidSQLiteDriver (platform SQLite), which Room 3 requires to be set explicitly.
    implementRoom(libs)
    implementation(libs.androidx.sqlite.framework)
    // Reporting loop + IO for the gs-loc queries.
    implementation(libs.kotlinx.coroutines.android)
    // Resumable, checksummed, unmetered-only fetch of the offline databases, which are no
    // longer bundled (see OfflineDatabases). Also supplies the progress UI on the status screen.
    implementation(project(":library:downloadservice"))
    // The offline geocoder search runs natively (Rust/ruzstd) over geocoder.geodb; there is no
    // Kotlin-side DB code, so no zstd-jni / serialization deps are needed here.
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobufJavalite.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
