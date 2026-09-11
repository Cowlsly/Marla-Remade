package com.vayunmathur.health.domain

import com.vayunmathur.health.data.AllergyCategory
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.SmokingStatus
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.VaccinationEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds and reads the FHIR R4 JSON that Health Connect's Personal Health Record store accepts.
 *
 * Health Connect takes the resource as an opaque JSON string and validates it against the spec, so
 * the required fields below are not optional decoration: `Immunization` must carry `status`,
 * `vaccineCode`, `patient` and an `occurrence[x]`, and `MedicationStatement` must carry `status`,
 * `subject` and a `medication[x]`. Both reference a `Patient`, which is why [patientJson] exists and
 * is written to the same data source before anything else.
 *
 * Reading is deliberately far more forgiving than writing. Resources synced from a real health system
 * are shown alongside the user's own, and those carry codings, extensions and date precisions this
 * app never produces; anything unrecognised is skipped rather than failing the import.
 */
object FhirRecords {

    /** The one synthetic patient every resource this app writes points at. */
    const val PATIENT_RESOURCE_ID = "ma-health-patient"

    const val CVX_SYSTEM = "http://hl7.org/fhir/sid/cvx"
    const val RXNORM_SYSTEM = "http://www.nlm.nih.gov/research/umls/rxnorm"
    const val ICD10_SYSTEM = "http://hl7.org/fhir/sid/icd-10-cm"
    const val LOINC_SYSTEM = "http://loinc.org"
    const val SNOMED_SYSTEM = "http://snomed.info/sct"
    const val UCUM_SYSTEM = "http://unitsofmeasure.org"
    private const val OBSERVATION_CATEGORY_SYSTEM =
        "http://terminology.hl7.org/CodeSystem/observation-category"

    /** LOINC code for the pregnancy status observation US Core defines. */
    const val LOINC_PREGNANCY_STATUS = "82810-3"

    /** LOINC code for the tobacco smoking status observation US Core requires. */
    const val LOINC_SMOKING_STATUS = "72166-2"

    /**
     * Extension carrying a scanned vaccination card.
     *
     * None of the resource types Health Connect accepts has an attachment field, and
     * `DocumentReference` is not one of them, so the file itself is kept in app storage and only
     * referenced from here. The URL is a `file://` path that is meaningless to any other reader,
     * which is exactly why this is a custom extension rather than a misuse of a standard field.
     */
    const val ATTACHMENT_EXTENSION_URL =
        "https://vayunmathur.com/fhir/StructureDefinition/attachment"

    /** One attached file, as it appears inside an [ATTACHMENT_EXTENSION_URL] extension. */
    data class FhirAttachment(
        val contentType: String,
        val title: String,
        val url: String,
        val creation: Instant,
    )

    private val json = Json { ignoreUnknownKeys = true }

    // --- Writing -------------------------------------------------------------

    fun patientJson(): String = buildJsonObject {
        put("resourceType", "Patient")
        put("id", PATIENT_RESOURCE_ID)
    }.toString()

