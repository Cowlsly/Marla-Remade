package com.vayunmathur.clock.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vayunmathur.clock.data.ClockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val repository = ClockRepository.get(context)
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val enabled = repository.getAllAlarms().filter { it.enabled }
                        enabled.forEach { AlarmScheduler.schedule(context, it) }
                        Log.i(TAG, "${intent.action}: rescheduled ${enabled.size} alarm(s)")
                    } catch (e: Exception) {
                        // Expected at LOCKED_BOOT_COMPLETED: the database lives in
                        // credential-encrypted storage, so BOOT_COMPLETED is what actually
                        // reschedules. A failure there means no alarm survives the reboot.
                        Log.e(TAG, "${intent.action}: could not reschedule alarms", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "ClockBootReceiver"
    }
}
