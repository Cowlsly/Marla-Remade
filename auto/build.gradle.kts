plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}
launcherIcon {
    symbol = "directions_car"
}
android {
    defaultConfig {
        applicationId = "com.vayunmathur.auto"
    }
    packaging {
        resources {
            // protoc copies every .proto it sees into the jar's resources, so without this
            // the APK carries our two schemas plus protobuf's well-known types - about
            // 125 KB of source that nothing reads. protobuf-lite never looks at a .proto at
            // runtime; only full-protobuf descriptor reflection would, and we do not use it.
            // Note :appstore has the same leak (22 files) and is left alone here: fixing it
            // repo-wide belongs in common-conventions-app, not in this app's build file.
            excludes += setOf("**/*.proto", "*.proto")
        }
    }
}
dependencies {
    // The GAL wire format. Nothing in the app re-implements it, and keeping it out of here is
    // what lets the handshake be tested on the JVM.
    implementation(project(":auto:protocol"))
}
