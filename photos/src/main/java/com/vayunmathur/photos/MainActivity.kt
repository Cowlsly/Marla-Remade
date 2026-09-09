package com.vayunmathur.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconMap
import com.vayunmathur.library.ui.IconPhotoLibrary
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.photos.R
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.BottomNavBarItem
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.widgets.updateWidgetPreviews
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.glance.PhotoGlanceWidgetReceiver
import com.vayunmathur.photos.ui.GalleryPage
import com.vayunmathur.photos.ui.MapPage
import com.vayunmathur.photos.ui.PeoplePage
import com.vayunmathur.photos.ui.PhotoPage
import com.vayunmathur.photos.ui.SecureFolderPage
import com.vayunmathur.photos.ui.TrashPage
import com.vayunmathur.photos.ui.WallpaperPage
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.GalleryViewModelFactory
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.PhotoMapViewModel
import com.vayunmathur.photos.util.PhotoMapViewModelFactory
import com.vayunmathur.photos.util.SecureFolderViewModel
import com.vayunmathur.photos.util.SecureFolderViewModelFactory
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

private const val COLUMN_COUNT_KEY = "photos_column_count"

val LocalColumnCount = staticCompositionLocalOf<MutableFloatState> {
    error("No LocalColumnCount provided")
}

class MainActivity : FragmentActivity() {
    private val galleryViewModel: GalleryViewModel by viewModels {
        GalleryViewModelFactory(application, PhotosRepository.get(application))
    }
    private val photoMapViewModel: PhotoMapViewModel by viewModels {
        PhotoMapViewModelFactory(application)
    }
    private val secureFolderViewModel: SecureFolderViewModel by viewModels {
        SecureFolderViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateWidgetPreviews(PhotoGlanceWidgetReceiver::class)
        enableEdgeToEdge()
        ImageLoader.init(this)
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        setContent {
            DynamicTheme {
                val columnCount = rememberSaveable {
                    mutableFloatStateOf(dataStore.getLong(COLUMN_COUNT_KEY)?.toFloat() ?: 3f)
                }
                LaunchedEffect(Unit) {
                    snapshotFlow { columnCount.floatValue.roundToInt().coerceIn(2, 8) }
                        .distinctUntilChanged()
                        .collect { dataStore.setLong(COLUMN_COUNT_KEY, it.toLong()) }
                }
                OfflineAware {
                    CompositionLocalProvider(LocalColumnCount provides columnCount) {
                        PermissionsWrapper(viewUri = if (intent?.action == Intent.ACTION_VIEW) intent?.data else null)
                    }
                }
            }
        }
    }

    @Composable
    private fun PermissionsWrapper(viewUri: Uri? = null) {
        val context = LocalContext.current
        
        // minSdk is 31. API 31-32 need READ_EXTERNAL_STORAGE, API 33+ need READ_MEDIA_*
        // MANAGE_MEDIA is needed on API 31+ (checked after storage permissions)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            PermissionsChecker(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                ), getString(R.string.grant_image_video_permissions)
            ) {
                CheckManageMediaPermission(context, viewUri)
            }
        } else {
            PermissionsChecker(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ), getString(R.string.grant_storage_permission)
            ) {
                CheckManageMediaPermission(context, viewUri)
            }
        }
    }
    
    @Composable
    private fun CheckManageMediaPermission(context: Context, viewUri: Uri? = null) {
        // Use state to track permission status, updated when activity resumes
        var hasManageMedia by remember { 
            mutableStateOf(MediaStore.canManageMedia(context)) 
        }
        
        // Re-check permission when the composable is resumed (user returns from Settings)
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasManageMedia = MediaStore.canManageMedia(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
        
        if (!hasManageMedia) {
            // Show a screen demanding MANAGE_MEDIA permission using Scaffold
            Scaffold { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(stringResource(R.string.media_management_permission_required),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.padding(16.dp))
                        Text(stringResource(R.string.this_app_needs_permission_to_manage_medi),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.padding(16.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                                context.startActivity(intent)
                            }
                        ) {
                            Text(stringResource(R.string.open_settings))
                        }
                    }
                }
            }
        } else {
            Navigation(galleryViewModel, photoMapViewModel, secureFolderViewModel, viewUri)
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Gallery: Route

    @Serializable
    data class PhotoPage(val id: Long, val overridePhotosList: List<Photo>?, val pendingUri: String? = null): Route

    @Serializable
    data object Map: Route

    @Serializable
    data object People: Route

    @Serializable
    data object Trash: Route

    @Serializable
    data object SecureFolder: Route

    @Serializable
    data class Wallpaper(val id: Long, val uri: String? = null) : Route
}

