package com.vayunmathur.maps.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point (P12).
 *
 * Declared in the manifest with the `androidx.car.app.CarAppService` action and
 * the `androidx.car.app.category.NAVIGATION` category, so the Android Auto host
 * treats this as a turn-by-turn navigation app (map surface + NavigationTemplate).
 * All routing, the navigation session/progress, and voice guidance are the
 * app's existing phone stack — see [MapsSession].
 */
class MapsCarAppService : CarAppService() {

    // Dev posture: accept any host. Before shipping this must be tightened to a
    // real allow-list (Android Auto / Automotive OS signatures) via
    // HostValidator.Builder + the car-app allowlist — that is part of the
    // on-device (DHU/car) verification follow-up, not compile-time.
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = MapsSession()
}
