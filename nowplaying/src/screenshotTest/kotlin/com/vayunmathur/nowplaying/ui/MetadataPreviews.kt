package com.vayunmathur.nowplaying.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.nowplaying.data.DetectionEvent
import com.vayunmathur.nowplaying.platform.DetectionStatus
import com.vayunmathur.nowplaying.platform.NowPlayingActions
import com.vayunmathur.nowplaying.platform.NowPlayingUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi - comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store-listing images for `:nowplaying`, rendered from Compose previews rather than an
 * instrumented test on a device — the screen is driven by a microphone and a foreground service,
 * neither of which produces a reproducible screenshot.
 *
 * `./gradlew :nowplaying:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/nowplaying/`, where `release.sh` picks them up.
 *
 * Each preview must carry @PreviewTest as well as @Preview and be a member of a class (not a
 * top-level function) or the screenshot engine silently skips it. Everything is a literal so the
 * output is reproducible from a clean checkout.
 */
class MetadataPreviews {

    private val actions = NowPlayingActions(onListeningChange = {}, onClearHistory = {})

    private val history = listOf(
        DetectionEvent(id = 1, startedAt = 1_786_530_120_000L, endedAt = 1_786_530_540_000L, peakConfidence = 0.94f),
        DetectionEvent(id = 2, startedAt = 1_786_526_400_000L, endedAt = 1_786_527_180_000L, peakConfidence = 0.81f),
        DetectionEvent(id = 3, startedAt = 1_786_519_500_000L, endedAt = 1_786_520_460_000L, peakConfidence = 0.72f),
    )

    @PreviewTest
    @Preview(name = "1-detecting", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Detecting() {
        DynamicTheme(darkTheme = true) {
            ListenScreen(
                state = NowPlayingUiState(
                    status = DetectionStatus.Music,
                    history = history,
                ),
                actions = actions,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-idle", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Idle() {
        DynamicTheme(darkTheme = true) {
            ListenScreen(
                state = NowPlayingUiState(status = DetectionStatus.Idle),
                actions = actions,
            )
        }
    }
}
