package com.vayunmathur.maps.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.maps.util.NavigationProgress
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.camera.CameraState

/** How long the follow animation takes. Matched to the ~1s GPS cadence so it never catches up
 *  to itself and stutters. */
private val FOLLOW_ANIMATION = 800.milliseconds

/**
 * A camera move within this window of our own `animateTo` is assumed to be that animation.
 *
 * `isCameraMoving` reports that the camera is moving but not who started it, so telling a user
 * pan apart from our own follow animation has to be done on timing. Generous relative to
 * [FOLLOW_ANIMATION] because the animation's settle can outlast its nominal duration.
 */
private const val PROGRAMMATIC_MOVE_WINDOW_MS = 1_200

/** Zoom and tilt the camera adopts while following the puck. */
private const val FOLLOW_ZOOM = 17.0
private const val FOLLOW_TILT = 60.0

/**
 * Follow the puck while navigating, and stop following when the user pans away.
 *
 * Two effects that only make sense as a pair: the first moves the camera, the second decides
 * whether a move was ours. Keeping them together is what makes the timing relationship between
 * [FOLLOW_ANIMATION] and [PROGRAMMATIC_MOVE_WINDOW_MS] visible.
 */
@Composable
fun NavigationCameraFollow(
    camera: CameraState,
    chrome: MapChromeState,
    navProgress: NavigationProgress?,
    isNavigating: Boolean,
) {
    LaunchedEffect(navProgress, chrome.autoFollow, chrome.northUp) {
        val progress = navProgress ?: return@LaunchedEffect
        if (!chrome.autoFollow) return@LaunchedEffect
        chrome.lastProgrammaticMoveMs = System.currentTimeMillis()
        camera.animateTo(
            camera.position.copy(
                target = progress.snappedPosition,
                bearing = if (chrome.northUp) 0.0 else progress.courseOverGround.toDouble(),
                tilt = if (chrome.northUp) 0.0 else FOLLOW_TILT,
                zoom = FOLLOW_ZOOM,
            ),
            FOLLOW_ANIMATION,
        )
    }

    LaunchedEffect(camera.isCameraMoving, isNavigating) {
        if (!isNavigating) return@LaunchedEffect
        val sinceOurMove = System.currentTimeMillis() - chrome.lastProgrammaticMoveMs
        if (camera.isCameraMoving && sinceOurMove > PROGRAMMATIC_MOVE_WINDOW_MS) {
            chrome.autoFollow = false
        }
    }
}
