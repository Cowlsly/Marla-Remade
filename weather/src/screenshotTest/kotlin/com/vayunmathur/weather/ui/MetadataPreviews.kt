package com.vayunmathur.weather.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.weather.R
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.network.AirQualityCurrent
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.Hourly
import com.vayunmathur.weather.domain.DisplayUnits
import com.vayunmathur.weather.domain.LocationRow
import com.vayunmathur.weather.domain.LocationUiState
import com.vayunmathur.weather.domain.LocationsUiState
import com.vayunmathur.weather.domain.SelectedDateOrTime
import com.vayunmathur.weather.platform.WeatherActions

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** London's UTC offset on the sample date (BST). */
private const val UTC_OFFSET_SEC = 3600

/**
 * Wednesday 15 July 2026, 14:30 local — the epoch second that [NOW_ISO_HOUR] and every
 * timestamp below hang off. The hourly strip drops anything more than an hour old and the
 * sun block positions its marker from it, so pinning "now" is what stops the sample data
 * ageing out and keeps the two images identical from one run to the next.
 */
private const val NOW_EPOCH_SEC = 1_784_122_200L

/** The hour the strip labels "Now"; the first entry of [SAMPLE_HOURLY]. */
private const val NOW_ISO_HOUR = "2026-07-15T14:00"

/** The hour preview 2 inspects: 6 PM the same day, five cells into the strip. */
private const val SELECTED_ISO_HOUR = "2026-07-15T18:00"

/**
 * 24 hours from [NOW_ISO_HOUR] onwards. Open-Meteo returns local-time ISO strings with no
 * offset — [UTC_OFFSET_SEC] is what turns them back into instants.
 */
private val SAMPLE_HOURLY = Hourly(
    time = listOf(
        "2026-07-15T14:00", "2026-07-15T15:00", "2026-07-15T16:00", "2026-07-15T17:00",
        "2026-07-15T18:00", "2026-07-15T19:00", "2026-07-15T20:00", "2026-07-15T21:00",
        "2026-07-15T22:00", "2026-07-15T23:00", "2026-07-16T00:00", "2026-07-16T01:00",
        "2026-07-16T02:00", "2026-07-16T03:00", "2026-07-16T04:00", "2026-07-16T05:00",
        "2026-07-16T06:00", "2026-07-16T07:00", "2026-07-16T08:00", "2026-07-16T09:00",
        "2026-07-16T10:00", "2026-07-16T11:00", "2026-07-16T12:00", "2026-07-16T13:00",
    ),
    temperature = listOf(
        21.8, 22.1, 22.4, 22.0, 21.2, 20.0, 18.6, 17.4,
        16.5, 15.9, 15.4, 15.0, 14.6, 14.3, 14.0, 13.8,
        14.4, 15.7, 17.2, 18.8, 20.1, 21.2, 22.0, 22.5,
    ),
    apparentTemperature = listOf(
        21.1, 21.4, 21.7, 21.3, 20.5, 19.3, 17.9, 16.7,
        15.8, 15.2, 14.7, 14.3, 13.9, 13.6, 13.3, 13.1,
        13.7, 15.0, 16.5, 18.1, 19.4, 20.5, 21.3, 21.8,
    ),
    relativeHumidity = listOf(
        58, 56, 55, 57, 60, 65, 70, 74,
        78, 81, 83, 85, 86, 87, 88, 89,
        87, 82, 76, 70, 64, 60, 57, 55,
    ),
    dewPoint = listOf(
        13.4, 13.2, 13.3, 13.4, 13.5, 13.6, 13.4, 13.2,
        13.0, 12.9, 12.8, 12.7, 12.6, 12.5, 12.4, 12.3,
        12.5, 12.8, 13.1, 13.3, 13.5, 13.6, 13.5, 13.4,
    ),
    weatherCode = listOf(
        2, 2, 1, 1, 2, 3, 3, 2,
        2, 1, 1, 2, 3, 3, 45, 45,
        3, 2, 1, 1, 0, 0, 1, 2,
    ),
    precipitationProbability = listOf(
        5, 5, 0, 0, 5, 10, 15, 10,
        5, 5, 10, 15, 20, 25, 30, 25,
        15, 10, 5, 0, 0, 0, 5, 10,
    ),
    precipitation = listOf(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.1, 0.2, 0.3, 0.2,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
    ),
    windSpeed = listOf(
        16.4, 17.2, 17.8, 17.1, 15.9, 14.3, 12.8, 11.6,
        10.7, 10.1, 9.6, 9.2, 8.9, 8.6, 8.4, 8.7,
        9.5, 11.0, 12.9, 14.6, 16.1, 17.3, 18.0, 18.4,
    ),
    windDirection = listOf(
        236, 240, 244, 246, 244, 240, 234, 228,
        222, 218, 214, 210, 208, 206, 208, 214,
        222, 230, 236, 242, 246, 250, 252, 254,
    ),
    pressureMsl = listOf(
        1015.2, 1015.0, 1014.8, 1014.7, 1014.9, 1015.2, 1015.6, 1016.0,
        1016.3, 1016.5, 1016.7, 1016.8, 1016.8, 1016.7, 1016.5, 1016.3,
        1016.0, 1015.7, 1015.4, 1015.0, 1014.7, 1014.4, 1014.2, 1014.1,
    ),
    visibility = listOf(
        24140.0, 24140.0, 24140.0, 24140.0, 22000.0, 20000.0, 18000.0, 16000.0,
        14000.0, 12000.0, 11000.0, 10000.0, 8000.0, 6000.0, 2500.0, 1800.0,
        5000.0, 12000.0, 18000.0, 22000.0, 24140.0, 24140.0, 24140.0, 24140.0,
    ),
    cloudCover = listOf(
        34, 30, 22, 18, 28, 44, 58, 46,
        38, 26, 30, 42, 60, 72, 90, 94,
        78, 55, 32, 20, 10, 8, 16, 30,
    ),
    windGusts = listOf(
        27.8, 29.1, 30.2, 29.0, 27.0, 24.3, 21.7, 19.6,
        18.1, 17.1, 16.3, 15.6, 15.1, 14.6, 14.3, 14.8,
        16.1, 18.7, 21.9, 24.8, 27.3, 29.4, 30.6, 31.2,
    ),
    uvIndex = listOf(
        5.2, 4.3, 3.2, 2.1, 1.2, 0.5, 0.1, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.3, 0.9, 1.8, 2.9, 4.1, 5.2, 6.0, 6.3,
    ),
    // Sunset is 21:07 and sunrise 05:05, so 22:00 through 05:00 are the night hours.
    isDay = listOf(
        1, 1, 1, 1, 1, 1, 1, 1,
        0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 1, 1, 1, 1,
    ),
)

