plugins {
    id("common-conventions-library")
}

dependencies {
    api(libs.androidx.work.runtime.ktx)
    implementation(project(":library"))
}
