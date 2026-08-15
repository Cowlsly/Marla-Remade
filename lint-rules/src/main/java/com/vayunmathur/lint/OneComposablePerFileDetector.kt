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
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile

/**
 * Flags a file that declares more than one *public* top-level `@Composable`.
 *
 * The convention is one public composable — a screen or a component — per file,
 * under `ui/`, `ui/component/`, or `ui/dialogs/`. Overloads of that same
 * composable (they share its name) and private helper composables may stay in
 * the file; a second, differently-named public composable belongs in its own
 * file. Keeping screens one-to-one with files is what makes the tree navigable
 * and keeps diffs and imports honest as the app grows.
 */
class OneComposablePerFileDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UFile::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitFile(node: UFile) {
                // Names of public top-level composables already seen in this file.
                // The first is allowed; each additional *distinct* name is a violation.
                // Overloads reuse a name, so they never trip the check.
                val seen = LinkedHashSet<String>()
                for (clazz in node.classes) {
                    for (method in clazz.methods) {
                        val ktFun = method.sourcePsi as? KtNamedFunction ?: continue
                        if (!ktFun.isTopLevel) continue
                        if (ktFun.isPrivateOrInternal()) continue
                        val isComposable =
                            method.uAnnotations.any { it.qualifiedName == COMPOSABLE }
                        if (!isComposable) continue

                        val name = method.name
                        if (name in seen) continue
                        if (seen.isNotEmpty()) {
                            context.report(
                                ISSUE,
                                method,
                                context.getNameLocation(method),
                                "Only one public @Composable is allowed per file. Move " +
                                    "`$name` to its own file under ui/, ui/component/, or " +
                                    "ui/dialogs/ (overloads of the same composable and " +
                                    "private helpers may stay).",
                            )
                        }
                        seen.add(name)
                    }
                }
            }
        }

    private fun KtNamedFunction.isPrivateOrInternal(): Boolean =
        hasModifier(KtTokens.PRIVATE_KEYWORD) ||
            hasModifier(KtTokens.INTERNAL_KEYWORD) ||
            hasModifier(KtTokens.PROTECTED_KEYWORD)

    companion object {
        private const val COMPOSABLE = "androidx.compose.runtime.Composable"

        val ISSUE: Issue = Issue.create(
            id = "OneComposablePerFile",
            briefDescription = "More than one public composable in a file",
            explanation = """
                Each file should contain exactly one public @Composable — its screen or \
                component entry point. Overloads of that same composable and private helper \
                composables may share the file, but a second, differently-named public \
                composable belongs in its own file under ui/, ui/component/, or ui/dialogs/.

                Keeping screens one-to-one with files makes the source tree map directly to \
                the UI, keeps imports and diffs scoped to a single screen, and stops files \
                from growing into grab-bags of unrelated screens.
                """.trimIndent(),
            category = Category.PRODUCTIVITY,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(
                OneComposablePerFileDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
