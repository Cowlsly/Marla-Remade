package com.vayunmathur.things

import kotlin.time.Clock
import kotlin.time.Instant
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
import com.vayunmathur.things.platform.BodyComposition
import com.vayunmathur.things.platform.BodyMetrics
import com.vayunmathur.things.platform.BottleStatus
import com.vayunmathur.things.platform.HydrationReading
import com.vayunmathur.things.platform.ScaleBleManager
import com.vayunmathur.things.platform.ScaleMeasurement
import com.vayunmathur.things.platform.ScaleProfile
import com.vayunmathur.things.platform.SegmentalImpedance
import com.vayunmathur.things.platform.Sex
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BleManager
    private lateinit var scaleBleManager: ScaleBleManager
    val messages = mutableStateListOf<String>()
    val totalMl = mutableIntStateOf(0)
    val connectionState = mutableStateOf("Disconnected")
    val scanning = mutableStateOf(false)
    val discoveredDevices = mutableStateListOf<BleManager.BleDevice>()
    val waterTempC = mutableStateOf<Int?>(null)
    val tds = mutableStateOf<Int?>(null)
    val batteryPct = mutableStateOf<Int?>(null)
    val charging = mutableStateOf(false)

    // Scale (Renpho Elis 1 / Qingniu) — offline BLE only, no cloud.
    val scaleDevices = mutableStateListOf<ScaleBleManager.ScaleBleDevice>()
    val scaleScanning = mutableStateOf(false)
    val scaleConnectionState = mutableStateOf("Disconnected")
    val scaleRealtimeWeight = mutableStateOf<Double?>(null)
    val scaleWeight = mutableStateOf<Double?>(null)
    val scaleR50 = mutableStateOf<Int?>(null)
    val scaleR500 = mutableStateOf<Int?>(null)
    val scaleMetrics = mutableStateOf<BodyMetrics?>(null)
    val scaleProfile = mutableStateOf(ScaleProfile())
    val scaleSex = mutableStateOf(Sex.Male)
    val scaleAge = mutableStateOf("30")
    val scaleHeight = mutableStateOf("175")
    val scaleAthlete = mutableStateOf(false)

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

    fun onDrinkLog(reading: HydrationReading) {
        val instant = Instant.fromEpochMilliseconds(reading.epochMillis)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val readingDate = LocalDate.of(local.date.year, local.date.monthNumber, local.date.dayOfMonth)
        val isReadingToday = readingDate.toString() == today()
        // Midnight rollover for the per-day persisted total.
        if (!isToday()) {
            totalMl.intValue = 0
            messages.clear()
        }
        if (isReadingToday) {
            totalMl.intValue += reading.amountMl
            saveTotal()
        }
        val formatted = formatReadingTimestamp(local)
        messages.add(0, "[$formatted]  +${reading.amountMl} mL")
    }

    fun onBottleStatus(status: BottleStatus) {
        waterTempC.value = status.tempC
        tds.value = status.tds
        batteryPct.value = status.batteryPct
        charging.value = status.charging
    }

    fun onScaleRealtimeWeight(weight: Double) {
        scaleRealtimeWeight.value = weight
        scaleConnectionState.value = "Weighing... %.1f kg".format(weight)
    }

    fun onScaleMeasurement(
        weightKg: Double,
        r50: Int,
        r500: Int,
        stable: Boolean,
        segmental: SegmentalImpedance? = null,
    ) {
        scaleRealtimeWeight.value = null
        scaleWeight.value = weightKg
        scaleR50.value = if (r50 == 0) null else r50
        scaleR500.value = if (r500 == 0) null else r500
        scaleConnectionState.value = if (stable) "Scale: %.1f kg".format(weightKg) else "Scale: %.1f kg".format(weightKg)
        // Recompute metrics with current profile.
        val profile = ScaleProfile(
            sex = scaleSex.value,
            age = scaleAge.value.toIntOrNull()?.coerceIn(3, 80) ?: scaleProfile.value.age,
            heightCm = scaleHeight.value.toDoubleOrNull()?.coerceIn(40.0, 240.0) ?: scaleProfile.value.heightCm,
            athlete = scaleAthlete.value,
        )
        scaleProfile.value = profile
        val metrics = BodyComposition.calculate(profile, ScaleMeasurement(weightKg, r50, r500, segmental))
        scaleMetrics.value = metrics
        try {
            prefs.edit {
                putString("scale_sex", profile.sex.name)
                putInt("scale_age", profile.age)
                putString("scale_height", profile.heightCm.toString())
                putString("scale_athlete", profile.athlete.toString())
            }
        } catch (_: Exception) {}
    }

    private fun formatReadingTimestamp(local: kotlinx.datetime.LocalDateTime): String {
        val readingDate = LocalDate.of(local.date.year, local.date.monthNumber, local.date.dayOfMonth)
        val timePart = local.time.format(LocalTime.Format { hour(); char(':'); minute() })
        return if (readingDate.toString() != today()) {
            "$readingDate $timePart"
        } else {
            timePart
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            bleManager.startScan()
        }
    }

    private fun loadScaleProfile() {
        try {
            val sexName = prefs.getString("scale_sex", null)
            if (sexName != null) scaleSex.value = Sex.valueOf(sexName)
            val ageInt = prefs.getInt("scale_age", -1)
            if (ageInt != -1) scaleAge.value = ageInt.toString()
            val hStr = prefs.getString("scale_height", null)
            if (hStr != null) scaleHeight.value = hStr
            val ath = prefs.getString("scale_athlete", null)
            if (ath != null) scaleAthlete.value = ath.toBoolean()
            scaleProfile.value = ScaleProfile(
                sex = scaleSex.value,
                age = scaleAge.value.toIntOrNull()?.coerceIn(3, 80) ?: 30,
                heightCm = scaleHeight.value.toDoubleOrNull()?.coerceIn(40.0, 240.0) ?: 175.0,
                athlete = scaleAthlete.value,
            )
        } catch (_: Exception) {}
    }

    private fun recalcScaleMetrics() {
        val w = scaleWeight.value ?: return
        val profile = ScaleProfile(
            sex = scaleSex.value,
            age = scaleAge.value.toIntOrNull()?.coerceIn(3, 80) ?: scaleProfile.value.age,
            heightCm = scaleHeight.value.toDoubleOrNull()?.coerceIn(40.0, 240.0) ?: scaleProfile.value.heightCm,
            athlete = scaleAthlete.value,
        )
        scaleProfile.value = profile
        val seg = scaleMetrics.value?.segmental?.let {
            // Keep existing segmental if present; otherwise null.
            null
        }
        // Recompute with current R values
        val r50 = scaleR50.value ?: 0
        val r500 = scaleR500.value ?: 0
        scaleMetrics.value = BodyComposition.calculate(profile, ScaleMeasurement(w, r50, r500, null))
        try {
            prefs.edit {
                putString("scale_sex", profile.sex.name)
                putInt("scale_age", profile.age)
                putString("scale_height", profile.heightCm.toString())
                putString("scale_athlete", profile.athlete.toString())
            }
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bleManager = BleManager(this)
        scaleBleManager = ScaleBleManager(this)
        loadTodayTotal()
        loadScaleProfile()
        setContent {
            DynamicTheme {
                Navigation(
                    totalMl = totalMl.intValue,
                    goalMl = GOAL_ML,
                    messages = messages,
                    connectionState = connectionState.value,
                    scanning = scanning.value,
                    discoveredDevices = discoveredDevices,
                    tempC = waterTempC.value,
                    tds = tds.value,
                    batteryPct = batteryPct.value,
                    charging = charging.value,
                    onScanClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                    },
                    onDeviceClick = { bleManager.connect(it.address) },
                    onDisconnectClick = { bleManager.disconnect() },
                    scaleWeight = scaleWeight.value,
                    scaleRealtimeWeight = scaleRealtimeWeight.value,
                    scaleR50 = scaleR50.value,
                    scaleConnectionState = scaleConnectionState.value,
                    scaleScanning = scaleScanning.value,
                    scaleDevices = scaleDevices,
                    scaleMetrics = scaleMetrics.value,
                    scaleSex = scaleSex.value,
                    scaleAge = scaleAge.value,
                    scaleHeight = scaleHeight.value,
                    scaleAthlete = scaleAthlete.value,
                    onScaleScanClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                        // Start scan after permission; the launcher callback starts bottle scan,
                        // so also start scale scan here (permission already granted path).
                        if (scaleBleManager.let { true }) scaleBleManager.startScan()
                    },
                    onScaleDeviceClick = { scaleBleManager.connect(it.address) },
                    onScaleDisconnectClick = { scaleBleManager.disconnect() },
                    onScaleSexChange = { scaleSex.value = it; recalcScaleMetrics() },
                    onScaleAgeChange = { scaleAge.value = it; recalcScaleMetrics() },
                    onScaleHeightChange = { scaleHeight.value = it; recalcScaleMetrics() },
                    onScaleAthleteChange = { scaleAthlete.value = it; recalcScaleMetrics() },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.close()
        scaleBleManager.close()
    }

    companion object {
        private const val GOAL_ML = 2000
    }
}
