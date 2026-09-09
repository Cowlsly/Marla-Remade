package com.vayunmathur.updater.platform

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.core.content.getSystemService

private const val TAG = "IdleReboot"

private const val JOB_ID = 3

/** Don't reboot the instant the payload lands; the user may still be looking at the phone. */
private const val MIN_LATENCY_MILLIS = 5 * 60 * 1000L

/**
 * Reboots into the freshly written slot once the device is not being used.
 *
 * A payload is applied to the *inactive* slot, so once it is written the device keeps running
 * normally and only the reboot is left. That reboot is the one genuinely disruptive act in the
 * whole update, so it waits — and it waits indefinitely. There is no deadline and no forced
 * restart: the applied slot is still there tomorrow, and no update is urgent enough to justify
 * taking a phone away mid-sentence.
 *
 * "Idle" is `JobInfo.Builder.setRequiresDeviceIdle`, i.e. the platform's own definition, which
 * already accounts for screen state, recent use and Doze. Deciding it here instead would mean
 * polling on an alarm and reimplementing — worse — something the scheduler already knows.
 */
class IdleReboot : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        reboot(this)
        // No background work: either the device is rebooting or the reboot was refused.
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {

        fun schedule(context: Context) {
            val scheduler = context.getSystemService<JobScheduler>() ?: return
            val result = scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, IdleReboot::class.java))
                    .setRequiresDeviceIdle(true)
                    .setMinimumLatency(MIN_LATENCY_MILLIS)
                    .build(),
            )
            if (result == JobScheduler.RESULT_FAILURE) Log.e(TAG, "could not schedule the reboot")
        }

        fun cancel(context: Context) {
            context.getSystemService<JobScheduler>()?.cancel(JOB_ID)
        }

        /** Also reached from the notification's "restart now" action. */
        fun reboot(context: Context) {
            val power = context.getSystemService<PowerManager>()
            if (power == null) {
                Log.e(TAG, "no PowerManager; cannot reboot")
                return
            }
            try {
                Log.i(TAG, "rebooting into the updated slot")
                power.reboot(null)
            } catch (e: Exception) {
                // SecurityException when REBOOT is not actually granted, which is what a
                // mismatched privapp-permissions entry looks like from here.
                Log.e(TAG, "reboot refused", e)
            }
        }
    }
}
