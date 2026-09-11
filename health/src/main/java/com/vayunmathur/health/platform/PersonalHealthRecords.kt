@file:OptIn(androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi::class)

package com.vayunmathur.health.platform

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.FhirVersion
import androidx.health.connect.client.records.MedicalResource
import androidx.health.connect.client.records.MedicalResourceId
import androidx.health.connect.client.request.CreateMedicalDataSourceRequest
import androidx.health.connect.client.request.GetMedicalDataSourcesRequest
import androidx.health.connect.client.request.ReadMedicalResourcesInitialRequest
import androidx.health.connect.client.request.ReadMedicalResourcesPageRequest
import androidx.health.connect.client.request.UpsertMedicalResourceRequest
import androidx.health.connect.client.records.FhirResource
import com.vayunmathur.health.domain.FhirRecords
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app's window onto Health Connect's Personal Health Record store.
 *
 * PHR is not part of the base Health Connect surface: it needs Android 15 with an updated Health
 * Connect module, while this app supports API 31 upwards. Every method here therefore degrades to a
 * no-op rather than throwing when [isAvailable] is false, and the two medical screens treat Room as
 * the source of truth with this as a mirror on top. That is the same arrangement `HealthSyncWorker`
 * already uses for fitness records, and it is what keeps the feature usable on the majority of
 * devices in the supported range.
 *
 * Every write also tolerates failure at runtime. A rejected resource must not cost the user the entry
 * they just typed, so failures are logged and swallowed and the Room row simply stays unmirrored.
 */
object PersonalHealthRecords {

    private const val TAG = "PersonalHealthRecords"

    /**
     * FHIR record access, requested separately from the app's other Health Connect permissions.
     *
     * Deliberately not folded into `MainActivity`'s `PERMISSIONS`, which gates the entire app: these
     * are only grantable on Android 15 with an updated Health Connect module, so requiring them
     * there would put every older device behind a wall it can never pass. Both medical screens work
     * from the local database when these are refused.
     */
    val PERMISSIONS = setOf(
        HealthPermission.PERMISSION_WRITE_MEDICAL_DATA,
        HealthPermission.PERMISSION_READ_MEDICAL_DATA_VACCINES,
        HealthPermission.PERMISSION_READ_MEDICAL_DATA_MEDICATIONS,
        HealthPermission.PERMISSION_READ_MEDICAL_DATA_ALLERGIES_INTOLERANCES,
        HealthPermission.PERMISSION_READ_MEDICAL_DATA_CONDITIONS,
        HealthPermission.PERMISSION_READ_MEDICAL_DATA_LABORATORY_RESULTS,
        HealthPermission.PERMISSION_READ_MEDICAL_DATA_PREGNANCY,
        HealthPermission.PERMISSION_READ_MEDICAL_DATA_SOCIAL_HISTORY,
        HealthPermission.PERMISSION_READ_MEDICAL_DATA_PERSONAL_DETAILS,
    )

    /** Health Connect currently accepts only FHIR R4. */
    private val FHIR_VERSION = FhirVersion(4, 0, 1)

    /** Key in the existing `"sync"` SharedPreferences that `HealthAPI` owns. */
    private const val PREF_DATA_SOURCE_ID = "phr_data_source_id"

    private const val DATA_SOURCE_DISPLAY_NAME = "MA Health"
    private val DATA_SOURCE_URI: Uri = "https://vayunmathur.com/fhir/ma-health".toUri()

    /** Serialises get-or-create so two screens opening at once cannot create two data sources. */
    private val dataSourceMutex = Mutex()

    private lateinit var appContext: Context
    private lateinit var client: HealthConnectClient

    fun init(context: Context, healthConnectClient: HealthConnectClient) {
        appContext = context.applicationContext
        client = healthConnectClient
    }

