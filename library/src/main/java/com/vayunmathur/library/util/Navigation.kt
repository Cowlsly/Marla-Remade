package com.vayunmathur.library.util

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// The Registry that holds the events
class NavResultRegistry {
    // Use a SharedFlow with some extra buffer capacity so events are not dropped
    private val _results = MutableSharedFlow<Pair<String, Any>>(extraBufferCapacity = 64)
    val results = _results.asSharedFlow()

    suspend fun dispatchResult(key: String, result: Any) {
        // emit is suspend and will suspend until the value is delivered or buffer accepts it
        _results.emit(key to result)
    }
}

// The Composable helper (The "ResultEffect" you saw)
@Composable
inline fun <reified T> ResultEffect(key: String, crossinline onResult: suspend (T) -> Unit) {
    val registry = LocalNavResultRegistry.current
    LaunchedEffect(registry) {
        registry.results.collect { (k, result) ->
            if (k == key && result is T) {
                onResult(result)
            }
        }
    }
}

interface NavKey
class NavBackStack<T: NavKey>(initial: Array<out T>) {
    private val backend = mutableStateListOf(*initial)
    val backStack: List<T> = backend

    fun pop() {
        if (backend.isNotEmpty()) backend.removeAt(backend.lastIndex)
    }

    fun set(index: Int, value: T) {
        backend[index] = value
    }

    fun add(value: T) {
        backend.add(value)
    }

    fun clear() {
        backend.clear()
    }

    fun setLast(value: T) {
        set(backend.lastIndex, value)
    }

    fun last(): T {
        return backend.last()
    }

    fun reset(vararg keys: T) {
        backend.clear()
        backend.addAll(keys)
    }
}

// Make it available everywhere via CompositionLocal
val LocalNavResultRegistry = staticCompositionLocalOf<NavResultRegistry> {
    error("No NavResultRegistry provided")
}

val LocalSnackbarHostState = compositionLocalOf<SnackbarHostState?> { null }

class EntryProviderScope<T: NavKey>(val obj: T) {
    var result: NavEntry<T>? = null

    inline fun <reified E: T> entry(metadata: Map<String, Any> = emptyMap(), crossinline content: @Composable (E) -> Unit) {
        if(obj is E) {
            result = NavEntry(obj, metadata = metadata) {
                // Republished as a nullable local so screens can ask for a shared element without
                // depending on nav3, and so the same screen still renders outside a NavDisplay.
                CompositionLocalProvider(
                    LocalEntryAnimatedScope provides LocalNavAnimatedContentScope.current
                ) {
                    content(obj)
                }
            }
        }
    }
}

/**
 * How a destination arrives and leaves.
 *
 * nav3's default is a slow crossfade for everything, and the app-wide slide that replaced it was no
 * better at saying *where* the user went: opening a photo, switching a tab and descending into
 * settings are different journeys that were all animated identically.
 *
 * Apps choose per destination with the `*Page()` helpers rather than by building transitions
 * themselves - nav3 and compose-animation are `implementation` dependencies of this module, so an
 * app module cannot name a [ContentTransform] even if it wanted to.
 */
enum class NavMotion {
    /** Descending a hierarchy - a list to its detail, a screen to its settings. */
    Detail,

    /** Content opening out of the thing that was tapped. */
    Zoom,

    /** Immersive content taking over the window - a viewer, a player, a game board. */
    Fullscreen,

    /** Moving between peers - bottom-bar destinations, tabs. */
    Sibling,

    /**
     * A component on the previous screen morphs into its counterpart on this one, via
     * [sharedContainer] or [sharedContent].
     *
     * The screen itself only crossfades. That is the whole point: if the destination also slid or
     * scaled, the morphing element would be travelling towards a target that is itself still
     * moving, and the two animations visibly fight. Pairing a morph with [Zoom] looks broken.
     */
    Morph,
}

private const val NavMotionKey = "com.vayunmathur.library.util.navMotion"

/** [NavMotion.Detail], the default, so this only needs stating for contrast with its siblings. */
fun DetailPage(): Map<String, Any> = mapOf(NavMotionKey to NavMotion.Detail)

/**
 * [NavMotion.Zoom]: grows out of the tapped item rather than sliding in from the side, which would
 * imply the destination was always over to the right instead of somewhere the user just pointed at.
 */
