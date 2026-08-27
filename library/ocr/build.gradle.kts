plugins {
    id("common-conventions-library")
}
dependencies {
    // On-device OCR via PP-OCRv5 on the Vulkan compute runtime in `:library:ml`. Both
    // models ship as `.maml` in this module's assets and the whole detect-crop-recognise
    // pipeline is Rust; consumers only see the OcrEngine API.
    //
    // `implementation` is enough even though `:library:ml` supplies `libmodelrunner.so`:
    // an Android library's jniLibs are packaged into the consuming APK transitively, and
    // OcrEngine maps `RecognizedLine` into its own TextBox rather than re-exporting it.
    implementation(project(":library:ml"))
    implementation(libs.kotlinx.coroutines.android)
}
