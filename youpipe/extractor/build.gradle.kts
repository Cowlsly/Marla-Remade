plugins {
    id("common-conventions-jvm")
    alias(libs.plugins.protobuf)
}

dependencies {
    // nanojson removed — replaced by kotlinx.serialization.json via common-conventions-jvm
    implementation(libs.google.jsr305)
    implementation(libs.protobuf.javalite)
    implementation(libs.brotli.dec)

    testImplementation(libs.kotlin.test)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }

    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // `named`, not `create` as in :appstore: the JVM path already pre-registers the
                // java builtin, and creating it a second time fails.
                named("java") {
                    option("lite")
                }
            }
        }
    }
}