fun ZoomPage(): Map<String, Any> = mapOf(NavMotionKey to NavMotion.Zoom)

/**
 * [NavMotion.Fullscreen]: no horizontal travel at all. Sliding a full-bleed media surface in from
 * the side draws attention to the edges of a frame meant to be the whole screen, and on a dark
 * viewer it reads as a flicker.
 */
fun FullscreenPage(): Map<String, Any> = mapOf(NavMotionKey to NavMotion.Fullscreen)

/**
 * [NavMotion.Sibling]: deliberately directionless. Peers have no hierarchy, and apps here switch
 * tabs with `backStack.reset(...)`, which nav3 sees as a forward push - so the hierarchical slide
 * would send a tab in from the right even when the user moved *left* along the bar.
 */
fun SiblingPage(): Map<String, Any> = mapOf(NavMotionKey to NavMotion.Sibling)

/**
 * [NavMotion.Morph]: crossfades the screen so that a [sharedContainer] or [sharedContent] element is
 * the only thing that appears to move.
 *
 * Use this, never [ZoomPage], on a destination that morphs a component out of the previous screen.
 */
fun MorphPage(): Map<String, Any> = mapOf(NavMotionKey to NavMotion.Morph)

/** The motion the destination asked for, defaulting to [NavMotion.Detail]. */
private fun Scene<*>.navMotion(): NavMotion =
    entries.lastOrNull()?.metadata?.get(NavMotionKey) as? NavMotion ?: NavMotion.Detail

/**
 * Which screen edge the back gesture started from, mirroring `BackEventCompat.EDGE_LEFT`. A plain
 * Int because androidx.activity is not a dependency of this module.
 */
private const val EdgeLeft = 0

/**
 * The transitions, built from [MaterialTheme]'s expressive motion scheme.
 *
 * Springs rather than tweens, because a spring is interruptible from wherever it currently is -
 * which is what a half-completed back gesture or a fast double tap actually needs. Spatial specs
 * carry position and size, effects specs carry alpha; mixing the two up is what makes a fade look
 * like it lags the movement it belongs to.
 */
private class NavTransitions(
    val offset: FiniteAnimationSpec<IntOffset>,
    val scale: FiniteAnimationSpec<Float>,
    val alpha: FiniteAnimationSpec<Float>,
) {
    fun push(motion: NavMotion): ContentTransform = when (motion) {
        NavMotion.Detail -> slide(enterFrom = 1, exitTo = -1)
        NavMotion.Zoom -> zoom(enterFrom = 0.92f, exitTo = 1.04f)
        NavMotion.Fullscreen -> immersiveIn()
        NavMotion.Sibling, NavMotion.Morph -> crossFade()
    }

    fun pop(motion: NavMotion): ContentTransform = when (motion) {
        NavMotion.Detail -> slide(enterFrom = -1, exitTo = 1)
        NavMotion.Zoom -> zoom(enterFrom = 1.04f, exitTo = 0.92f)
        NavMotion.Fullscreen -> immersiveOut()
        NavMotion.Sibling, NavMotion.Morph -> crossFade()
    }

    /**
     * Predictive back: the screen being dismissed shrinks and slides *towards the edge the finger
     * came from*, revealing the screen behind it.
     *
     * The edge is the whole point, and was previously discarded - so a swipe from the right
     * animated identically to one from the left, and the motion fought the gesture half the time.
     * Everything here is a plain scale, translate or fade so it still reads correctly at any
     * fraction: the user can stop half way and change their mind.
     */
    fun predictivePop(motion: NavMotion, swipeEdge: Int): ContentTransform {
        // A morph has to stay a crossfade even under the gesture, or the element is chasing a target
        // that the predictive scale is still moving.
        if (motion == NavMotion.Sibling || motion == NavMotion.Morph) return crossFade()
        val towardsFinger = if (swipeEdge == EdgeLeft) 1 else -1
        return (fadeIn(alpha) + scaleIn(scale, initialScale = 0.96f)).togetherWith(
            fadeOut(alpha) +
                scaleOut(scale, targetScale = 0.90f) +
                slideOutHorizontally(offset) { towardsFinger * it / 8 }
        )
    }

    private fun slide(enterFrom: Int, exitTo: Int): ContentTransform =
        (fadeIn(alpha) + slideInHorizontally(offset) { enterFrom * it / 12 }).togetherWith(
            fadeOut(alpha) + slideOutHorizontally(offset) { exitTo * it / 12 }
        )

    private fun zoom(enterFrom: Float, exitTo: Float): ContentTransform =
        (fadeIn(alpha) + scaleIn(scale, initialScale = enterFrom)).togetherWith(
            fadeOut(alpha) + scaleOut(scale, targetScale = exitTo)
        )

    private fun immersiveIn(): ContentTransform =
        (fadeIn(alpha) + scaleIn(scale, initialScale = 0.97f)).togetherWith(fadeOut(alpha))

    private fun immersiveOut(): ContentTransform =
        fadeIn(alpha).togetherWith(fadeOut(alpha) + scaleOut(scale, targetScale = 0.97f))

    private fun crossFade(): ContentTransform = fadeIn(alpha).togetherWith(fadeOut(alpha))
}

