@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class,
)

package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExposedDropdownMenuBox as Material3ExposedDropdownMenuBox
import androidx.compose.material3.FloatingActionButtonMenuItem as Material3FloatingActionButtonMenuItem
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp

// --- Theme function (distinct from the MaterialTheme object alias) ---
@Composable
fun MaterialTheme(
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    shapes: Shapes = MaterialTheme.shapes,
    typography: Typography = MaterialTheme.typography,
    content: @Composable () -> Unit,
) = androidx.compose.material3.MaterialTheme(
    colorScheme = colorScheme, shapes = shapes, typography = typography, content = content,
)

// --- Exposed dropdown menu ---
@Composable
fun ExposedDropdownMenuBox(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ExposedDropdownMenuBoxScope.() -> Unit,
) = Material3ExposedDropdownMenuBox(
    expanded = expanded, onExpandedChange = onExpandedChange, modifier = modifier, content = content,
)

@Composable
fun ExposedDropdownMenuBoxScope.ExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = ExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, content = content)

// --- Navigation drawer item ---
@Composable
fun NavigationDrawerItem(
    label: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    colors: NavigationDrawerItemColors = NavigationDrawerItemDefaults.colors(),
) = androidx.compose.material3.NavigationDrawerItem(
    label = label, selected = selected, onClick = onClick, modifier = modifier,
    icon = icon, badge = badge, colors = colors,
)

// --- Bottom app bars ---
@Composable
fun BottomAppBar(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = androidx.compose.material3.BottomAppBarDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.BottomAppBar(modifier = modifier, contentPadding = contentPadding, content = content)

@Composable
fun FlexibleBottomAppBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.FlexibleBottomAppBar(modifier = modifier, content = content)

// --- Bottom sheets ---
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.ModalBottomSheet(
    onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState,
    containerColor = containerColor, content = content,
)

@Composable
fun BottomSheetScaffold(
    sheetContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    scaffoldState: BottomSheetScaffoldState = rememberBottomSheetScaffoldState(),
    sheetPeekHeight: Dp = BottomSheetDefaults.SheetPeekHeight,
    sheetContainerColor: Color = BottomSheetDefaults.ContainerColor,
    sheetSwipeEnabled: Boolean = true,
    sheetDragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    topBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) = androidx.compose.material3.BottomSheetScaffold(
    sheetContent = sheetContent, modifier = modifier, scaffoldState = scaffoldState,
    sheetPeekHeight = sheetPeekHeight, sheetContainerColor = sheetContainerColor,
    sheetSwipeEnabled = sheetSwipeEnabled, sheetDragHandle = sheetDragHandle, topBar = topBar, content = content,
)

// --- Floating action button variants ---
@Composable
fun ExtendedFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.ExtendedFloatingActionButton(onClick = onClick, modifier = modifier, content = content)

@Composable
fun ExtendedFloatingActionButton(
    text: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
) = androidx.compose.material3.ExtendedFloatingActionButton(
    text = text, icon = icon, onClick = onClick, modifier = modifier, expanded = expanded,
)

@Composable
fun SmallFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = androidx.compose.material3.SmallFloatingActionButton(onClick = onClick, modifier = modifier, content = content)

@Composable
fun ToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.ToggleButton(
    checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled, content = content,
)

@Composable
fun ToggleFloatingActionButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: (Float) -> Color = androidx.compose.material3.ToggleFloatingActionButtonDefaults.containerColor(),
    content: @Composable ToggleFloatingActionButtonScope.() -> Unit,
) = androidx.compose.material3.ToggleFloatingActionButton(
    checked = checked, onCheckedChange = onCheckedChange, modifier = modifier,
    containerColor = containerColor, content = content,
)

@Composable
fun FloatingActionButtonMenu(
    expanded: Boolean,
    button: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable FloatingActionButtonMenuScope.() -> Unit,
) = androidx.compose.material3.FloatingActionButtonMenu(
    expanded = expanded, button = button, modifier = modifier, content = content,
)

@Composable
fun FloatingActionButtonMenuScope.FloatingActionButtonMenuItem(
    onClick: () -> Unit,
    text: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) = Material3FloatingActionButtonMenuItem(onClick = onClick, text = text, icon = icon, modifier = modifier)

// --- Short navigation bar (expressive) ---
@Composable
fun ShortNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = androidx.compose.material3.ShortNavigationBar(modifier = modifier, content = content)