@Composable
fun Navigation(
    galleryViewModel: GalleryViewModel,
    photoMapViewModel: PhotoMapViewModel,
    secureFolderViewModel: SecureFolderViewModel,
    viewUri: Uri? = null,
) {
    // Opened via ACTION_VIEW from another app (e.g. the camera): that one photo is the whole app
    // for this launch, so it is the root of the stack and the gallery is not underneath it. Back
    // then belongs to the system, which finishes this Activity straight back to whoever fired the
    // intent - the same task they started us in - and runs the cross-activity predictive back
    // animation itself. An in-app back handler would swallow that gesture, animate nothing, and
    // land the user on a home screen they never asked for.
    //
    // The MediaStore _id in a content URI equals Photo.id, so the page can be named up front and
    // render the incoming URI directly while the background index writes the row and the full sync
    // populates the rest of the library for swiping. PhotoPage reconciles to the DB-backed pager.
    val viewerRoute = remember(viewUri) {
        viewUri?.let {
            Route.PhotoPage(
                runCatching { ContentUris.parseId(it) }.getOrNull() ?: -1L,
                null,
                it.toString(),
            )
        }
    }

    val backStack = rememberNavBackStack<Route>(viewerRoute ?: Route.Gallery)
    val vaultPhotoDao by secureFolderViewModel.vaultPhotoDao.collectAsState()
    val vaultPassword by secureFolderViewModel.vaultPassword.collectAsState()

    // Indexed once per incoming URI, and `rememberSaveable` rather than the LaunchedEffect key so
    // a configuration change does not re-run it.
    var indexedViewUri by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewUri) {
        if (viewUri == null || indexedViewUri) return@LaunchedEffect
        indexedViewUri = true
        galleryViewModel.resolveAndIndex(viewUri) {}
    }

    // PhotoPage pops itself when its last remaining photo is deleted. With the viewer as the root
    // there is nothing under it to pop to, and leaving the viewer here means leaving the app: hand
    // back to whoever sent us rather than hand NavDisplay an empty stack, which it rejects.
    val activity = LocalActivity.current
    if (backStack.backStack.isEmpty()) {
        LaunchedEffect(Unit) { activity?.finish() }
        return
    }

    MainNavigation(backStack) {
        entry<Route.Gallery>(metadata = SiblingPage()) {
            GalleryPage(backStack, galleryViewModel, secureFolderViewModel)
        }

        entry<Route.Map>(metadata = SiblingPage()) {
            MapPage(backStack, galleryViewModel, photoMapViewModel)
        }

        entry<Route.People>(metadata = SiblingPage()) {
            PeoplePage(backStack, galleryViewModel)
        }

        entry<Route.PhotoPage>(metadata = MorphPage()) {
            PhotoPage(galleryViewModel, photoMapViewModel, it.id, it.overridePhotosList, it.pendingUri, backStack)
        }

        entry<Route.Wallpaper> {
            WallpaperPage(backStack, it.id, it.uri)
        }

        entry<Route.Trash>(metadata = SiblingPage()) {
            TrashPage(backStack, galleryViewModel)
        }

        entry<Route.SecureFolder>(metadata = SiblingPage()) {
            SecureFolderEntry(backStack, secureFolderViewModel, vaultPhotoDao != null, vaultPassword)
        }
    }
}

@Composable
private fun SecureFolderEntry(
    backStack: NavBackStack<Route>,
    secureFolderViewModel: SecureFolderViewModel,
    isUnlocked: Boolean,
    vaultPassword: String?,
) {
    val activity = LocalActivity.current as FragmentActivity
    if (!isUnlocked) {
        LaunchedEffect(Unit) {
            secureFolderViewModel.unlock(
                activity,
                onSuccess = { _, _ -> },
                onFailure = { message ->
                    if (message != null) {
                        AppMessages.show(message)
                    }
                    backStack.pop()
                },
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
    } else {
        SecureFolderPage(backStack, vaultPassword!!, secureFolderViewModel)
    }
}

private enum class MainRoute(val route: Route, @StringRes val titleRes: Int, val icon: @Composable () -> Unit) {
    Gallery(Route.Gallery, R.string.label_gallery, { IconPhotoLibrary() }),
    Map(Route.Map, R.string.label_map, { IconMap() }),
    People(Route.People, R.string.label_people, { IconGroup() }),
    Trash(Route.Trash, R.string.label_trash, { IconDelete() }),
    SecureFolder(Route.SecureFolder, R.string.label_secure_folder, { IconLock() })
}

@Composable
fun NavigationBar(currentRoute: Route, backStack: NavBackStack<Route>) {
    BottomNavBar {
        MainRoute.entries.forEach {
            BottomNavBarItem(
                selected = it.route == currentRoute,
                // Photos pushes rather than resetting, so back returns to the
                // previous tab instead of leaving the app.
                onClick = { backStack.add(it.route) },
                icon = { it.icon() },
                label = stringResource(it.titleRes),
            )
        }
    }
}
