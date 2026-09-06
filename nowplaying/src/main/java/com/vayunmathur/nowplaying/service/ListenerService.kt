package com.vayunmathur.nowplaying.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.vayunmathur.library.util.ensureNotificationChannel
import com.vayunmathur.nowplaying.MainActivity
import com.vayunmathur.nowplaying.R
import com.vayunmathur.nowplaying.data.DetectionEvent
import com.vayunmathur.nowplaying.data.DetectionHistory
import com.vayunmathur.nowplaying.domain.GateMusicDetector
import com.vayunmathur.nowplaying.domain.LatchingGate
import com.vayunmathur.nowplaying.domain.MusicDetector
import com.vayunmathur.nowplaying.platform.DetectionStatus
import com.vayunmathur.nowplaying.platform.ListeningController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the microphone and runs the music detector for as long as the user wants to listen.
 *
 * Audio is read, scored and discarded inside this process. Nothing is written to storage and
 * nothing is sent anywhere — only the start/end timestamps of each detected stretch of music are
 * persisted.
 */
class ListenerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val gate = LatchingGate()
    private val history by lazy { DetectionHistory.get(this) }

    private var openInterval: DetectionEvent? = null
    private var peakConfidence = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(
            CHANNEL_ID,
            getString(R.string.listening_channel_name),
            NotificationManager.IMPORTANCE_LOW,
            getString(R.string.listening_channel_desc),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_STICKY

        val notification = buildNotification()

        // A microphone-typed foreground start requires the permission; if it was revoked since we
        // were scheduled, satisfy the foreground-start contract and then stop.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
            ListeningController.publish(DetectionStatus.Idle)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not enter the foreground", e)
            ListeningController.publish(DetectionStatus.Idle)
            stopSelf()
            return START_NOT_STICKY
        }

        gate.reset()
        openInterval = null
        peakConfidence = 0f
        ListeningController.publish(DetectionStatus.Silent)
        job = scope.launch { listen() }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        scope.cancel()
        if (ListeningController.status.value != DetectionStatus.Unavailable) {
            ListeningController.publish(DetectionStatus.Idle)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun CoroutineScope.listen() {
        // Owned by the session rather than the service so it is closed on the same coroutine that
        // last used it - closing it from onDestroy would race a score already in flight.
        val detector: MusicDetector = GateMusicDetector.inProcess()
        if (!detector.isAvailable) {
            detector.close()
            ListeningController.publish(DetectionStatus.Unavailable)
            stopSelf()
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            Log.e(TAG, "no usable microphone buffer size")
            detector.close()
            stopSelf()
            return
        }

        val record = try {
            AudioRecord(audioSource(), SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minBuffer, SAMPLE_RATE))
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord init failed", t)
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            detector.close()
            stopSelf()
            return
        }

        try {
            record.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            record.release()
            detector.close()
            stopSelf()
            return
        }

        val window = ShortArray(detector.windowSamples)
        var filled = 0
        try {
            while (isActive) {
                val read = record.read(window, filled, window.size - filled)
                if (read <= 0) {
                    Log.w(TAG, "microphone read returned $read")
                    break
                }
                filled += read
                if (filled < window.size) continue

                consume(detector, window)
                filled = 0
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            detector.close()
            withContext(NonCancellable) { endInterval() }
            // Published here rather than only in onDestroy because a consume() already in flight
            // when the job is cancelled finishes afterwards and would republish Silent over it,
            // leaving the UI claiming to listen with the microphone closed.
            if (ListeningController.status.value != DetectionStatus.Unavailable) {
                ListeningController.publish(DetectionStatus.Idle)
            }
        }
    }

    /**
     * Scores one window and moves the gate on.
     *
     * A null score means either that the detector died under us or that it has not seen enough
     * windows to compare against yet, and only the first is a reason to stop; [MusicDetector]
     * distinguishes them by staying available across the warm-up.
     */
    private suspend fun consume(detector: MusicDetector, window: ShortArray) {
        val score = detector.score(window)
        if (score == null) {
            if (detector.isAvailable) return
            ListeningController.publish(DetectionStatus.Unavailable)
            stopSelf()
            return
        }
        val flipped = gate.push(score)
        if (gate.isMusic) peakConfidence = maxOf(peakConfidence, gate.smoothedScore)
        if (flipped) {
            if (gate.isMusic) beginInterval() else endInterval()
        }
        ListeningController.publish(
            if (gate.isMusic) DetectionStatus.Music else DetectionStatus.Silent,
        )
    }

    private suspend fun beginInterval() {
        peakConfidence = gate.smoothedScore
        val event = DetectionEvent(startedAt = System.currentTimeMillis())
        openInterval = event.copy(id = history.upsert(event))
    }

    private suspend fun endInterval() {
        val open = openInterval ?: return
        openInterval = null
        history.upsert(
            open.copy(endedAt = System.currentTimeMillis(), peakConfidence = peakConfidence),
        )
        peakConfidence = 0f
    }

    /**
     * The speech-oriented sources apply noise suppression and automatic gain, which distort the
     * log-mel features the detector reads, so unprocessed audio is preferred where the device
     * offers it.
     */
    private fun audioSource(): Int {
        val unprocessed = getSystemService<AudioManager>()
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            .toBoolean()
        return if (unprocessed) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.listening_notification_title))
            .setContentText(getString(R.string.listening_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "ListenerService"

        /** `IMPORTANCE_LOW`: an open microphone is a status indicator, not an event. */
        private const val CHANNEL_ID = "nowplaying_listening"
        private const val NOTIFICATION_ID = 2301

        private const val SAMPLE_RATE = MusicDetector.SAMPLE_RATE
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        fun start(context: Context) {
            val app = context.applicationContext
            try {
                ContextCompat.startForegroundService(app, Intent(app, ListenerService::class.java))
            } catch (e: Exception) {
                // Refused because the app is in the background with no exemption.
                Log.w(TAG, "could not start listening", e)
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, ListenerService::class.java))
        }
    }
}
