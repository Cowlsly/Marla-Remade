import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

fun readVersionInfo(): Pair<Int, String> {
    val versionFile = File(rootDir, "version.txt")

    return if (versionFile.exists()) {
        val lines = versionFile.readLines()
        if (lines.size >= 2) {
            val code = lines[0].trim().toIntOrNull() ?: 1
            val name = lines[1].trim()
            code to name
        } else throw IllegalStateException("Invalid version.txt format")
    } else throw IllegalStateException("version.txt not found")
}

val proguardFile
    get() = File(rootDir, "proguard-rules.pro")

val (appVersionCode, appVersionName) = readVersionInfo()

// Adaptive launcher icons are generated at build time from Material Symbols
// instead of committing an ic_launcher_foreground.xml per app. Each app declares
// its symbol via `launcherIcon { symbol = "..." }` (see LauncherIconExtension).
// The symbol path is downloaded from google/material-design-icons pinned at this
// commit for reproducible builds, wrapped in the standard foreground vector, and
// wired into every variant's generated res.
val launcherIcon = extensions.create("launcherIcon", LauncherIconExtension::class.java).apply {
    scale.convention(0.435)
}
val materialSymbolsRef = "819d78680a849ceef4c78f863d8753e3160b7c89"
val materialSymbolsCache = File(gradle.gradleUserHomeDir, "material-symbols-cache")

// Apps are arm64-only unless they opt in with `nativeAbis { armv7 = true }`
// (see NativeAbisExtension). Read in finalizeDsl below, not here.
val nativeAbis = extensions.create("nativeAbis", NativeAbisExtension::class.java).apply {
    armv7.convention(false)
}

extensions.configure<com.android.build.api.variant.ApplicationAndroidComponentsExtension> {
    // Only `dev` and `release` ship. AGP always creates a `debug` build type and
    // forbids removing it, so disable its variants instead — no debug variant means
    // no assembleDebug/compileDebugKotlin tasks are generated. (The `debug` *signing*
    // config is untouched; `release` still falls back to it, and testBuildType = "dev".)
    beforeVariants(selector().withBuildType("debug")) { variant ->
        variant.enable = false
    }
    // abiFilters is a plain Set rather than a lazy Provider, so the opt-in can only be
    // read once the app's own build file has been evaluated — reading nativeAbis.armv7
    // straight from the defaultConfig block below would always observe the convention.
    finalizeDsl { ext ->
        if (nativeAbis.armv7.get()) {
            ext.defaultConfig.ndk.abiFilters.add(ABI_ARMV7)
            ext.buildTypes.getByName("dev").ndk.abiFilters.add(ABI_ARMV7)
        }
    }
    onVariants { variant ->
        val gen = tasks.register(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}LauncherIcon",
            GenerateLauncherIconTask::class.java,
        ) {
            symbol.set(launcherIcon.symbol)
            scale.set(launcherIcon.scale)
            ref.set(materialSymbolsRef)
            cacheDir.set(materialSymbolsCache)
        }
        variant.sources.res?.addGeneratedSourceDirectory(gen, GenerateLauncherIconTask::outputDir)
    }
}