    fun isAvailable(): Boolean = try {
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_PERSONAL_HEALTH_RECORD) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    } catch (e: Exception) {
        Log.w(TAG, "Could not query the Personal Health Record feature status", e)
        false
    }

    /**
     * The id of this app's data source, creating it on first use.
     *
     * The id is cached in preferences only as a fast path. Health Connect is still asked first,
     * because the user can delete a data source from Health Connect's own settings at any time and a
     * stale cached id would make every subsequent write fail.
     */
    suspend fun dataSourceId(): String? {
        if (!isAvailable()) return null
        return dataSourceMutex.withLock {
            try {
                val existing = client
                    .getMedicalDataSources(GetMedicalDataSourcesRequest(listOf(appContext.packageName)))
                    .firstOrNull()
                if (existing != null) {
                    cacheDataSourceId(existing.id)
                    return@withLock existing.id
                }

                val created = client.createMedicalDataSource(
                    CreateMedicalDataSourceRequest(
                        fhirBaseUri = DATA_SOURCE_URI,
                        displayName = DATA_SOURCE_DISPLAY_NAME,
                        fhirVersion = FHIR_VERSION,
                    )
                )
                cacheDataSourceId(created.id)
                // Immunization.patient and MedicationStatement.subject are both required references,
                // so the patient has to exist before anything can point at it.
                upsert(created.id, FhirRecords.patientJson())
                created.id
            } catch (e: Exception) {
                Log.e(TAG, "Could not resolve a medical data source", e)
                null
            }
        }
    }

    private fun cacheDataSourceId(id: String) {
        appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
            .edit { putString(PREF_DATA_SOURCE_ID, id) }
    }

    /** Writes one FHIR resource, returning its Health Connect id or null if the write did not land. */
    suspend fun upsert(dataSourceId: String, data: String): String? = try {
        client.upsertMedicalResources(
            listOf(UpsertMedicalResourceRequest(dataSourceId, FHIR_VERSION, data))
        ).firstOrNull()?.id?.fhirResourceId
    } catch (e: Exception) {
        Log.e(TAG, "Could not write a medical resource", e)
        null
    }

    suspend fun delete(dataSourceId: String, fhirResourceType: Int, fhirResourceId: String) {
        if (!isAvailable()) return
        try {
            client.deleteMedicalResources(
                listOf(MedicalResourceId(dataSourceId, fhirResourceType, fhirResourceId))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not delete a medical resource", e)
        }
    }

    /**
     * One FHIR resource as it came out of Health Connect.
     *
     * The `MedicalResource` type it is unwrapped from is experimental, and this is where that stops:
     * callers get plain strings, so the opt-in stays confined to this file and a signature change
     * upstream cannot ripple through the ViewModel and the screens.
     */
    data class RawResource(val dataSourceId: String, val data: String)

    /**
     * Every resource of [medicalResourceType] readable by this app, across all data sources.
     *
     * Not restricted to this app's own data source: the point of reading is to surface immunisations
     * and medications a health system has synced in, which by definition live somewhere else.
     */
    suspend fun readAll(medicalResourceType: Int): List<RawResource> {
        if (!isAvailable()) return emptyList()
        return try {
            val all = mutableListOf<MedicalResource>()
            var response = client.readMedicalResources(
                ReadMedicalResourcesInitialRequest(medicalResourceType, emptySet(), PAGE_SIZE)
            )
            all += response.medicalResources
            var token = response.nextPageToken
            while (token != null) {
                response = client.readMedicalResources(
                    ReadMedicalResourcesPageRequest(token, PAGE_SIZE)
                )
                all += response.medicalResources
                token = response.nextPageToken
            }
            all.map { RawResource(it.dataSourceId, it.fhirResource.data) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read medical resources of type $medicalResourceType", e)
            emptyList()
        }
    }

    val vaccinesType: Int get() = MedicalResource.MEDICAL_RESOURCE_TYPE_VACCINES
    val medicationsType: Int get() = MedicalResource.MEDICAL_RESOURCE_TYPE_MEDICATIONS
    val allergiesType: Int get() = MedicalResource.MEDICAL_RESOURCE_TYPE_ALLERGIES_INTOLERANCES
    val conditionsType: Int get() = MedicalResource.MEDICAL_RESOURCE_TYPE_CONDITIONS
    val labsType: Int get() = MedicalResource.MEDICAL_RESOURCE_TYPE_LABORATORY_RESULTS
    val pregnancyType: Int get() = MedicalResource.MEDICAL_RESOURCE_TYPE_PREGNANCY
    val socialHistoryType: Int get() = MedicalResource.MEDICAL_RESOURCE_TYPE_SOCIAL_HISTORY
    val immunizationResourceType: Int get() = FhirResource.FHIR_RESOURCE_TYPE_IMMUNIZATION
    val medicationStatementResourceType: Int get() = FhirResource.FHIR_RESOURCE_TYPE_MEDICATION_STATEMENT
    val allergyResourceType: Int get() = FhirResource.FHIR_RESOURCE_TYPE_ALLERGY_INTOLERANCE
    val conditionResourceType: Int get() = FhirResource.FHIR_RESOURCE_TYPE_CONDITION
    val observationResourceType: Int get() = FhirResource.FHIR_RESOURCE_TYPE_OBSERVATION

    private const val PAGE_SIZE = 500
}
