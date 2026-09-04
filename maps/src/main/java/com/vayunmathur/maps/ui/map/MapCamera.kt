package com.vayunmathur.maps.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.toGeoPoint
import kotlin.time.Duration.Companion.milliseconds

/** How long the follow animation takes. Matched to the ~1s GPS cadence so it never catches up
 *  to itself and stutters. */
private val FOLLOW_ANIMATION_MS = 800.milliseconds.inWholeMilliseconds.toInt()

/**
 * A camera move within this window of our own `animateTo` is assumed to be that animation.
 *
 * library:map's [CameraState] has no `isCameraMoving`, so the pan-away detection the MapLibre
 * version had is gone with it (GAP: renderer owns the gesture API — see the task-7 report).
 * `autoFollow` therefore stays on until the user toggles it via the recenter control, and this
 * timestamp is kept so that detection can be re-added without re-threading the state.
 */
private const val PROGRAMMATIC_MOVE_WINDOW_MS = 1_200

/** Zoom the camera adopts while following the puck. */
private const val FOLLOW_ZOOM = 17.0

/**
 * Follow the puck while navigating.
 *
 * North-up always: library:map's camera is target + zoom only, so there is no heading-up tilt
 * or course-over-ground bearing to drive (GAP: same renderer gap as above). The follow still
 * tracks the snapped position and zoom, which is the part that keeps the puck on screen.
 */
@Composable
fun NavigationCameraFollow(
    camera: CameraState,
    chrome: MapChromeState,
    navProgress: NavigationProgress?,
    isNavigating: Boolean,
) {
    LaunchedEffect(navProgress, chrome.autoFollow, isNavigating) {
        if (!isNavigating) return@LaunchedEffect
        val progress = navProgress ?: return@LaunchedEffect
        if (!chrome.autoFollow) return@LaunchedEffect
        chrome.lastProgrammaticMoveMs = System.currentTimeMillis()
        camera.animateTo(
            camera.position.copy(
                target = progress.snappedPosition.toGeoPoint(),
                zoom = FOLLOW_ZOOM,
            ),
            FOLLOW_ANIMATION_MS,
        )
    }
}