    fun immunizationJson(
        entry: VaccinationEntry,
        attachments: List<FhirAttachment> = emptyList(),
    ): String = buildJsonObject {
        put("resourceType", "Immunization")
        put("id", entry.id)
        put("status", "completed")
        putJsonObject("vaccineCode") {
            if (entry.cvxCode != null) {
                putJsonArray("coding") {
                    add(
                        buildJsonObject {
                            put("system", CVX_SYSTEM)
                            put("code", entry.cvxCode)
                            put("display", entry.displayName)
                        }
                    )
                }
            }
            put("text", entry.displayName)
        }
        putJsonObject("patient") { put("reference", "Patient/$PATIENT_RESOURCE_ID") }
        put("occurrenceDateTime", formatDateTime(entry.occurredAt))
        entry.lotNumber?.takeIf { it.isNotBlank() }?.let { put("lotNumber", it) }
        entry.site?.takeIf { it.isNotBlank() }?.let { putJsonObject("site") { put("text", it) } }
        entry.route?.takeIf { it.isNotBlank() }?.let { putJsonObject("route") { put("text", it) } }

        // doseQuantity is a SimpleQuantity with no text field, so a dose the user typed freehand can
        // only be carried when it splits cleanly into a number and a unit. Anything else would have
        // to be dropped, so it falls through to the note instead.
        val dose = entry.doseQuantity?.let(::parseQuantity)
        if (dose != null) {
            putJsonObject("doseQuantity") {
                put("value", dose.first)
                put("unit", dose.second)
            }
        }
        entry.performer?.takeIf { it.isNotBlank() }?.let {
            putJsonArray("performer") {
                add(buildJsonObject { putJsonObject("actor") { put("display", it) } })
            }
        }

        val unparsedDose = entry.doseQuantity?.takeIf { it.isNotBlank() && dose == null }
        val notes = listOfNotNull(entry.note?.takeIf { it.isNotBlank() }, unparsedDose)
        if (notes.isNotEmpty()) {
            putJsonArray("note") {
                notes.forEach { add(buildJsonObject { put("text", it) }) }
            }
        }

        if (attachments.isNotEmpty()) {
            putJsonArray("extension") {
                attachments.forEach { attachment ->
                    add(
                        buildJsonObject {
                            put("url", ATTACHMENT_EXTENSION_URL)
                            putJsonObject("valueAttachment") {
                                put("contentType", attachment.contentType)
                                put("title", attachment.title)
                                put("url", attachment.url)
                                put("creation", formatDateTime(attachment.creation))
                            }
                        }
                    )
                }
            }
        }
    }.toString()

    fun medicationStatementJson(entry: MedicationEntry): String = buildJsonObject {
        put("resourceType", "MedicationStatement")
        put("id", entry.id)
        put("status", statusToFhir(entry.status))
        putJsonObject("medicationCodeableConcept") {
            if (entry.rxcui != null) {
                putJsonArray("coding") {
                    add(
                        buildJsonObject {
                            put("system", RXNORM_SYSTEM)
                            put("code", entry.rxcui)
                            put("display", fullMedicationName(entry))
                        }
                    )
                }
            }
            put("text", fullMedicationName(entry))
        }
        putJsonObject("subject") { put("reference", "Patient/$PATIENT_RESOURCE_ID") }
        putJsonObject("effectivePeriod") {
            put("start", formatDateTime(entry.startedAt))
            entry.endedAt?.let { put("end", formatDateTime(it)) }
        }
        entry.dosageText?.takeIf { it.isNotBlank() }?.let {
            putJsonArray("dosage") { add(buildJsonObject { put("text", it) }) }
        }
        entry.note?.takeIf { it.isNotBlank() }?.let {
            putJsonArray("note") { add(buildJsonObject { put("text", it) }) }
        }
    }.toString()

    /** "Amoxicillin", "500 mg", "Capsule" rendered as one line for `display` and `text`. */
    fun fullMedicationName(entry: MedicationEntry): String =
        listOfNotNull(
            entry.displayName.takeIf { it.isNotBlank() },
            entry.strength?.takeIf { it.isNotBlank() },
            entry.doseForm?.takeIf { it.isNotBlank() },
        ).joinToString(" ")

