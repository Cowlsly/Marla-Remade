package com.vayunmathur.cast.platform.mirror

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vayunmathur.cast.MainActivity
import com.vayunmathur.cast.R
import com.vayunmathur.cast.platform.CastController
import com.vayunmathur.cast.platform.MirrorPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Start and stop mirroring from the shade.
 *
 * A close transcription of `ShareReceiveTileService` with **one deliberate divergence**: `:share`
 * treats its persisted flag as authoritative and reconciles it headlessly after a reboot. Mirroring
 * cannot do that, because screen-capture consent is single-use and unreplayable. So tile state is
 * read from the in-process [CastController] instead: the foreground service keeps the process alive
 * for as long as mirroring is on, which means that if the process died, mirroring is dead and
 * `STATE_INACTIVE` is the truth. Only the *target* is persisted.
 *
 * A tile can also never start mirroring by itself - consent needs an Activity - so turning it on
 * hands off to [MirrorConsentActivity] via `startActivityAndCollapse`. Turning it off needs no
 * Activity and goes straight to the controller.
 *
 * No `requestListeningState()`: nothing in this repo uses it and the house idiom is to reconcile on
 * the next listen.
 */
class MirrorTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val phase = CastController.mirrorPhase.value
        // Failed is drawn as inactive, so a tap on it has to *start* rather than stop - otherwise
        // the first press after a failure looks like it did nothing.
        if (phase == MirrorPhase.Mirroring || phase == MirrorPhase.Negotiating) {
            CastController.stopMirroring(this)
            // stopMirroring is asynchronous, so waiting for Idle rather than reading the phase
            // straight back: refreshing now would re-publish the state we are leaving.
            scope.launch {
                CastController.mirrorPhase.first { it != MirrorPhase.Mirroring }
                refreshTile()
            }
            return
        }
        scope.launch {
            // Nothing to mirror to until a target has been chosen once in the app, and there is no
            // device list in the shade to choose one from.
            val target = MirrorPreferences.target(this@MirrorTileService)
            if (target == null) {
                openApp()
                return@launch
            }
            // Consent must be collected by an Activity, and it must be collected afresh every time.
            startConsent()
        }
    }

    private fun startConsent() {
        collapseTo(MirrorConsentActivity.intent(this))
    }

    /**
     * Sets only `state` and `subtitle`; the label and icon come from the manifest, as they do in
     * `:share`.
     */
    private fun refreshTile() {
        scope.launch {
            val tile = qsTile ?: return@launch
            val phase = CastController.mirrorPhase.value
            val device = CastController.device.value
            val target = MirrorPreferences.target(this@MirrorTileService)
            tile.state = when (phase) {
                MirrorPhase.Mirroring, MirrorPhase.Negotiating -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            tile.subtitle = when {
                phase == MirrorPhase.Mirroring -> device?.friendlyName
                    ?: getString(R.string.cast_tile_mirroring)
                phase == MirrorPhase.Negotiating -> getString(R.string.cast_tile_starting)
                target == null -> getString(R.string.cast_tile_no_target)
                else -> target.friendlyName
            }
            tile.updateTile()
        }
    }

    private fun openApp() {
        collapseTo(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Launch [intent] and close the shade.
     *
     * One call site for the version split, because the `PendingIntent` overload only exists from
     * API 34 and `minSdk` is 31 - so the deprecated one is genuinely the only option below that,
     * and suppressing it twice would be twice as easy to lose track of.
     */
    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    private fun collapseTo(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
