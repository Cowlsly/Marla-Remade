plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:solitaire:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "playing_cards"
}

android {
    defaultConfig {
        versionCode = 20260816
        versionName = "v2.6.7"
        applicationId = "com.vayunmathur.games.solitaire"
    }
}

dependencies {
    implementation(project(":sdk:games"))
}