    fun allergyIntoleranceJson(entry: AllergyEntry): String = buildJsonObject {
        put("resourceType", "AllergyIntolerance")
        put("id", entry.id)
        // An allergy list is only safe to act on if "not currently allergic" is distinguishable
        // from "never recorded", so clinicalStatus is always written rather than left implicit.
        putJsonObject("clinicalStatus") {
            putJsonArray("coding") {
                add(
                    buildJsonObject {
                        put(
                            "system",
                            "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical",
                        )
                        put("code", "active")
                    }
                )
            }
        }
        put("type", "allergy")
        putJsonArray("category") { add(JsonPrimitive(categoryToFhir(entry.category))) }
        criticalityToFhir(entry.criticality)?.let { put("criticality", it) }
        putJsonObject("code") {
            // Only medication allergens can be coded: RxNorm is the one terminology shipped that
            // covers them. Food and environmental allergens would need SNOMED CT, which cannot be
            // redistributed, so they travel as text and remain valid FHIR.
            if (entry.rxcui != null) {
                putJsonArray("coding") {
                    add(
                        buildJsonObject {
                            put("system", RXNORM_SYSTEM)
                            put("code", entry.rxcui)
                            put("display", entry.displayName)
                        }
                    )
                }
            }
            put("text", entry.displayName)
        }
        putJsonObject("patient") { put("reference", "Patient/$PATIENT_RESOURCE_ID") }
        entry.onsetAt?.let { put("onsetDateTime", formatDateTime(it)) }
        put("recordedDate", formatDateTime(entry.recordedAt))
        entry.reaction?.takeIf { it.isNotBlank() }?.let { reaction ->
            putJsonArray("reaction") {
                add(
                    buildJsonObject {
                        putJsonArray("manifestation") {
                            add(buildJsonObject { put("text", reaction) })
                        }
                    }
                )
            }
        }
        entry.note?.takeIf { it.isNotBlank() }?.let {
            putJsonArray("note") { add(buildJsonObject { put("text", it) }) }
        }
    }.toString()

    fun conditionJson(entry: ConditionEntry): String = buildJsonObject {
        put("resourceType", "Condition")
        put("id", entry.id)
        putJsonObject("clinicalStatus") {
            putJsonArray("coding") {
                add(
                    buildJsonObject {
                        put("system", "http://terminology.hl7.org/CodeSystem/condition-clinical")
                        put("code", statusToFhir(entry.status))
                    }
                )
            }
        }
        // US Core requires a category on Condition; "problem-list-item" is what a user-maintained
        // diagnosis list is, as opposed to an encounter diagnosis.
        putJsonArray("category") {
            add(
                buildJsonObject {
                    putJsonArray("coding") {
                        add(
                            buildJsonObject {
                                put(
                                    "system",
                                    "http://terminology.hl7.org/CodeSystem/condition-category",
                                )
                                put("code", "problem-list-item")
                            }
                        )
                    }
                }
            )
        }
        putJsonObject("code") {
            if (entry.icd10Code != null) {
                putJsonArray("coding") {
                    add(
                        buildJsonObject {
                            put("system", ICD10_SYSTEM)
                            put("code", entry.icd10Code)
                            put("display", entry.displayName)
                        }
                    )
                }
            }
            put("text", entry.displayName)
        }
        putJsonObject("subject") { put("reference", "Patient/$PATIENT_RESOURCE_ID") }
        put("onsetDateTime", formatDateTime(entry.onsetAt))
        entry.resolvedAt?.let { put("abatementDateTime", formatDateTime(it)) }
        entry.note?.takeIf { it.isNotBlank() }?.let {
            putJsonArray("note") { add(buildJsonObject { put("text", it) }) }
        }
    }.toString()

    // --- Reading -------------------------------------------------------------

    fun parseImmunization(data: String, dataSourceId: String): VaccinationEntry? {
        val root = parseObject(data) ?: return null
        if (root.string("resourceType") != "Immunization") return null
        val id = root.string("id") ?: return null

        val vaccineCode = root["vaccineCode"] as? JsonObject
        val coding = vaccineCode.codingWithSystem(CVX_SYSTEM)
        val displayName = coding?.string("display")
            ?: vaccineCode?.string("text")
            ?: vaccineCode.anyCoding()?.string("display")
            ?: return null

        val occurredAt = parseDateTime(root.string("occurrenceDateTime")) ?: return null

        return VaccinationEntry(
            id = id,
            cvxCode = coding?.string("code"),
            displayName = displayName,
            occurredAt = occurredAt,
            lotNumber = root.string("lotNumber"),
            site = (root["site"] as? JsonObject).conceptText(),
            route = (root["route"] as? JsonObject).conceptText(),
            doseQuantity = (root["doseQuantity"] as? JsonObject)?.let { quantity ->
                val value = quantity["value"]?.jsonPrimitive?.doubleOrNull ?: return@let null
                listOfNotNull(formatQuantity(value), quantity.string("unit")).joinToString(" ")
            },
            performer = (root["performer"] as? JsonArray)
                ?.firstNotNullOfOrNull { (it as? JsonObject)?.get("actor") as? JsonObject }
                ?.string("display"),
            note = root.firstNoteText(),
            fhirResourceId = id,
            dataSourceId = dataSourceId,
        )
    }