configure<com.android.build.api.dsl.ApplicationExtension> {
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        compose = true
        // Repo-wide DEV_BUILD flag (see buildTypes below). Every app gets a
        // BuildConfig.DEV_BUILD constant: true for assembleDev, false for
        // assembleRelease. Gate experimental/dev-only features on it so
        // R8 strips them from release builds.
        buildConfig = true
    }

    namespace = "com.vayunmathur${path.replace(":", ".")}"
    compileSdk {
        version = release(37)
    }
    //compileSdkExtension = 19

    androidResources {
        generateLocaleConfig = true
    }

    lint {
        checkDependencies = true
        warning += listOf("HardcodedText")
        abortOnError = false
        // Don't fail on missing translations - empty skeletons exist for Weblate
        disable += listOf("MissingTranslation")
        // Toast is banned repo-wide; this one fails the build even though
        // abortOnError is off for everything else. See :lint-rules.
        fatal += listOf("ToastUsage")
    }

    // Every app declares the same res/resources.properties (unqualifiedResLocale) for
    // per-app locale config. Share a single committed copy instead of one file per app.
    // (Generated res dirs are NOT scanned by extractSupportedLocales, so this must be a
    // real res source directory.)
    sourceSets.getByName("main").res.directories.add(File(rootDir, "build-logic/shared-res").absolutePath)

    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 31
        versionCode = appVersionCode
        versionName = appVersionName
        targetSdk = 37
        // arm64-v8a only by default — all phones/tablets + Apple Silicon emulator are
        // arm64. AGP otherwise configures buildCMakeDebug for armeabi-v7a/x86/x86_64
        // which wastes time and broke when offline router's sqlite3.c was removed.
        // 32-bit devices (Google TV) opt in with `nativeAbis { armv7 = true }`.
        ndk {
            abiFilters.add(ABI_ARM64)
        }
    }

    signingConfigs {
        val isSigningConfigAvailable = project.hasProperty("RELEASE_STORE_FILE")

        if (isSigningConfigAvailable) {
            create("release") {
                storeFile = file(project.property("RELEASE_STORE_FILE") as String)
                storePassword = project.property("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.property("RELEASE_KEY_ALIAS") as String
                keyPassword = project.property("RELEASE_KEY_PASSWORD") as String

                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    // AGP auto-creates a `debug` build type; this repo only ships `dev` and `release`.
    // Point all test tasks at `dev` before dropping `debug` in the block below.
    testBuildType = "dev"

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (signingConfigs.findByName("release") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), proguardFile.absolutePath,
            )
            buildConfigField("boolean", "DEV_BUILD", "false")
        }
        create("dev") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            // The Compose screenshot-test plugin (com.android.compose.screenshot) only
            // registers its per-variant render tasks (preview/update/validateDevScreenshotTest)
            // for a variant whose component is debuggable. `dev` is our testBuildType and is
            // built from `release` (initWith), which is not debuggable, so without this the
            // render tasks are never created and `metadata` fails with "No rendered previews
            // found". `dev` is a developer build and never ships, so debuggable is correct here.
            isDebuggable = true
            matchingFallbacks += listOf("release")
            ndk {
                abiFilters.clear()
                abiFilters.add(ABI_ARM64)
            }
            // initWith(release) copied DEV_BUILD=false; dev is a developer build, so flip it on.
            buildConfigField("boolean", "DEV_BUILD", "true")
        }
    }

    packaging {
        resources {
            // Multiple dependencies ship these license files, which collide
            // during Java-resource merge.
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
        }
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose UI (BOM Managed)
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    // Material 3 is intentionally NOT declared here. Apps must consume Material only
    // through `:library:ui` (which exposes it via `api`), so all Material usage goes
    // through the curated wrappers in `com.vayunmathur.library.ui`.

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    implementation(project(":library"))
    implementation(project(":library:ui"))

    // Repo-specific lint checks (currently: no Toast).
    lintChecks(project(":lint-rules"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

fun DependencyHandlerScope.justSoItShowsAsUsedSomewhere() {
    implementation(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)
}

// Apps consume Material only through `:library:ui` wrappers, but some re-exported
// Material 3 types (sheets, tabs, adaptive, etc.) are still marked @RequiresOptIn.
// Opt in globally so app code doesn't need to import Material's markers.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.optIn.addAll(
        "androidx.compose.material3.ExperimentalMaterial3Api",
        "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        "androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
    )
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Reproducible builds: log SOURCE_DATE_EPOCH if present for verification
System.getenv("SOURCE_DATE_EPOCH")?.let {
    logger.lifecycle("Reproducible build: SOURCE_DATE_EPOCH=$it")
}
