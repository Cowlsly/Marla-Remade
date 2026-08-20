package com.vayunmathur.fooddelivery.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.fooddelivery.R
import android.content.Context
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.fooddelivery.api.BitesApi
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.platform.AppInit

@Composable
fun HomeScreen(onMerchantClick: (Int) -> Unit) {
    val context = LocalContext.current
    var merchants by remember { mutableStateOf<List<Merchant>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var noAddress by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // The saved token is restored by the background warm-up; wait for it so a signed-in
        // session isn't asked for the catalog anonymously.
        AppInit.awaitReady()
        val defaultAddr = AddressStore.getDefault(context)
        if (defaultAddr != null) {
            merchants = BitesApi.getMerchants(lat = defaultAddr.latitude, lng = defaultAddr.longitude)
                .sortedBy { it.distance ?: Double.MAX_VALUE }
            noAddress = false
        } else {
            noAddress = true
        }
        loading = false
    }

    HomeContent(
        merchants = merchants,
        loading = loading,
        noAddress = noAddress,
        onMerchantClick = onMerchantClick,
    )
}

/**
 * The nearby-restaurant list, with no API call or address lookup of its own so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@Composable
fun HomeContent(
    merchants: List<Merchant>,
    loading: Boolean = false,
    noAddress: Boolean = false,
    onMerchantClick: (Int) -> Unit = {},
) {
    Scaffold { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (noAddress) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconHome(modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.set_your_address), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.add_an_address_in_account_to_see_nearby),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (merchants.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.no_restaurants_found), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.check_your_connection_or_try_again_later),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding)
            ) {
                // Deliberately unkeyed: /merchants/all/stores can return several store rows
                // per merchant and Merchant has no id-independent identity, so a key risks the
                // duplicate-key crash. It would buy nothing anyway — `merchants` is assigned
                // once and never replaced while the grid is on screen.
                items(merchants) { merchant ->
                    MerchantCard(merchant) { onMerchantClick(merchant.id) }
                }
            }
        }
    }
}

@Composable
private fun MerchantCard(merchant: Merchant, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            if (merchant.displayImage.isNotEmpty()) {
                AsyncImage(
                    model = merchant.displayImage,
                    contentDescription = merchant.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
            }
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        if (merchant.displayLogo.isNotEmpty()) {
                            AsyncImage(
                                model = merchant.displayLogo,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Column {
                            Text(merchant.name, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall)
                            if (merchant.merchantTags.isNotEmpty()) {
                                Text(
                                    merchant.merchantTags.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (merchant.displayRating > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconStar(modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(2.dp))
                            Text("%.1f".format(merchant.displayRating),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (!merchant.isOpen) {
                    Spacer(Modifier.height(4.dp))
                    Text(merchant.closingTime.ifEmpty { "Closed" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                } else if (merchant.nextOpenWindow.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(merchant.nextOpenWindow,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (merchant.distance != null) {
                        Text("%.1f mi".format(merchant.distance),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (merchant.address.isNotEmpty()) {
                        Text(merchant.addressCity.ifEmpty { merchant.address },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if ((merchant.doordashMarkup ?: 0) > 0) {
                        val saving = ((merchant.doordashMarkupComparison ?: 0) - (merchant.doordashMarkup ?: 0)) / 100.0
                        if (saving > 0) {
                            Text("Save $%.0f vs DoorDash".format(saving),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (merchant.displayRewardsPercentage > 0) {
                        Text(stringResource(R.string.back, merchant.displayRewardsPercentage.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
