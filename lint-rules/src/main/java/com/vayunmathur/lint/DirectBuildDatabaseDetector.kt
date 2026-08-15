package com.vayunmathur.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.getContainingUFile

/**
 * Forbids constructing a Room database directly via the shared
 * `com.vayunmathur.library.room.buildDatabase(...)` (or `buildDatabaseUncached(...)`)
 * helpers anywhere outside the `library` module.
 *
 * Every database must have a single application-scoped owner: subclass
 * `com.vayunmathur.library.room.RoomRepository<DB>` and obtain data through the
 * repository. Scattered `buildDatabase` calls across Activities, Services,
 * Workers, Receivers, widgets, and assistant intents are exactly the anti-pattern
 * this rule exists to prevent (duplicated DAO holders, no single source of truth,
 * leaked component contexts).
 */
class DirectBuildDatabaseDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> =
        listOf("buildDatabase", "buildDatabaseUncached")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        // Only flag the library's own helpers — not unrelated methods that happen
        // to share the name (e.g. a private file-conversion helper).
        val declaringPackage = context.evaluator.getPackage(method)?.qualifiedName
        if (declaringPackage != LIBRARY_ROOM_PACKAGE) return

        // The library itself (RoomRepository + the helpers) is allowed to call them.
        val callSitePackage = node.getContainingUFile()?.packageName.orEmpty()
        if (callSitePackage == LIBRARY_ROOM_PACKAGE || callSitePackage.startsWith("$LIBRARY_PACKAGE.")) {
            return
        }

        context.report(
            ISSUE,
            node,
            context.getLocation(node),
            "Do not build databases directly. Subclass " +
                "`com.vayunmathur.library.room.RoomRepository` and access data through the " +
                "repository instead of calling `${method.name}(...)`.",
        )
    }

    companion object {
        private const val LIBRARY_PACKAGE = "com.vayunmathur.library"
        private const val LIBRARY_ROOM_PACKAGE = "com.vayunmathur.library.room"

        val ISSUE: Issue = Issue.create(
            id = "DirectBuildDatabase",
            briefDescription = "Database built outside a RoomRepository",
            explanation = """
                Databases must have a single application-scoped owner. Subclass \
                `com.vayunmathur.library.room.RoomRepository<DB>` and expose the DAOs / \
                Flows / suspend operations from it, then obtain that repository (as a \
                process-wide singleton) from Activities, Services, Workers, Receivers, \
                widgets, and assistant intents.

                Calling `buildDatabase(...)` directly scatters database construction across \
                the app, duplicates DAO holders, loses the single source of truth between \
                the UI and background components, and risks leaking a component Context into \
                a long-lived database instance.
                """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(
                DirectBuildDatabaseDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
