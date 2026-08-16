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
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.uast.UCallExpression

/**
 * Flags a raw `Scaffold` used directly in an app screen.
 *
 * Two calls are flagged: the Material 3 `androidx.compose.material3.Scaffold`
 * and the thin library wrapper `com.vayunmathur.library.ui.Scaffold` (defined in
 * `MaterialComponents.kt`). App screens must instead build on a shared scaffold —
 * `AppScaffold` / `DetailScaffold` / `DetailLazyColumn` / `ListPage` /
 * `LazyListScaffold` / `TabbedPagerScaffold` (or `TopAppBarOverlay` for
 * full-bleed) — which own the window insets and content padding once, in one
 * place. A raw Scaffold in a screen re-derives that padding by hand, and that is
 * where the ad-hoc padding bugs come from.
 *
 * Scope: only app modules are flagged. The shared library modules
 * (`com.vayunmathur.library.*`) are excluded because the shared scaffolds are
 * BUILT on the raw Scaffold there. Note this exclusion is intentionally just the
 * library prefix, not the broader [LintPackageExclusions] set — games ARE apps
 * and should be flagged.
 *
 * Per-file opt-out: a file carrying the marker comment `// RAW SCAFFOLD EXCEPTION`
 * is skipped, for documented cases such as music's nested scaffolds.
 */
class RawScaffoldInAppDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> = listOf("Scaffold")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val isRawScaffold =
            context.evaluator.isMemberInClass(method, MATERIAL3_SCAFFOLD_CLASS) ||
                context.evaluator.isMemberInClass(method, LIBRARY_SCAFFOLD_CLASS)
        if (!isRawScaffold) return

        val packageName = context.uastFile?.packageName ?: return
        // App-only rule: skip the shared library modules (where the shared
        // scaffolds are built on raw Scaffold), and anything outside our apps.
        if (!packageName.startsWith(APP_PACKAGE_PREFIX)) return
        if (isLibraryPackage(packageName)) return

        // Documented per-file opt-out.
        val fileText = (context.uastFile?.sourcePsi as? KtFile)?.text
        if (fileText != null && fileText.contains(EXCEPTION_MARKER)) return

        context.report(
            ISSUE,
            node,
            context.getLocation(node),
            "App screens must use a shared scaffold (AppScaffold / DetailScaffold / " +
                "DetailLazyColumn / ListPage / LazyListScaffold / TabbedPagerScaffold, or " +
                "TopAppBarOverlay for full-bleed) instead of a raw Scaffold, which is where " +
                "ad-hoc padding bugs come from. If this screen genuinely needs a raw Scaffold " +
                "(e.g. nested scaffolds), add the `$EXCEPTION_MARKER` marker comment to the file.",
        )
    }

    companion object {
        private const val MATERIAL3_SCAFFOLD_CLASS = "androidx.compose.material3.ScaffoldKt"
        private const val LIBRARY_SCAFFOLD_CLASS = "com.vayunmathur.library.ui.MaterialComponentsKt"

        private const val APP_PACKAGE_PREFIX = "com.vayunmathur."
        private const val LIBRARY_PREFIX = "com.vayunmathur.library"

        private const val EXCEPTION_MARKER = "// RAW SCAFFOLD EXCEPTION"

        private fun isLibraryPackage(packageName: String): Boolean =
            packageName == LIBRARY_PREFIX || packageName.startsWith("$LIBRARY_PREFIX.")

        val ISSUE: Issue = Issue.create(
            id = "RawScaffoldInApp",
            briefDescription = "Raw Scaffold used in an app screen",
            explanation = """
                App screens must not use a raw `Scaffold` — neither \
                `androidx.compose.material3.Scaffold` nor the library wrapper \
                `com.vayunmathur.library.ui.Scaffold`. They must build on a shared scaffold \
                instead: AppScaffold, DetailScaffold, DetailLazyColumn, ListPage, \
                LazyListScaffold, or TabbedPagerScaffold (or TopAppBarOverlay for full-bleed \
                screens).

                The shared scaffolds own window insets and content padding once, in one place. \
                A raw Scaffold in a screen re-derives that padding by hand, and that hand-rolled \
                padding is where the ad-hoc inset/padding bugs come from.

                The shared library modules (com.vayunmathur.library.*) are excluded because the \
                shared scaffolds are built on the raw Scaffold there. If a screen genuinely needs \
                a raw Scaffold (for example music's nested scaffolds), add the marker comment \
                `// RAW SCAFFOLD EXCEPTION: <reason>` to the file to opt it out.
                """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                RawScaffoldInAppDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