/** A week from the sample date, Wednesday through the following Tuesday. */
private val SAMPLE_DAILY = Daily(
    time = listOf(
        "2026-07-15", "2026-07-16", "2026-07-17", "2026-07-18",
        "2026-07-19", "2026-07-20", "2026-07-21",
    ),
    weatherCode = listOf(2, 45, 1, 0, 0, 61, 3),
    temperatureMax = listOf(22.6, 23.4, 24.8, 26.1, 24.9, 20.7, 19.8),
    temperatureMin = listOf(13.8, 14.5, 15.2, 16.0, 15.4, 14.1, 13.2),
    apparentTemperatureMax = listOf(21.9, 22.7, 24.1, 25.5, 24.2, 20.0, 19.0),
    apparentTemperatureMin = listOf(13.1, 13.8, 14.5, 15.3, 14.7, 13.4, 12.5),
    sunrise = listOf(
        "2026-07-15T05:05", "2026-07-16T05:06", "2026-07-17T05:08", "2026-07-18T05:09",
        "2026-07-19T05:10", "2026-07-20T05:12", "2026-07-21T05:13",
    ),
    sunset = listOf(
        "2026-07-15T21:07", "2026-07-16T21:06", "2026-07-17T21:05", "2026-07-18T21:03",
        "2026-07-19T21:02", "2026-07-20T21:01", "2026-07-21T20:59",
    ),
    daylightDuration = listOf(57720.0, 57600.0, 57420.0, 57240.0, 57120.0, 56940.0, 56760.0),
    sunshineDuration = listOf(43200.0, 25200.0, 47000.0, 50400.0, 48600.0, 19800.0, 34200.0),
    uvIndexMax = listOf(6.4, 5.7, 6.8, 7.2, 7.0, 4.4, 5.6),
    precipitationProbabilityMax = listOf(15, 30, 5, 0, 0, 70, 25),
    precipitationSum = listOf(0.0, 0.8, 0.0, 0.0, 0.0, 5.4, 0.4),
    // A real Open-Meteo lunation (waxing gibbous through full), relabelled onto
    // the sample week. The moon is a 2%-lit sliver on these actual dates, which
    // would render as an all-but-invisible disc in the store screenshot.
    moonPhase = listOf(0.318, 0.35, 0.381, 0.412, 0.443, 0.474, 0.504),
    moonrise = listOf(
        "2026-07-15T13:52", "2026-07-16T15:06", "2026-07-17T16:19", "2026-07-18T17:33",
        "2026-07-19T18:47", "2026-07-20T19:59", "2026-07-21T21:04",
    ),
    moonset = listOf(
        "2026-07-15T01:27", "2026-07-16T01:38", "2026-07-17T01:49", "2026-07-18T02:02",
        "2026-07-19T02:17", "2026-07-20T02:38", "2026-07-21T03:07",
    ),
)

