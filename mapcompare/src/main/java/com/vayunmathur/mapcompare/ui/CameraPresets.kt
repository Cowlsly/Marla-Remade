package com.vayunmathur.mapcompare.ui

data class CameraPreset(
    val label: String,
    val lon: Double,
    val lat: Double,
    val zoom: Double,
)

/**
 * Preset cameras for visual delta triage. Each at three zooms so the screenshot
 * sweep covers overview (z6), city (z10) and street (z14) without manual panning.
 */
val PRESETS: List<CameraPreset> = listOf(
    // Coastline/land: SF Bay — earth fill vs water check
    CameraPreset("SF Bay z6", -122.4194, 37.7749, 6.0),
    CameraPreset("SF Bay z10", -122.4194, 37.7749, 10.0),
    CameraPreset("SF Bay z14", -122.4194, 37.7749, 14.0),
    // Maritime boundary: Strait of Gibraltar
    CameraPreset("Gibraltar z6", -5.35, 36.0, 6.0),
    CameraPreset("Gibraltar z10", -5.35, 36.0, 10.0),
    CameraPreset("Gibraltar z14", -5.35, 36.0, 14.0),
    // Dense roads: Tokyo
    CameraPreset("Tokyo z6", 139.76, 35.68, 6.0),
    CameraPreset("Tokyo z10", 139.76, 35.68, 10.0),
    CameraPreset("Tokyo z14", 139.76, 35.68, 14.0),
    // Extra cities for roads delta
    CameraPreset("Paris z10", 2.35, 48.85, 10.0),
    CameraPreset("NYC z10", -74.006, 40.7128, 10.0),
    // NA-only iteration (local na.mamaps): NA bounds per mamaps_build, so these require a local file
    CameraPreset("Seattle z10", -122.3321, 47.6062, 10.0),
    CameraPreset("Great Lakes z6", -87.6, 44.5, 6.0),
    CameraPreset("NA NYC z14", -74.006, 40.7128, 14.0),
    // Issue #3: static road width / lane-offset at super-zoom (wide/divided highway, big intersection).
    // mamaps caps at z14 and v4.pmtiles at z15–16 — both over-zoom, which is exactly where the bug shows.
    CameraPreset("SF wide road z16", -122.4194, 37.7749, 16.0),
    CameraPreset("SF wide road z17", -122.4194, 37.7749, 17.0),
    CameraPreset("SF wide road z18", -122.4194, 37.7749, 18.0),
    CameraPreset("Tokyo wide road z16", 139.76, 35.68, 16.0),
    CameraPreset("Tokyo wide road z17", 139.76, 35.68, 17.0),
    CameraPreset("Tokyo wide road z18", 139.76, 35.68, 18.0),
)
