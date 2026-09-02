package com.vayunmathur.things.platform

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-scoped owner of the BLE managers, device state, and all the bottle/scale logic that
 * used to live on [com.vayunmathur.things.MainActivity].
 *
 * Both [DeviceService] (which keeps the process alive in the background) and the Compose UI read
 * and drive this singleton, so the GATT links survive the Activity being stopped or swiped away.
 *
 * State is held as Compose `mutableStateOf`/`mutableStateListOf` directly (rather than the repo's
 * usual `StateFlow` singletons) because the whole `things` UI already passes these value types as
 * params through `Navigation` → `HomePage`/`DevicesPage`, so this keeps the UI layer unchanged.
 */
object DeviceController {

    private lateinit var appContextRef: Context
    val appContext: Context get() = appContextRef

    private lateinit var bleManager: BleManager
    private lateinit var scaleBleManager: ScaleBleManager

    private val mainHandler = Handler(Looper.getMainLooper())

    // Replaces the Activity's lifecycleScope for the async Health Connect writes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefs: SharedPreferences by lazy {
        appContextRef.getSharedPreferences("hydration", Context.MODE_PRIVATE)
    }

    private var initialized = false

    // Bottle state.
    val connectionState = mutableStateOf("Disconnected")
    val scanning = mutableStateOf(false)
    val discoveredDevices = mutableStateListOf<BleManager.BleDevice>()
    val waterTempC = mutableStateOf<Int?>(null)
    val tds = mutableStateOf<Int?>(null)
    val batteryPct = mutableStateOf<Int?>(null)
    val charging = mutableStateOf(false)
    val bottleVolumePct = mutableStateOf<Int?>(null)
    val bottleLastUpdated = mutableStateOf<Long?>(null)

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

    /** Idempotent. Safe to call from both the service and the Activity. */
    fun init(context: Context) {
        if (initialized) return
        appContextRef = context.applicationContext
        bleManager = BleManager()
        scaleBleManager = ScaleBleManager()
        loadScaleProfile()
        initialized = true
    }

    /** Marshal onto the main thread; replaces the Activity's `runOnUiThread`. */
    fun runOnMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    // --- Callbacks invoked by the BLE managers ---

    fun onDrinkLog(reading: HydrationReading) {
        // Health data is owned by the Health app; this app only writes it to Health Connect and
        // never displays it. Body of the record stays; the on-screen total/list is gone.
        writeHydrationToHealthConnect(reading)
    }

    fun onBottleStatus(status: BottleStatus) {
        waterTempC.value = status.tempC
        tds.value = status.tds
        batteryPct.value = status.batteryPct
        charging.value = status.charging
        bottleVolumePct.value = status.volumePct
        bottleLastUpdated.value = System.currentTimeMillis()
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
        writeBodyCompositionToHealthConnect(weightKg, metrics)
    }

    // --- Actions used by the UI / service ---

    fun startBottleScan() = bleManager.startScan()

    fun connectBottle(address: String) {
        // First-ever connect to this bottle = setup: run the registration handshake (blue LED +
        // button press). Reconnects to the already-remembered bottle skip straight to data sync.
        val isNewDevice = prefs.getString(BOTTLE_ADDRESS_KEY, null) != address
        saveDeviceAddress(BOTTLE_ADDRESS_KEY, address)
        bleManager.connect(address, register = isNewDevice)
    }

    fun disconnectBottle() {
        clearDeviceAddress(BOTTLE_ADDRESS_KEY)
        bleManager.disconnect()
    }

    fun startScaleScan() = scaleBleManager.startScan()

    fun connectScale(address: String) {
        saveDeviceAddress(SCALE_ADDRESS_KEY, address)
        scaleBleManager.connect(address)
    }

    fun disconnectScale() {
        clearDeviceAddress(SCALE_ADDRESS_KEY)
        scaleBleManager.disconnect()
    }

    /** Reconnect to any remembered devices. No-op without permission or a powered-on adapter. */
    fun autoConnectSavedDevices() {
        if (!hasBluetoothConnectPermission() || !bluetoothEnabled()) return
        prefs.getString(BOTTLE_ADDRESS_KEY, null)?.let { bleManager.connect(it) }
        prefs.getString(SCALE_ADDRESS_KEY, null)?.let { scaleBleManager.connect(it) }
    }

    /** Whether at least one device is remembered (drives the service lifecycle). */
    fun hasRememberedDevice(): Boolean =
        prefs.getString(BOTTLE_ADDRESS_KEY, null) != null ||
            prefs.getString(SCALE_ADDRESS_KEY, null) != null

    /** Last-resort cleanup when the service is intentionally stopped. */
    fun closeManagers() {
        bleManager.close()
        scaleBleManager.close()
    }

    fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun bluetoothEnabled(): Boolean =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /**
     * True iff Health Connect is available; otherwise surfaces a message. The actual permission
     * *request* stays in the Activity because it needs an Activity result launcher.
     */
    fun isHealthConnectAvailable(): Boolean {
        val status = HealthConnectHelper.availabilityStatus(appContext)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            AppMessages.show("Health Connect not available")
            return false
        }
        return true
    }

    fun recalcScaleMetrics() {
        val w = scaleWeight.value ?: return
        val profile = ScaleProfile(
            sex = scaleSex.value,
            age = scaleAge.value.toIntOrNull()?.coerceIn(3, 80) ?: scaleProfile.value.age,
            heightCm = scaleHeight.value.toDoubleOrNull()?.coerceIn(40.0, 240.0) ?: scaleProfile.value.heightCm,
            athlete = scaleAthlete.value,
        )
        scaleProfile.value = profile
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

    // Remembered device addresses so the app silently reconnects to both devices on launch
    // instead of making the user scan and tap every time it is reopened.
    private fun saveDeviceAddress(key: String, address: String) {
        prefs.edit { putString(key, address) }
    }

    private fun clearDeviceAddress(key: String) {
        prefs.edit { remove(key) }
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

    private fun writeHydrationToHealthConnect(reading: HydrationReading) {
        // Check Health Connect availability synchronously; writes are async.
        val status = HealthConnectHelper.availabilityStatus(appContext)
        if (status != HealthConnectClient.SDK_AVAILABLE) return
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(appContext)
                if (!HealthConnectHelper.hasAllPermissions(client)) return@launch
                val instant = java.time.Instant.ofEpochMilli(reading.epochMillis)
                HealthConnectHelper.writeHydration(client, instant, reading.amountMl / 1000.0)
            } catch (_: Exception) {}
        }
    }

    private fun writeBodyCompositionToHealthConnect(weightKg: Double, metrics: BodyMetrics) {
        val status = HealthConnectHelper.availabilityStatus(appContext)
        if (status != HealthConnectClient.SDK_AVAILABLE) return
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(appContext)
                if (!HealthConnectHelper.hasAllPermissions(client)) return@launch
                val instant = java.time.Instant.now()
                val waterMassKg = if (metrics.waterPercent > 0) weightKg * metrics.waterPercent / 100.0 else null
                HealthConnectHelper.writeBodyComposition(
                    client = client,
                    instant = instant,
                    weightKg = weightKg,
                    bodyFatPct = metrics.bodyFatPercent.takeIf { it > 0 },
                    leanMassKg = metrics.lbmKg.takeIf { it > 0 },
                    boneMassKg = metrics.boneKg.takeIf { it > 0 },
                    bodyWaterMassKg = waterMassKg,
                    bmrKcal = metrics.bmrKcal.takeIf { it > 0 },
                )
            } catch (_: Exception) {}
        }
    }

    private const val BOTTLE_ADDRESS_KEY = "bottle_address"
    private const val SCALE_ADDRESS_KEY = "scale_address"
}
