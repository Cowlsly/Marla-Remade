package com.vayunmathur.measure.domain

/** Which device edge is pointing at the ground, derived purely from gravity. */
enum class HeldOrientation {
    Portrait,
    PortraitUpsideDown,
    LandscapeLeft,
    LandscapeRight,
    Flat,
}
