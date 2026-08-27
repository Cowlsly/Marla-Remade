package com.vayunmathur.library.map

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * Hosts the Vulkan renderer in Compose.
 *
 * ## TextureView, not SurfaceView
 *
 * `maps/src/main/java/com/vayunmathur/maps/ui/map/MapSurface.kt:76-83` records why, from
 * production: a **SurfaceView renders into a separate surface and goes black and stops
 * taking input** after the composable is disposed navigating forward and recomposed on
 * the back-pop through Nav3's `AnimatedContent` transition — it only repaints once some
 * later recomposition forces a relayout. A TextureView draws in the normal view
 * hierarchy, so it composites and stays interactive across the transition.
 *
 * `games/voxels/.../ui/VoxelSurfaceView.kt` is a SurfaceView, but voxels' surface is
 * never navigated away from and back. Five consumer apps' navigation is, so the
 * known-good configuration wins over the cheaper one. The cost is one extra composite
 * per frame.
 *
 * A `SurfaceTexture` also gives the lifecycle honestly: returning true from
 * [TextureView.SurfaceTextureListener.onSurfaceTextureDestroyed] means we release it, so
 * the renderer is torn down — device idle, buffers freed, `ANativeWindow` released —
 * exactly when the window goes away.
 */
@Composable
internal fun VulkanMapSurface(
    cameraState: CameraState,
    darkBasemap: Boolean,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onFrame: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val host = remember(context) { MapSurfaceHost(context, cameraState, density, onFrame) }

    DisposableEffect(host) { onDispose { host.dispose() } }

    // Following the system theme costs nothing: the layer set is identical between
    // variants, so this re-colours in place rather than reloading a single tile.
    LaunchedEffect(darkBasemap, muted, host) { host.setPalette(darkBasemap, muted) }

    AndroidView(
        factory = {
            TextureView(context).apply {
                // The map is opaque, so tell the compositor: a translucent TextureView is
                // blended every frame for nothing.
                isOpaque = true
                surfaceTextureListener = host.listener
            }
        },
        modifier = modifier,
    )
}

/**
 * Owns the native renderer and the frame loop for one surface.
 *
 * Every native call happens on the main thread — the `Choreographer` callback is on it
 * anyway, and the renderer is not safe to drive from two threads at once. The expensive
 * work (range fetch, inflate, MVT decode, tessellation) is on a worker thread inside the
 * native side, so this callback only uploads finished meshes and records a command buffer.
 */
private class MapSurfaceHost(
    context: Context,
    private val cameraState: CameraState,
    private val density: Float,
    private val onFrame: () -> Unit,
) {
    private val appContext = context.applicationContext
    private var handle = 0L
    private var surface: Surface? = null
    private var frameCallback: Choreographer.FrameCallback? = null

    /** Remembered so a surface created after the theme was set still starts in it. */
    private var dark = false
    private var muted = false

    /**
     * Where cached byte ranges live. External files rather than the cache dir: this is
     * large and expensive to rebuild, so it should not be the first thing the platform
     * reclaims, and external files are outside the 25 MB cloud-backup quota.
     */
    private val cacheDir: File by lazy {
        val root = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        File(root, CACHE_DIR_NAME).apply { mkdirs() }
    }

    val listener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            if (!MapNative.isAvailable) {
                Log.e(TAG, "libmap_renderer.so did not load; the map will not draw")
                return
            }
            val created = Surface(texture)
            surface = created
            handle = MapNative.create(created, cacheDir.absolutePath, width, height, dark, muted)
            if (handle == 0L) {
                Log.e(TAG, "the Vulkan renderer failed to start; see MapRenderer in logcat")
                created.release()
                surface = null
                return
            }
            MapNative.setOnline(handle, isOnline())
            startFrameLoop()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            if (handle != 0L) MapNative.resize(handle, width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            teardown()
            // True: we release the SurfaceTexture, having already dropped the
            // ANativeWindow that pointed at it.
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private fun startFrameLoop() {
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (handle == 0L) return
                val viewport = cameraState.viewportDp
                if (viewport != null) {
                    val position = cameraState.position
                    val drawn = MapNative.render(
                        handle,
                        position.target.longitude.toFloat(),
                        position.target.latitude.toFloat(),
                        position.zoom.toFloat(),
                        viewport.width,
                        viewport.height,
                        density,
                    )
                    if (drawn) onFrame()
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun teardown() {
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
        // Destroy before releasing the Surface: the native side waits for the GPU to go
        // idle and releases the ANativeWindow that points at it.
        if (handle != 0L) {
            MapNative.destroy(handle)
            handle = 0L
        }
        surface?.release()
        surface = null
    }

    fun dispose() = teardown()

    fun setPalette(dark: Boolean, muted: Boolean) {
        this.dark = dark
        this.muted = muted
        if (handle != 0L) MapNative.setPalette(handle, dark, muted)
    }

    private fun isOnline(): Boolean {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            // No connectivity service is not evidence of being offline, and claiming
            // offline would pin the map to whatever is cached.
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val TAG = "VulkanMapSurface"
        const val CACHE_DIR_NAME = "vectortilecache"
    }
}
