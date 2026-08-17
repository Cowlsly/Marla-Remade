plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:youpipe:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "play_arrow"
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// YouTube (InnerTube) extractor — a Rust port of PipePipeExtractor. See youpipe/src/main/rust/.
rustNativeLib("youpipe_extractor", "youpipe")

android {
    defaultConfig {
        versionCode = 20260816
        versionName = "v2.6.7"
        applicationId = "com.vayunmathur.youpipe"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(project(":library:image"))
    implementation(project(":youpipe:extractor"))
    implementation(libs.quickjs.kt)
    implementation(libs.androidx.webkit)

    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.session)
    // DefaultHttpDataSource (HttpURLConnection) for progressive; SABR via library:network – no okhttp

    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":library:work"))

    // Android-only network – HttpURLConnection + own WS; no direct okhttp dep
    implementation(project(":library:network"))

}