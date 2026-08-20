package com.vayunmathur.fooddelivery.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.fooddelivery.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.fooddelivery.api.BitesApi
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.MerchantDetail
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.data.MerchantRewards
import com.vayunmathur.fooddelivery.data.SelectedModifier
import com.vayunmathur.fooddelivery.platform.AppInit
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** How long typing has to settle before the menu is re-filtered. */
private const val FILTER_DEBOUNCE_MS = 150L

@Composable
fun RestaurantScreen(
    merchantId: Int,
    onBack: () -> Unit,
    onAddToCart: (CartItem) -> Unit,
) {
    var merchant by remember { mutableStateOf<MerchantDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var rewards by remember { mutableStateOf<MerchantRewards?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(merchantId) {
        AppInit.awaitReady()
        merchant = BitesApi.getMerchantDetail(merchantId)
        loading = false
        rewards = BitesApi.getCustomerMerchantRewards().firstOrNull { it.merchantId == merchantId }
    }

    RestaurantContent(
        merchant = merchant,
        loading = loading,
        rewards = rewards,
        onJoinLoyalty = { code ->
            scope.launch {
                if (BitesApi.createCustomerMerchantLoyalty(code, merchantId)) {
                    rewards = BitesApi.getCustomerMerchantRewards().firstOrNull { it.merchantId == merchantId }
                }
            }
        },
        onLeaveLoyalty = {
            scope.launch {
                if (BitesApi.deleteCustomerMerchantLoyalty(merchantId)) {
                    rewards = BitesApi.getCustomerMerchantRewards().firstOrNull { it.merchantId == merchantId }
                }
            }
        },
        onBack = onBack,
        onAddItem = { item, selectedModifiers ->
            onAddToCart(CartItem(
                menuItem = item,
                merchantId = merchantId,
                merchantName = merchant?.name ?: "",
                selectedModifiers = selectedModifiers
            ))
        },
    )
}

/**
 * The menu, with no API call of its own so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(FlowPreview::class)
@Composable
fun RestaurantContent(
    merchant: MerchantDetail?,
    loading: Boolean = false,
    rewards: MerchantRewards? = null,
    onJoinLoyalty: (String) -> Unit = {},
    onLeaveLoyalty: () -> Unit = {},
    onBack: () -> Unit = {},
    onAddItem: (MenuItem, List<SelectedModifier>) -> Unit = { _, _ -> },
) {
    var customizeItem by remember { mutableStateOf<MenuItem?>(null) }
    var query by remember { mutableStateOf("") }
    // The field itself stays instant; the menu is only re-filtered once typing settles, so a
    // keystroke no longer re-walks and re-sorts every category.
    var appliedQuery by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        snapshotFlow { query }.debounce(FILTER_DEBOUNCE_MS).collect { appliedQuery = it }
    }

    customizeItem?.let { item ->
        ModifierDialog(
            item = item,
            onDismiss = { customizeItem = null },
            onConfirm = { selectedModifiers ->
                onAddItem(item, selectedModifiers)
                customizeItem = null
            }
        )
    }

    Scaffold { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (merchant == null) {
            EmptyState(
                title = stringResource(R.string.restaurant_not_found),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            val m = merchant
            val q = appliedQuery.trim()
            // Rebuilding the id index and the filtered menu on every recomposition is what made
            // typing in the search field lag, so both are kept until their inputs change.
            val itemsById = remember(m.items) { m.items.associateBy { it.id } }
            val activeCategories = remember(m.categories) {
                m.categories.filter { it.isActive }.sortedBy { it.sortOrder }
            }
            val sections = remember(m.items, m.categories, q) {
                activeCategories.mapNotNull { category ->
                    val categoryItems = category.itemIds.mapNotNull { itemsById[it] }
                        .filter {
                            it.isAvailable && it.isInStock && (q.isEmpty() ||
                                it.name.contains(q, ignoreCase = true) ||
                                it.description.contains(q, ignoreCase = true))
                        }
                    if (categoryItems.isEmpty()) null else category to categoryItems
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.padding(padding)
            ) {
                if (m.imageUrl.isNotEmpty()) {
                    item {
                        Box {
                            AsyncImage(
                                model = m.imageUrl,
                                contentDescription = m.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            )
                            IconButton(onClick = onBack,
                                modifier = Modifier.padding(4.dp)) {
                                IconBack()
                            }
                        }
                    }
                } else {
                    item {
                        IconButton(onClick = onBack,
                            modifier = Modifier.padding(4.dp)) {
                            IconBack()
                        }
                    }
                }
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(m.name, style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        if (m.merchantTags.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(m.merchantTags.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if ((m.averageRating ?: 0.0) > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconStar(Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(2.dp))
                                    Text("%.1f (%d)".format(m.averageRating, m.totalRatings ?: 0),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (m.nextOpenWindow.isNotEmpty()) {
                                Text(m.nextOpenWindow, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if ((m.rewardsPercentage ?: 0.0) > 0) {
                                Text(stringResource(R.string.back, (m.rewardsPercentage?.toInt()).toString()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                item {
                    MerchantRewardsCard(
                        rewards = rewards,
                        onJoin = onJoinLoyalty,
                        onLeave = onLeaveLoyalty,
                    )
                }

                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.search_menu)) },
                        singleLine = true,
                        leadingIcon = { IconSearch() },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) { IconClose() }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (sections.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.no_menu_items_match, q),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                sections.forEach { (category, categoryItems) ->
                    item {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(category.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(8.dp))
                    }
                    // An item can sit in more than one category, so the key has to include the
                    // category to stay unique across the whole list.
                    items(categoryItems, key = { "${category.id}-${it.id}" }) { menuItem ->
                        MenuItemRow(menuItem) {
                            if (menuItem.modifierGroups.isNotEmpty()) {
                                customizeItem = menuItem
                            } else {
                                onAddItem(menuItem, emptyList())
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pick modifiers for [item]. Pass [initialSelection] to reopen it over an existing choice
 * (editing a cart line) instead of starting empty.
 */
@Composable
fun ModifierDialog(
    item: MenuItem,
    onDismiss: () -> Unit,
    onConfirm: (List<SelectedModifier>) -> Unit,
    initialSelection: List<SelectedModifier> = emptyList(),
    confirmLabel: String? = null,
) {
    val selections = remember(item.id, initialSelection) {
        mutableStateMapOf<Int, MutableSet<Int>>().apply {
            initialSelection.forEach { sel ->
                getOrPut(sel.modifierGroupId) { mutableSetOf() }.add(sel.modifierId)
            }
            // Seeded here rather than written back during composition, which would dirty the
            // map it had just been read from and force another pass.
            item.modifierGroups.forEach { group -> getOrPut(group.id) { mutableSetOf() } }
        }
    }

    // Capture each pick together with the group it came from, so checkout never has to
    // reconstruct modifierGroupId by searching the menu (which silently fell back to 0).
    val allModifiers = item.modifierGroups.flatMap { group ->
        val selected = selections[group.id] ?: emptySet()
        group.modifiers.filter { it.id in selected }.map { mod ->
            SelectedModifier(
                modifierGroupId = group.id,
                modifierId = mod.id,
                name = mod.name,
                price = mod.price,
            )
        }
    }
    val extrasTotal = allModifiers.sumOf { it.priceDollars }
    val totalPrice = item.priceDollars + extrasTotal

    val requiredMet = item.modifierGroups.all { group ->
        !group.required || (selections[group.id]?.size ?: 0) >= group.minSelections
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (item.description.isNotEmpty()) {
                    Text(item.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Text("$%.2f".format(item.priceDollars),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                item.modifierGroups.forEach { group ->
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text(group.name, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall)
                        if (group.required) {
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.required), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (group.maxSelections > 1 || !group.required) {
                        Text(stringResource(R.string.select_up_to, group.maxSelections),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    val selected = selections[group.id] ?: mutableSetOf()
                    val isSingleSelect = group.maxSelections == 1

                    group.modifiers.forEach { mod ->
                        val isSelected = mod.id in selected
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSingleSelect) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selections[group.id] = mutableSetOf(mod.id)
                                    }
                                )
                            } else {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        val set = selections.getOrPut(group.id) { mutableSetOf() }
                                        if (checked && set.size < group.maxSelections) {
                                            set.add(mod.id)
                                        } else {
                                            set.remove(mod.id)
                                        }
                                        selections[group.id] = set.toMutableSet()
                                    }
                                )
                            }
                            Text(mod.name, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            if (mod.price > 0) {
                                Text("+$%.2f".format(mod.priceDollars),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(allModifiers) },
                enabled = requiredMet
            ) {
                Text(confirmLabel ?: "Add $%.2f".format(totalPrice))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun MenuItemRow(item: MenuItem, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium)
            if (item.description.isNotEmpty()) {
                Text(item.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$%.2f".format(item.priceDollars), style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium)
                val ddPrice = item.doordashPriceDollars
                if (ddPrice != null && ddPrice > item.priceDollars) {
                    Text("$%.2f on DD".format(ddPrice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (item.modifierGroups.isNotEmpty()) {
                Text(stringResource(R.string.customizable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (item.displayImage.isNotEmpty()) {
            AsyncImage(
                model = item.displayImage,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onAdd) {
            IconAdd(tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Per-merchant reward balance plus loyalty enrolment. Joining takes the merchant's invite
 * code; leaving removes the customer from that merchant's loyalty programme.
 */
@Composable
private fun MerchantRewardsCard(
    rewards: MerchantRewards?,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val enrolled = rewards != null

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.rewards), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (rewards != null && rewards.balance > 0) {
                Text(
                    stringResource(R.string.rewards_available,
                        "$%.2f".format(rewards.balanceDollars)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(stringResource(R.string.no_rewards_here_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))
            if (enrolled) {
                OutlinedButton(onClick = onLeave) { Text(stringResource(R.string.leave_loyalty)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.invite_code)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onJoin(code.trim()); code = "" },
                        enabled = code.isNotBlank(),
                    ) { Text(stringResource(R.string.join)) }
                }
            }
        }
    }
}
