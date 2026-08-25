plugins {
    id("common-conventions-app")
}

launcherIcon {
    symbol = "local_taxi"
}

android {
    defaultConfig {
        versionCode = 20260825
        versionName = "v2.6.8"
        applicationId = "com.vayunmathur.taxi"
    }
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:map"))
    implementation(project(":library:image"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