@Composable
private fun rememberNavTransitions(): NavTransitions {
    val scheme = MaterialTheme.motionScheme
    // Keyed on the scheme, not the specs: the spec factories hand back a fresh object each call, so
    // keying on those would rebuild this on every recomposition.
    return remember(scheme) {
        NavTransitions(
            offset = scheme.defaultSpatialSpec(),
            scale = scheme.defaultSpatialSpec(),
            alpha = scheme.defaultEffectsSpec(),
        )
    }
}

/**
 * How a morphing element travels between its two positions.
 *
 * A tween rather than a spring, and that is not a style choice. Predictive back drives the transition
 * by gesture *fraction*, seeking it back and forth as the finger moves, and only duration-based specs
 * can be seeked. A spring has no notion of "40% of the way through", so under the gesture the element
 * would sit still and then snap into place on release.
 *
 * Also not from the motion scheme, whose spatial springs are all deliberately underdamped (0.8 by
 * default, 0.6 for fast). An overshoot on a bounds morph means the element visibly flies past its
 * target and rubber-bands back, which is far more obvious across a long travel than on a short slide.
 */
internal const val NavMorphMillis = 220

/** M3's emphasized curve: slow start, quick middle, gentle settle. */
private val NavMorphEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private val NavMorphBounds: FiniteAnimationSpec<Rect> =
    tween(NavMorphMillis, easing = NavMorphEasing)

private val NavMorphBoundsTransform = BoundsTransform { _, _ -> NavMorphBounds }

/**
 * The crossfade between the two contents inside a morphing container.
 *
 * Faster than the bounds travel on purpose: the old content should be gone well before the container
 * finishes resizing, or both are legible at once and the item appears to contain two things. Linear,
 * and duration-based for the same seekability reason as the bounds above.
 */
private val NavMorphContentFade: FiniteAnimationSpec<Float> =
    tween(NavMorphMillis / 2, easing = LinearEasing)

/**
 * The scopes [sharedContainer] and [sharedContent] need to morph a component across a destination
 * change.
 *
 * Nullable and defaulting to null rather than erroring, because a screen rendered outside a
 * [NavDisplay] - a `@Preview`, a screenshot test, an Activity hosting one page directly - is a
 * legitimate caller that should simply not animate rather than crash.
 */
private val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * True when the host is showing more than one pane side by side.
 *
 * A morph is then meaningless and actively broken: on a two-pane layout the list and the detail are
 * composed *at the same time*, so the row and the header the row would morph into are both on screen.
 * There is no transition to animate, and two live shared elements holding one key cannot be matched.
 * [sharedContainer] and [sharedContent] step aside rather than produce that.
 */
private val LocalNavMultiPane = staticCompositionLocalOf { false }

