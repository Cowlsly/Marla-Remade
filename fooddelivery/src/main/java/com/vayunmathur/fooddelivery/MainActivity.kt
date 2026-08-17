package com.vayunmathur.fooddelivery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.stripe.android.PaymentConfiguration
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconLocalOffer
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.fooddelivery.api.BitesApi
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CartStore
import com.vayunmathur.fooddelivery.ipc.OrderLookupContract
import com.vayunmathur.fooddelivery.notifications.OrderLiveUpdate
import com.vayunmathur.fooddelivery.ui.AccountScreen
import com.vayunmathur.fooddelivery.ui.CartScreen
import com.vayunmathur.fooddelivery.ui.CheckoutScreen
import com.vayunmathur.fooddelivery.ui.DealsScreen
import com.vayunmathur.fooddelivery.ui.HomeScreen
import com.vayunmathur.fooddelivery.ui.OrderTrackingScreen
import com.vayunmathur.fooddelivery.ui.OrdersScreen
import com.vayunmathur.fooddelivery.ui.RestaurantScreen
import kotlinx.serialization.Serializable
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle

sealed interface Route : NavKey {
    @Serializable data class Main(val initialTab: Int = 0) : Route
    @Serializable data class Restaurant(val id: Int) : Route
    @Serializable data object Checkout : Route
    @Serializable data class OrderTracking(val orderId: Int) : Route
}

class MainActivity : ComponentActivity() {
    // The order to open on the tracking screen, set from a notification tap. Held as
    // Compose state so onNewIntent can push a new deep link into the running UI.
    private val trackOrderId = mutableStateOf<Int?>(null)

    // A restaurant to open, set from a fooddelivery://restaurant/<id> deep link
    // (e.g. the maps place-sheet "Order" button). Same Compose-state pattern so
    // onNewIntent can re-deep-link into the running UI.
    private val openRestaurantId = mutableStateOf<Int?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        trackOrderId.value = intent.trackOrderIdOrNull()
        openRestaurantId.value = intent.restaurantIdOrNull()
        requestNotificationPermissionIfNeeded()
        // api.deliverycollective.com is on AWS Elastic Beanstalk and serves an ACM cert
        // chaining to Amazon Root CA 1, which FIRST_PARTY (ISRG + GTS only) doesn't carry —
        // pinning to it fails the handshake before any request goes out. STANDARD adds the
        // Amazon roots.
        NetworkClient.init(this, TrustBundle.STANDARD)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("fooddelivery_prefs", Context.MODE_PRIVATE)
        val tokenJson = prefs.getString("token_json", null)
        if (tokenJson != null) {
            BitesApi.restoreToken(tokenJson)
        }

        PaymentConfiguration.init(
            applicationContext,
            "pk_live_51NQy7lFJFBMK4hv9KubgZcyH2Wy0MsXn9BtrtM7moEi762WE7pcmZ1JL9BrCKPRKw6ZJdGo9YJSA1pidb0KUthlJ00Wr4bcpVD"
        )

        setContent {
            DynamicTheme {
                FoodDeliveryApp(trackOrderId, openRestaurantId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.trackOrderIdOrNull()?.let { trackOrderId.value = it }
        intent.restaurantIdOrNull()?.let { openRestaurantId.value = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun Intent.trackOrderIdOrNull(): Int? =
    getIntExtra(OrderLiveUpdate.EXTRA_TRACK_ORDER_ID, -1).takeIf { it > 0 }

// fooddelivery://restaurant/<id> — the cross-app "open this restaurant's order
// page" deep link (see OrderLookupContract). Returns null for anything else.
private fun Intent.restaurantIdOrNull(): Int? {
    val uri = data ?: return null
    if (uri.scheme != OrderLookupContract.DEEP_LINK_SCHEME || uri.host != OrderLookupContract.DEEP_LINK_HOST) return null
    return uri.lastPathSegment?.toIntOrNull()?.takeIf { it > 0 }
}

@Composable
private fun FoodDeliveryApp(trackOrderId: MutableState<Int?>, openRestaurantId: MutableState<Int?>) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack<Route>(Route.Main())

    // A notification tap deep-links to that order's tracking screen.
    LaunchedEffect(trackOrderId.value) {
        val id = trackOrderId.value ?: return@LaunchedEffect
        backStack.add(Route.OrderTracking(id))
        trackOrderId.value = null
    }

    // A fooddelivery://restaurant/<id> deep link opens that restaurant's page.
    LaunchedEffect(openRestaurantId.value) {
        val id = openRestaurantId.value ?: return@LaunchedEffect
        backStack.add(Route.Restaurant(id))
        openRestaurantId.value = null
    }
    val cart = remember { mutableStateListOf<CartItem>().also { it.addAll(CartStore.getAll(context)) } }

    MainNavigation(backStack) {
        entry<Route.Main> { route ->
            FoodDeliveryTabs(
                initialTab = route.initialTab,
                backStack = backStack,
                cart = cart,
            )
        }
        entry<Route.Restaurant> { route ->
            RestaurantScreen(
                merchantId = route.id,
                onBack = { backStack.pop() },
                onAddToCart = { item -> cart.add(item); CartStore.save(context, cart) }
            )
        }
        entry<Route.Checkout> {
            CheckoutScreen(
                items = cart,
                onBack = { backStack.pop() },
                onOrderPlaced = {
                    cart.clear()
                    CartStore.clear(context)
                    backStack.reset(Route.Main(initialTab = 3))
                },
            )
        }
        entry<Route.OrderTracking> { route ->
            OrderTrackingScreen(orderId = route.orderId, onBack = { backStack.pop() })
        }
    }
}

/**
 * The five bottom-nav tabs, hosted in a swipeable pager (see [TabbedPagerScaffold]).
 * Restaurant, Checkout and OrderTracking are pushed on top of this host as ordinary routes.
 */
@Composable
private fun FoodDeliveryTabs(
    initialTab: Int,
    backStack: NavBackStack<Route>,
    cart: androidx.compose.runtime.snapshots.SnapshotStateList<CartItem>,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, 4), pageCount = { 5 })
    val tabs = listOf(
        PagerTab("Home", { IconHome() }) {
            HomeScreen(onMerchantClick = { id -> backStack.add(Route.Restaurant(id)) })
        },
        PagerTab("Cart", { IconShoppingCart() }) {
            CartScreen(
                items = cart,
                onRemoveItem = { cart.removeAt(it); CartStore.save(context, cart) },
                onCheckout = { backStack.add(Route.Checkout) },
                onEditModifiers = { index, modifiers ->
                    cart[index] = cart[index].copy(selectedModifiers = modifiers)
                    CartStore.save(context, cart)
                },
            )
        },
        PagerTab("Deals", { IconLocalOffer() }) {
            DealsScreen(onMerchantClick = { id -> backStack.add(Route.Restaurant(id)) })
        },
        PagerTab("Orders", { IconPackage() }) {
            OrdersScreen(onTrackOrder = { id -> backStack.add(Route.OrderTracking(id)) })
        },
        PagerTab("Account", { IconPerson() }) {
            AccountScreen()
        },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
