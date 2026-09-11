@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.platform

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.health.data.AllergyCategory
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.data.HealthProfile
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicalAttachment
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.RepeatUnit
import com.vayunmathur.health.data.SmokingStatus
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.health.domain.FhirRecords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toLocalDateTime
import java.time.Instant
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Today in the device's timezone. The default date on both add forms. */
private fun today(): LocalDate =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

/** Midnight local time on this date, which is the precision a medical record carries. */
private fun LocalDate.toInstant(): Instant =
    Instant.ofEpochMilli(atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds())

/** The local calendar date an [Instant] falls on. */
private fun Instant.toLocalDate(): LocalDate =
    atZone(java.time.ZoneId.systemDefault()).toLocalDate().toKotlinLocalDate()

private fun String.blankToNull(): String? = trim().ifBlank { null }

/**
 * Owns the Medication and Medical History screens.
 *
 * Separate from `HealthViewModel` rather than bolted onto it: that one is already over 600 lines and
 * covers an unrelated set of Health Connect record types.
 *
 * Room is the source of truth and Health Connect is a mirror written afterwards. That ordering is
 * what makes the feature work at all below Android 15, where the Personal Health Record API does not
 * exist, and it means a rejected or failed FHIR write costs the user nothing — the row is already
 * saved, it simply has no `fhirResourceId` yet.
 */
