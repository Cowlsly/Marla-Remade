plugins {
    id("common-conventions-library")
    alias(libs.plugins.protobuf)
}
// The Google Automotive Link (GAL) wire protocol: framing, TLS, channel multiplexing and the
// generated protobuf. Deliberately free of Android framework calls so the whole handshake and
// every codec round-trip is host-runnable, the same split :cast:protocol uses.
//
// An Android library rather than common-conventions-jvm because the credential loader reads the
// PEMs from this module's assets.
//
// No `defaultConfig {` block: release.sh sed-injects versionCode/versionName after that
// literal in every build.gradle.kts it finds, and a library has no such properties.
dependencies {
    implementation(project(":library"))
    implementation(libs.protobuf.javalite)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // `create`, not `named` as in :youpipe:extractor: the Android path does not
                // pre-register the java builtin, so it has to be added here.
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
