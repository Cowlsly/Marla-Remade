package com.vayunmathur.things

import kotlin.time.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.things.platform.BleManager
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BleManager
    val messages = mutableStateListOf<String>()
    val totalMl = mutableIntStateOf(0)
    val connectionState = mutableStateOf("Disconnected")
    val scanning = mutableStateOf(false)
    val discoveredDevices = mutableStateListOf<BleManager.BleDevice>()

    private val prefs by lazy { getSharedPreferences("hydration", MODE_PRIVATE) }

    private fun today() = LocalDate.now().toString()
    private fun isToday() = prefs.getString("date", null) == today()

    private fun loadTodayTotal() {
        totalMl.intValue = if (isToday()) prefs.getInt("total_ml", 0) else 0
    }

    private fun saveTotal() {
        prefs.edit {
            putString("date", today())
            putInt("total_ml", totalMl.intValue)
        }
    }

    fun onDrinkReceived(ml: Int) {
        if (!isToday()) {
            totalMl.intValue = 0
            messages.clear()
        }
        totalMl.intValue += ml
        saveTotal()
        val timestamp = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
            .format(LocalTime.Format { hour(); char(':'); minute() })
        messages.add(0, "[$timestamp]  +$ml mL")
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            bleManager.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bleManager = BleManager(this)
        loadTodayTotal()
        setContent {
            DynamicTheme {
                Navigation(
                    totalMl = totalMl.intValue,
                    goalMl = GOAL_ML,
                    messages = messages,
                    connectionState = connectionState.value,
                    scanning = scanning.value,
                    discoveredDevices = discoveredDevices,
                    onScanClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                    },
                    onDeviceClick = { bleManager.connect(it.address) },
                    onDisconnectClick = { bleManager.disconnect() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.close()
    }

    companion object {
        private const val GOAL_ML = 2000
    }
}
