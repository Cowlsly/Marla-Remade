package com.vayunmathur.safefamily.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log

private const val TAG = "SafeFamilySetup"

/**
 * Where `android.app.supervision.action.INSTALL_SUPERVISION_APP` lands, and the only activity
 * this app has.
 *
 * **It draws nothing.** Settings looks for an activity answering that action so it can show the
 * Parental controls entry on a device where the supervision app still needs installing, and it is
 * the second half of `TopLevelSupervisionPreferenceController`'s availability test - without it,
 * the row is hidden until supervision is already on, which is a chicken and egg. On MAOS the app
 * is preinstalled, so there is nothing to install and nothing to explain: the honest response is
 * to hand straight to the platform's own setup flow.
 *
 * A screen here would be a dead end. Every control - the PIN, schedules, app limits, content
 * filters - belongs to Settings, so anything drawn in this app could only tell the user to go
 * back to where they already were.
 */
class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val enable = Intent(ACTION_ENABLE_SUPERVISION).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(enable)
        } catch (e: ActivityNotFoundException) {
            // Only reachable on a build whose Settings has no supervision flow at all, in which
            // case this app should not have been resolvable either. Logged rather than shown:
            // there is no useful action for the user, and a dialog from an app with no icon and
            // no other UI would be more confusing than the entry simply not working.
            Log.w(TAG, "no activity for $ACTION_ENABLE_SUPERVISION; is this a supervision build?", e)
        }
        finish()
    }

    private companion object {
        const val ACTION_ENABLE_SUPERVISION = "android.app.supervision.action.ENABLE_SUPERVISION"
    }
}