    fun parseMedicationStatement(data: String, dataSourceId: String): MedicationEntry? {
        val root = parseObject(data) ?: return null
        if (root.string("resourceType") != "MedicationStatement") return null
        val id = root.string("id") ?: return null

        val concept = root["medicationCodeableConcept"] as? JsonObject
        val coding = concept.codingWithSystem(RXNORM_SYSTEM)
        val displayName = coding?.string("display")
            ?: concept?.string("text")
            ?: concept.anyCoding()?.string("display")
            ?: return null

        val period = root["effectivePeriod"] as? JsonObject
        val startedAt = parseDateTime(period?.string("start"))
            ?: parseDateTime(root.string("effectiveDateTime"))
            ?: return null

        return MedicationEntry(
            id = id,
            rxcui = coding?.string("code"),
            displayName = displayName,
            status = statusFromFhir(root.string("status")),
            startedAt = startedAt,
            endedAt = parseDateTime(period?.string("end")),
            dosageText = (root["dosage"] as? JsonArray)
                ?.firstNotNullOfOrNull { (it as? JsonObject)?.string("text") },
            note = root.firstNoteText(),
            fhirResourceId = id,
            dataSourceId = dataSourceId,
        )
    }

    fun parseAllergyIntolerance(data: String, dataSourceId: String): AllergyEntry? {
        val root = parseObject(data) ?: return null
        if (root.string("resourceType") != "AllergyIntolerance") return null
        val id = root.string("id") ?: return null

        val code = root["code"] as? JsonObject
        val coding = code.codingWithSystem(RXNORM_SYSTEM)
        val displayName = coding?.string("display")
            ?: code?.string("text")
            ?: code.anyCoding()?.string("display")
            ?: return null

        return AllergyEntry(
            id = id,
            rxcui = coding?.string("code"),
            displayName = displayName,
            category = categoryFromFhir(
                (root["category"] as? JsonArray)
                    ?.firstOrNull()
                    ?.let { (it as? JsonPrimitive)?.content }
            ),
            criticality = criticalityFromFhir(root.string("criticality")),
            reaction = (root["reaction"] as? JsonArray)
                ?.firstNotNullOfOrNull { entry ->
                    ((entry as? JsonObject)?.get("manifestation") as? JsonArray)
                        ?.firstNotNullOfOrNull { (it as? JsonObject).conceptText() }
                },
            onsetAt = parseDateTime(root.string("onsetDateTime")),
            recordedAt = parseDateTime(root.string("recordedDate")) ?: Instant.EPOCH,
            note = root.firstNoteText(),
            fhirResourceId = id,
            dataSourceId = dataSourceId,
        )
    }

