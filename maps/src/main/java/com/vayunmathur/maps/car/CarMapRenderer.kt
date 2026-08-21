package com.vayunmathur.maps.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.vayunmathur.maps.ui.map.style.patchStyleForHybrid
import com.vayunmathur.maps.util.MapTileCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.spatialk.geojson.Position

/**
 * Renders the maps basemap onto the Android Auto car [Surface] (P12).
 *
 * `maplibre-compose` is a declarative, phone-only Compose wrapper and cannot
 * draw into an arbitrary car [Surface]. Instead we drive the **prebuilt** native
 * MapLibre Android SDK (`org.maplibre.android`, pulled in transitively by
 * maplibre-compose — prebuilt native is allowed, no hand-authored C++) via its
 * [MapSnapshotter]: for each frame we render the same patched `style.json` the
 * phone map uses off-screen to a [Bitmap], overlay the route line + puck onto
 * the bitmap (projected with [MapSnapshot.pixelForLatLng]), and blit it to the
 * car surface. This reuses the app's existing tile stack: [MapTileCache]
 * installs the `library:network` HTTP module so the snapshotter streams the same
 * pmtiles basemap as the phone.
 *
 * Frames are produced on demand — when the camera follows a new GPS fix
 * ([setCamera]) or the route/puck changes — not on a continuous render loop, so
 * a parked or stationary car costs nothing.
 *
 * All snapshotter interaction happens on the main thread (the snapshotter needs
 * a Looper and its callbacks return on the thread that started it); the
 * [SurfaceCallback] entry points are already invoked on the main thread and
 * [setCamera]/[setRoute]/[setPuck] marshal onto [mainHandler].
 */
