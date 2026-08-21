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

// No Rust crate, unlike :share. CastV2 needs a TLS client socket and the root Cargo.toml
// forbids network stacks in Rust, so the socket has to be Kotlin either way - and once it
// is, CastMessage's seven scalar protobuf fields are cheaper to hand-roll in Kotlin than
// to bridge over JNI. See cast/src/main/java/.../network/CastMessageCodec.kt.