    fun parseCondition(data: String, dataSourceId: String): ConditionEntry? {
        val root = parseObject(data) ?: return null
        if (root.string("resourceType") != "Condition") return null
        val id = root.string("id") ?: return null

        val code = root["code"] as? JsonObject
        val coding = code.codingWithSystem(ICD10_SYSTEM)
        val displayName = coding?.string("display")
            ?: code?.string("text")
            ?: code.anyCoding()?.string("display")
            ?: return null

        // A provider may date a diagnosis by age or by a period rather than an instant, and may not
        // date it at all. Falling back to the recorded date keeps the row usable; without any date
        // it would sort unpredictably in a reverse-chronological list.
        val onset = parseDateTime(root.string("onsetDateTime"))
            ?: parseDateTime((root["onsetPeriod"] as? JsonObject).string("start"))
            ?: parseDateTime(root.string("recordedDate"))
            ?: Instant.EPOCH

        return ConditionEntry(
            id = id,
            icd10Code = coding?.string("code"),
            displayName = displayName,
            status = conditionStatusFromFhir(
                (root["clinicalStatus"] as? JsonObject).clinicalStatusCode()
            ),
            onsetAt = onset,
            resolvedAt = parseDateTime(root.string("abatementDateTime"))
                ?: parseDateTime((root["abatementPeriod"] as? JsonObject).string("end")),
            note = root.firstNoteText(),
            fhirResourceId = id,
            dataSourceId = dataSourceId,
        )
    }

    // --- Observations --------------------------------------------------------

    /**
     * A lab result, as an `Observation` in the `laboratory` category.
     *
     * `valueQuantity` when the result is a number, `valueString` when it is not — "positive",
     * "trace", "not detected" are all real results and none of them is a quantity. FHIR draws the
     * same distinction, so nothing has to be invented to carry them.
     */
    fun labResultJson(entry: LabResultEntry): String = buildJsonObject {
        put("resourceType", "Observation")
        put("id", entry.id)
        put("status", "final")
        putObservationCategory("laboratory")
        putJsonObject("code") {
            if (entry.loincCode != null) {
                putJsonArray("coding") {
                    add(
                        buildJsonObject {
                            put("system", LOINC_SYSTEM)
                            put("code", entry.loincCode)
                            put("display", entry.displayName)
                        }
                    )
                }
            }
            put("text", entry.displayName)
        }
        putJsonObject("subject") { put("reference", "Patient/$PATIENT_RESOURCE_ID") }
        put("effectiveDateTime", formatDateTime(entry.takenAt))

        val numeric = entry.value
        if (numeric != null) {
            putJsonObject("valueQuantity") {
                put("value", numeric)
                entry.unit?.takeIf { it.isNotBlank() }?.let {
                    put("unit", it)
                    put("system", UCUM_SYSTEM)
                    put("code", it)
                }
            }
        } else {
            entry.valueText?.takeIf { it.isNotBlank() }?.let { put("valueString", it) }
        }

        if (entry.referenceLow != null || entry.referenceHigh != null) {
            putJsonArray("referenceRange") {
                add(
                    buildJsonObject {
                        entry.referenceLow?.let {
                            putJsonObject("low") { putQuantity(it, entry.unit) }
                        }
                        entry.referenceHigh?.let {
                            putJsonObject("high") { putQuantity(it, entry.unit) }
                        }
                    }
                )
            }
        }
        entry.note?.takeIf { it.isNotBlank() }?.let {
            putJsonArray("note") { add(buildJsonObject { put("text", it) }) }
        }
    }.toString()

    /**
     * Pregnancy status, as the `Observation` US Core defines.
     *
     * A coded answer rather than a boolean, because "unknown" is a distinct and clinically
     * meaningful third state — which is why [PregnancyStatus.Unknown] is never written at all rather
     * than being recorded as "not pregnant".
     */
    fun pregnancyStatusJson(
        id: String,
        status: PregnancyStatus,
        recordedAt: Instant,
        dueDate: Instant?,
    ): String = buildJsonObject {
        put("resourceType", "Observation")
        put("id", id)
        put("status", "final")
        putObservationCategory("social-history")
        putCoded("code", LOINC_SYSTEM, LOINC_PREGNANCY_STATUS, "Pregnancy status")
        putJsonObject("subject") { put("reference", "Patient/$PATIENT_RESOURCE_ID") }
        put("effectiveDateTime", formatDateTime(recordedAt))
        val (code, display) = pregnancyCoding(status)
        putCoded("valueCodeableConcept", SNOMED_SYSTEM, code, display)
        // The estimated delivery date rides along as its own component rather than a second
        // resource, which is how US Core carries it.
        dueDate?.let {
            putJsonArray("component") {
                add(
                    buildJsonObject {
                        putCoded("code", LOINC_SYSTEM, "11778-8", "Estimated date of delivery")
                        put("valueDateTime", formatDateTime(it))
                    }
                )
            }
        }
    }.toString()

