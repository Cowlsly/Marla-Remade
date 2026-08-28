// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Repairs corrupted translations contributed via Weblate where the <xliff:g> markup
// was escaped (e.g. "&lt;xliff:g ...&gt;%1$s&lt;/xliff:g&gt;"). When escaped, Android
// renders the markup literally in the UI instead of only the placeholder. This task
// unwraps such occurrences back to their inner placeholder (e.g. "%1$s"). See issue #477.
tasks.register("fixStrings") {
    group = "translation"
    description = "Fixes translated strings where <xliff:g> markup was escaped and shows up literally in the UI (#477)."
    val projectRoot = rootDir
    doLast {
        val escapedXliff = Regex(
            "&lt;\\s*xliff:g\\b.*?&gt;(.*?)&lt;\\s*/\\s*xliff:g\\s*&gt;",
            RegexOption.DOT_MATCHES_ALL,
        )
        var filesChanged = 0
        var replacements = 0
        projectRoot.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.name == "strings.xml" }
            .forEach { file ->
                val original = file.readText()
                var count = 0
                val fixed = escapedXliff.replace(original) { match ->
                    count++
                    match.groupValues[1]
                }
                if (count > 0) {
                    file.writeText(fixed)
                    filesChanged++
                    replacements += count
                    println("fixStrings: fixed $count occurrence(s) in ${file.relativeTo(projectRoot)}")
                }
            }
        println("fixStrings: done. Fixed $replacements occurrence(s) across $filesChanged file(s).")
    }
}

// Store listings are generated from metadata_data/<module-key>.md by release.sh and the
// "Prepare F-Droid Metadata" step of .github/workflows/android.yml, both of which take line 1
// as the short description and the whole file as the full description. Play and F-Droid cap a
// summary at 80 characters, and nothing else in the build looks at these files, so the format
// is enforced here. Required shape, exactly:
//
//   1  a summary, at most 80 characters
//   2  blank
//   3  Features:
//   4+ one or more "- " bullets
//      blank
//      a connectivity line: "100% offline", "Requires internet",
//      "Internet required only for initial asset downloads", or
//      "Internet only used for: <feature(s)>" - the last only when the app still mostly
//      works without a connection.
tasks.register("checkMetadata") {
    group = "verification"
    description = "Checks every app module has a metadata_data/*.md store listing in the required format."
    val projectRoot = rootDir
    doLast {
        val fixedConnectivity = listOf(
            "100% offline",
            "Requires internet",
            "Internet required only for initial asset downloads",
        )
        val connectivityPrefix = "Internet only used for: "

        // Declared inside the task action rather than as a script-level function so the
        // action does not capture a reference to the build script, which the configuration
        // cache cannot serialize.
        val problemsIn = { lines: List<String> ->
            // summary, blank, "Features:", one bullet, blank, connectivity
            if (lines.size < 6) {
                listOf("has only ${lines.size} line(s); the format needs at least 6")
            } else {
                val problems = mutableListOf<String>()
                val summary = lines[0]
                if (summary.isBlank()) {
                    problems += "line 1 (the short description) is empty"
                } else if (summary.length > 80) {
                    problems += "line 1 is ${summary.length} characters; the summary caps at 80"
                }
                if (lines[1].isNotEmpty()) {
                    problems += "line 2 must be blank, but is \"${lines[1]}\""
                }
                if (lines[2] != "Features:") {
                    problems += "line 3 must be \"Features:\", but is \"${lines[2]}\""
                }

                val connectivity = lines.last()
                if (connectivity in fixedConnectivity) {
                    // fine
                } else if (connectivity.startsWith(connectivityPrefix)) {
                    if (connectivity.removePrefix(connectivityPrefix).isBlank()) {
                        problems += "\"$connectivityPrefix\" must name the feature(s) using the internet"
                    }
                } else {
                    problems += "the last line must be one of " +
                        fixedConnectivity.joinToString(", ") { "\"$it\"" } +
                        " or \"$connectivityPrefix<feature(s)>\", but is \"$connectivity\""
                }
                if (lines[lines.size - 2].isNotEmpty()) {
                    problems += "the last line must be preceded by a blank line"
                }

                val bullets = lines.subList(3, lines.size - 2)
                if (bullets.isEmpty()) problems += "there must be at least one feature bullet"
                bullets.forEachIndexed { index, line ->
                    if (!line.startsWith("- ")) {
                        problems += "line ${index + 4} must be a \"- \" bullet, but is \"$line\""
                    } else if (line.removePrefix("- ").isBlank()) {
                        problems += "line ${index + 4} is an empty feature bullet"
                    }
                }
                problems
            }
        }

        val metadataDir = File(projectRoot, "metadata_data")

        // release.sh and android.yml find app modules by grepping for the bare string
        // "common-conventions-app", which also matches library/map, where it appears only in a
        // comment. Matching the plugin application instead keeps this list to real apps.
        val appPlugin = Regex("""id\("common-conventions-app"\)""")
        val moduleKeys = projectRoot.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.name == "build.gradle.kts" }
            .filter { appPlugin.containsMatchIn(it.readText()) }
            .map { it.parentFile.relativeTo(projectRoot).invariantSeparatorsPath.replace('/', '-') }
            .toSortedSet()

        val problems = mutableListOf<String>()
        for (key in moduleKeys) {
            val file = File(metadataDir, "$key.md")
            if (!file.isFile) {
                problems += "$key.md: missing; every app module needs a store listing"
                continue
            }
            problemsIn(file.readLines()).forEach { problems += "$key.md: $it" }
        }

        val listings = metadataDir.listFiles { f: File -> f.isFile && f.extension == "md" }
            ?.map { it.nameWithoutExtension }
            .orEmpty()
        (listings - moduleKeys).sorted().forEach {
            problems += "$it.md: does not match any app module, so it is never published"
        }

        problems.forEach { println("checkMetadata: $it") }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "checkMetadata: ${problems.size} problem(s) across ${moduleKeys.size} app module(s).",
            )
        }
        println("checkMetadata: ${moduleKeys.size} store listing(s) are correctly formatted.")
    }
}