package com.vayunmathur.clock.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.vayunmathur.clock.R

/**
 * The silent channel the ringing service posts its ongoing notification to. Silent because
 * [ALARM_RING_CHANNEL_ID] is what actually rings, and a sounding channel here would make the
 * service's own foreground notification ring a second time on top of the MediaPlayer.
 *
 * A channel's sound, vibration and DND-bypass are fixed when it is first created; later
 * `createNotificationChannel` calls cannot change them. The original channel was created
 * without [NotificationChannel.setSound]/[NotificationChannel.enableVibration], so bumping the
 * id is the only way to apply the intended configuration to installs that predate them.
 */
const val ALARM_CHANNEL_ID = "alarm_channel_v2"

/**
 * Rings the alarm from the system side, and owns the alarm sound.
 *
 * AlarmSoundService cannot be relied on to reach `startForeground`: the start can be denied
 * outright, or the service can be killed asynchronously for missing the five-second window.
 * Neither failure is visible at the receiver's call site, so there is nothing to catch. A
 * `FLAG_INSISTENT` notification on a channel that owns the sound keeps ringing without any
 * process of ours surviving, and the service cancels it when it does manage to take over.
 */
const val ALARM_RING_CHANNEL_ID = "alarm_ring_channel"

private const val LEGACY_ALARM_CHANNEL_ID = "ALARM_CHANNEL_ID"

fun createNotificationChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)

    nm.deleteNotificationChannel(LEGACY_ALARM_CHANNEL_ID)

    nm.createNotificationChannels(listOf(
        // 1. Quiet channel for ongoing countdowns
        NotificationChannel("active_timers_channel", context.getString(R.string.channel_ongoing_timers_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.channel_ongoing_timers_description)
            setShowBadge(false)
        },
        // 2. Loud channel for the "Time's Up" alert
        NotificationChannel("finished_timers_channel", context.getString(R.string.channel_completed_timers_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_completed_timers_description)
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        },
        alarmChannel(context),
        alarmRingChannel(context),
        // 3. Stopwatch channel
        NotificationChannel("stopwatch_channel", context.getString(R.string.channel_stopwatch_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.channel_stopwatch_description)
            setShowBadge(false)
            setSound(null, null)
        }
    ))
}

/**
 * Just the service's own channel. A service has five seconds from `startForegroundService` to
 * reach `startForeground`, so AlarmSoundService creates only what it posts on rather than paying
 * for the delete plus the batched create that [createNotificationChannels] does.
 */
fun createAlarmChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(alarmChannel(context))
}

private fun alarmChannel(context: Context) = NotificationChannel(
    ALARM_CHANNEL_ID,
    context.getString(R.string.channel_alarm_playing_name),
    NotificationManager.IMPORTANCE_HIGH,
).apply {
    description = context.getString(R.string.channel_alarm_playing_description)
    setBypassDnd(true)
    setSound(null, null) // The ring channel and the MediaPlayer own the sound.
    enableVibration(false) // AlarmSoundService.startVibration honours the per-alarm setting.
    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
}

private fun alarmRingChannel(context: Context) = NotificationChannel(
    ALARM_RING_CHANNEL_ID,
    context.getString(R.string.channel_alarms_name),
    NotificationManager.IMPORTANCE_HIGH,
).apply {
    description = context.getString(R.string.channel_alarms_description)
    setBypassDnd(true)
    setSound(
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
    )
    enableVibration(true)
    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
}