    /** Tobacco smoking status, as the `Observation` US Core requires. */
    fun smokingStatusJson(id: String, status: SmokingStatus, recordedAt: Instant): String =
        buildJsonObject {
            put("resourceType", "Observation")
            put("id", id)
            put("status", "final")
            putObservationCategory("social-history")
            putCoded("code", LOINC_SYSTEM, LOINC_SMOKING_STATUS, "Tobacco smoking status")
            putJsonObject("subject") { put("reference", "Patient/$PATIENT_RESOURCE_ID") }
            put("effectiveDateTime", formatDateTime(recordedAt))
            val (code, display) = smokingCoding(status)
            putCoded("valueCodeableConcept", SNOMED_SYSTEM, code, display)
        }.toString()

    /**
     * One answered social history question, as an `Observation` carrying a LOINC answer code.
     *
     * Unlike smoking and pregnancy, whose US Core profiles bind to SNOMED, these questions have
     * LOINC's own normative answer lists — so both the question and the answer are LOINC codes and
     * no second terminology is involved.
     */
    fun socialHistoryAnswerJson(
        id: String,
        question: SocialHistoryQuestions.Question,
        answer: SocialHistoryQuestions.Answer,
        recordedAt: Instant,
    ): String = buildJsonObject {
        put("resourceType", "Observation")
        put("id", id)
        put("status", "final")
        putObservationCategory("social-history")
        putCoded("code", LOINC_SYSTEM, question.loinc, question.display)
        putJsonObject("subject") { put("reference", "Patient/$PATIENT_RESOURCE_ID") }
        put("effectiveDateTime", formatDateTime(recordedAt))
        putCoded("valueCodeableConcept", LOINC_SYSTEM, answer.code, answer.display)
    }.toString()

    /** The LA answer code from a social history observation, if it carries one. */
    fun parseSocialHistoryAnswer(data: String): String? {
        val root = parseObject(data) ?: return null
        if (root.string("resourceType") != "Observation") return null
        return (root["valueCodeableConcept"] as? JsonObject)
            .codingWithSystem(LOINC_SYSTEM)?.string("code")
    }

    fun parseLabResult(data: String, dataSourceId: String): LabResultEntry? {
        val root = parseObject(data) ?: return null
        if (root.string("resourceType") != "Observation") return null
        val id = root.string("id") ?: return null

        val code = root["code"] as? JsonObject
        val coding = code.codingWithSystem(LOINC_SYSTEM)
        val displayName = coding?.string("display")
            ?: code?.string("text")
            ?: code.anyCoding()?.string("display")
            ?: return null

        val quantity = root["valueQuantity"] as? JsonObject
        val range = (root["referenceRange"] as? JsonArray)?.firstOrNull() as? JsonObject

        return LabResultEntry(
            id = id,
            loincCode = coding?.string("code"),
            displayName = displayName,
            value = quantity?.get("value")?.jsonPrimitive?.doubleOrNull,
            valueText = root.string("valueString")
                ?: (root["valueCodeableConcept"] as? JsonObject).conceptText(),
            unit = quantity.string("unit") ?: quantity.string("code"),
            referenceLow = (range?.get("low") as? JsonObject)
                ?.get("value")?.jsonPrimitive?.doubleOrNull,
            referenceHigh = (range?.get("high") as? JsonObject)
                ?.get("value")?.jsonPrimitive?.doubleOrNull,
            takenAt = parseDateTime(root.string("effectiveDateTime"))
                ?: parseDateTime(root.string("issued"))
                ?: return null,
            note = root.firstNoteText(),
            fhirResourceId = id,
            dataSourceId = dataSourceId,
        )
    }

