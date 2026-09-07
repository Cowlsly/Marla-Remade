package com.vayunmathur.clock.service
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.vayunmathur.clock.R
import com.vayunmathur.clock.platform.ALARM_CHANNEL_ID
import com.vayunmathur.clock.platform.createAlarmChannel
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.library.ui.RINGTONE_SILENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlarmSoundService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // The five-second startForegroundService deadline is mostly spent before we get here -
        // process fork, class loading, Application.onCreate - so claim the contract immediately
        // and do everything else afterwards. Missing it gets the service killed asynchronously,
        // which the receiver cannot see or catch.
        startForeground(
            NOTIFICATION_ID,
            ongoingNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        // AlarmReceiver creates the channels before starting us, so this only matters for a
        // START_STICKY restart that no receiver preceded.
        createAlarmChannel(this)
    }

    private fun ongoingNotification(): Notification =
        NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_access_alarm_24)
            .setContentTitle(getString(R.string.alarm_ringing_notification_title))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getLongExtra("ALARM_ID", -1L) ?: -1L

        // We are alive and foreground, so take the ring off the system before doing anything
        // slow: the insistent notification and the MediaPlayer must not overlap for longer than
        // this handover.
        if (alarmId != -1L) {
            getSystemService(NotificationManager::class.java).cancel(alarmId.toInt())
        }

        // Now handle the hardware, using this alarm's per-alarm settings.
        if (!started) {
            started = true
            scope.launch {
                val alarm = if (alarmId != -1L) {
                    runCatching {
                        ClockRepository.get(applicationContext).getAlarm(alarmId)
                    }.getOrNull()
                } else null

                val ringtoneUri = alarm?.ringtoneUri
                val vibrate = alarm?.vibrate ?: true
                val gradualSeconds = alarm?.gradualVolumeSeconds ?: 0

                // setDataSource()/prepare() are blocking; run them on the IO
                // dispatcher (this coroutine) instead of the main thread.
                //
                // Vibration comes first: a stored ringtone can be a content:// URI owned by
                // another app, and one that no longer resolves used to throw out of playAlarm
                // and take the vibration down with it.
                if (vibrate) {
                    withContext(Dispatchers.Main) { startVibration() }
                }
                if (ringtoneUri != RINGTONE_SILENT) {
                    runCatching { playAlarm(resolveRingtone(ringtoneUri), gradualSeconds) }
                        .onFailure { failure ->
                            Log.e(TAG, "Alarm $alarmId: ringtone $ringtoneUri failed to play", failure)
                            releasePlayer()
                            runCatching { playAlarm(resolveRingtone(null), gradualSeconds) }
                                .onFailure { fallbackFailure ->
                                    Log.e(TAG, "Alarm $alarmId: default ringtone failed too", fallbackFailure)
                                    releasePlayer()
                                }
                        }
                }
            }
        }

        return START_STICKY
    }

    private fun resolveRingtone(uriString: String?): Uri? = when (uriString) {
        null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        RINGTONE_SILENT -> null
        else -> runCatching { uriString.toUri() }.getOrNull()
    }

    private fun playAlarm(alarmUri: Uri?, gradualSeconds: Int) {
        alarmUri ?: return
        mediaPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, alarmUri)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            isLooping = true
            prepare()
            if (gradualSeconds > 0) setVolume(0f, 0f)
            start()
        }
        if (gradualSeconds > 0) rampVolume(gradualSeconds)
    }

    private fun releasePlayer() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    /** Fade the alarm in from silent to full over [seconds]. */
    private fun rampVolume(seconds: Int) {
        scope.launch {
            val steps = 20
            val stepDelay = (seconds * 1000L) / steps
            for (i in 1..steps) {
                val volume = i / steps.toFloat()
                withContext(Dispatchers.Main) {
                    runCatching { mediaPlayer?.setVolume(volume, volume) }
                }
                delay(stepDelay)
            }
        }
    }

    private fun startVibration() {
        vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator
        val pattern = longArrayOf(0, 500, 500) // Off, On, Off
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    override fun onDestroy() {
        scope.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        vibrator?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val TAG = "AlarmSoundService"
    }
}
