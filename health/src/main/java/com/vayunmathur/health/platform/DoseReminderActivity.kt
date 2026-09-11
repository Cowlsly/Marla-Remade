package com.vayunmathur.health.platform

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.notifications.DoseNotification
import com.vayunmathur.health.service.DoseSoundService
import com.vayunmathur.health.ui.DoseReminderScreen
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The screen a due dose puts in front of the user, over the keyguard if need be.
 *
 * `showWhenLocked` and `turnScreenOn` are set both here and in the manifest, following the clock's
 * `AlarmActivity`: the manifest attributes cover the launch, these cover a re-entry into an existing
 * instance, and it is `singleInstance` so that happens.
 *
 * Dismissal goes through [DoseActionReceiver] rather than being handled here, so that the
 * notification's own buttons and these do exactly the same thing — including when the activity was
 * never allowed to start.
 */
class DoseReminderActivity : ComponentActivity() {

    private var scheduleId: String = ""
    private var medicationId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        super.onCreate(savedInstanceState)
        readExtras(intent)

        (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)

        val repository = HealthRepository.get(applicationContext)
        setContent {
            val medication by produceState<MedicationEntry?>(null, medicationId) {
                value = withContext(Dispatchers.IO) { repository.getMedication(medicationId) }
            }
            DynamicTheme {
                DoseReminderScreen(
                    medicationName = medication?.displayName.orEmpty(),
                    amount = medication?.let {
                        listOfNotNull(it.strength, it.doseForm).joinToString(" ")
                    },
                    onTaken = { act(DoseActionReceiver.ACTION_TAKEN) },
                    onSnooze = { act(DoseActionReceiver.ACTION_SNOOZE) },
                )
            }
        }
    }

    /** `singleInstance`, so a second dose arriving while this is up replaces the first. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readExtras(intent)
    }

    private fun readExtras(intent: Intent) {
        scheduleId = intent.getStringExtra(DoseScheduler.EXTRA_SCHEDULE_ID).orEmpty()
        medicationId = intent.getStringExtra(DoseScheduler.EXTRA_MEDICATION_ID).orEmpty()
    }

    private fun act(action: String) {
        // Stop the noise here as well as in the receiver: a broadcast is not instant and the
        // silence should follow the tap, not the dispatch.
        DoseNotification.cancel(this)
        runCatching { stopService(Intent(this, DoseSoundService::class.java)) }

        sendBroadcast(
            Intent(this, DoseActionReceiver::class.java).apply {
                this.action = action
                putExtra(DoseScheduler.EXTRA_SCHEDULE_ID, scheduleId)
                putExtra(DoseScheduler.EXTRA_MEDICATION_ID, medicationId)
            }
        )
        finish()
    }
}
