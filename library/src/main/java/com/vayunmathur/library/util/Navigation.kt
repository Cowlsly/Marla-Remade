package com.vayunmathur.library.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.NavDisplay
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
                content(obj)
            }
        }
    }
}

/**
 * Single owner of the IME (keyboard) inset for every screen it hosts: it applies
 * [imePadding] once to all hosted content. Screens and reusable components rendered
 * inside must NOT call [imePadding] themselves, or the inset is applied twice.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T: NavKey> MainNavigation(backStack: NavBackStack<T>, bottomBar: @Composable () -> Unit = {}, entryProvider: EntryProviderScope<T>.() -> Unit) {
    val sceneStrategy: ListDetailSceneStrategy<T> = rememberListDetailSceneStrategy()
    val resultRegistry = remember { NavResultRegistry() }
    val snackbarHostState = remember { SnackbarHostState() }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = bottomBar
    ) { paddingValues ->
        CompositionLocalProvider(
            LocalNavResultRegistry provides resultRegistry,
            LocalSnackbarHostState provides snackbarHostState
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
                backStack = backStack.backStack, entryProvider = {
                    EntryProviderScope(it).apply {
                        entryProvider()
                    }.result!!
                })
        }
    }
}
@Composable
fun <T: NavKey> rememberNavBackStack(vararg elements: T): NavBackStack<T> {
    return remember { NavBackStack(elements) }
}

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
