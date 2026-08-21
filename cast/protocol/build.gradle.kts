plugins {
    id("common-conventions-library")
}

// Deliberately NOT common-conventions-jvm, even though every test here runs on the JVM:
// SecretSealing reaches ML-KEM through :library:e2ee-p2p, whose implementation is a Rust
// .so, so this has to be an Android library to consume it. Nothing else in the module
// touches the Android framework, which is what keeps the round-trip suite host-runnable.
//
// No `defaultConfig {` block: release.sh sed-injects versionCode/versionName after that
// literal in every build.gradle.kts it finds, and a library has no such properties.

dependencies {
    // Hkdf: the whole key schedule expands from one 32-byte session secret.
    implementation(project(":library"))
    // Pqc.encryptTo / PqcIdentity.decrypt, used only by SecretSealing.
    implementation(project(":library:e2ee-p2p"))
}
