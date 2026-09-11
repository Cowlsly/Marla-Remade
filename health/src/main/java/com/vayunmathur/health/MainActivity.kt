package com.vayunmathur.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.ui.IconBodySystem
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconFire
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.ReferenceCatalog
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.platform.MedicalViewModelFactory
import com.vayunmathur.health.platform.PersonalHealthRecords
import com.vayunmathur.health.ui.AboutYouPage
import com.vayunmathur.health.ui.AddAllergyPage
import com.vayunmathur.health.ui.AddConditionPage
import com.vayunmathur.health.ui.AddLabResultPage
import com.vayunmathur.health.ui.AddMedicationPage
import com.vayunmathur.health.ui.AddVaccinationPage
import com.vayunmathur.health.ui.AllergiesPage
import com.vayunmathur.health.ui.BarChartDetails
import com.vayunmathur.health.ui.BodyPage
import com.vayunmathur.health.ui.CatalogPickerPage
import com.vayunmathur.health.ui.ConditionsPage
import com.vayunmathur.health.ui.ExerciseDetailsPage
import com.vayunmathur.health.ui.HealthMetricConfig
import com.vayunmathur.health.ui.LabResultsPage
import com.vayunmathur.health.ui.MedicationPage
import com.vayunmathur.health.ui.RecordsPage
import com.vayunmathur.health.ui.VaccinationsPage

import com.vayunmathur.health.ui.NutritionDetailsPage
import com.vayunmathur.health.ui.NutritionPage
import com.vayunmathur.health.ui.RecipeEditorPage
import com.vayunmathur.health.ui.RecipeManagementPage
import com.vayunmathur.health.ui.TodayPage
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthSyncWorker
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.health.util.HealthViewModelFactory
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconMedication
import com.vayunmathur.library.ui.PermissionWall
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.ui.dialog.TimePickerDialogContent
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

val CLASSES = setOf(
    // Activity & Energy
    StepsRecord::class, WheelchairPushesRecord::class, DistanceRecord::class, TotalCaloriesBurnedRecord::class,
    ActiveCaloriesBurnedRecord::class, BasalMetabolicRateRecord::class, FloorsClimbedRecord::class, ElevationGainedRecord::class,

    // Vitals & Clinical
    HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class, RespiratoryRateRecord::class,
    OxygenSaturationRecord::class, BloodPressureRecord::class, BloodGlucoseRecord::class, Vo2MaxRecord::class, SkinTemperatureRecord::class,

    // Body Composition
    WeightRecord::class, HeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class, BoneMassRecord::class, BodyWaterMassRecord::class,

    // Exercise
    ExerciseSessionRecord::class,

    // Lifestyle & Nutrition
    MindfulnessSessionRecord::class, HydrationRecord::class, NutritionRecord::class, SleepSessionRecord::class
)

