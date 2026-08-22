package com.vayunmathur.clock.ui.dialogs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.platform.WorldClockCities
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SelectTimeZonesDialog(backStack: NavBackStack<Route>, ds: DataStoreUtils, clockViewModel: ClockViewModel) {
    val selectedCities by WorldClockCities.flow(ds).collectAsState(initial = null)
    val cities by clockViewModel.cities.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Map all available IDs to a pair of (Clean City Name, Original ID)
    val allOptions = remember(cities) {
        cities?.entries?.map { Triple(it.key, it.value, "${it.key} ${it.value}".lowercase()) }
    } ?: listOf()

    // Keyed on the arrival of the stored selection rather than on the selection itself, so the
    // pinned block is recomputed per search but a row never jumps out from under a tapping finger.
    val selectionLoaded = selectedCities != null
    val filteredOptions by produceState(initialValue = allOptions, allOptions, searchQuery, selectionLoaded) {
        val selected = selectedCities.orEmpty().toSet()
        value = withContext(Dispatchers.Default) {
            val matches = if (searchQuery.isEmpty()) {
                allOptions
            } else {
                allOptions.filter { (_, _, searchable) ->
                    searchable.contains(searchQuery.lowercase())
                }
            }
            val (pinned, rest) = matches.partition { (city, _, _) -> city in selected }
            pinned + rest
        }
    }
    Dialog({ backStack.pop() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f) // Limit height so it doesn't take the whole screen
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.select_cities_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.search_city_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.weight(1f)) {
                    items(filteredOptions, key = { (city, _) -> city }) { (city, id) ->
                        val isSelected = city in selectedCities.orEmpty()
                        val toggle = { scope.launch { WorldClockCities.toggle(ds, city) } }
                        ListItem(
                            content = { Text(city) },
                            supportingContent = { Text(id, style = MaterialTheme.typography.labelSmall) },
                            trailingContent = {
                                Checkbox(checked = isSelected, onCheckedChange = { toggle() })
                            },
                            modifier = Modifier.clickable { toggle() },
                            colors = ListItemDefaults.colors(Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}