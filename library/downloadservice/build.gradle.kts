plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(project(":library"))
    // WorkManager: the download runs in ModelDownloadWorker so it outlives the gating screen.
    implementation(project(":library:work"))
}
