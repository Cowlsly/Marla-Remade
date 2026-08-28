plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:openassistant:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "robot_2"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.openassistant"
    }

    packaging {
        jniLibs {
            pickFirsts.add("**/libLiteRtTopKOpenClSampler.so")
        }
    }
}

dependencies {

    // room
    implementRoom(libs)
    implementation(project(":library:room"))

    // adaptive navigation
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    // display images
    implementation(project(":library:image"))

    // ai
    implementation(libs.litertlm.android)
    // litertlm 0.14.0 needs kotlinx-coroutines 1.11.0 (close$default on the
    // SendChannel interface); requesting it directly wins over the transitive 1.9.0.
    implementation(libs.kotlinx.coroutines.android)

    implementation(project(":library:downloadservice"))
}