@Composable
fun ShortNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) = androidx.compose.material3.ShortNavigationBarItem(
    selected = selected, onClick = onClick, icon = icon, label = label, modifier = modifier, enabled = enabled,
)

// --- Tab rows ---
@Composable
fun TabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = TabRowDefaults.primaryContainerColor,
    contentColor: Color = TabRowDefaults.primaryContentColor,
    tabs: @Composable () -> Unit,
) = androidx.compose.material3.SecondaryTabRow(
    selectedTabIndex = selectedTabIndex, modifier = modifier,
    containerColor = containerColor, contentColor = contentColor, tabs = tabs,
)

@Composable
fun SecondaryTabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = TabRowDefaults.secondaryContainerColor,
    contentColor: Color = TabRowDefaults.secondaryContentColor,
    tabs: @Composable () -> Unit,
) = androidx.compose.material3.SecondaryTabRow(
    selectedTabIndex = selectedTabIndex, modifier = modifier,
    containerColor = containerColor, contentColor = contentColor, tabs = tabs,
)

@Composable
fun SecondaryTabRow(
    selectedTabIndex: Int,
    indicator: @Composable TabIndicatorScope.() -> Unit,
    divider: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    tabs: @Composable () -> Unit,
) = androidx.compose.material3.SecondaryTabRow(
    selectedTabIndex = selectedTabIndex, modifier = modifier, indicator = indicator, divider = divider, tabs = tabs,
)

@Composable
fun PrimaryTabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = TabRowDefaults.primaryContainerColor,
    contentColor: Color = TabRowDefaults.primaryContentColor,
    tabs: @Composable () -> Unit,
) = androidx.compose.material3.PrimaryTabRow(
    selectedTabIndex = selectedTabIndex, modifier = modifier,
    containerColor = containerColor, contentColor = contentColor, tabs = tabs,
)

@Composable
fun PrimaryScrollableTabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    tabs: @Composable () -> Unit,
) = androidx.compose.material3.PrimaryScrollableTabRow(selectedTabIndex = selectedTabIndex, modifier = modifier, tabs = tabs)

// --- SearchBar InputField (wrapper over SearchBarDefaults.InputField) ---
@Suppress("NO_TAIL_COMMA")
@Composable
fun SearchBarInputField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) = androidx.compose.material3.SearchBarDefaults.InputField(
    query = query,
    onQueryChange = onQueryChange,
    onSearch = onSearch,
    expanded = expanded,
    onExpandedChange = onExpandedChange,
    modifier = modifier,
    enabled = enabled,
    placeholder = placeholder,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
)

// --- Adaptive navigation suite (openassistant) ---
@Composable
fun NavigationSuiteScaffold(
    navigationSuiteItems: NavigationSuiteScope.() -> Unit,
    modifier: Modifier = Modifier,
    layoutType: NavigationSuiteType = androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
        .calculateFromAdaptiveInfo(androidx.compose.material3.adaptive.currentWindowAdaptiveInfo()),
    content: @Composable () -> Unit,
) = androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold(
    navigationSuiteItems = navigationSuiteItems, modifier = modifier, layoutType = layoutType, content = content,
)

// --- Expressive button group ---
/**
 * A connected row of buttons that behave as one control.
 *
 * The Expressive replacement for a tab row when the choices are actions or short icon-led
 * options: the group reads as a single object, and pressing a member animates its neighbours
 * aside rather than sliding an indicator. Use [ButtonGroupScope.toggleableItem] for a
 * single-select group and [ButtonGroupScope.clickableItem] for a row of actions.
 *
 * [overflowIndicator] is what shows when the items do not fit; it defaults to a "more" button
 * that opens them as a menu. Defaulted rather than required because every caller wants the same
 * thing, but not omitted — a group with no indicator silently drops whatever overflows.
 */
@Composable
fun ButtonGroup(
    modifier: Modifier = Modifier,
    horizontalArrangement: androidx.compose.foundation.layout.Arrangement.Horizontal =
        androidx.compose.foundation.layout.Arrangement.spacedBy(
            androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween
        ),
    overflowIndicator: @Composable (androidx.compose.material3.ButtonGroupMenuState) -> Unit = { state ->
        FilledTonalIconButton(onClick = { if (state.isShowing) state.dismiss() else state.show() }) {
            IconMoreVert()
        }
    },
    content: ButtonGroupScope.() -> Unit,
) = androidx.compose.material3.ButtonGroup(
    overflowIndicator = overflowIndicator,
    modifier = modifier,
    horizontalArrangement = horizontalArrangement,
    content = content,
)