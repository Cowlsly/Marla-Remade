plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "cast"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.cast"
    }
}

dependencies {
    // The wire format, shared verbatim with the TV receiver. Nothing here re-implements it.
    implementation(project(":cast:protocol"))
}

// No Rust crate. The protocol module already reaches ML-KEM through :library:e2ee-p2p, and
// everything left here is Android platform work: MediaProjection, MediaCodec, sockets.
