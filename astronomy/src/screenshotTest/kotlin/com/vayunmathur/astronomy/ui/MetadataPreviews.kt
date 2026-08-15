package com.vayunmathur.astronomy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.data.model.DeepSkyObject
import com.vayunmathur.astronomy.data.model.Star
import com.vayunmathur.astronomy.domain.engine.AltAz
import com.vayunmathur.astronomy.domain.engine.RaDec
import com.vayunmathur.astronomy.platform.ConstellationLine
import com.vayunmathur.astronomy.platform.ConstellationMode
import com.vayunmathur.astronomy.platform.ObserverLocation
import com.vayunmathur.astronomy.platform.SearchActions
import com.vayunmathur.astronomy.platform.SearchResult
import com.vayunmathur.astronomy.platform.SettingsActions
import com.vayunmathur.astronomy.platform.SettingsUiState
import com.vayunmathur.astronomy.platform.SkyMapActions
import com.vayunmathur.astronomy.platform.SkyMapUiState
import com.vayunmathur.astronomy.platform.VisibleDeepSky
import com.vayunmathur.astronomy.platform.VisibleMoon
import com.vayunmathur.astronomy.platform.VisiblePlanet
import com.vayunmathur.astronomy.platform.VisibleSky
import com.vayunmathur.astronomy.platform.VisibleStar
import com.vayunmathur.astronomy.platform.VisibleSun
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:astronomy`, rendered from Compose previews instead of from an
 * instrumented test on a device. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview (@Preview alone renders in Studio but
 * is not collected as a screenshot test), previews must be members of a class rather than
 * top-level functions, and listing order comes from the function names — `Preview1…`,
 * `Preview2…`. All three failure modes surface as "did not discover any tests".
 *
 * The app normally derives everything on screen from the phone's clock, GPS and compass,
 * through the native Rust engine. None of that exists in Layoutlib and none of it would be
 * reproducible anyway, so the sky below is a fixed sample: real catalog coordinates for the
 * winter sky over San Francisco at 2026-02-14T04:30Z, with the alt/az already resolved (see
 * [SampleSky]). The only production concession is `projectAll`, which falls back to the
 * Kotlin projection when the `.so` is missing.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-sky-map", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1SkyMap() {
        DynamicTheme(darkTheme = true) {
            // The time scrubber receives its date-picker result through ResultEffect, which
            // reads LocalNavResultRegistry. Outside MainNavigation nothing provides it and
            // composition fails, so hand it a throwaway one — no preview sends a result.
            CompositionLocalProvider(LocalNavResultRegistry provides remember { NavResultRegistry() }) {
                SkyMapScreen(
                    backStack = rememberNavBackStack<Route>(Route.SkyMap),
                    state = SampleSky,
                    actions = SkyMapActions.Noop,
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "2-search", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Search() {
        DynamicTheme(darkTheme = true) {
            SearchScreen(
                backStack = rememberNavBackStack<Route>(Route.Search),
                actions = SampleSearch,
                initialQuery = "al",
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-settings", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Settings() {
        DynamicTheme(darkTheme = true) {
            SettingsScreen(
                backStack = rememberNavBackStack<Route>(Route.Settings),
                state = SettingsUiState(
                    constellationMode = ConstellationMode.LINES,
                    showGrid = true,
                    showDeepSky = true,
                    showPlanets = true,
                    showBelowHorizon = true,
                    nightMode = false,
                    magLimit = 6.0f,
                    fovDeg = 70f,
                    latDeg = 37.7749,
                    lonDeg = -122.4194,
                    starCount = 9096,
                    constellationCount = 88,
                    deepSkyCount = 110,
                ),
                actions = SettingsActions.Noop,
            )
        }
    }
}

// ---- Sample data -----------------------------------------------------------------

/**
 * Search hits for the "al" prefix: real stars, subtitled the way
 * `AstronomyViewModel.search` subtitles them. The list ignores the query because the
 * screen only ever asks once here.
 */
private val SampleSearch = object : SearchActions {
    override fun search(query: String): List<SearchResult> = listOf(
        SearchResult("STAR_21421", "Aldebaran", "Star mag 0.87", 1.20393 to 0.28814),
        SearchResult("STAR_54061", "Alioth", "Star mag 1.76", 3.37744 to 0.97600),
        SearchResult("STAR_62956", "Alkaid", "Star mag 1.85", 3.51014 to 0.86131),
        SearchResult("STAR_26311", "Alnilam", "Star mag 1.69", 1.46701 to -0.02098),
        SearchResult("STAR_26727", "Alnitak", "Star mag 1.74", 1.48684 to -0.03390),
        SearchResult("STAR_17702", "Alcyone", "Star mag 2.85", 0.99259 to 0.42071),
        SearchResult("STAR_31681", "Alhena", "Star mag 1.93", 1.73535 to 0.28622),
        SearchResult("STAR_9640", "Almach", "Star mag 2.10", 0.53948 to 0.74234),
        SearchResult("STAR_46390", "Alphard", "Star mag 1.99", 2.47686 to -0.15125),
        SearchResult("STAR_677", "Alpheratz", "Star mag 2.07", 0.03797 to 0.51124),
        SearchResult("STAR_14576", "Algol", "Star mag 2.09", 0.81704 to 0.71455),
        SearchResult("STAR_97649", "Altair", "Star mag 0.76", 5.19577 to 0.15478),
    )
}

private val SampleConstellations = listOf(
    ConstellationLine(
        "Ori", "Orion",
        listOf(
            listOf(27989, 25336), listOf(25336, 25930), listOf(25930, 26311),
            listOf(26311, 26727), listOf(26727, 27989), listOf(25930, 24436),
            listOf(26727, 27366), listOf(26207, 27989), listOf(26207, 25336),
            listOf(26311, 26241),
        )
    ),
    ConstellationLine(
        "CMa", "Canis Major",
        listOf(
            listOf(32349, 30324), listOf(32349, 33579), listOf(33579, 34444),
            listOf(34444, 35904), listOf(34444, 32349),
        )
    ),
    ConstellationLine("CMi", "Canis Minor", listOf(listOf(37279, 36188))),
    ConstellationLine(
        "Tau", "Taurus",
        listOf(
            listOf(25428, 20889), listOf(20889, 21421), listOf(21421, 20894),
            listOf(20894, 20205), listOf(21421, 26451),
        )
    ),
    ConstellationLine(
        "Gem", "Gemini",
        listOf(
            listOf(36850, 37826), listOf(36850, 32246), listOf(32246, 31681),
            listOf(37826, 35550), listOf(35550, 31681),
        )
    ),
    ConstellationLine(
        "Aur", "Auriga",
        listOf(
            listOf(24608, 28360), listOf(28360, 28380), listOf(28380, 25428),
            listOf(25428, 23015), listOf(23015, 23416), listOf(23416, 24608),
        )
    ),
)

/**
 * Named stars (real HIP ids, so the constellation segments above line up) followed by
 * anonymous field stars down to magnitude 6.3, which is what the app's default magnitude
 * limit shows.
 */
private val SampleStars = listOf(
    star(27989, 1.54973, 0.12928, 0.45, 1.85, "Betelgeuse", "Ori", 3.15877, 1.04072),
    star(24436, 1.37243, -0.14315, 0.18, -0.03, "Rigel", "Ori", 3.39445, 0.74974),
    star(25336, 1.41865, 0.11082, 1.64, -0.22, "Bellatrix", "Ori", 3.40414, 1.00779),
    star(25930, 1.44865, -0.00522, 2.25, -0.18, "Mintaka", "Ori", 3.31854, 0.89859),
    star(26311, 1.46701, -0.02098, 1.69, -0.18, "Alnilam", "Ori", 3.28637, 0.88529),
    star(26727, 1.48684, -0.03390, 1.74, -0.20, "Alnitak", "Ori", 3.25336, 0.87443),
    star(27366, 1.51737, -0.16877, 2.07, -0.17, "Saiph", "Ori", 3.19658, 0.74184),
    star(26207, 1.46232, 0.17338, 3.39, -0.16, "Meissa", "Ori", 3.34258, 1.07724),
    star(26241, 1.46361, -0.10315, 2.75, -0.21, "Hatysa", "Ori", 3.27773, 0.80325),
    star(32349, 1.76780, -0.29175, -1.44, 0.01, "Sirius", "CMa", 2.89815, 0.59960),
    star(33579, 1.82660, -0.50566, 1.50, -0.21, "Adhara", "CMa", 2.88946, 0.37910),
    star(34444, 1.86921, -0.46065, 1.83, 0.67, "Wezen", "CMa", 2.83786, 0.41351),
    star(30324, 1.66984, -0.31339, 1.98, -0.24, "Mirzam", "CMa", 3.01381, 0.59249),
    star(35904, 1.93773, -0.51144, 2.45, -0.08, "Aludra", "CMa", 2.79110, 0.34745),
    star(37279, 2.00408, 0.09119, 0.40, 0.43, "Procyon", "CMi", 2.41042, 0.87269),
    star(36188, 1.95107, 0.14468, 2.89, -0.09, "Gomeisa", "CMi", 2.43788, 0.94582),
    star(21421, 1.20393, 0.28814, 0.87, 1.54, "Aldebaran", "Tau", 3.93716, 1.08601),
    star(25428, 1.42371, 0.49929, 1.65, -0.13, "Elnath", "Tau", 3.79386, 1.37521),
    star(17702, 0.99259, 0.42071, 2.85, -0.09, "Alcyone", "Tau", 4.41656, 1.03370),
    star(20205, 1.13359, 0.27275, 3.65, 0.98, "Hyadum I", "Tau", 4.02708, 1.03240),
    star(20894, 1.17205, 0.27699, 3.40, 0.18, "Chamukuy", "Tau", 3.97450, 1.05875),
    star(20889, 1.17206, 0.33476, 3.53, 1.01, "Ain", "Tau", 4.05248, 1.10341),
    star(26451, 1.47326, 0.36901, 3.00, -0.19, "Tianguan", "Tau", 3.41406, 1.27130),
    star(37826, 2.03034, 0.48915, 1.16, 1.00, "Pollux", "Gem", 1.83894, 1.14164),
    star(36850, 1.98356, 0.55656, 1.58, 0.03, "Castor", "Gem", 1.73015, 1.20827),
    star(31681, 1.73535, 0.28622, 1.93, 0.00, "Alhena", "Gem", 2.69813, 1.16651),
    star(35550, 1.92040, 0.38366, 3.53, 0.37, "Wasat", "Gem", 2.19275, 1.15495),
    star(32246, 1.76249, 0.43862, 2.98, 1.40, "Mebsuta", "Gem", 2.41748, 1.29028),
    star(24608, 1.38182, 0.80282, 0.08, 0.80, "Capella", "Aur", 5.59886, 1.37644),
    star(28360, 1.56874, 0.78448, 1.90, 0.08, "Menkalinan", "Aur", 0.05806, 1.44538),
    star(28380, 1.56956, 0.64949, 2.62, -0.08, "Mahasim", "Aur", 2.40774, 1.55763),
    star(23015, 1.29583, 0.57886, 2.69, 1.51, "Hassaleh", "Aur", 4.43126, 1.34259),
    star(23416, 1.31759, 0.76486, 2.99, 0.54, "Almaaz", "Aur", 5.31386, 1.36049),
    star(90001, 4.09136, -0.31724, 4.42, 0.89, null, null, 2.49785, 0.43897),
    star(90002, 5.11403, -0.57472, 6.28, 0.53, null, null, 3.50032, 0.27798),
    star(90003, 4.56226, -0.12646, 4.60, -0.20, null, null, 2.94973, 0.77459),
    star(90004, 4.76459, 0.21980, 5.91, 1.05, null, null, 3.28882, 1.12754),
    star(90005, 4.44541, 0.85446, 4.79, 1.46, null, null, 0.67446, 1.30270),
    star(90006, 3.90522, 0.01471, 5.16, -0.12, null, null, 2.09995, 0.59747),
    star(90007, 5.49992, 0.85597, 6.04, 0.75, null, null, 5.30584, 0.96780),
    star(90008, 4.77621, 0.28026, 5.17, 0.38, null, null, 3.33747, 1.18586),
    star(90009, 3.76073, 0.38954, 5.17, -0.25, null, null, 1.61675, 0.72652),
    star(90010, 4.73202, 0.86037, 5.57, 1.00, null, null, 6.17939, 1.36841),
    star(90011, 5.04470, 0.46031, 5.19, -0.20, null, null, 4.21226, 1.21864),
    star(90012, 4.88283, 0.29950, 4.18, 1.09, null, null, 3.61101, 1.17683),
    star(90013, 3.60281, -0.19741, 4.22, 0.52, null, null, 2.02764, 0.23548),
    star(90014, 4.05962, 0.13917, 5.11, -0.06, null, null, 2.13774, 0.79324),
    star(90015, 4.82371, 0.58850, 5.33, 0.66, null, null, 4.13481, 1.44806),
    star(90016, 4.68570, -0.03266, 6.06, 0.73, null, null, 3.11906, 0.87872),
    star(90017, 5.76020, 0.38301, 5.18, 0.57, null, null, 4.73424, 0.62768),
    star(90018, 3.67982, -0.37175, 6.14, 1.50, null, null, 2.20632, 0.16348),
    star(90019, 4.74422, -0.55840, 5.66, 0.63, null, null, 3.18148, 0.35241),
    star(90020, 4.36425, 0.61861, 4.32, 0.93, null, null, 1.61841, 1.29866),
    star(90021, 5.79122, 0.48725, 6.20, 1.15, null, null, 4.86466, 0.65494),
    star(90022, 5.15439, 0.13623, 4.72, 1.04, null, null, 3.92332, 0.90570),
    star(90023, 4.17336, -0.04193, 5.62, 0.82, null, null, 2.41245, 0.71684),
    star(90024, 5.42341, 0.39048, 5.80, 1.33, null, null, 4.51289, 0.89637),
    star(90025, 4.98455, 0.55292, 4.20, 0.50, null, null, 4.36962, 1.31442),
    star(90026, 5.08990, 0.57128, 5.28, 1.46, null, null, 4.55954, 1.24142),
    star(90027, 4.42590, -0.27892, 4.34, 0.40, null, null, 2.82122, 0.59782),
    star(90028, 5.37311, -0.19259, 4.88, 0.78, null, null, 3.91922, 0.51139),
    star(90029, 4.62767, -0.21102, 6.03, 1.53, null, null, 3.04913, 0.69783),
    star(90030, 5.46629, -0.35371, 5.01, 0.30, null, null, 3.89908, 0.32795),
    star(90031, 5.79114, -0.02472, 4.66, 1.22, null, null, 4.38399, 0.35710),
    star(90032, 5.06184, -0.28474, 4.13, 0.49, null, null, 3.55615, 0.56738),
    star(90033, 3.66452, -0.14712, 4.14, 0.95, null, null, 2.03397, 0.31414),
    star(90034, 4.21048, 0.82381, 4.26, 0.08, null, null, 0.98355, 1.17687),
    star(90035, 5.51288, 0.41196, 4.25, 1.21, null, null, 4.60848, 0.83785),
    star(90036, 5.14124, 0.55999, 4.78, 1.30, null, null, 4.57654, 1.19702),
    star(90037, 3.64671, 0.49766, 4.39, 0.81, null, null, 1.42636, 0.68927),
    star(90038, 4.15728, 0.18737, 5.00, 1.21, null, null, 2.19833, 0.89318),
    star(90039, 3.49460, -0.35058, 4.58, 0.35, null, null, 2.06884, 0.05485),
    star(90040, 4.64147, 0.75416, 5.49, -0.08, null, null, 0.42045, 1.46601),
    star(90041, 4.58068, 0.25218, 5.27, 1.51, null, null, 2.85524, 1.15013),
    star(90042, 3.95726, -0.19113, 5.73, 0.63, null, null, 2.29984, 0.47263),
    star(90043, 4.59565, 0.52813, 4.37, 1.23, null, null, 2.52822, 1.41373),
    star(90044, 4.25944, 0.14383, 6.18, 0.35, null, null, 2.37127, 0.91957),
    star(90045, 5.86412, -0.11477, 4.87, 0.55, null, null, 4.36410, 0.24289),
    star(90046, 4.54773, 1.04127, 4.74, 0.94, null, null, 0.20095, 1.17660),
    star(90047, 4.42965, 0.40509, 4.35, 0.97, null, null, 2.32576, 1.22694),
    star(90048, 5.43507, -0.57951, 5.03, 0.85, null, null, 3.74564, 0.15581),
    star(90049, 3.82875, -0.26148, 4.76, 0.40, null, null, 2.24054, 0.33973),
    star(90050, 4.00606, -0.35359, 5.74, 0.19, null, null, 2.44367, 0.36601),
    star(90051, 4.52387, 0.01385, 3.94, 1.18, null, null, 2.85375, 0.90527),
    star(90052, 3.78086, 0.66971, 6.02, 0.96, null, null, 1.26118, 0.85727),
    star(90053, 4.27688, -0.37604, 4.61, 1.38, null, null, 2.70082, 0.46159),
    star(90054, 5.00030, -0.28518, 3.96, 1.17, null, null, 3.48894, 0.58505),
    star(90055, 4.27727, -0.59845, 5.86, 0.99, null, null, 2.78383, 0.25314),
    star(90056, 3.98585, 0.08986, 4.65, 0.71, null, null, 2.10965, 0.70733),
    star(90057, 4.48373, -0.27668, 4.31, 1.41, null, null, 2.88636, 0.61298),
    star(90058, 3.79972, 0.44623, 4.34, 0.84, null, null, 1.57393, 0.78579),
    star(90059, 5.05962, -0.28804, 4.47, -0.12, null, null, 3.55247, 0.56497),
    star(90060, 5.18181, -0.46878, 4.94, -0.24, null, null, 3.59821, 0.35560),
)

/**
 * The winter sky over San Francisco (37.7749N, 122.4194W) at 2026-02-14T04:30Z, looking
 * due south at 45° altitude — Orion on the meridian with Sirius below it, the Hyades and
 * Pleiades to the west, Gemini rising in the east.
 *
 * Coordinates are the real J2000 RA/Dec of each object; the alt/az beside them was
 * precomputed for that instant and location with the same transform the app uses, so the
 * image is identical on every machine and never touches the clock or the native engine.
 *
 * Declared after the lists it reads: top-level properties initialise in file order.
 */
private val SampleSky = SkyMapUiState(
    visibleSky = VisibleSky(
        stars = SampleStars,
        planets = listOf(
            planet("MARS", "Mars", 1.64934, 0.41888, -0.52, 0.912, 2.80360, 1.31818),
            planet("JUPITER", "Jupiter", 1.28282, 0.37525, -2.31, 4.523, 3.91630, 1.20034),
        ),
        // Deep midnight: the Sun is 32° below the horizon, so it projects off screen.
        sun = VisibleSun(AltAz(4.85161, -0.55291), RaDec(5.74650, -0.23038), 0.98781),
        moon = VisibleMoon(AltAz(4.33077, 0.91452), RaDec(0.92153, 0.31067), 0.35, 0.62, 9.4),
        deepSky = listOf(
            deepSky("M42", "Orion Nebula", 1.46297, -0.09407, 4.00, "nebula", 85.0, "Ori", 3.28006, 0.81220),
            deepSky("M45", "Pleiades", 0.99047, 0.42092, 1.60, "cluster", 110.0, "Tau", 4.41900, 1.03221),
            deepSky("M35", "Shoe-Buckle Cluster", 1.61050, 0.42470, 5.30, "cluster", 28.0, "Gem", 2.93991, 1.33204),
            deepSky("M41", "Little Beehive", 1.77151, -0.36216, 4.50, "cluster", 38.0, "CMa", 2.91042, 0.52987),
        ),
        lstRad = 1.558487,
        jd = 2461085.6875,
        observer = ObserverLocation(0.659296, -2.136622, 37.7749, -122.4194),
        time = Instant.parse("2026-02-14T04:30:00Z"),
        constellations = SampleConstellations,
    ),
    simTime = Instant.parse("2026-02-14T04:30:00Z"),
    timeZone = TimeZone.of("America/Los_Angeles"),
    // Not live: in "Now" mode the scrubber ticks itself to the present, which would both
    // read the clock and never settle.
    isLive = false,
    fovDeg = 70f,
    centerAzRad = 3.14159,
    centerAltRad = 0.78540,
    constellationMode = ConstellationMode.LINES,
    showGrid = true,
)

private fun star(
    id: Int,
    ra: Double,
    dec: Double,
    mag: Double,
    bv: Double,
    properName: String?,
    constellation: String?,
    az: Double,
    alt: Double,
) = VisibleStar(
    Star(id = id, ra = ra, dec = dec, mag = mag, bv = bv, properName = properName, constellation = constellation),
    AltAz(az, alt),
    RaDec(ra, dec),
)

private fun planet(
    id: String,
    name: String,
    ra: Double,
    dec: Double,
    mag: Double,
    distanceAu: Double,
    az: Double,
    alt: Double,
) = VisiblePlanet(id, name, AltAz(az, alt), RaDec(ra, dec), mag, distanceAu)

private fun deepSky(
    id: String,
    name: String,
    ra: Double,
    dec: Double,
    mag: Double,
    type: String,
    sizeArcmin: Double,
    constellation: String,
    az: Double,
    alt: Double,
) = VisibleDeepSky(
    DeepSkyObject(id, name, ra, dec, mag, type, sizeArcmin, constellation),
    AltAz(az, alt),
    RaDec(ra, dec),
)
