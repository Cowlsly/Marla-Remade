@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.ui

import kotlin.uuid.Uuid
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.*
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.FoodSearchAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.ui.BackupButtons
import kotlinx.coroutines.launch
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeManagementPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showIngredientSearch by remember { mutableStateOf(false) }

    val recipes by viewModel.allRecipes.collectAsState(emptyList())
    val ingredients by viewModel.allIngredients.collectAsState(emptyList())

    val isListEmpty = if (selectedTab == 0) recipes.isEmpty() else ingredients.isEmpty()

    if (showIngredientSearch) {
        IngredientSearchDialog(
            viewModel = viewModel,
            onDismiss = { showIngredientSearch = false },
            onIngredientSelected = { ingredient ->
                viewModel.insertIngredient(ingredient)
                showIngredientSearch = false
            }
        )
    }

    AppScaffold(
        title = stringResource(R.string.recipes),
        backStack = backStack,
        actions = { BackupButtons() },
        floatingActionButton = {
            if (!isListEmpty) {
                FloatingActionButton(onClick = { 
                    if (selectedTab == 0) {
                        backStack.add(Route.RecipeEditor()) 
                    } else {
                        showIngredientSearch = true
                    }
                }) {
                    IconAdd()
                }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                contentColor = HealthColors.Nutrition,
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, selectedContentColor = HealthColors.Nutrition, text = { Text(stringResource(R.string.recipes)) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, selectedContentColor = HealthColors.Nutrition, text = { Text(stringResource(R.string.ingredients)) })
            }

            // Ingredient search depends on the bundled food database, so its
            // first-run state belongs on the Ingredients tab.
            if (selectedTab == 1) {
                FoodDatabaseCard(viewModel, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            if (isListEmpty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = {
                        if (selectedTab == 0) {
                            backStack.add(Route.RecipeEditor())
                        } else {
                            showIngredientSearch = true
                        }
                    }) {
                        IconAdd()
                        Spacer(Modifier.width(8.dp))
                        Text(if (selectedTab == 0) stringResource(R.string.create_your_first_recipe) else stringResource(R.string.add_your_first_ingredient))
                    }
                }
            } else {
                if (selectedTab == 0) {
                    RecipesList(recipes, viewModel) { recipeId -> backStack.add(Route.RecipeEditor(recipeId)) }
                } else {
                    IngredientsList(ingredients, viewModel)
                }
            }
        }
    }
}

@Composable
fun RecipesList(recipes: List<Recipe>, viewModel: HealthViewModel, onRecipeClick: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(recipes, key = { it.id }) { recipe ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onRecipeClick(recipe.id) }
            ) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(recipe.name, style = MaterialTheme.typography.titleMedium, color = HealthColors.Nutrition)
                    IconButton(onClick = { viewModel.deleteRecipe(recipe) }) {
                        IconDelete()
                    }
                }
            }
        }
    }
}

