plugins {
    id("common-conventions-app")
}

launcherIcon {
    symbol = "terminal"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.logviewer"
    }
}

// No dependencies of its own. Everything the app needs beyond :library and :library:ui is either
// public framework API or, for the handful of header fields that are not, reached reflectively
// with a null fallback - see platform/HiddenFrameworkApi.kt for why a compileOnly stubs module in
// the style of :library:euicc-stubs would be the wrong shape here.
