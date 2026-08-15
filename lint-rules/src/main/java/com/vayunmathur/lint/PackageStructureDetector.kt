package com.vayunmathur.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile

/**
 * Enforces the closed canonical package structure (§2 of docs/package-structure.md).
 *
 * Each app's `src/main/java/com/vayunmathur/<app>/` may only contain the
 * closed set of roots; anything else is a violation. The check derives
 * `<app>` from the file's `package` declaration (source of truth, not file
 * path) and inspects the first segment after `com.vayunmathur.<app>`.
 */
class PackageStructureDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UFile::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitFile(node: UFile) {
                val packageName = node.packageName
                if (packageName.isEmpty()) return
                if (isExcluded(packageName)) return
                if (!packageName.startsWith("com.vayunmathur.")) return

                val result = deriveAppBaseAndSegment(packageName) ?: return
                val segment = result.second
                // null segment = file directly in app root (MainActivity/Route/Navigation) — allowed.
                if (segment == null) return
                if (segment in ALLOWED_ROOTS) return

                // Unknown root — report.
                val suggestion = aliasSuggestion(segment)
                val allowedList = ALLOWED_ROOTS.sorted().joinToString(", ")
                val message = buildString {
                    append("Package `")
                    append(packageName)
                    append("` is not in the canonical closed set. ")
                    append("Segment `")
                    append(segment)
                    append("` is not an allowed root. ")
                    append("Allowed roots: [")
                    append(allowedList)
                    append("].")
                    if (suggestion != null) {
                        append(" Did you mean `")
                        append(suggestion)
                        append("`? ")
                    }
                    append(" See docs/package-structure.md §2.")
                    append(" (If this is a JNI FQN exception, add it to the exception list in lint.xml.)")
                }

                context.report(
                    ISSUE,
                    node,
                    context.getLocation(node),
                    message,
                )
            }
        }

    companion object {

        private val ALLOWED_ROOTS = setOf(
            "ui", "data", "domain", "platform", "network", "intents",
            "service", "provider", "widget", "notifications", "auth", "sync", "telephony"
        )

        private val EXCLUDED_PREFIXES = listOf(
            "com.vayunmathur.library",
            "com.vayunmathur.sdk",
            "com.vayunmathur.games",
            "com.vayunmathur.tools",
            "com.vayunmathur.personal",
        )

        fun isExcluded(packageName: String): Boolean =
            EXCLUDED_PREFIXES.any { packageName == it || packageName.startsWith("$it.") }

        /**
         * Derives the app base package and the first segment after it.
         * Returns null if the package does not start with `com.vayunmathur.`.
         * Returns Pair(base, null) for root files (no extra segment).
         *
         * Source: plan §5.3 normative snippet.
         */
        private fun deriveAppBaseAndSegment(packageName: String): Pair<String, String?>? {
            if (!packageName.startsWith("com.vayunmathur.")) return null
            val remainder = packageName.removePrefix("com.vayunmathur.")
            if (remainder.isEmpty()) return null
            val dot = remainder.indexOf('.')
            return if (dot == -1) {
                // package is exactly com.vayunmathur.<app> — root file like MainActivity
                Pair("com.vayunmathur.$remainder", null)
            } else {
                val app = remainder.substring(0, dot)
                val segment = remainder.substring(dot + 1).substringBefore('.')
                Pair("com.vayunmathur.$app", segment)
            }
        }

        private val BANNED_ALIAS_MAP: Map<String, String> = mapOf(
            "util" to "domain/ or platform/ (see litmus test in docs/package-structure.md §2.1)",
            "api" to "network/",
            "model" to "data/ (or data/model/ subpackage)",
            "viewmodel" to "platform/",
            "glance" to "widget/ (move glance/Foo → widget/glance/Foo)",
            "composer" to "ui/composer/",
            "shields" to "domain/shields/",
            "syntax" to "domain/syntax/",
            "ime" to "platform/ime/",
            "tts" to "platform/tts/",
            "imap" to "network/imap/",
            "smtp" to "network/smtp/",
            "saf" to "data/saf/",
            "crypto" to "domain/crypto/",
            "format" to "domain/format/",
            "remote" to "network/remote/",
            "sink" to "data/sink/",
        )

        private fun aliasSuggestion(badSegment: String): String? {
            // For known banned aliases return the canonical suggestion;
            // for unknown singletons return a generic hint.
            val mapped = BANNED_ALIAS_MAP[badSegment]
            return when {
                mapped != null -> mapped
                else -> "Move under one of [data|network|domain|platform|...] per docs/package-structure.md §2.3"
            }
        }

        val ISSUE: Issue = Issue.create(
            id = "PackageStructure",
            briefDescription = "Package not in canonical closed set",
            explanation = """
                Each app's `src/main/java/com/vayunmathur/<app>/` may only contain the closed \
                set of roots: [ui, data, domain, platform, network, intents, service, provider, \
                widget, notifications, auth, sync, telephony]. Any other top-level segment is a \
                violation. Re-nest the code under the closest canonical root per \
                docs/package-structure.md §2.3, or amend the plan to add a new root. Files \
                directly in the app root (MainActivity, Route, Navigation) are allowed, and any \
                depth under an allowed root is allowed (e.g. widget/glance, data/lyft). \
                Shared library modules (com.vayunmathur.library*) are excluded from this check.
                """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.WARNING,
            implementation = Implementation(
                PackageStructureDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
