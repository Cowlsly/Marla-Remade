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

}

dependencies {

    // room
    implementRoom(libs)
    implementation(project(":library:room"))

    // adaptive navigation
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    // display images
    implementation(project(":library:image"))

    // ai: this repo's own Vulkan runtime, which replaced com.google.ai.edge.litertlm and
    // its 19.83 MB liblitertlm_jni.so. The weights are the same Gemma 4 E2B, converted to
    // .maml by scripts/ml/maml_convert.py.
    implementation(project(":library:ml"))
    // ToolRegistry reflects over AssistantToolSet's @Tool methods. This used to arrive
    // transitively through the litertlm AAR, so removing that dependency took it away.
    implementation(libs.kotlin.reflect)
    // Was pinned to 1.11.0 only because litertlm 0.14.0 needed close$default on SendChannel.
    // Kept because other code in this module now uses it directly; drop it if that changes.
    implementation(libs.kotlinx.coroutines.android)

    implementation(project(":library:downloadservice"))
}