    /** The LOINC code an observation is about, so a reader can tell the three kinds apart. */
    fun observationLoincCode(data: String): String? {
        val root = parseObject(data) ?: return null
        if (root.string("resourceType") != "Observation") return null
        return (root["code"] as? JsonObject).codingWithSystem(LOINC_SYSTEM)?.string("code")
    }

    /** The status and estimated delivery date from a pregnancy observation. */
    fun parsePregnancyStatus(data: String): Pair<PregnancyStatus, Instant?>? {
        val root = parseObject(data) ?: return null
        val value = (root["valueCodeableConcept"] as? JsonObject)
            .codingWithSystem(SNOMED_SYSTEM)?.string("code")
        val due = (root["component"] as? JsonArray)
            ?.firstNotNullOfOrNull { (it as? JsonObject)?.string("valueDateTime") }
            ?.let(::parseDateTime)
        return pregnancyFromCode(value) to due
    }

    fun parseSmokingStatus(data: String): SmokingStatus? {
        val root = parseObject(data) ?: return null
        val value = (root["valueCodeableConcept"] as? JsonObject)
            .codingWithSystem(SNOMED_SYSTEM)?.string("code")
        return smokingFromCode(value)
    }

    // --- Helpers -------------------------------------------------------------

    private fun JsonObjectBuilder.putObservationCategory(code: String) {
        putJsonArray("category") {
            add(
                buildJsonObject {
                    putJsonArray("coding") {
                        add(
                            buildJsonObject {
                                put("system", OBSERVATION_CATEGORY_SYSTEM)
                                put("code", code)
                            }
                        )
                    }
                }
            )
        }
    }

    /** A CodeableConcept with exactly one coding, which is all any of these need. */
    private fun JsonObjectBuilder.putCoded(
        key: String,
        system: String,
        code: String,
        display: String,
    ) {
        putJsonObject(key) {
            putJsonArray("coding") {
                add(
                    buildJsonObject {
                        put("system", system)
                        put("code", code)
                        put("display", display)
                    }
                )
            }
            put("text", display)
        }
    }

    private fun JsonObjectBuilder.putQuantity(value: Double, unit: String?) {
        put("value", value)
        unit?.takeIf { it.isNotBlank() }?.let {
            put("unit", it)
            put("system", UCUM_SYSTEM)
            put("code", it)
        }
    }

    // SNOMED CT concept ids from the US Core value sets. Referencing a handful of specific codes
    // is not the same as shipping SNOMED, which would need a licence.
    private fun pregnancyCoding(status: PregnancyStatus): Pair<String, String> = when (status) {
        PregnancyStatus.Pregnant -> "77386006" to "Pregnant"
        PregnancyStatus.NotPregnant -> "60001007" to "Not pregnant"
        PregnancyStatus.Unknown -> "261665006" to "Unknown"
    }

    private fun pregnancyFromCode(code: String?): PregnancyStatus = when (code) {
        "77386006" -> PregnancyStatus.Pregnant
        "60001007" -> PregnancyStatus.NotPregnant
        else -> PregnancyStatus.Unknown
    }

    private fun smokingCoding(status: SmokingStatus): Pair<String, String> = when (status) {
        SmokingStatus.Current -> "449868002" to "Current every day smoker"
        SmokingStatus.Former -> "8517006" to "Former smoker"
        SmokingStatus.Never -> "266919005" to "Never smoker"
        SmokingStatus.Unknown -> "266927001" to "Unknown if ever smoked"
    }

    private fun smokingFromCode(code: String?): SmokingStatus = when (code) {
        "449868002", "428041000124106", "77176002" -> SmokingStatus.Current
        "8517006" -> SmokingStatus.Former
        "266919005" -> SmokingStatus.Never
        else -> SmokingStatus.Unknown
    }

