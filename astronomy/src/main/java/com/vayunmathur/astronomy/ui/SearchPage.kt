package com.vayunmathur.astronomy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.astronomy.R
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.platform.AstronomyViewModel
import com.vayunmathur.astronomy.platform.SearchActions
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource

/** Binds [AstronomyViewModel] to the stateless [SearchScreen]. */
@Composable
fun SearchPage(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel) {
    SearchScreen(backStack = backStack, actions = viewModel)
}

/**
 * The catalog search screen, with no dependency on the ViewModel so it can be rendered
 * from a `@Preview` — see `src/screenshotTest`, which is where the store listing images
 * come from.
 */
@Composable
fun SearchScreen(
    backStack: NavBackStack<Route>,
    actions: SearchActions,
    /**
     * Seed for the screen's own UI-only state (what is typed in the box). The app always
     * takes the default; a preview sets it to capture a screen with results on it.
     */
    initialQuery: String = "",
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf(actions.search(initialQuery)) }

    AppScaffold(
        title = stringResource(R.string.search_1),
        backStack = backStack,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it; results = actions.search(it) }, label = { Text(stringResource(R.string.search_stars_planets_messier)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results) { r ->
                    ListItem(
                        headlineContent = { Text(r.title) },
                        supportingContent = { Text(r.subtitle) },
                        trailingContent = { IconChevronRight() },
                        modifier = Modifier.clickable {
                            if (!r.id.startsWith("CONST_")) {
                                actions.selectObject(r.id)
                                // Drop the search page from the stack so back from the
                                // detail page returns straight to the sky map.
                                backStack.pop()
                                backStack.add(Route.ObjectDetail(r.id))
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