private val SAMPLE_FORECAST = ForecastResponse(
    latitude = 51.5072,
    longitude = -0.1276,
    timezone = "Europe/London",
    timezoneAbbreviation = "BST",
    utcOffsetSeconds = UTC_OFFSET_SEC,
    current = Current(
        time = "2026-07-15T14:30",
        temperature = 21.9,
        apparentTemperature = 21.2,
        relativeHumidity = 58,
        dewPoint = 13.4,
        weatherCode = 2,
        windSpeed = 16.4,
        windDirection = 236,
        pressureMsl = 1015.2,
        visibility = 24140.0,
        cloudCover = 34,
        windGusts = 27.8,
        isDay = 1,
    ),
    hourly = SAMPLE_HOURLY,
    daily = SAMPLE_DAILY,
)

private val SAMPLE_AIR_QUALITY = AirQualityCurrent(
    time = NOW_ISO_HOUR,
    usAqi = 38,
    alderPollen = 0.0,
    birchPollen = 0.8,
    grassPollen = 12.4,
    mugwortPollen = 0.3,
    olivePollen = 0.0,
    ragweedPollen = 0.4,
)

private val LONDON = SavedLocation(
    id = 1,
    name = "London",
    country = "United Kingdom",
    latitude = 51.5072,
    longitude = -0.1276,
    displayOrder = 0,
)

/**
 * Store listing images for `:weather`, rendered from Compose previews instead of from an
 * instrumented test on a device.
 *
 * `./gradlew :weather:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/weather/`, where `release.sh` picks them up.
 *
 * Four things to keep in mind when editing:
 *
 *  - Order matters, and it comes from the function names. The generated PNG filenames embed
 *    the function name, so `Preview1Forecast`/`Preview2Hour`/... sort into listing order.
 *    Renumber the functions if you reorder the listing.
 *  - Everything must be a literal. Open-Meteo, the Room cache and the device's regional
 *    preferences do not exist here, so the state above is the whole input — which is also
 *    what makes the output reproducible from a clean checkout. Timestamps hang off
 *    [NOW_EPOCH_SEC] rather than the clock for the same reason.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in Studio
 *    but is not collected as a screenshot test, and the build fails with the unhelpful "did
 *    not discover any tests".
 *  - The previews must be members of a class, not top-level functions. Top-level previews
 *    land in a synthetic `…Kt` facade that the screenshot engine silently skips.
 *
 * The map page is deliberately absent: it draws through a native surface that Layoutlib
 * cannot render.
 *
 * Rendering goes through the app's real [DynamicTheme] with `darkTheme = true`, matching the
 * `cmd uimode night yes` the old on-device generator used. Material You sources its palette
 * from the device wallpaper, which does not exist here, so these render with the fallback
 * scheme rather than a user's actual accent colour.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-forecast", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Forecast() {
        DynamicTheme(darkTheme = true) {
            HomeScreen(
                state = LocationUiState(
                    location = LONDON,
                    forecast = SAMPLE_FORECAST,
                    airQuality = SAMPLE_AIR_QUALITY,
                ),
                units = DisplayUnits(),
                actions = WeatherActions.Noop,
                // What precipitationNowcast() would return for this data. Read from the
                // string table rather than copied, so it stays correct when the copy is
                // edited or the listing is generated for a translated locale.
                precipitationNowcast = stringResource(R.string.precip_no_rain_next_2h),
                nowEpochSec = NOW_EPOCH_SEC,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-selected-hour", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Hour() {
        DynamicTheme(darkTheme = true) {
            HomeScreen(
                state = LocationUiState(
                    location = LONDON,
                    forecast = SAMPLE_FORECAST,
                    airQuality = SAMPLE_AIR_QUALITY,
                    selected = SelectedDateOrTime.Time(SELECTED_ISO_HOUR),
                ),
                units = DisplayUnits(),
                actions = WeatherActions.Noop,
                // Inspecting a specific hour replaces the nowcast with that hour's figures.
                nowEpochSec = NOW_EPOCH_SEC,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-locations", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Locations() {
        DynamicTheme(darkTheme = true) {
            LocationsScreen(
                state = LocationsUiState(
                    rows = listOf(
                        LocationRow(
                            location = LONDON,
                            description = "Last updated just now",
                            weatherCode = 2,
                        ),
                        LocationRow(
                            location = SavedLocation(
                                id = 2,
                                name = "Tokyo",
                                country = "Japan",
                                latitude = 35.6762,
                                longitude = 139.6503,
                                displayOrder = 1,
                            ),
                            description = "Last updated 12m ago",
                            weatherCode = 61,
                            isDay = false,
                        ),
                        LocationRow(
                            location = SavedLocation(
                                id = 3,
                                name = "Reykjavík",
                                country = "Iceland",
                                latitude = 64.1466,
                                longitude = -21.9426,
                                displayOrder = 2,
                            ),
                            description = "Last updated 1h ago",
                            weatherCode = 71,
                        ),
                    ),
                    activeLocationId = LONDON.id,
                ),
                actions = WeatherActions.Noop,
            )
        }
    }
}