    private fun categoryToFhir(category: AllergyCategory): String = when (category) {
        AllergyCategory.Medication -> "medication"
        AllergyCategory.Food -> "food"
        AllergyCategory.Environment -> "environment"
        AllergyCategory.Biologic -> "biologic"
    }

    private fun categoryFromFhir(value: String?): AllergyCategory = when (value) {
        "food" -> AllergyCategory.Food
        "environment" -> AllergyCategory.Environment
        "biologic" -> AllergyCategory.Biologic
        else -> AllergyCategory.Medication
    }

    private fun criticalityToFhir(criticality: AllergyCriticality): String? = when (criticality) {
        AllergyCriticality.Low -> "low"
        AllergyCriticality.High -> "high"
        AllergyCriticality.Unknown -> null
    }

    private fun criticalityFromFhir(value: String?): AllergyCriticality = when (value) {
        "low" -> AllergyCriticality.Low
        "high" -> AllergyCriticality.High
        else -> AllergyCriticality.Unknown
    }

    private fun statusToFhir(status: ConditionStatus): String = when (status) {
        ConditionStatus.Active -> "active"
        ConditionStatus.Recurrence -> "recurrence"
        ConditionStatus.Remission -> "remission"
        ConditionStatus.Resolved -> "resolved"
    }

    private fun conditionStatusFromFhir(value: String?): ConditionStatus = when (value) {
        "recurrence", "relapse" -> ConditionStatus.Recurrence
        "remission" -> ConditionStatus.Remission
        "resolved", "inactive" -> ConditionStatus.Resolved
        else -> ConditionStatus.Active
    }

    /** The code carried in a `clinicalStatus`-shaped CodeableConcept. */
    private fun JsonObject?.clinicalStatusCode(): String? =
        this.anyCoding()?.string("code") ?: this.string("text")

    private fun parseObject(data: String): JsonObject? = try {
        json.parseToJsonElement(data) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject?.codingWithSystem(system: String): JsonObject? =
        (this?.get("coding") as? JsonArray)
            ?.firstOrNull { (it as? JsonObject).string("system") == system } as? JsonObject

    private fun JsonObject?.anyCoding(): JsonObject? =
        (this?.get("coding") as? JsonArray)?.firstOrNull() as? JsonObject

    private fun JsonObject?.conceptText(): String? =
        this.string("text") ?: this.anyCoding()?.string("display")

    private fun JsonObject.firstNoteText(): String? =
        (this["note"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.string("text") }

    private fun statusToFhir(status: MedicationStatus): String = when (status) {
        MedicationStatus.Active -> "active"
        MedicationStatus.Completed -> "completed"
        MedicationStatus.Stopped -> "stopped"
    }

    private fun statusFromFhir(status: String?): MedicationStatus = when (status) {
        "completed" -> MedicationStatus.Completed
        "stopped", "not-taken", "entered-in-error" -> MedicationStatus.Stopped
        else -> MedicationStatus.Active
    }

    /** Splits "0.5 mL" into 0.5 and "mL". Null when the text does not start with a number. */
    internal fun parseQuantity(text: String): Pair<Double, String>? {
        val match = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*(.*)$""").find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].trim()
        return if (unit.isEmpty()) null else value to unit
    }

    private fun formatQuantity(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun formatDateTime(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    /**
     * FHIR `dateTime` allows YYYY, YYYY-MM and YYYY-MM-DD as well as a full timestamp, and a provider
     * feed uses all of them. Partial dates are anchored to the start of their first day in UTC, which
     * is the wrong hour but the right day, and the log only ever shows the day.
     */
    internal fun parseDateTime(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        runCatching { return Instant.parse(text) }
        runCatching { return java.time.OffsetDateTime.parse(text).toInstant() }
        runCatching { return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant() }
        runCatching {
            return java.time.YearMonth.parse(text).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
        runCatching {
            return java.time.Year.parse(text).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
        return null
    }
}
