package com.vayunmathur.library.map

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * A tap on the map: the geographic position under the finger plus the screen
 * point (Dp from the viewport top-left) that produced it. The screen point is
 * what [MapFeaturePicker]-style hit-testing needs; the geo alone cannot
 * recover it once the camera moves.
 */
data class MapClick(val position: GeoPoint, val screen: DpOffset)
/**
 * Viewport measurement and every pan/zoom/tap gesture the map supports.
 *
 * Extracted from [RasterMap] so the WebGPU renderer behaves identically rather
 * than approximately: pan, pinch, double-tap-to-zoom and the double-tap-hold-swipe
 * "quick zoom" are what users of five apps already have, and a second
 * implementation would drift from this one. It also means the px-to-Dp conversion
 * happens in exactly one place, which is the regression the plan is most worried
 * about.
 */
@Composable
internal fun Modifier.mapGestures(
    cameraState: CameraState,
    gestures: GestureOptions,
    zoomRange: ClosedFloatingPointRange<Float>,
    density: Float,
    onMapClick: (GeoPoint) -> Unit,
    onMapClickWithScreen: ((MapClick) -> Unit)? = null,
): Modifier {
    val scope = rememberCoroutineScope()
    // In-flight double-tap zoom animation, cancelled as soon as a new gesture wants
    // to drive the camera itself.
    val zoomAnim = remember { mutableStateOf<Job?>(null) }

    fun toDp(offsetPx: Offset) = Offset(offsetPx.x / density, offsetPx.y / density)
    fun clickAt(offsetPx: Offset) {
        val projection = cameraState.projection ?: return
        val dp = toDp(offsetPx)
        val screen = DpOffset(dp.x.dp, dp.y.dp)
        onMapClick(projection.positionFromScreenLocation(screen))
        // The screen point alongside the geo: hit-testing needs the Dp point
        // the tap landed on, which the geo alone cannot recover.
        onMapClickWithScreen?.invoke(MapClick(projection.positionFromScreenLocation(screen), screen))
    }

    return this
        .onSizeChanged {
            cameraState.setViewport(Size(it.width / density, it.height / density))
        }
        .pointerInput(cameraState, gestures, zoomRange, density) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                if (!gestures.isScrollEnabled && !gestures.isZoomEnabled) return@detectTransformGestures
                zoomAnim.value?.cancel()
                cameraState.onGesture(
                    centroidDp = toDp(centroid),
                    panDp = toDp(pan),
                    zoomChange = zoom,
                    minZoom = zoomRange.start.toDouble(),
                    maxZoom = zoomRange.endInclusive.toDouble(),
                    scrollEnabled = gestures.isScrollEnabled,
                    zoomEnabled = gestures.isZoomEnabled,
                )
            }
        }
        // Declared after the transform detector so it sees pointer events first and
        // can consume a quick-zoom drag out from under it.
        .pointerInput(cameraState, gestures, zoomRange, density) {
            if (!gestures.isZoomEnabled) {
                // Nothing to disambiguate against, so report taps immediately.
                detectTapGestures { clickAt(it) }
            } else {
                var quickZoomStart = cameraState.position
                detectTapAndQuickZoomGestures(
                    onTap = { clickAt(it) },
                    onDoubleTap = { anchor ->
                        zoomAnim.value?.cancel()
                        zoomAnim.value = scope.launch {
                            cameraState.animateZoomBy(
                                deltaZoom = 1.0,
                                anchorDp = toDp(anchor),
                                minZoom = zoomRange.start.toDouble(),
                                maxZoom = zoomRange.endInclusive.toDouble(),
                            )
                        }
                    },
                    onQuickZoomStart = {
                        zoomAnim.value?.cancel()
                        quickZoomStart = cameraState.position
                    },
                    onQuickZoom = { anchor, dragPx ->
                        cameraState.onQuickZoom(
                            from = quickZoomStart,
                            anchorDp = toDp(anchor),
                            dragDp = dragPx / density,
                            minZoom = zoomRange.start.toDouble(),
                            maxZoom = zoomRange.endInclusive.toDouble(),
                        )
                    },
                )
            }
        }
}

/**
 * Single-pointer tap gestures: [onTap], [onDoubleTap], and the "quick zoom" that
 * follows a double-tap the user holds and swipes — [onQuickZoomStart] then
 * [onQuickZoom] with the tapped anchor and the signed vertical drag in px (down is
 * positive). A gesture reports either a double-tap or a quick zoom, never both.
 *
 * Meant to sit alongside [detectTransformGestures]: it stays out of the way of
 * pan/pinch, and once a quick-zoom drag passes touch slop it consumes the moves so
 * the transform detector cancels instead of also panning.
 */
private suspend fun PointerInputScope.detectTapAndQuickZoomGestures(
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onQuickZoomStart: () -> Unit,
    onQuickZoom: (anchor: Offset, dragPx: Float) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown()
    down.consume()
    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
    up.consume()

    val secondDown = awaitSecondDown(up)
    if (secondDown == null) {
        onTap(up.position)
        return@awaitEachGesture
    }
    secondDown.consume()

    // Zoom about the tapped point, which stays put for the whole gesture
    // (following the finger instead would drift the map out from under it).
    val anchor = up.position
    val slop = viewConfiguration.touchSlop
    var zooming = false
    var dragOrigin = 0f
    while (true) {
        val event = awaitPointerEvent()
        // A second finger means the user wants a pinch; hand it over untouched.
        if (event.changes.size > 1) return@awaitEachGesture
        val change = event.changes.firstOrNull { it.id == secondDown.id } ?: break
        if (!change.pressed) {
            change.consume()
            break
        }
        val dy = change.position.y - secondDown.position.y
        if (!zooming) {
            if (abs(dy) < slop) continue
            // Start measuring from the slop boundary so the zoom doesn't jump.
            dragOrigin = dy - slop * sign(dy)
            zooming = true
            onQuickZoomStart()
        }
        change.consume()
        onQuickZoom(anchor, dy - dragOrigin)
    }
    if (!zooming) onDoubleTap(anchor)
}

/** The second down of a double-tap, or null if none arrives in time. */
private suspend fun AwaitPointerEventScope.awaitSecondDown(
    firstUp: PointerInputChange,
): PointerInputChange? = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
    val minUptime = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
    var change: PointerInputChange
    do {
        change = awaitFirstDown()
    } while (change.uptimeMillis < minUptime)
    change
}
