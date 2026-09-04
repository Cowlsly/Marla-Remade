plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "compare"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.mapcompare"
    }
}

dependencies {
    implementation(project(":library:map"))
    implementation(libs.maplibre.compose)
    implementation(project(":library:network"))
    implementation(project(":library:downloadservice"))
}