val PERMISSIONS = CLASSES.map { HealthPermission.getReadPermission(it) }.toSet() + 
    setOf(
        HealthPermission.getWritePermission(NutritionRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(HeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    )


class MainActivity : ComponentActivity() {
    private val healthViewModel: HealthViewModel by viewModels {
        HealthViewModelFactory(application, HealthRepository.get(this))
    }
    private val medicalViewModel: MedicalViewModel by viewModels {
        MedicalViewModelFactory(application, HealthRepository.get(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val healthConnectClient = HealthConnectClient.getOrCreate(this)
        val repository = HealthRepository.get(this)
        HealthAPI.init(healthConnectClient, this, repository)
        FoodDatabase.init(this)
        ReferenceCatalog.init(this)
        // Unpacking is half a second of decompression and disk write. Do it now, in the background,
        // rather than the first time the user opens a picker and waits for it.
        ReferenceCatalog.warmUp(lifecycleScope)
        PersonalHealthRecords.init(this, healthConnectClient)
        setContent {
            DynamicTheme {
                var hasPermissions by remember { mutableStateOf(false) }

                val requestPermissions = rememberLauncherForActivityResult(
                    contract = PermissionController.createRequestPermissionResultContract(),
                    onResult = { granted ->
                        hasPermissions = granted.containsAll(PERMISSIONS)
                    }
                )

                val requestMedicalPermissions = rememberLauncherForActivityResult(
                    contract = PermissionController.createRequestPermissionResultContract(),
                    onResult = { /* Refusal is fine — the medical screens fall back to local data. */ }
                )

                LaunchedEffect(Unit) {
                    hasPermissions = healthConnectClient.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
                }

                if (hasPermissions) {
                    LaunchedEffect(Unit) {
                        HealthSyncWorker.enqueue(this@MainActivity)
                    }
                    // FHIR record access is requested separately and is allowed to fail. It only
                    // exists on Android 15 with an updated Health Connect module, so folding it
                    // into PERMISSIONS above would gate the whole app on something most supported
                    // devices cannot grant; the medical screens work from Room without it.
                    LaunchedEffect(Unit) {
                        if (PersonalHealthRecords.isAvailable() &&
                            !healthConnectClient.permissionController.getGrantedPermissions()
                                .containsAll(PersonalHealthRecords.PERMISSIONS)
                        ) {
                            requestMedicalPermissions.launch(PersonalHealthRecords.PERMISSIONS)
                        }
                    }
                    Navigation(healthViewModel, medicalViewModel)
                } else {
                    // Health Connect has its own permission contract, which is
                    // why this passes onRequest rather than using the runtime
                    // permission helper.
                    //
                    // The Surface is load-bearing: PermissionWall draws no background of its
                    // own, and this is the one screen here that is not inside a scaffold, so
                    // without it the window background shows through and the screen stays
                    // light in dark mode.
                    Surface(Modifier.fillMaxSize()) {
                        PermissionWall(
                            title = stringResource(R.string.grant_permissions),
                            actionLabel = stringResource(R.string.grant_permissions),
                            onRequest = { requestPermissions.launch(PERMISSIONS) },
                            rationale = stringResource(R.string.grant_permissions_rationale),
                            icon = { IconFavorite() },
                        )
                    }
                }
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Today: Route

    @Serializable
    data object Body: Route

    @Serializable
    data object NutritionDetails: Route

    @Serializable
    data object NutritionFullBreakdown: Route

    @Serializable
    data object RecipeManagement: Route

    @Serializable
    data class RecipeEditor(val recipeId: String? = null): Route

    @Serializable
    data class BarChartDetails(val healthMetric: HealthMetricConfig): Route

    @Serializable
    data object SleepDetails: Route

    @Serializable
    data object ExerciseDetails: Route

    @Serializable
    data object Records: Route

    @Serializable
    data object Vaccinations: Route

    @Serializable
    data object Allergies: Route

    @Serializable
    data object Conditions: Route

    @Serializable
    data object LabResults: Route

    @Serializable
    data object AboutYou: Route

    @Serializable
    data object Medication: Route

    /** The vaccination form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditVaccination(val id: String? = null): Route

    /** The medication form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditMedication(val id: String? = null): Route

    /** The allergy form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditAllergy(val id: String? = null): Route

    /** The condition form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditCondition(val id: String? = null): Route

    /** The lab result form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditLabResult(val id: String? = null): Route

    /**
     * The searchable reference-data picker. [ingredient] narrows
     * [com.vayunmathur.health.ui.CatalogKind.MedicationProduct] to one drug and is unused otherwise.
     *
     * Carries no result key: the picker writes straight into the ViewModel's draft, because this is
     * a full-screen destination and the form underneath is not composed while it is open.
     */
    @Serializable
    data class CatalogPicker(
        val kind: com.vayunmathur.health.ui.CatalogKind,
        val ingredient: String? = null,
    ): Route

    /**
     * The shared date picker, hosted here so the medical forms can reach it.
     *
     * [allowClear] adds a Clear button and changes the result type to `DateSelection`; set it for
     * any field that is allowed to have no date.
     */
    @Serializable
    data class MedicalDatePicker(
        val key: String,
        val initialDate: LocalDate,
        val allowClear: Boolean = false,
    ): Route

    /** The shared time picker, for dose reminder times. */
    @Serializable
    data class MedicalTimePicker(val key: String, val initialTime: LocalTime): Route
}

@Composable
fun Navigation(viewModel: HealthViewModel, medicalViewModel: MedicalViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Today)
    MainNavigation(
        backStack = backStack,
        bottomBar = {
            com.vayunmathur.library.util.BottomNavBar(
                backStack = backStack,
                pages = listOf(
                    com.vayunmathur.library.util.BottomBarItem(
                        stringResource(R.string.nav_today),
                        Route.Today,
                    ) { IconFavorite() },
                    com.vayunmathur.library.util.BottomBarItem(
                        stringResource(R.string.nav_nutrition),
                        Route.NutritionDetails,
                    ) { IconFire() },
                    com.vayunmathur.library.util.BottomBarItem(
                        stringResource(R.string.nav_body),
                        Route.Body,
                    ) { IconBodySystem() },
                    com.vayunmathur.library.util.BottomBarItem(
                        stringResource(R.string.nav_medication),
                        Route.Medication,
                    ) { IconMedication() },
                    com.vayunmathur.library.util.BottomBarItem(
                        stringResource(R.string.nav_records),
                        Route.Records,
                    ) { IconHistory() },
                ),
                currentPage = backStack.last()
            )
        }
    ) {
        entry<Route.Today>(metadata = SiblingPage()) {
            TodayPage(backStack, viewModel)
        }
        entry<Route.Body>(metadata = SiblingPage()) {
            BodyPage(backStack, viewModel)
        }
        entry<Route.NutritionDetails>(metadata = SiblingPage()) {
            NutritionPage(backStack, viewModel)
        }
        entry<Route.NutritionFullBreakdown> {
            NutritionDetailsPage(backStack, viewModel)
        }
        entry<Route.RecipeManagement> {
            RecipeManagementPage(backStack, viewModel)
        }
        entry<Route.RecipeEditor> {
            RecipeEditorPage(backStack, viewModel, it.recipeId)
        }
        entry<Route.BarChartDetails>(metadata = MorphPage()) {
            BarChartDetails(backStack, viewModel, it.healthMetric)
        }
        entry<Route.SleepDetails>(metadata = MorphPage()) {
            com.vayunmathur.health.ui.SleepDetailsPage(backStack, viewModel)
        }
        entry<Route.ExerciseDetails>(metadata = MorphPage()) {
            ExerciseDetailsPage(backStack, viewModel)
        }
        entry<Route.Records>(metadata = SiblingPage()) {
            RecordsPage(backStack, medicalViewModel)
        }
        entry<Route.Medication>(metadata = SiblingPage()) {
            MedicationPage(backStack, medicalViewModel)
        }
        entry<Route.Vaccinations> {
            VaccinationsPage(backStack, medicalViewModel)
        }
        entry<Route.Allergies> {
            AllergiesPage(backStack, medicalViewModel)
        }
        entry<Route.Conditions> {
            ConditionsPage(backStack, medicalViewModel)
        }
        entry<Route.LabResults> {
            LabResultsPage(backStack, medicalViewModel)
        }
        entry<Route.AboutYou> {
            AboutYouPage(backStack, medicalViewModel)
        }
        entry<Route.EditVaccination> {
            AddVaccinationPage(backStack, medicalViewModel)
        }
        entry<Route.EditMedication> {
            AddMedicationPage(backStack, medicalViewModel)
        }
        entry<Route.EditAllergy> {
            AddAllergyPage(backStack, medicalViewModel)
        }
        entry<Route.EditCondition> {
            AddConditionPage(backStack, medicalViewModel)
        }
        entry<Route.EditLabResult> {
            AddLabResultPage(backStack, medicalViewModel)
        }
        entry<Route.CatalogPicker> {
            CatalogPickerPage(backStack, medicalViewModel, it)
        }
        entry<Route.MedicalDatePicker>(metadata = DialogPage()) {
            DatePickerDialog(backStack, it.key, it.initialDate, allowClear = it.allowClear)
        }
        entry<Route.MedicalTimePicker>(metadata = DialogPage()) {
            TimePickerDialogContent(backStack, it.key, it.initialTime)
        }
    }
}