class MedicalViewModel(
    application: Application,
    private val repository: HealthRepository = HealthRepository.get(application),
) : AndroidViewModel(application) {

    val vaccinations: StateFlow<List<VaccinationEntry>> = repository.getVaccinationsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val medications: StateFlow<List<MedicationEntry>> = repository.getMedicationsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allergies: StateFlow<List<AllergyEntry>> = repository.getAllergiesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conditions: StateFlow<List<ConditionEntry>> = repository.getConditionsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val labResults: StateFlow<List<LabResultEntry>> = repository.getLabResultsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The standing facts — pregnancy and smoking status. Never null once first written. */
    val profile: StateFlow<HealthProfile> = repository.getProfileFlow()
        .map { it ?: HealthProfile() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthProfile())

    /** Attachments keyed by the vaccination they belong to. */
    val attachments: StateFlow<Map<String, List<MedicalAttachment>>> =
        repository.getAttachmentsFlow()
            .map { all -> all.groupBy { it.vaccinationId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Reminder schedules keyed by the medication they belong to. */
    val schedules: StateFlow<Map<String, MedicationSchedule>> =
        repository.getSchedulesFlow()
            .map { all -> all.associateBy { it.medicationId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /**
     * Whether this device can mirror to Health Connect at all. Drives an explanatory banner.
     *
     * Read once: the answer depends on the platform's Health Connect module, which cannot change
     * while the process is alive, and the screens read it on every recomposition.
     */
    val healthConnectAvailable: Boolean by lazy { PersonalHealthRecords.isAvailable() }

    // --- In-progress forms ---------------------------------------------------

    /**
     * The half-filled add forms live here rather than in `remember` inside the screens.
     *
     * `NavDisplay` composes only the top destination, so pushing the full-screen catalogue picker
     * disposes the form underneath it. Anything held in `remember` would be gone by the time the
     * user came back, and a result posted through `LocalNavResultRegistry` would land on a
     * collector that no longer exists — its `SharedFlow` has no replay. Keeping the draft here is
     * what makes the picker able to fill a field in at all, and it stops the rest of the form being
     * quietly wiped on the way past.
     */
    data class VaccinationDraft(
        /**
         * The row being edited, or null when adding.
         *
         * Reused as the FHIR resource id on save, so editing re-upserts the same Health Connect
         * resource rather than orphaning the old one and writing a second.
         */
        val editingId: String? = null,
        val cvxCode: String? = null,
        val displayName: String = "",
        val occurredOn: LocalDate = today(),
        val lotNumber: String = "",
        val site: String = "",
        val route: String = "",
        val dose: String = "",
        val performer: String = "",
        val note: String = "",
        /** Attachments already on disk, from a row being edited. */
        val savedAttachments: List<MedicalAttachment> = emptyList(),
        /**
         * Saved attachments the user has removed. Their files are deleted on save, not on tap, so
         * backing out of an edit cannot destroy a file the record still references.
         */
        val removedAttachmentIds: Set<String> = emptySet(),
        /** Newly picked files, held as strings so the draft stays comparable. */
        val attachmentUris: List<String> = emptyList(),
    ) {
        /** The saved attachments still to be shown, i.e. not pending removal. */
        val visibleSavedAttachments: List<MedicalAttachment>
            get() = savedAttachments.filterNot { it.id in removedAttachmentIds }
    }

    data class MedicationDraft(
        val editingId: String? = null,
        val rxcui: String? = null,
        val ingredient: String = "",
        val strength: String? = null,
        val doseForm: String? = null,
        val status: MedicationStatus = MedicationStatus.Active,
        val startedOn: LocalDate = today(),
        val endedOn: LocalDate? = null,
        val dosage: String = "",
        val note: String = "",
        // --- Reminder schedule ---
        val scheduleId: String? = null,
        val remindersEnabled: Boolean = false,
        /** Seconds since midnight, one per dose. */
        val times: List<Int> = listOf(9 * 60 * 60),
        val repeatUnit: RepeatUnit = RepeatUnit.Daily,
        val interval: Int = 1,
        val daysOfWeek: Int = 0,
        val anchorDate: LocalDate = today(),
        val remindersUntil: LocalDate? = null,
        /**
         * Which entry in [times] the time picker is currently editing, or [times].size to append.
         *
         * One picker route serves every row, so the index has to live somewhere the picker's result
         * can be applied against — and the form is not composed while a dialog is up.
         */
        val editingTimeIndex: Int? = null,
    )

    private val _vaccinationDraft = MutableStateFlow(VaccinationDraft())
    val vaccinationDraft: StateFlow<VaccinationDraft> = _vaccinationDraft.asStateFlow()

    private val _medicationDraft = MutableStateFlow(MedicationDraft())
    val medicationDraft: StateFlow<MedicationDraft> = _medicationDraft.asStateFlow()

    data class AllergyDraft(
        val editingId: String? = null,
        val rxcui: String? = null,
        val displayName: String = "",
        val category: AllergyCategory = AllergyCategory.Medication,
        val criticality: AllergyCriticality = AllergyCriticality.Unknown,
        val reaction: String = "",
        val onsetOn: LocalDate? = null,
        val note: String = "",
    )

    data class ConditionDraft(
        val editingId: String? = null,
        val icd10Code: String? = null,
        val displayName: String = "",
        val status: ConditionStatus = ConditionStatus.Active,
        val onsetOn: LocalDate = today(),
        val resolvedOn: LocalDate? = null,
        val note: String = "",
    )

    data class LabResultDraft(
        val editingId: String? = null,
        val loincCode: String? = null,
        val displayName: String = "",
        /** Free text while being typed; parsed to a number on save if it is one. */
        val value: String = "",
        val unit: String = "",
        val referenceLow: String = "",
        val referenceHigh: String = "",
        val takenOn: LocalDate = today(),
        val note: String = "",
    )

    private val _allergyDraft = MutableStateFlow(AllergyDraft())
    val allergyDraft: StateFlow<AllergyDraft> = _allergyDraft.asStateFlow()

    private val _conditionDraft = MutableStateFlow(ConditionDraft())
    val conditionDraft: StateFlow<ConditionDraft> = _conditionDraft.asStateFlow()

    fun startAllergyDraft(id: String? = null) {
        _allergyDraft.value = AllergyDraft()
        if (id == null) return
        viewModelScope.launch {
            val entry = repository.getAllergy(id) ?: return@launch
            _allergyDraft.value = AllergyDraft(
                editingId = entry.id,
                rxcui = entry.rxcui,
                displayName = entry.displayName,
                category = entry.category,
                criticality = entry.criticality,
                reaction = entry.reaction.orEmpty(),
                onsetOn = entry.onsetAt?.toLocalDate(),
                note = entry.note.orEmpty(),
            )
        }
    }

    fun editAllergyDraft(transform: (AllergyDraft) -> AllergyDraft) {
        _allergyDraft.update(transform)
    }

    fun startConditionDraft(id: String? = null) {
        _conditionDraft.value = ConditionDraft()
        if (id == null) return
        viewModelScope.launch {
            val entry = repository.getCondition(id) ?: return@launch
            _conditionDraft.value = ConditionDraft(
                editingId = entry.id,
                icd10Code = entry.icd10Code,
                displayName = entry.displayName,
                status = entry.status,
                onsetOn = entry.onsetAt.toLocalDate(),
                resolvedOn = entry.resolvedAt?.toLocalDate(),
                note = entry.note.orEmpty(),
            )
        }
    }

    fun editConditionDraft(transform: (ConditionDraft) -> ConditionDraft) {
        _conditionDraft.update(transform)
    }

    private val _labDraft = MutableStateFlow(LabResultDraft())
    val labDraft: StateFlow<LabResultDraft> = _labDraft.asStateFlow()

    fun startLabDraft(id: String? = null) {
        _labDraft.value = LabResultDraft()
        if (id == null) return
        viewModelScope.launch {
            val entry = repository.getLabResult(id) ?: return@launch
            _labDraft.value = LabResultDraft(
                editingId = entry.id,
                loincCode = entry.loincCode,
                displayName = entry.displayName,
                value = entry.value?.let { formatNumber(it) } ?: entry.valueText.orEmpty(),
                unit = entry.unit.orEmpty(),
                referenceLow = entry.referenceLow?.let { formatNumber(it) }.orEmpty(),
                referenceHigh = entry.referenceHigh?.let { formatNumber(it) }.orEmpty(),
                takenOn = entry.takenAt.toLocalDate(),
                note = entry.note.orEmpty(),
            )
        }
    }

    fun editLabDraft(transform: (LabResultDraft) -> LabResultDraft) {
        _labDraft.update(transform)
    }

    /**
     * Prepares the vaccination form: blank for a new record, or loaded from [id] to edit one.
     *
     * Called when the user opens the form, not when it is composed — the form is disposed and
     * recomposed every time a picker opens over it, and reloading there would discard their edits.
     */
    fun startVaccinationDraft(id: String? = null) {
        _vaccinationDraft.value = VaccinationDraft()
        if (id == null) return
        viewModelScope.launch {
            val entry = repository.getVaccination(id) ?: return@launch
            _vaccinationDraft.value = VaccinationDraft(
                editingId = entry.id,
                cvxCode = entry.cvxCode,
                displayName = entry.displayName,
                occurredOn = entry.occurredAt.toLocalDate(),
                lotNumber = entry.lotNumber.orEmpty(),
                site = entry.site.orEmpty(),
                route = entry.route.orEmpty(),
                dose = entry.doseQuantity.orEmpty(),
                performer = entry.performer.orEmpty(),
                note = entry.note.orEmpty(),
                savedAttachments = repository.getAttachmentsFor(entry.id),
            )
        }
    }

    fun editVaccinationDraft(transform: (VaccinationDraft) -> VaccinationDraft) {
        _vaccinationDraft.update(transform)
    }

    fun startMedicationDraft(id: String? = null) {
        _medicationDraft.value = MedicationDraft()
        if (id == null) return
        viewModelScope.launch {
            val entry = repository.getMedication(id) ?: return@launch
            val schedule = repository.getScheduleFor(id)
            _medicationDraft.value = MedicationDraft(
                editingId = entry.id,
                rxcui = entry.rxcui,
                ingredient = entry.displayName,
                strength = entry.strength,
                doseForm = entry.doseForm,
                status = entry.status,
                startedOn = entry.startedAt.toLocalDate(),
                endedOn = entry.endedAt?.toLocalDate(),
                dosage = entry.dosageText.orEmpty(),
                note = entry.note.orEmpty(),
                scheduleId = schedule?.id,
                remindersEnabled = schedule?.enabled == true,
                times = schedule?.times?.takeIf { it.isNotEmpty() } ?: listOf(9 * 60 * 60),
                repeatUnit = schedule?.repeatUnit ?: RepeatUnit.Daily,
                interval = schedule?.interval ?: 1,
                daysOfWeek = schedule?.daysOfWeek ?: 0,
                anchorDate = schedule?.anchorDate ?: today(),
                remindersUntil = schedule?.endDate,
            )
        }
    }

    fun editMedicationDraft(transform: (MedicationDraft) -> MedicationDraft) {
        _medicationDraft.update(transform)
    }

    // --- Vaccinations --------------------------------------------------------

    /**
     * Saves the vaccination form, whether adding or editing, then mirrors it.
     *
     * Newly picked attachments are imported here rather than when the user picked them, so
     * abandoning the form cannot leave orphaned files behind; removed ones are deleted here for the
     * mirror-image reason. [fallbackAttachmentName] covers a content provider that reports no
     * display name.
     */
    fun saveVaccinationDraft(fallbackAttachmentName: String) {
        val draft = _vaccinationDraft.value
        if (draft.displayName.isBlank()) return

        viewModelScope.launch {
            // Keeping the row id on edit is what makes the Health Connect mirror an update rather
            // than a second resource, since the row id is also the FHIR resource id.
            val existing = draft.editingId?.let { repository.getVaccination(it) }
            val entry = (existing ?: VaccinationEntry(
                id = Uuid.random().toString(),
                displayName = "",
                occurredAt = Instant.now(),
            )).copy(
                cvxCode = draft.cvxCode,
                displayName = draft.displayName.trim(),
                occurredAt = draft.occurredOn.toInstant(),
                lotNumber = draft.lotNumber.blankToNull(),
                site = draft.site.blankToNull(),
                route = draft.route.blankToNull(),
                doseQuantity = draft.dose.blankToNull(),
                performer = draft.performer.blankToNull(),
                note = draft.note.blankToNull(),
            )
            repository.upsertVaccination(entry)

            draft.savedAttachments
                .filter { it.id in draft.removedAttachmentIds }
                .forEach { attachment ->
                    AttachmentStore.delete(getApplication(), attachment.fileName)
                    repository.deleteAttachment(attachment)
                }

            val stored = withContext(Dispatchers.IO) {
                draft.attachmentUris.mapNotNull { uri ->
                    AttachmentStore.import(getApplication(), uri.toUri(), fallbackAttachmentName)
                }
            }
            val now = Instant.now()
            stored.forEach { imported ->
                repository.insertAttachment(
                    MedicalAttachment(
                        id = Uuid.random().toString(),
                        vaccinationId = entry.id,
                        fileName = imported.fileName,
                        displayName = imported.displayName,
                        mimeType = imported.mimeType,
                        sizeBytes = imported.sizeBytes,
                        importedAt = now,
                    )
                )
            }

            mirrorVaccination(entry.id)
        }
    }

    fun deleteVaccination(entry: VaccinationEntry) {
        viewModelScope.launch {
            repository.getAttachmentsFor(entry.id).forEach { attachment ->
                AttachmentStore.delete(getApplication(), attachment.fileName)
                repository.deleteAttachment(attachment)
            }
            repository.deleteVaccination(entry)

            val dataSourceId = entry.dataSourceId
            val fhirId = entry.fhirResourceId
            if (dataSourceId != null && fhirId != null) {
                PersonalHealthRecords.delete(
                    dataSourceId,
                    PersonalHealthRecords.immunizationResourceType,
                    fhirId,
                )
            }
        }
    }

    private suspend fun mirrorVaccination(id: String) {
        val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return
        val entry = repository.getVaccination(id) ?: return
        val fhirAttachments = repository.getAttachmentsFor(id).map { attachment ->
            FhirRecords.FhirAttachment(
                contentType = attachment.mimeType,
                title = attachment.displayName,
                url = AttachmentStore.fileFor(getApplication(), attachment.fileName).toURI().toString(),
                creation = attachment.importedAt,
            )
        }
        val written = PersonalHealthRecords.upsert(
            dataSourceId,
            FhirRecords.immunizationJson(entry, fhirAttachments),
        ) ?: return
        repository.upsertVaccination(
            entry.copy(fhirResourceId = written, dataSourceId = dataSourceId)
        )
    }

    // --- Medications ---------------------------------------------------------

    fun saveMedicationDraft() {
        val draft = _medicationDraft.value
        if (draft.ingredient.isBlank()) return
        viewModelScope.launch {
            val existing = draft.editingId?.let { repository.getMedication(it) }
            val entry = (existing ?: MedicationEntry(
                id = Uuid.random().toString(),
                displayName = "",
                startedAt = Instant.now(),
            )).copy(
                rxcui = draft.rxcui,
                displayName = draft.ingredient.trim(),
                strength = draft.strength,
                doseForm = draft.doseForm,
                status = draft.status,
                startedAt = draft.startedOn.toInstant(),
                endedAt = draft.endedOn?.toInstant(),
                dosageText = draft.dosage.blankToNull(),
                note = draft.note.blankToNull(),
            )
            repository.upsertMedication(entry)
            saveSchedule(draft, entry.id)
            mirrorMedication(entry.id)
        }
    }

    /**
     * Writes or clears the reminder schedule for [medicationId] and re-arms the alarm.
     *
     * Always re-arms from the stored row rather than the draft, so what fires is what was saved.
     */
    private suspend fun saveSchedule(draft: MedicationDraft, medicationId: String) {
        val context: Context = getApplication()
        if (!draft.remindersEnabled || draft.times.isEmpty()) {
            draft.scheduleId?.let { DoseScheduler.cancel(context, it) }
            repository.deleteScheduleFor(medicationId)
            return
        }
        val schedule = MedicationSchedule(
            id = draft.scheduleId ?: Uuid.random().toString(),
            medicationId = medicationId,
            enabled = true,
            times = draft.times.sorted(),
            repeatUnit = draft.repeatUnit,
            interval = draft.interval.coerceAtLeast(1),
            daysOfWeek = draft.daysOfWeek,
            anchorDate = draft.anchorDate,
            endDate = draft.remindersUntil,
        )
        repository.upsertSchedule(schedule)
        DoseScheduler.arm(context, schedule)
    }

    fun deleteMedication(entry: MedicationEntry) {
        viewModelScope.launch {
            repository.getScheduleFor(entry.id)?.let {
                DoseScheduler.cancel(getApplication(), it.id)
            }
            repository.deleteScheduleFor(entry.id)
            repository.deleteMedication(entry)
            val dataSourceId = entry.dataSourceId
            val fhirId = entry.fhirResourceId
            if (dataSourceId != null && fhirId != null) {
                PersonalHealthRecords.delete(
                    dataSourceId,
                    PersonalHealthRecords.medicationStatementResourceType,
                    fhirId,
                )
            }
        }
    }

    private suspend fun mirrorMedication(id: String) {
        val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return
        val entry = repository.getMedication(id) ?: return
        val written = PersonalHealthRecords.upsert(
            dataSourceId,
            FhirRecords.medicationStatementJson(entry),
        ) ?: return
        repository.upsertMedication(
            entry.copy(fhirResourceId = written, dataSourceId = dataSourceId)
        )
    }

    // --- Allergies and conditions --------------------------------------------

    fun saveAllergyDraft() {
        val draft = _allergyDraft.value
        if (draft.displayName.isBlank()) return
        viewModelScope.launch {
            val existing = draft.editingId?.let { repository.getAllergy(it) }
            val entry = (existing ?: AllergyEntry(
                id = Uuid.random().toString(),
                displayName = "",
                recordedAt = Instant.now(),
            )).copy(
                rxcui = draft.rxcui,
                displayName = draft.displayName.trim(),
                category = draft.category,
                criticality = draft.criticality,
                reaction = draft.reaction.blankToNull(),
                onsetAt = draft.onsetOn?.toInstant(),
                note = draft.note.blankToNull(),
            )
            repository.upsertAllergy(entry)
            mirrorAllergy(entry.id)
        }
    }

    fun deleteAllergy(entry: AllergyEntry) {
        viewModelScope.launch {
            repository.deleteAllergy(entry)
            val dataSourceId = entry.dataSourceId
            val fhirId = entry.fhirResourceId
            if (dataSourceId != null && fhirId != null) {
                PersonalHealthRecords.delete(
                    dataSourceId,
                    PersonalHealthRecords.allergyResourceType,
                    fhirId,
                )
            }
        }
    }

    private suspend fun mirrorAllergy(id: String) {
        val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return
        val entry = repository.getAllergy(id) ?: return
        val written = PersonalHealthRecords.upsert(
            dataSourceId,
            FhirRecords.allergyIntoleranceJson(entry),
        ) ?: return
        repository.upsertAllergy(entry.copy(fhirResourceId = written, dataSourceId = dataSourceId))
    }

    fun saveConditionDraft() {
        val draft = _conditionDraft.value
        if (draft.displayName.isBlank()) return
        viewModelScope.launch {
            val existing = draft.editingId?.let { repository.getCondition(it) }
            val entry = (existing ?: ConditionEntry(
                id = Uuid.random().toString(),
                displayName = "",
                onsetAt = Instant.now(),
            )).copy(
                icd10Code = draft.icd10Code,
                displayName = draft.displayName.trim(),
                status = draft.status,
                onsetAt = draft.onsetOn.toInstant(),
                resolvedAt = draft.resolvedOn?.toInstant(),
                note = draft.note.blankToNull(),
            )
            repository.upsertCondition(entry)
            mirrorCondition(entry.id)
        }
    }

    fun deleteCondition(entry: ConditionEntry) {
        viewModelScope.launch {
            repository.deleteCondition(entry)
            val dataSourceId = entry.dataSourceId
            val fhirId = entry.fhirResourceId
            if (dataSourceId != null && fhirId != null) {
                PersonalHealthRecords.delete(
                    dataSourceId,
                    PersonalHealthRecords.conditionResourceType,
                    fhirId,
                )
            }
        }
    }

    private suspend fun mirrorCondition(id: String) {
        val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return
        val entry = repository.getCondition(id) ?: return
        val written = PersonalHealthRecords.upsert(
            dataSourceId,
            FhirRecords.conditionJson(entry),
        ) ?: return
        repository.upsertCondition(entry.copy(fhirResourceId = written, dataSourceId = dataSourceId))
    }

    // --- Lab results and the standing profile --------------------------------

    fun saveLabDraft() {
        val draft = _labDraft.value
        if (draft.displayName.isBlank()) return
        viewModelScope.launch {
            val existing = draft.editingId?.let { repository.getLabResult(it) }
            // A result is either a number or a word. Anything that does not parse is kept verbatim
            // as text rather than being coerced to zero, because "positive" and "trace" are real
            // results and silently turning one into 0.0 would be a clinically wrong record.
            val numeric = draft.value.trim().replace(',', '.').toDoubleOrNull()
            val entry = (existing ?: LabResultEntry(
                id = Uuid.random().toString(),
                displayName = "",
                takenAt = Instant.now(),
            )).copy(
                loincCode = draft.loincCode,
                displayName = draft.displayName.trim(),
                value = numeric,
                valueText = if (numeric == null) draft.value.blankToNull() else null,
                unit = draft.unit.blankToNull(),
                referenceLow = draft.referenceLow.trim().toDoubleOrNull(),
                referenceHigh = draft.referenceHigh.trim().toDoubleOrNull(),
                takenAt = draft.takenOn.toInstant(),
                note = draft.note.blankToNull(),
            )
            repository.upsertLabResult(entry)
            mirrorLabResult(entry.id)
        }
    }

    fun deleteLabResult(entry: LabResultEntry) {
        viewModelScope.launch {
            repository.deleteLabResult(entry)
            val dataSourceId = entry.dataSourceId
            val fhirId = entry.fhirResourceId
            if (dataSourceId != null && fhirId != null) {
                PersonalHealthRecords.delete(
                    dataSourceId,
                    PersonalHealthRecords.observationResourceType,
                    fhirId,
                )
            }
        }
    }

    private suspend fun mirrorLabResult(id: String) {
        val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return
        val entry = repository.getLabResult(id) ?: return
        val written = PersonalHealthRecords.upsert(
            dataSourceId,
            FhirRecords.labResultJson(entry),
        ) ?: return
        repository.upsertLabResult(entry.copy(fhirResourceId = written, dataSourceId = dataSourceId))
    }

    /**
     * Records a new pregnancy status, dated now.
     *
     * Each change writes a fresh observation rather than editing the last one, because "pregnant as
     * of March" and "not pregnant as of September" are both true statements about different moments
     * and a clinician reading the record needs the date attached.
     */
    fun setPregnancyStatus(status: PregnancyStatus, dueDate: LocalDate?) {
        viewModelScope.launch {
            val now = Instant.now()
            val current = repository.getProfile() ?: HealthProfile()
            val updated = current.copy(
                pregnancyStatus = status,
                dueDate = dueDate?.toInstant(),
                pregnancyRecordedAt = now,
                pregnancyFhirId = current.pregnancyFhirId ?: Uuid.random().toString(),
            )
            repository.upsertProfile(updated)
            mirrorProfile(pregnancy = true)
        }
    }

    fun setSmokingStatus(status: SmokingStatus) {
        viewModelScope.launch {
            val now = Instant.now()
            val current = repository.getProfile() ?: HealthProfile()
            val updated = current.copy(
                smokingStatus = status,
                smokingRecordedAt = now,
                smokingFhirId = current.smokingFhirId ?: Uuid.random().toString(),
            )
            repository.upsertProfile(updated)
            mirrorProfile(pregnancy = false)
        }
    }

    private suspend fun mirrorProfile(pregnancy: Boolean) {
        val dataSourceId = PersonalHealthRecords.dataSourceId() ?: return
        val profile = repository.getProfile() ?: return
        if (pregnancy) {
            val id = profile.pregnancyFhirId ?: return
            val recordedAt = profile.pregnancyRecordedAt ?: return
            PersonalHealthRecords.upsert(
                dataSourceId,
                FhirRecords.pregnancyStatusJson(
                    id, profile.pregnancyStatus, recordedAt, profile.dueDate
                ),
            )
        } else {
            val id = profile.smokingFhirId ?: return
            val recordedAt = profile.smokingRecordedAt ?: return
            PersonalHealthRecords.upsert(
                dataSourceId,
                FhirRecords.smokingStatusJson(id, profile.smokingStatus, recordedAt),
            )
        }
        repository.upsertProfile(profile.copy(dataSourceId = dataSourceId))
    }

    // --- Import --------------------------------------------------------------

    /**
     * Pulls vaccinations and medications out of Health Connect into Room.
     *
     * This is what surfaces records a hospital or pharmacy has synced in, so it deliberately reads
     * every data source rather than only this app's. Rows the app wrote are matched on their FHIR id
     * and left alone; anything else is inserted.
     */
    fun importFromHealthConnect() {
        if (_syncing.value || !PersonalHealthRecords.isAvailable()) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val vaccines = PersonalHealthRecords.readAll(PersonalHealthRecords.vaccinesType)
                    .mapNotNull { resource ->
                        FhirRecords.parseImmunization(resource.data, resource.dataSourceId)
                    }
                if (vaccines.isNotEmpty()) repository.upsertVaccinations(vaccines)

                val meds = PersonalHealthRecords.readAll(PersonalHealthRecords.medicationsType)
                    .mapNotNull { resource ->
                        FhirRecords.parseMedicationStatement(resource.data, resource.dataSourceId)
                    }
                if (meds.isNotEmpty()) repository.upsertMedications(meds)

                val allergyRows = PersonalHealthRecords.readAll(PersonalHealthRecords.allergiesType)
                    .mapNotNull { resource ->
                        FhirRecords.parseAllergyIntolerance(resource.data, resource.dataSourceId)
                    }
                if (allergyRows.isNotEmpty()) repository.upsertAllergies(allergyRows)

                val conditionRows = PersonalHealthRecords.readAll(PersonalHealthRecords.conditionsType)
                    .mapNotNull { resource ->
                        FhirRecords.parseCondition(resource.data, resource.dataSourceId)
                    }
                if (conditionRows.isNotEmpty()) repository.upsertConditions(conditionRows)

                val labRows = PersonalHealthRecords.readAll(PersonalHealthRecords.labsType)
                    .mapNotNull { resource ->
                        FhirRecords.parseLabResult(resource.data, resource.dataSourceId)
                    }
                if (labRows.isNotEmpty()) repository.upsertLabResults(labRows)

                importProfile()
            } catch (e: Exception) {
                Log.e(TAG, "Import from Health Connect failed", e)
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * Pulls the two standing observations back out of Health Connect.
     *
     * Both live in the same PHR category and are told apart by their LOINC code, since a category
     * read returns every social-history observation a provider has ever written — alcohol use,
     * housing status and the rest — not just the two this app writes. Only the newest of each is
     * kept: these are current answers, not a log.
     */
    private suspend fun importProfile() {
        val resources = PersonalHealthRecords.readAll(PersonalHealthRecords.socialHistoryType) +
            PersonalHealthRecords.readAll(PersonalHealthRecords.pregnancyType)
        if (resources.isEmpty()) return

        var profile = repository.getProfile() ?: HealthProfile()
        resources.forEach { resource ->
            when (FhirRecords.observationLoincCode(resource.data)) {
                FhirRecords.LOINC_PREGNANCY_STATUS ->
                    FhirRecords.parsePregnancyStatus(resource.data)?.let { (status, due) ->
                        profile = profile.copy(pregnancyStatus = status, dueDate = due)
                    }
                FhirRecords.LOINC_SMOKING_STATUS ->
                    FhirRecords.parseSmokingStatus(resource.data)?.let { status ->
                        profile = profile.copy(smokingStatus = status)
                    }
            }
        }
        repository.upsertProfile(profile)
    }

    companion object {
        private const val TAG = "MedicalViewModel"
    }
}

/** Trims a trailing ".0" so an integral result edits back as "12" rather than "12.0". */
private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

class MedicalViewModelFactory(
    private val application: Application,
    private val repository: HealthRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MedicalViewModel::class.java))
        return MedicalViewModel(application, repository) as T
    }
}
