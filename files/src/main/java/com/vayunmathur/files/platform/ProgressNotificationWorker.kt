package com.vayunmathur.files.util
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.vayunmathur.files.R

/**
 * Shared foreground-notification plumbing for the zip/unzip workers. Progress updates are
 * throttled so the notification is only re-posted when the integer percentage changes.
 */
abstract class ProgressNotificationWorker(
    context: Context,
    params: WorkerParameters,
    private val channelId: String,
    private val notificationId: Int,
    @StringRes private val channelNameRes: Int,
    @StringRes private val contentTitleRes: Int,
) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var lastProgress = -1

    protected fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                channelId,
                applicationContext.getString(channelNameRes),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(progress: Int) =
        NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(applicationContext.getString(contentTitleRes))
            .setSmallIcon(R.drawable.folder_24px)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()

    protected fun createForegroundInfo(progress: Int) =
        ForegroundInfo(
            notificationId,
            buildNotification(progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

    protected fun updateProgress(current: Long, total: Long) {
        if (total <= 0) return
        val progress = (current * 100 / total).toInt()
        if (progress != lastProgress) {
            lastProgress = progress
            try {
                notificationManager.notify(notificationId, buildNotification(progress))
            } catch (_: Exception) {
            }
        }
    }

    protected fun cancelNotification() {
        try {
            notificationManager.cancel(notificationId)
        } catch (_: Exception) {
        }
    }
}