internal class CarMapRenderer(
    private val carContext: CarContext,
    private val darkMap: Boolean = false,
) : SurfaceCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var pixelRatio = 1f

    private var snapshotter: MapSnapshotter? = null
    private var styleJson: String? = null

    // Camera + overlay state (main-thread only).
    private var cameraTarget: Position? = null
    private var cameraZoom = 15.0
    private var cameraBearing = 0.0
    private var cameraTilt = 0.0
    private var routePolyline: List<Position> = emptyList()
    private var puck: Position? = null

    private var rendering = false
    private var dirty = false

    // ---- Paints for the route/puck overlay ----
    private val routeCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 18f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#1A73E8")
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val puckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A73E8")
    }
    private val puckCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    // ----------------------------------------------------------------
    // SurfaceCallback
    // ----------------------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "onSurfaceAvailable ${surfaceContainer.width}x${surfaceContainer.height}")
        surface = surfaceContainer.getSurface()
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        pixelRatio = (surfaceContainer.dpi / 160f).coerceAtLeast(1f)
        ensureStyleAndSnapshotter()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "onSurfaceDestroyed")
        surface = null
        snapshotter?.cancel()
    }

    // ----------------------------------------------------------------
    // Public drive API (safe to call from any thread)
    // ----------------------------------------------------------------

    fun setCamera(target: Position, zoom: Double, bearing: Double, tilt: Double) = onMain {
        cameraTarget = target
        cameraZoom = zoom
        cameraBearing = bearing
        cameraTilt = tilt
        requestRender()
    }

    fun setRoute(polyline: List<Position>) = onMain {
        routePolyline = polyline
        requestRender()
    }

    fun setPuck(position: Position?) = onMain {
        puck = position
        requestRender()
    }

    fun destroy() = onMain {
        snapshotter?.cancel()
        snapshotter = null
        surface = null
        scope.cancel()
    }

    // ----------------------------------------------------------------
    // Snapshotter setup + render loop (main thread)
    // ----------------------------------------------------------------

    private fun ensureStyleAndSnapshotter() {
        if (surface == null || surfaceWidth <= 0 || surfaceHeight <= 0) return
        // Init the native SDK + our caching HTTP stack once; both are idempotent.
        MapLibre.getInstance(carContext)
        MapTileCache.install(carContext)

        if (styleJson == null) {
            scope.launch {
                val json = withContext(Dispatchers.IO) {
                    val raw = carContext.assets.open("style.json").bufferedReader().readText()
                    // Stream the same pmtiles basemap the phone uses (both the
                    // base and hybrid sources point at the streamed URL — the
                    // car app doesn't select downloaded offline zones).
                    patchStyleForHybrid(
                        raw,
                        MapTileCache.BASEMAP_PMTILES_URL,
                        MapTileCache.BASEMAP_PMTILES_URL,
                        darkMap,
                    )
                }
                styleJson = json
                createSnapshotter()
                requestRender()
            }
        } else if (snapshotter == null) {
            createSnapshotter()
            requestRender()
        } else {
            requestRender()
        }
    }

    private fun createSnapshotter() {
        val json = styleJson ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        snapshotter?.cancel()
        val options = MapSnapshotter.Options(surfaceWidth, surfaceHeight)
            .withStyleBuilder(org.maplibre.android.maps.Style.Builder().fromJson(json))
            .withPixelRatio(pixelRatio)
            .withLogo(false)
        options.showAttribution = false
        snapshotter = MapSnapshotter(carContext, options)
    }

    private fun requestRender() {
        val snap = snapshotter ?: return
        val target = cameraTarget ?: return
        if (surface?.isValid != true) return
        if (rendering) {
            dirty = true
            return
        }
        rendering = true
        dirty = false

        snap.setSize(surfaceWidth, surfaceHeight)
        snap.setCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(target.latitude, target.longitude))
                .zoom(cameraZoom)
                .bearing(cameraBearing)
                .tilt(cameraTilt)
                .build()
        )
        snap.start(
            { result -> onSnapshotReady(result) },
            { error -> onSnapshotError(error) },
        )
    }

    private fun onSnapshotError(error: String?) {
        Log.w(TAG, "snapshot error: $error")
        rendering = false
        if (dirty) requestRender()
    }

    private fun onSnapshotReady(result: MapSnapshot) {
        rendering = false
        val surface = this.surface
        if (surface != null && surface.isValid) {
            val bitmap = result.bitmap
            drawOverlay(bitmap, result)
            blitToSurface(surface, bitmap)
        }
        if (dirty) requestRender()
    }

    /** Draw the route line + puck onto the snapshot bitmap (bitmap pixel space). */
    private fun drawOverlay(bitmap: Bitmap, snapshot: MapSnapshot) {
        val canvas = Canvas(bitmap)

        val poly = routePolyline
        if (poly.size >= 2) {
            val path = Path()
            var started = false
            for (p in poly) {
                val pt: PointF = snapshot.pixelForLatLng(LatLng(p.latitude, p.longitude))
                if (!started) {
                    path.moveTo(pt.x, pt.y)
                    started = true
                } else {
                    path.lineTo(pt.x, pt.y)
                }
            }
            canvas.drawPath(path, routeCasingPaint)
            canvas.drawPath(path, routePaint)
        }

        puck?.let { p ->
            val pt = snapshot.pixelForLatLng(LatLng(p.latitude, p.longitude))
            drawPuck(canvas, pt.x, pt.y)
        }
    }

    /**
     * Draw a north-pointing chevron for the puck. During navigation the camera
     * bearing tracks the course-over-ground, so "up" on screen is the direction
     * of travel and a fixed up-arrow reads correctly.
     */
    private fun drawPuck(canvas: Canvas, cx: Float, cy: Float) {
        val r = 22f
        canvas.drawCircle(cx, cy, r + 4f, puckCasingPaint)
        val arrow = Path().apply {
            moveTo(cx, cy - r)
            lineTo(cx - r * 0.7f, cy + r * 0.7f)
            lineTo(cx, cy + r * 0.35f)
            lineTo(cx + r * 0.7f, cy + r * 0.7f)
            close()
        }
        canvas.drawPath(arrow, puckPaint)
    }

    private fun blitToSurface(surface: Surface, bitmap: Bitmap) {
        val canvas: Canvas = try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            Log.w(TAG, "lockCanvas failed", e)
            return
        }
        try {
            val dst = Rect(0, 0, surfaceWidth, surfaceHeight)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, null, dst, null)
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    private companion object {
        const val TAG = "CarMapRenderer"
    }
}