@Composable
fun IngredientsList(ingredients: List<Ingredient>, viewModel: HealthViewModel) {
    var editingIngredient by remember { mutableStateOf<Ingredient?>(null) }
    
    if (editingIngredient != null) {
        var customName by remember { mutableStateOf(editingIngredient!!.customName ?: "") }
        AlertDialog(
            onDismissRequest = { editingIngredient = null },
            title = { Text(stringResource(R.string.rename_ingredient)) },
            text = {
                Column {
                    Text(stringResource(R.string.original_1, editingIngredient!!.originalName), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text(stringResource(R.string.custom_name)) }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newIng = editingIngredient!!.copy(customName = customName.ifBlank { null })
                    viewModel.updateIngredient(newIng)
                    editingIngredient = null
                }) { Text(stringResource(UiR.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingIngredient = null }) { Text(stringResource(UiR.string.cancel)) }
            }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ingredients, key = { it.id }) { ingredient ->
            Card(modifier = Modifier.fillMaxWidth().clickable { editingIngredient = ingredient }) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ingredient.displayName, style = MaterialTheme.typography.titleMedium)
                        if (ingredient.customName != null) {
                            Text(stringResource(R.string.original_1, ingredient.originalName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            viewModel.updateIngredient(ingredient.copy(isRecipe = !ingredient.isRecipe))
                        }) {
                            if (ingredient.isRecipe) IconFire() else IconFire(tint = MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { viewModel.deleteIngredient(ingredient) }) {
                            IconDelete()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditorPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel, recipeId: String? = null) {
    var recipeName by remember { mutableStateOf("") }
    var recipeIngredients by remember { mutableStateOf(listOf<RecipeIngredientData>()) }
    var showSearch by remember { mutableStateOf(false) }
    var editingIngredientData by remember { mutableStateOf<RecipeIngredientData?>(null) }
    var isAddingNewIngredient by remember { mutableStateOf(false) }
    // Guards against repeated taps launching multiple saves (which created
    // duplicate recipes and popped the back stack more than once).
    var saving by remember { mutableStateOf(false) }

    // Load existing recipe if editing
    LaunchedEffect(recipeId) {
        if (recipeId != null) {
            val loaded = viewModel.loadRecipeForEdit(recipeId)
            if (loaded != null) {
                recipeName = loaded.name
                recipeIngredients = loaded.ingredients.map { RecipeIngredientData(it.ingredient, it.unit, it.quantity) }
            }
        }
    }

    if (showSearch) {
        IngredientSearchDialog(
            viewModel = viewModel,
            includeLocal = true,
            onDismiss = { showSearch = false },
            onIngredientSelected = { ingredient ->
                showSearch = false
                // Default to 100g
                editingIngredientData = RecipeIngredientData(
                    ingredient,
                    ServingUnit(id = Uuid.random().toString(), ingredientId = ingredient.id, name = "g", grams = 1.0),
                    100.0
                )
                isAddingNewIngredient = true
            }
        )
    }

    if (editingIngredientData != null) {
        IngredientQuantityDialog(
            viewModel = viewModel,
            ingredient = editingIngredientData!!.ingredient,
            initialQuantity = editingIngredientData!!.quantity,
            initialUnit = editingIngredientData!!.unit,
            onDismiss = {
                editingIngredientData = null
                isAddingNewIngredient = false
            },
            onConfirm = { quantity, unit ->
                if (isAddingNewIngredient) {
                    recipeIngredients = recipeIngredients + RecipeIngredientData(editingIngredientData!!.ingredient, unit, quantity)
                } else {
                    recipeIngredients = recipeIngredients.map {
                        if (it === editingIngredientData) {
                            RecipeIngredientData(it.ingredient, unit, quantity)
                        } else it
                    }
                }
                editingIngredientData = null
                isAddingNewIngredient = false
            }
        )
    }

    AppScaffold(
        title = if (recipeId == null) stringResource(R.string.create_recipe) else stringResource(R.string.edit_recipe),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = recipeName,
                onValueChange = { recipeName = it },
                label = { Text(stringResource(R.string.recipe_name)) },
                modifier = Modifier.fillMaxWidth()
            )
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ingredients), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showSearch = true }) {
                    IconAdd()
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(UiR.string.add))
                }
            }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recipeIngredients, key = { it.ingredient.id }) { riData ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            editingIngredientData = riData
                            isAddingNewIngredient = false
                        }
                    ) {
                        Row(modifier = Modifier.padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(riData.ingredient.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text("${riData.quantity} ${riData.unit.name}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = {
                                recipeIngredients = recipeIngredients - riData
                            }) {
                                IconDelete()
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (saving) return@Button
                    saving = true
                    val items = recipeIngredients.map {
                        HealthViewModel.RecipeIngredientLoad(it.ingredient, it.unit, it.quantity)
                    }
                    viewModel.saveRecipe(recipeId, recipeName, items) {
                        backStack.pop()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = recipeName.isNotBlank() && recipeIngredients.isNotEmpty() && !saving
            ) {
                Text(stringResource(R.string.save_recipe))
            }
        }
    }
}

data class RecipeIngredientData(
    val ingredient: Ingredient,
    val unit: ServingUnit,
    val quantity: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientQuantityDialog(
    viewModel: HealthViewModel,
    ingredient: Ingredient,
    initialQuantity: Double,
    initialUnit: ServingUnit,
    onDismiss: () -> Unit,
    onConfirm: (Double, ServingUnit) -> Unit
) {
    var quantityStr by remember { mutableStateOf(initialQuantity.toString()) }
    var selectedUnit by remember { mutableStateOf(initialUnit) }
    var unitExpanded by remember { mutableStateOf(false) }
    var availableUnits by remember { mutableStateOf(listOf<ServingUnit>()) }

    LaunchedEffect(ingredient.id) {
        val units = viewModel.getUnitsForIngredient(ingredient.id)
        // Ensure the default unit or current unit is in the list
        val unitsWithCurrent = if (units.none { it.name == initialUnit.name }) {
            units + initialUnit
        } else units
        
        // Add "g" if not present at all
        val finalUnits = if (unitsWithCurrent.none { it.name == "g" }) {
            unitsWithCurrent + ServingUnit(id = Uuid.random().toString(), ingredientId = ingredient.id, name = "g", grams = 1.0)
        } else unitsWithCurrent
        
        availableUnits = finalUnits
        selectedUnit = finalUnits.find { it.name == initialUnit.name } ?: initialUnit
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_quantity_for_format, ingredient.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = quantityStr,
                    onValueChange = { quantityStr = it },
                    label = { Text(stringResource(R.string.quantity)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedUnit.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.unit)) },
                        trailingIcon = {
                            IconButton(onClick = { unitExpanded = true }) {
                                IconArrowDropDown()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = unitExpanded,
                        onDismissRequest = { unitExpanded = false }
                    ) {
                        availableUnits.forEach { unit ->
                            SelectableDropdownMenuItem(
                                selected = unit == selectedUnit,
                                onClick = {
                                    selectedUnit = unit
                                    unitExpanded = false
                                },
                                text = { Text(unit.name) },
                                selectedLeadingIcon = { IconCheck() },
                            )
                        }
                    }
                    Box(Modifier.matchParentSize().clickable { unitExpanded = true })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val q = quantityStr.toDoubleOrNull() ?: 0.0
                    onConfirm(q, selectedUnit)
                },
                enabled = quantityStr.toDoubleOrNull() != null
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        }
    )
}