/** Internal because [EntryProviderScope.entry] is a public inline function and has to reach it. */
@PublishedApi
internal val LocalEntryAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Morphs this component into the component carrying the same [key] on the destination: the bounds
 * travel and resize while the two different contents crossfade inside them.
 *
 * This is the Material container transform, and it is meant for the *whole* item - put it on the
 * list row and on the block that row opens. Morphing one child in isolation, an avatar out of a row,
 * reads worse than no morph at all: everything around it cuts while one thing glides.
 *
 * Content is remeasured to the animating bounds rather than scaled, so text reflows at each size
 * instead of stretching. That costs a relayout per frame, which is affordable for one item and would
 * not be for a whole list.
 *
 * Pair with [MorphPage] on the destination, or the screen transition animates too and the two fight.
 *
 * A key must be unique *within* a screen and equal *across* the two. Watch for an item rendered
 * twice - a favourite shown both in a favourites section and again in the main list is one component
 * with two origins, and the morph has no way to choose. Key only one of them.
 *
 * No-ops outside a [MainNavigation], so previews and screenshot tests are unaffected.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedContainer(key: Any): Modifier {
    if (LocalNavMultiPane.current) return this
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalEntryAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedContainer.sharedBounds(
            rememberSharedContentState(key),
            animated,
            enter = fadeIn(NavMorphContentFade),
            exit = fadeOut(NavMorphContentFade),
            boundsTransform = NavMorphBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}

/**
 * A single leaf that keeps its identity through a [sharedContainer] morph - typically the photo or
 * icon, whose pixels are the same on both screens.
 *
 * Nest inside a [sharedContainer] rather than using alone: on its own it produces exactly the
 * one-thing-glides effect that [sharedContainer] exists to avoid.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedContent(key: Any): Modifier {
    if (LocalNavMultiPane.current) return this
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalEntryAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedContent.sharedElement(
            rememberSharedContentState(key),
            animated,
            boundsTransform = NavMorphBoundsTransform,
        )
    }
}

/**
 * Single owner of the IME (keyboard) inset for every screen it hosts: it applies
 * [imePadding] once to all hosted content. Screens and reusable components rendered
 * inside must NOT call [imePadding] themselves, or the inset is applied twice.
 *
 * [containerColor] is [Color.Unspecified] by default, which leaves the scaffold's own
 * opaque `colorScheme.background`. Pass [Color.Transparent] when something behind the
 * activity window has to show through - the launcher needs the wallpaper visible, and an
 * opaque scaffold paints over it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun <T: NavKey> MainNavigation(
    backStack: NavBackStack<T>,
    bottomBar: @Composable () -> Unit = {},
    containerColor: Color = Color.Unspecified,
    entryProvider: EntryProviderScope<T>.() -> Unit,
) {
    val sceneStrategy: ListDetailSceneStrategy<T> = rememberListDetailSceneStrategy()
    val resultRegistry = remember { NavResultRegistry() }
    val snackbarHostState = remember { SnackbarHostState() }
    val transitions = rememberNavTransitions()

    // Matches the width at which the list-detail strategy starts showing two panes at once.
    val multiPane = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    val resolvedContainerColor = containerColor.takeOrElse { MaterialTheme.colorScheme.background }

    // Resolved rather than left to Scaffold's own default, which is `contentColorFor(container)`.
    // That has no answer for a colour outside the scheme and falls back to LocalContentColor -
    // which is plain black unless something upstream set it. A transparent container is exactly
    // that case, so the launcher's icons and text were coming out black on the wallpaper.
    val resolvedContentColor = MaterialTheme.colorScheme
        .contentColorFor(resolvedContainerColor)
        .takeOrElse { MaterialTheme.colorScheme.onBackground }

    // Drain messages posted from outside composition - ViewModels, workers,
    // anything without a Context. See AppMessages.
    LaunchedEffect(snackbarHostState) {
        AppMessages.messages.collect { message ->
            val result = snackbarHostState.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                duration = when (message.duration) {
                    AppMessages.Duration.Short -> androidx.compose.material3.SnackbarDuration.Short
                    AppMessages.Duration.Long -> androidx.compose.material3.SnackbarDuration.Long
                    AppMessages.Duration.Indefinite -> androidx.compose.material3.SnackbarDuration.Indefinite
                },
            )
            if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(),
        containerColor = resolvedContainerColor,
        contentColor = resolvedContentColor,
        // The scaffold disables automatic insets (contentWindowInsets = WindowInsets()), so the
        // snackbar host must apply its own navigation-bar and IME padding — otherwise messages
        // render behind the system navigation bar and are hidden (issue #630).
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
            )
        },
        bottomBar = bottomBar
    ) { paddingValues ->
        CompositionLocalProvider(
            LocalNavResultRegistry provides resultRegistry,
            LocalSnackbarHostState provides snackbarHostState
        ) {
            // Lets a component morph into its counterpart on the next screen instead of the two
            // screens merely swapping underneath it. See sharedContainer.
            SharedTransitionLayout {
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides this@SharedTransitionLayout,
                    LocalNavMultiPane provides multiPane,
                ) {
                    NavDisplay(
                        // consumeWindowInsets before imePadding: when a bottom bar is
                        // present it has already shifted itself up, and that shows up
                        // in paddingValues. Without consuming it the content would be
                        // pushed up by the keyboard twice.
                        modifier = Modifier
                            .padding(paddingValues)
                            .consumeWindowInsets(paddingValues)
                            .imePadding(),
                        sceneStrategies = listOf(DialogSceneStrategy(), sceneStrategy),
                        // The destination decides: the motion is read off the entry the user is
                        // arriving at when pushing, and off the one they are leaving when popping,
                        // so a route animates the same way in both directions.
                        transitionSpec = { transitions.push(targetState.navMotion()) },
                        popTransitionSpec = { transitions.pop(initialState.navMotion()) },
                        predictivePopTransitionSpec = { swipeEdge ->
                            transitions.predictivePop(initialState.navMotion(), swipeEdge)
                        },
                        backStack = backStack.backStack, entryProvider = {
                            EntryProviderScope(it).apply {
                                entryProvider()
                            }.result!!
                        })
                }
            }
        }
    }
}
/**
 * Keeps a [NavBackStack] alive while the Activity around it is destroyed and recreated.
 *
 * A ViewModel rather than `rememberSaveable` because the stack holds app-defined [NavKey]s,
 * which are not all bundleable — and a configuration change is what actually loses them.
 */
internal class RetainedNavBackStack : ViewModel() {
    var stack: NavBackStack<*>? = null
}

@Composable
fun <T: NavKey> rememberNavBackStack(vararg elements: T): NavBackStack<T> {
    // Changing the font scale or rotating recreates the Activity, and a plain remember would
    // rebuild the stack from `elements` — dropping the user back on the start screen, losing
    // whatever they were part-way through. Keyed on the start route so an owner hosting more
    // than one stack (previews render several) gives each its own.
    val owner = LocalViewModelStoreOwner.current
        ?: return remember { NavBackStack(elements) }
    val retained = viewModel<RetainedNavBackStack>(
        viewModelStoreOwner = owner,
        key = "nav-back-stack-${elements.firstOrNull()?.let { it::class.qualifiedName }}",
    )
    return remember(retained) {
        @Suppress("UNCHECKED_CAST")
        (retained.stack as? NavBackStack<T>)
            ?: NavBackStack(elements).also { retained.stack = it }
    }
}

/**
 * [sharedContainer] for text.
 *
 * Scales the glyphs to the animating bounds instead of laying them out again at each intermediate
 * size. That difference is the whole point: the same name is `bodyLarge` in a list row and
 * `headlineMedium` in a header, and remeasuring between the two re-wraps the string on nearly every
 * frame, which reads as the text garbling rather than growing. Scaling interpolates it smoothly.
 *
 * Use [sharedContainer] instead when the two ends hold genuinely different content - a read-only row
 * becoming a text field - where a reflow is correct and scaling would distort.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedText(key: Any): Modifier {
    if (LocalNavMultiPane.current) return this
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalEntryAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedText.sharedBounds(
            rememberSharedContentState(key),
            animated,
            enter = fadeIn(NavMorphContentFade),
            exit = fadeOut(NavMorphContentFade),
            boundsTransform = NavMorphBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}

/**
 * Enters by unfurling from a flat horizontal line into full height, rather than fading in.
 *
 * For a block of fields arriving as a unit: it reads as the form opening up in place, which suits a
 * destination whose content is a stack of rows. A fade makes the same block look like it was already
 * there and merely became visible.
 *
 * Duration-based, so it stays seekable under a predictive-back gesture.
 *
 * No-ops outside a [MainNavigation].
 */
@Composable
fun Modifier.expandFromLine(): Modifier {
    val scope = LocalEntryAnimatedScope.current ?: return this
    return with(scope) {
        this@expandFromLine.animateEnterExit(
            enter = expandVertically(
                animationSpec = tween(NavMorphMillis, easing = NavMorphEasing),
                expandFrom = Alignment.CenterVertically,
                initialHeight = { 0 },
            ) + fadeIn(NavMorphContentFade),
            exit = shrinkVertically(
                animationSpec = tween(NavMorphMillis, easing = NavMorphEasing),
                shrinkTowards = Alignment.CenterVertically,
                targetHeight = { 0 },
            ) + fadeOut(NavMorphContentFade),
        )
    }
}

/**
 * True while a shared element is mid-flight between two destinations.
 *
 * For content that would otherwise be visible in both places at once: a field can hide its own text
 * while the copy travelling in from the previous screen is on screen, so there is one piece of text
 * rather than two, and reveal it as the morph lands.
 *
 * False outside a [MainNavigation], so a screen on its own always shows its content.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun isNavMorphing(): Boolean =
    LocalSharedTransitionScope.current?.isTransitionActive == true

fun DialogPage() = DialogSceneStrategy.dialog()

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun ListPage(detailPlaceholder: @Composable () -> Unit = {}) = ListDetailSceneStrategy.listPane(Unit) {detailPlaceholder()}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun ListDetailPage() = ListDetailSceneStrategy.detailPane()

@Composable
inline fun <reified T: NavKey> rememberNavBackStack(elements: List<T>): NavBackStack<T> {
    return rememberNavBackStack(*elements.toTypedArray())
}

data class BottomBarItem<Route: NavKey>(
    val name: String,
    val route: Route,
    val icon: @Composable () -> Unit
)

/**
 * Height of the bar's items, excluding the system navigation inset beneath it.
 *
 * Exposed so a screen that draws its own floating content above the bar can
 * reserve the right amount of room. Do not use it to build a second kind of
 * bottom bar - the point of [BottomNavBar] is that every app has exactly one
 * shape and one height.
 */
val BottomNavBarHeight = 64.dp

/**
 * The bottom navigation bar, shared by every app.
 *
 * Built on the short navigation bar so the height is the same everywhere; the
 * apps previously used a mix of `FlexibleBottomAppBar` (deliberately
 * variable), the 80dp `NavigationBar`, and this, so bars visibly changed
 * height from app to app.
 *
 * Rides above the keyboard, which is handled here rather than at each call
 * site because the bar has to work in both the places apps put it: some pass
 * it to [MainNavigation]'s `bottomBar` slot, which sits outside the content
 * and gets no inset handling of its own, and others render it inside a page.
 * That slot is why the app store's bar stayed behind the keyboard while the
 * contacts one, drawn inside the page, moved with it.
 *
 * The inset is the union of the bar's normal one and the keyboard rather than
 * the two added together. A visible keyboard already covers the navigation
 * bar, so padding for both would leave the bar floating a navigation bar's
 * height above the keyboard.
 *
 * Takes a content slot rather than a fixed item model because the apps
 * navigate in genuinely different ways - a route back stack, a tab enum, a
 * selected index. Use [BottomNavBarItem] for each entry; there is a
 * [BottomNavBar] overload below for the common back-stack case.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BottomNavBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ShortNavigationBar(
        modifier = modifier,
        windowInsets = ShortNavigationBarDefaults.windowInsets.union(WindowInsets.ime),
        content = content,
    )
}

/** One entry in a [BottomNavBar]. Pass a null [label] for an icon-only item. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BottomNavBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String? = null,
    enabled: Boolean = true,
) {
    ShortNavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = label?.let { { Text(it) } },
        enabled = enabled,
    )
}

/**
 * [BottomNavBar] for the common case: one item per destination in a back stack.
 *
 * [onSelect] defaults to resetting the stack to the tapped route, which is what
 * a tab bar usually wants. Apps that need different semantics - pushing rather
 * than replacing, or collapsing an intermediate screen first - pass their own.
 */
@Composable
fun <Route : NavKey> BottomNavBar(
    backStack: NavBackStack<Route>,
    pages: List<BottomBarItem<out Route>>,
    currentPage: Route,
    modifier: Modifier = Modifier,
    onSelect: (Route) -> Unit = { if (backStack.last() != it) backStack.reset(it) },
) {
    BottomNavBar(modifier) {
        pages.forEach { page ->
            BottomNavBarItem(
                selected = currentPage == page.route,
                onClick = { onSelect(page.route) },
                icon = page.icon,
                label = page.name,
            )
        }
    }
}
