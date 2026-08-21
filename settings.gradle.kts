pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Local maven first so freshly built ncnn-android:1.4.0 (Small100) resolves
        // even when JitPack is rate-limited (429) or hasn't yet built the tag.
        mavenLocal()
        google()
        mavenCentral()
        // JitPack for Stockfish-Library (games:chess) and ncnn-android
        maven("https://jitpack.io")
    }
}

rootProject.name = "apps"
include(":lint-rules")
include(":library")
include(":library:network")
include(":library:biometric")
include(":library:downloadservice")
include(":library:ui")
include(":library:room")
include(":library:ink")
include(":library:e2ee-p2p")
include(":library:widgets")
include(":library:work")
include(":library:ocr")
include(":library:map")
include(":library:image")
include(":calendar")
include(":contacts")
include(":pdf")
include(":openassistant")
include(":passwords")
include(":notes")
include(":flashcards")
include(":files")
include(":photos")
include(":health")
include(":youpipe")
include(":youpipe:extractor")
include(":findfamily")
include(":maps")
include(":games:chess")
include(":games:unblockjam")
include(":games:wordmaker")
include(":music")
include(":clock")
include(":games:alchemist")
include(":games:pipes")
include(":games:solitaire")
include(":games:logicgate")
include(":games:voxels")
include(":games:hub")
include(":email")
include(":weather")
include(":camera")
include(":sdk:openassistant")
include(":sdk:games")
include(":things")
include(":office")
include(":tools:holidaygen")
include(":education")
include(":everysync")
include(":travel")
include(":astronomy")
include(":translate")
include(":calculator")
include(":code")
include(":keyboard")
include(":speech")
include(":vpn")
include(":web")
include(":appstore")
include(":library:locationprovider")
include(":library:euicc-stubs")
include(":library:backup-stubs")
include(":networklocation")
include(":fooddelivery")
include(":musicbrainz")
include(":measure")
include(":taxi")
include(":communicate")
include(":euicc")
include(":backup")
include(":share")
include(":launcher")
include(":cast")
include(":cast:protocol")
include(":cast:tv")

// Personal / private app modules live under personal/ (gitignored). Included only
// when present so the public repo still configures without them.
if (file("personal/dooraccess").exists()) {
    include(":personal:dooraccess")
}
if (file("personal/amazon").exists()) {
    include(":personal:amazon")
}
if (file("personal/kaiser").exists()) {
    include(":personal:kaiser")
}