@Composable
fun IngredientSearchDialog(
    viewModel: HealthViewModel,
    includeLocal: Boolean = false,
    onDismiss: () -> Unit,
    onIngredientSelected: (Ingredient) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var remoteResults by remember { mutableStateOf(listOf<FoodSearchAPI.SearchResult>()) }
    var localResults by remember { mutableStateOf(listOf<Ingredient>()) }
    var isSearching by remember { mutableStateOf(false) }
    var isFetchingData by remember { mutableStateOf(false) }
    val foodDbStatus by viewModel.foodDatabaseStatus.collectAsState()
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_ingredient)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.search)) },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        isSearching = true
                        scope.launch {
                            val results = viewModel.searchIngredients(query, includeLocal)
                            remoteResults = results.remote
                            localResults = results.local
                            isSearching = false
                        }
                    }) {
                        Text(stringResource(R.string.search))
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                if (isSearching || isFetchingData) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        // Until the database is unpacked there is nothing to
                        // search but the user's own ingredients; say so here.
                        if (foodDbStatus.installed == null) {
                            item {
                                FoodDatabaseCard(viewModel, modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }

                        // Show Local results first if included
                        if (includeLocal && localResults.isNotEmpty()) {
                            item {
                                Text(stringResource(R.string.saved_locally), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
                            }
                            items(localResults, key = { "local-${it.id}" }) { ingredient ->
                                ListItem(
                                    content = { Text(ingredient.displayName) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.clickable { onIngredientSelected(ingredient) }
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }

                        // Show matches from the downloaded food database
                        if (remoteResults.isNotEmpty()) {
                            item {
                                Text(stringResource(R.string.food_database), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
                            }
                            items(remoteResults, key = { "remote-${it.id}" }) { result ->
                                // Skip if already in local results to avoid duplicates
                                if (includeLocal && localResults.any { it.id == result.id.toString() }) return@items

                                ListItem(
                                    content = { Text(result.displayName) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.clickable {
                                        isFetchingData = true
                                        scope.launch {
                                            val ingredient = FoodSearchAPI.getIngredientData(result.id, result.displayName)
                                            isFetchingData = false
                                            if (ingredient != null) {
                                                onIngredientSelected(ingredient)
                                            }
                                        }
                                    }
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                        
                        // The card above already explains an empty result set
                        // when the database isn't installed.
                        if (!isSearching && !isFetchingData && remoteResults.isEmpty() && localResults.isEmpty() &&
                            query.isNotBlank() && foodDbStatus.installed != null
                        ) {
                            item {
                                EmptyState(
                                    title = stringResource(R.string.no_results_found),
                                    icon = { IconSearch() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                // ODbL requires attribution wherever this data is shown.
                Text(
                    stringResource(R.string.food_database_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        }
    )
}

/**
 * First-run expansion of the food database shipped inside the APK.
 *
 * There is nothing to download and nothing to choose, so this only ever
 * reports progress or an error. SQLite can't read a file inside an APK, so the
 * asset has to be unpacked into app storage once before ingredient search can
 * use it; until then the recipe builder can only find ingredients already
 * saved on the device.
 */
@Composable
fun FoodDatabaseCard(viewModel: HealthViewModel, modifier: Modifier = Modifier) {
    val status by viewModel.foodDatabaseStatus.collectAsState()

    // Unpacking is what makes search work, so start it as soon as a screen
    // that needs it appears. A no-op once it has been done.
    LaunchedEffect(Unit) { viewModel.prepareFoodDatabase() }

    // Nothing worth saying once it's ready and searchable.
    if (status is FoodDatabase.Status.Ready) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.food_database),
                style = MaterialTheme.typography.titleMedium,
                color = HealthColors.Nutrition,
            )

            when (val state = status) {
                is FoodDatabase.Status.Absent -> {
                    Text(stringResource(R.string.food_database_absent), style = MaterialTheme.typography.bodySmall)
                }

                is FoodDatabase.Status.Preparing -> {
                    // totalProducts is 0 only in the instant before the asset
                    // header is read; show an indeterminate bar, not 0%.
                    if (state.totalProducts > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (state.productsWritten.toFloat() / state.totalProducts).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        stringResource(
                            R.string.food_database_preparing,
                            NumberFormat.getIntegerInstance().format(state.productsWritten),
                            NumberFormat.getIntegerInstance().format(state.totalProducts),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is FoodDatabase.Status.Failed -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { viewModel.prepareFoodDatabase() }) {
                        Text(stringResource(R.string.food_database_retry))
                    }
                }

                // Filtered out above; the compiler still wants the branch.
                is FoodDatabase.Status.Ready -> Unit
            }
        }
    }
}
