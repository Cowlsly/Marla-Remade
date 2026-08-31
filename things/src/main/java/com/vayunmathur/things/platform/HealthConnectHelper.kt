package com.vayunmathur.things.platform

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Volume

object HealthConnectHelper {

    private val ALL_PERMISSIONS: Set<String> = buildSet {
        add(HealthPermission.getWritePermission(HydrationRecord::class))
        add(HealthPermission.getWritePermission(WeightRecord::class))
        add(HealthPermission.getWritePermission(BodyFatRecord::class))
        add(HealthPermission.getWritePermission(LeanBodyMassRecord::class))
        add(HealthPermission.getWritePermission(BoneMassRecord::class))
        add(HealthPermission.getWritePermission(BodyWaterMassRecord::class))
        add(HealthPermission.getWritePermission(BasalMetabolicRateRecord::class))
        // Read-back for permission wall gating is optional, but health does it; request read too
        // so the permission screen is consistent if the user already uses Health Connect.
        add(HealthPermission.getReadPermission(HydrationRecord::class))
        add(HealthPermission.getReadPermission(WeightRecord::class))
        add(HealthPermission.getReadPermission(BodyFatRecord::class))
    }

    val requiredPermissions: Set<String> get() = ALL_PERMISSIONS

    fun permissionsContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasAllPermissions(client: HealthConnectClient): Boolean {
        return try {
            client.permissionController.getGrantedPermissions().containsAll(ALL_PERMISSIONS)
        } catch (_: Exception) {
            false
        }
    }

    fun availabilityStatus(context: Context): Int =
        HealthConnectClient.getSdkStatus(context)

    private fun zoneOffsetAt(instant: java.time.Instant) =
        java.time.ZoneId.systemDefault().rules.getOffset(instant)

    suspend fun writeHydration(
        client: HealthConnectClient,
        instant: java.time.Instant,
        volumeLiters: Double,
    ) {
        try {
            val record = HydrationRecord(
                startTime = instant,
                startZoneOffset = zoneOffsetAt(instant),
                endTime = instant,
                endZoneOffset = zoneOffsetAt(instant),
                volume = Volume.liters(volumeLiters),
                metadata = Metadata.manualEntry(),
            )
            client.insertRecords(listOf(record))
            Log.i("HealthConnectHelper", "Wrote HydrationRecord ${volumeLiters}L")
        } catch (e: Exception) {
            Log.e("HealthConnectHelper", "Failed to write HydrationRecord", e)
        }
    }

    suspend fun writeBodyComposition(
        client: HealthConnectClient,
        instant: java.time.Instant,
        weightKg: Double,
        bodyFatPct: Double?,
        leanMassKg: Double?,
        boneMassKg: Double?,
        bodyWaterMassKg: Double?,
        bmrKcal: Int?,
    ) {
        try {
            val off = zoneOffsetAt(instant)
            val records = mutableListOf<androidx.health.connect.client.records.Record>()
            records.add(
                WeightRecord(
                    time = instant,
                    zoneOffset = off,
                    weight = Mass.kilograms(weightKg),
                    metadata = Metadata.manualEntry(),
                )
            )
            if (bodyFatPct != null && bodyFatPct > 0) {
                records.add(
                    BodyFatRecord(
                        time = instant,
                        zoneOffset = off,
                        percentage = Percentage(bodyFatPct),
                        metadata = Metadata.manualEntry(),
                    )
                )
            }
            if (leanMassKg != null && leanMassKg > 0) {
                records.add(
                    LeanBodyMassRecord(
                        time = instant,
                        zoneOffset = off,
                        mass = Mass.kilograms(leanMassKg),
                        metadata = Metadata.manualEntry(),
                    )
                )
            }
            if (boneMassKg != null && boneMassKg > 0) {
                records.add(
                    BoneMassRecord(
                        time = instant,
                        zoneOffset = off,
                        mass = Mass.kilograms(boneMassKg),
                        metadata = Metadata.manualEntry(),
                    )
                )
            }
            if (bodyWaterMassKg != null && bodyWaterMassKg > 0) {
                records.add(
                    BodyWaterMassRecord(
                        time = instant,
                        zoneOffset = off,
                        mass = Mass.kilograms(bodyWaterMassKg),
                        metadata = Metadata.manualEntry(),
                    )
                )
            }
            if (bmrKcal != null && bmrKcal > 0) {
                records.add(
                    BasalMetabolicRateRecord(
                        time = instant,
                        zoneOffset = off,
                        basalMetabolicRate = Power.kilocaloriesPerDay(bmrKcal.toDouble()),
                        metadata = Metadata.manualEntry(),
                    )
                )
            }
            client.insertRecords(records)
            Log.i("HealthConnectHelper", "Wrote ${records.size} body records")
        } catch (e: Exception) {
            Log.e("HealthConnectHelper", "Failed to write body composition", e)
        }
    }
}
