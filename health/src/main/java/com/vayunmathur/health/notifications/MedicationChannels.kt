package com.vayunmathur.health.notifications

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.app.Notification
import com.vayunmathur.health.R
import com.vayunmathur.library.util.ensureNotificationChannel

/**
 * The channel that actually rings when a dose is due.
 *
 * Two channels rather than one, copying the clock's arrangement and for the same reason it gives at
 * `clock/.../platform/NotificationUtils.kt:24-31`: a foreground service cannot be relied on to reach
 * `startForeground` inside its five-second window, and neither that refusal nor the asynchronous
 * kill that follows is catchable at the call site. So the notification owns the sound — an
 * insistent notification on a sounding channel keeps ringing with no process of ours alive — and the
 * service takes over when it manages to start.
 */
const val DOSE_REMINDER_CHANNEL_ID = "medication_reminder"

/**
 * The silent channel [com.vayunmathur.health.service.DoseSoundService] posts its own ongoing
 * notification to. Silent because [DOSE_REMINDER_CHANNEL_ID] is what rings; a sounding channel here
 * would ring a second time on top of the MediaPlayer.
 */
const val DOSE_RINGING_CHANNEL_ID = "medication_ringing"

/**
 * The one id the reminder is ever posted under.
 *
 * Deliberately not per-medication. An insistent notification rings until cancelled, so every cancel
 * site has to be able to name it without knowing which dose posted it — and the reminder activity is
 * `singleInstance`, so it holds whichever id it was first created with. One id also means a second
 * dose replaces the first rather than adding a second thing that rings.
 */
const val DOSE_REMINDER_NOTIFICATION_ID = 200_001

/** The sound service's own foreground notification. */
const val DOSE_SERVICE_NOTIFICATION_ID = 200_002

fun createDoseChannels(context: Context) {
    context.ensureNotificationChannel(
        id = DOSE_REMINDER_CHANNEL_ID,
        name = context.getString(R.string.channel_dose_reminder_name),
        importance = NotificationManager.IMPORTANCE_HIGH,
        description = context.getString(R.string.channel_dose_reminder_description),
    ) {
        setBypassDnd(true)
        setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
        )
        enableVibration(true)
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }

    context.ensureNotificationChannel(
        id = DOSE_RINGING_CHANNEL_ID,
        name = context.getString(R.string.channel_dose_ringing_name),
        importance = NotificationManager.IMPORTANCE_HIGH,
        description = context.getString(R.string.channel_dose_ringing_description),
    ) {
        setBypassDnd(true)
        setSound(null, null) // The reminder channel and the MediaPlayer own the sound.
        enableVibration(false) // DoseSoundService owns the vibration.
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }
}
