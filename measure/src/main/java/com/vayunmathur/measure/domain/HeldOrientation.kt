package com.vayunmathur.measure.domain

/** Which device edge is pointing at the ground, derived purely from gravity. */
enum class HeldOrientation(val label: String) {
    Portrait("Portrait"),
    PortraitUpsideDown("Portrait, upside down"),
    LandscapeLeft("Landscape, left edge down"),
    LandscapeRight("Landscape, right edge down"),
    Flat("Flat"),
}
