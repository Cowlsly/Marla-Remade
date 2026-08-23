plugins {
    id("common-conventions-app")
    // No preview-metadata convention. Every screen here is either a full-screen decoder surface or
    // a TV-shaped idle screen, and Layoutlib can render neither into a *phone* listing image -
    // release.sh writes previews into a hardcoded graphics/phone-screenshots. `camera` and
    // `games/voxels` set the precedent for opting out. metadata_data/cast-tv.md still exists, which
    // is what makes release.sh generate a listing at all.
}

launcherIcon {
    symbol = "cast"
}

// Google TV hardware (the onn 4K Streaming Box and friends) runs a 32-bit userspace and
// reports only `armeabi-v7a,armeabi`, so an arm64-only APK is rejected at install time.
nativeAbis {
    armv7 = true
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.cast.tv"
    }
}

dependencies {
    // The wire format, shared verbatim with the phone. Nothing in this app re-implements it.
    implementation(project(":cast:protocol"))
    // PqcIdentity: the TV's long-term ML-KEM identity, which the phone seals the session secret to.
    implementation(project(":library:e2ee-p2p"))
}
