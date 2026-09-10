package com.vayunmathur.cast.platform.remotedisplay

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.util.Log
import android.view.Display
import android.view.Surface
import com.vayunmathur.cast.platform.mirror.CaptureGeometry

private const val TAG = "CastSystemDisplay"

/**
 * `VIRTUAL_DISPLAY_FLAG_PUBLIC`. No permission of its own; it is what keeps the display off
 * `FLAG_PRIVATE` and is a precondition of [FLAG_ALLOWS_CONTENT_MODE_SWITCH].
 */
private const val FLAG_PUBLIC = 1 shl 0

/**
 * `VIRTUAL_DISPLAY_FLAG_PRESENTATION`. Required, not cosmetic: `DisplayManager` filters the
 * presentation display category on `Display.FLAG_PRESENTATION`, so without this the id published
 * through `RemoteDisplay.setPresentationDisplayId` names a display `MediaRouter` will not accept.
 */
private const val FLAG_PRESENTATION = 1 shl 1

/**
 * `VIRTUAL_DISPLAY_FLAG_TRUSTED`. Gated on `ADD_TRUSTED_DISPLAY`, which this app holds only by
 * being platform-signed. Lets the system place activities on the display, and is a precondition of
 * [FLAG_ALLOWS_CONTENT_MODE_SWITCH].
 */
private const val FLAG_TRUSTED = 1 shl 10

/**
 * `VIRTUAL_DISPLAY_FLAG_ALLOWS_CONTENT_MODE_SWITCH`. The whole point of this class: it lets the
 * display switch between mirroring and extending at runtime, driven by
 * `Settings.Secure.MIRROR_BUILT_IN_DISPLAY`, for which Settings already draws a switch on the
 * Connected Displays page. That is why this app offers no mirror-or-extend chooser of its own.
 */
private const val FLAG_ALLOWS_CONTENT_MODE_SWITCH = 1 shl 17

/**
 * `VIRTUAL_DISPLAY_FLAG_CONNECTION_PENDING`. Creates the display connected but not enabled, so it
 * arrives as a pending connection and SystemUI raises its mirror-or-desktop sheet before anything
 * is shown. Without it the display is enabled the instant it exists, which is what kept the cast
 * display out of `DisplayRepository.pendingDisplay` - that set is
 * `connected - enabled - ignored`, and a display born enabled is subtracted straight back out.
 *
 * MAOS-only, and gated on `virtual_displays_support_desktop_mode`. A build without the framework
 * change ignores an unknown flag bit, which leaves the old behaviour rather than a failure.
 */
private const val FLAG_CONNECTION_PENDING = 1 shl 18

/**
 * These are `@SystemApi` or public in `DisplayManager`, but three of them - `TRUSTED`,
 * `ALLOWS_CONTENT_MODE_SWITCH` and `CONNECTION_PENDING` - are not in the public SDK this module
 * compiles against, so they are spelled as literals. Bit positions verified against
 * `DisplayManager.java` (`PUBLIC` 1<<0, `PRESENTATION` 1<<1, `TRUSTED` 1<<10,
 * `ALLOWS_CONTENT_MODE_SWITCH` 1<<17, `CONNECTION_PENDING` 1<<18).
 *
 * `AUTO_MIRROR` and `OWN_CONTENT_ONLY` are deliberately absent and must stay absent: either one
 * causes the framework to silently drop `ALLOWS_CONTENT_MODE_SWITCH`, leaving an extend-only
 * display and a log line rather than an error.
 */
private const val CAST_DISPLAY_FLAGS =
    FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_TRUSTED or FLAG_ALLOWS_CONTENT_MODE_SWITCH or
        FLAG_CONNECTION_PENDING

/** What the display is called, in Settings and in `dumpsys display`. */
private const val DISPLAY_NAME = "cast"
/**
 * Nominal refresh rate for a declared mode. `VirtualDisplayAdapter` rebuilds each declared mode at
 * the display's own refresh rate and reads only its width and height, so this value is not shown.
 */
private const val DESKTOP_MODE_REFRESH_RATE = 60f

/**
 * Namespaces the unique id so it cannot collide with another adapter's.
 *
 * The framework builds its own default from the owner package, uid and a per-session counter -
 * `virtual:com.vayunmathur.cast,10113,cast,5` - and it is that trailing counter which made every
 * reconnect look like a different television.
 */
private const val UNIQUE_ID_PREFIX = "virtual:com.vayunmathur.cast:tv:"

/**
 * The cast display, created by this app and handed to the framework.
 *
 * A remote display route does not get a display for free. `MediaRouterService` never creates one -
 * `computePresentationDisplayId` only reads back the id the provider published and canonicalises
 * negatives, and its comment requires that id to name a display which already exists. So the
 * provider makes the display and tells the framework about it, which is what
 * [com.android.media.remotedisplay.RemoteDisplay.setPresentationDisplayId] is for.
 *
 * This replaces the `MediaProjection` path for routes selected in Settings. There is no consent
 * Activity because there is no screen capture: the framework composes the display's own content
 * into [Surface], and whether that content is a mirror of the phone or a separate desktop is the
 * system's decision, not ours.
 */
