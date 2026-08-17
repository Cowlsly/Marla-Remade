plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "location_on"
}

android {
    defaultConfig {
        versionCode = 20260816
        versionName = "v2.6.7"
        applicationId = "com.vayunmathur.maps"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native offline routing engine + traffic MVT tile encoder (Rust). See
// maps/src/main/rust/. Replaces the previous CMake/C++ libofflinerouter.
rustNativeLib("offlinerouter", "maps")

dependencies {
    implementation(libs.maplibre.compose)
    implementation(project(":library:image"))
    // MapTileCache installs a MapLibre ModuleProvider whose HttpRequest runs on
    // library:network, so the map stack never touches MapLibre's bundled OkHttp
    // implementation.
    implementation(project(":library:network"))

    implementation(project(":library:downloadservice"))

    // Android Auto (P12): AndroidX Car App Library. `app` provides the
    // CarAppService/Session/Screen/template model; `app-projected` provides the
    // phone-projected (Android Auto) host connection. Coordinates are declared
    // inline (rather than via the version catalog) to keep this phase confined
    // to maps/. Only pulled into the car code path — the phone UI is untouched.
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
}
