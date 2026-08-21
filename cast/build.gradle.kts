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
    // The IPC contract, shared with every app that streams its content through here. Depended on
    // rather than duplicated, which is the one thing FamilyLocationProtocol gets wrong.
    implementation(project(":sdk:cast"))
}

// No Rust crate. The protocol module already reaches ML-KEM through :library:e2ee-p2p, and
// everything left here is Android platform work: MediaProjection, MediaCodec, sockets.