class CastSystemDisplay(context: Context) {

    private val displays =
        context.applicationContext.getSystemService(DisplayManager::class.java)

    private var display: VirtualDisplay? = null

    /**
     * The framework display id, for publishing to the route. `-1` when there is no display, which
     * is the same "none" value `RemoteDisplayInfo.presentationDisplayId` defaults to.
     */
    val displayId: Int
        get() = display?.display?.displayId ?: -1

    /**
     * Returns false if the platform refused the display, which leaves nothing to encode.
     *
     * The likely refusal is a `SecurityException` for `ADD_TRUSTED_DISPLAY` on a build where the
     * app has not been granted it, so it is logged loudly rather than folded into a generic
     * failure - it is a packaging mistake, not a runtime condition.
     *
     * `WrongConstant` is suppressed because [CAST_DISPLAY_FLAGS] is deliberately built from
     * literals: two of the four flags are `@SystemApi` and so absent from the public SDK this
     * module compiles against, which is exactly what lint is objecting to. The bit positions are
     * verified against `DisplayManager.java` and restated on each constant above.
     */
    @SuppressLint("WrongConstant")
    fun start(
        surface: Surface,
        geometry: CaptureGeometry,
        receiverId: String?,
        supportedModes: List<CaptureGeometry> = emptyList(),
    ): Boolean {
        if (displays == null) {
            Log.w(TAG, "no DisplayManager")
            return false
        }
        return try {
            val builder = VirtualDisplayConfig.Builder(
                DISPLAY_NAME,
                geometry.width,
                geometry.height,
                geometry.densityDpi,
            )
                .setFlags(CAST_DISPLAY_FLAGS)
                .setSurface(surface)
            builder.applySupportedModes(supportedModes)
            receiverId?.let { builder.applyUniqueId("$UNIQUE_ID_PREFIX$it") }
            display = displays.createVirtualDisplay(builder.build())
            if (display == null) Log.w(TAG, "the platform returned no display")
            display != null
        } catch (e: SecurityException) {
            Log.e(
                TAG,
                "refused a trusted virtual display - ADD_TRUSTED_DISPLAY was not granted. It is " +
                    "signature|role, so privileged placement alone never grants it; check that " +
                    "the app holds the role that carries it",
                e,
            )
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "could not create the cast display", e)
            false
        }
    }

    /**
     * `VirtualDisplayConfig.Builder.setUniqueId`, which is `@SystemApi` and so not on the public
     * SDK this module compiles against - hence reflection rather than a direct call.
     *
     * Best effort. Without it the framework assigns its own per-session id and nothing about this
     * television is remembered between connects, which is a worse experience rather than a broken
     * one. It is also `@FlaggedApi(FLAG_VIRTUAL_DISPLAYS_SUPPORT_DESKTOP_MODE)`, so a build with
     * that flag off will not honour it even where the method exists.
     */
    private fun VirtualDisplayConfig.Builder.applyUniqueId(uniqueId: String) {
        try {
            VirtualDisplayConfig.Builder::class.java
                .getMethod("setUniqueId", String::class.java)
                .invoke(this, uniqueId)
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "setUniqueId unavailable; display preferences will not persist", e)
        }
    }

    /**
     * `VirtualDisplayConfig.Builder.setSupportedModes`, which is `@SystemApi` and
     * `@FlaggedApi(FLAG_VIRTUAL_DISPLAYS_SUPPORT_DESKTOP_MODE)` - not on the public SDK this module
     * compiles against - hence reflection. Each [CaptureGeometry] becomes a `Display.Mode`; the
     * framework re-creates each at the display's own refresh rate, reading only width and height,
     * so Settings' resolution picker ends up offering exactly these sizes.
     *
     * Best effort, and never fatal to the display: on a build without the framework change the
     * method is absent and the picker simply shows the single mode the display was created at,
     * which is the pre-desktop behaviour.
     */
    private fun VirtualDisplayConfig.Builder.applySupportedModes(modes: List<CaptureGeometry>) {
        if (modes.isEmpty()) return
        try {
            val displayModes = modes.mapNotNull { buildDisplayMode(it.width, it.height) }
            if (displayModes.isEmpty()) return
            VirtualDisplayConfig.Builder::class.java
                .getMethod("setSupportedModes", List::class.java)
                .invoke(this, displayModes)
        } catch (e: Throwable) {
            Log.w(TAG, "setSupportedModes unavailable; the resolution picker will show one mode", e)
        }
    }

    /**
     * A `Display.Mode` for [width] x [height]. Its constructor is not on the public SDK, so it is
     * reached reflectively; the refresh rate is nominal (see [DESKTOP_MODE_REFRESH_RATE]).
     */
    private fun buildDisplayMode(width: Int, height: Int): Display.Mode? =
        try {
            Display.Mode::class.java
                .getDeclaredConstructor(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                )
                .apply { isAccessible = true }
                .newInstance(width, height, DESKTOP_MODE_REFRESH_RATE)
        } catch (e: Throwable) {
            Log.w(TAG, "Display.Mode(int, int, float) unavailable; cannot declare ${width}x$height", e)
            null
        }

    fun release() {
        runCatching { display?.release() }
        display = null
    }
}
