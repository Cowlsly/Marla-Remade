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
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UImportStatement

/**
 * Flags an app module reaching for Compose's declarative animation APIs directly.
 *
 * Motion that is written at the call site carries no intent, so the same interaction ends up with a
 * different duration and curve on every screen and the app stops feeling like one app. The shared
 * helpers name the interaction instead - `animatedDp`, `FadeVisibility`, `itemMotion`,
 * `sharedText`, `MorphPage` - and put the timing in one place where it can be tuned once.
 *
 * Only the declarative layer is flagged: the `AnimatedVisibility` / `fadeIn` / `animate*AsState`
 * family, which is where that drift happens. Imperative motion built on
 * [androidx.compose.animation.core.Animatable] is deliberately allowed - a game board wave or a
 * camera flash is genuinely one-off, and wrapping it in the library would be a passthrough that
 * names nothing.
 *
 * Scope: app modules only. `com.vayunmathur.library.*` is where the helpers live and is exempt, and
 * so is `com.vayunmathur.launcher.*`, which drives its home screen from `animateBounds` and
 * `Animatable` and declares the dependency explicitly for that reason.
 *
 * Per-file opt-out: a file carrying `// RAW ANIMATION EXCEPTION: <reason>` is skipped.
 */
class DirectComposeAnimationDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UImportStatement::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        val packageName = context.uastFile?.packageName ?: return null
        if (!packageName.startsWith(APP_PACKAGE_PREFIX)) return null
        if (isExemptPackage(packageName)) return null

        // Documented per-file opt-out, matching the repo's other escape hatches.
        val fileText = (context.uastFile?.sourcePsi as? KtFile)?.text
        if (fileText != null && fileText.contains(EXCEPTION_MARKER)) return null

        return object : UElementHandler() {
            override fun visitImportStatement(node: UImportStatement) {
                val reference = node.importReference?.asSourceString() ?: return
                val banned = bannedSymbolIn(reference) ?: return
                context.report(
                    ISSUE,
                    node,
                    context.getLocation(node as UElement),
                    "`$banned` must not be used directly in an app module. Use the motion helpers " +
                        "in `com.vayunmathur.library.ui` (animatedDp / animatedFloat / " +
                        "animatedColor / pressedShape / itemMotion / staggeredEntrance, " +
                        "FadeVisibility / PopVisibility / ExpandVisibility / SwappedContent) or " +
                        "`com.vayunmathur.library.util` for navigation and shared-element motion, " +
                        "so the timing is named once instead of re-derived per screen. Imperative " +
                        "one-off motion on Animatable is allowed - mark the file with " +
                        "`$EXCEPTION_MARKER <reason>` if that is what this is.",
                )
            }
        }
    }

    companion object {
        private const val APP_PACKAGE_PREFIX = "com.vayunmathur."
        private const val LIBRARY_PREFIX = "com.vayunmathur.library"

        /**
         * The launcher's home screen is built on `animateBounds` and `Animatable`, which have no
         * shared equivalent, and it declares `androidx-compose-animation` explicitly for that.
         * Exempted wholesale rather than per file so its drag-and-drop code is not littered with
         * markers.
         */
        private const val LAUNCHER_PREFIX = "com.vayunmathur.launcher"

        const val EXCEPTION_MARKER = "// RAW ANIMATION EXCEPTION:"

        private const val ANIMATION_PACKAGE = "androidx.compose.animation"

        /**
         * The declarative surface. Matched on the imported name rather than the package, because
         * these live across both `androidx.compose.animation` and `androidx.compose.animation.core`
         * while their imperative neighbours - Animatable, tween, spring, the easings - stay allowed.
         */
        private val BANNED_SYMBOLS = listOf(
            "AnimatedVisibility",
            "AnimatedContent",
            "Crossfade",
            "animateColorAsState",
            "animateDpAsState",
            "animateFloatAsState",
            "animateIntAsState",
            "animateIntOffsetAsState",
            "animateOffsetAsState",
            "animateSizeAsState",
            "animateValueAsState",
            "fadeIn",
            "fadeOut",
            "scaleIn",
            "scaleOut",
            "slideIn",
            "slideInHorizontally",
            "slideInVertically",
            "slideOut",
            "slideOutHorizontally",
            "slideOutVertically",
            "expandHorizontally",
            "expandIn",
            "expandVertically",
            "shrinkHorizontally",
            "shrinkOut",
            "shrinkVertically",
            "togetherWith",
        )

        private fun isExemptPackage(packageName: String): Boolean =
            packageName == LIBRARY_PREFIX ||
                packageName.startsWith("$LIBRARY_PREFIX.") ||
                packageName == LAUNCHER_PREFIX ||
                packageName.startsWith("$LAUNCHER_PREFIX.")

        /** The banned symbol this import refers to, or null if the import is fine. */
        private fun bannedSymbolIn(reference: String): String? {
            if (!reference.startsWith(ANIMATION_PACKAGE)) return null
            // A star import pulls the whole declarative surface in with it.
            if (reference == "$ANIMATION_PACKAGE.*") return "$ANIMATION_PACKAGE.*"
            val symbol = reference.substringAfterLast('.')
            return BANNED_SYMBOLS.firstOrNull { it == symbol }?.let { "$ANIMATION_PACKAGE...$it" }
        }

        val ISSUE: Issue = Issue.create(
            id = "DirectComposeAnimation",
            briefDescription = "Compose animation API used directly in an app module",
            explanation = """
                App modules must not use Compose's declarative animation APIs directly — \
                AnimatedVisibility, AnimatedContent, Crossfade, the `animate*AsState` family, or \
                the `fadeIn`/`scaleIn`/`slideIn`/`expandVertically` enter-exit builders.

                Motion written at a call site carries no intent. The same interaction picks up a \
                different duration and curve on each screen, and a spec written with no arguments \
                silently takes Compose's own default rather than the motion scheme the app is \
                themed with. The shared helpers name the interaction instead and hold the timing \
                in one place: `animatedDp`, `animatedFloat`, `animatedColor`, `pressedShape`, \
                `itemMotion` and `staggeredEntrance` in com.vayunmathur.library.ui, the \
                `FadeVisibility` / `PopVisibility` / `ExpandVisibility` / `SwappedContent` \
                wrappers beside them, and `sharedText` / `sharedContainer` / `MorphPage` in \
                com.vayunmathur.library.util for navigation and shared-element motion.

                Imperative motion on androidx.compose.animation.core.Animatable is NOT flagged. A \
                game board wave or a camera capture flash is genuinely one-off, and wrapping it \
                would be a passthrough that names nothing.

                com.vayunmathur.library.* is exempt because the helpers are built there, and \
                com.vayunmathur.launcher.* is exempt because its home screen is built on \
                animateBounds and Animatable. For anything else that genuinely needs the raw API, \
                add the marker comment `// RAW ANIMATION EXCEPTION: <reason>` to the file.
                """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                DirectComposeAnimationDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
