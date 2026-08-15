package com.vayunmathur.clock.platform
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vayunmathur.clock.R
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.ui.components.AlarmRingingScreen
import com.vayunmathur.clock.ui.components.formatAlarmTime
import com.vayunmathur.clock.service.AlarmSoundService
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AlarmActivity : ComponentActivity() {
    private var alarmId: Long = -1L
    private var snoozeMinutes: Int = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true); setTurnScreenOn(true); super.onCreate(savedInstanceState)
        alarmId = intent.getLongExtra("ALARM_ID", -1L)
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        km.requestDismissKeyguard(this, null)
        val repository = ClockRepository.get(applicationContext)
        setContent {
            val alarm by androidx.compose.runtime.produceState<com.vayunmathur.clock.data.Alarm?>(initialValue = null) {
                value = withContext(Dispatchers.IO) { repository.getAlarm(alarmId) }
            }
            androidx.compose.runtime.LaunchedEffect(alarm) { alarm?.let { snoozeMinutes = it.snoozeMinutes } }
            DynamicTheme {
                AlarmRingingScreen(
                    alarmTime = alarm?.let { formatAlarmTime(this@AlarmActivity, it.time) } ?: "--:--",
                    alarmName = alarm?.name ?: getString(R.string.label_alarm),
                    onDismiss = { dismissAlarm() },
                    onSnooze = { snoozeAlarm() }
                )
            }
        }
    }

    private fun dismissAlarm() {
        stopService(Intent(this, AlarmSoundService::class.java))
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(alarmId.toInt()); finish()
    }

    private fun snoozeAlarm() {
        stopService(Intent(this, AlarmSoundService::class.java))
        val snoozeTime = Clock.System.now().plus(snoozeMinutes.minutes)
        val triggerMillis = snoozeTime.toEpochMilliseconds()
        val intent = Intent(this, AlarmReceiver::class.java).apply { putExtra("ALARM_ID", alarmId); putExtra("IS_SNOOZE", true) }
        val pendingIntent = PendingIntent.getBroadcast(this, alarmId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = getSystemService(AlarmManager::class.java)
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerMillis, pendingIntent), pendingIntent); finish()
    }
}
