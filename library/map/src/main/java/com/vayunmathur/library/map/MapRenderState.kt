package com.vayunmathur.library.map

/**
 * Whether this map surface is actually drawing.
 *
 * Exists because both failure paths used to be silent: a `Log.e` and a flat background
 * rectangle, with nothing the host could react to. The *state* lives here; the rendered
 * message does not. `:library:map` has no resources and deliberately no material3, so a
 * themed, translated message belongs in `:library:ui` — see `MapUnavailableMessage` there,
 * which takes plain parameters rather than a [MapRenderState] so `:library:ui` never has to
 * depend on this module.
 *
 * ## Not exhaustive
 *
 * This covers the two failures the surface can observe at creation time. A renderer that
 * creates successfully and then stops drawing — device lost, swapchain loss, a driver that
 * accepts every command and presents nothing — is still [Rendering] as far as this is
 * concerned, and still a black map. Detecting that would need a liveness signal out of the
 * native side, which does not exist.
 */
sealed interface MapRenderState {
    /** No surface yet, or one created and not yet reported. The normal first state. */
    data object Initialising : MapRenderState

    /** A native renderer exists and the frame loop is driving it. */
    data object Rendering : MapRenderState

    /** The renderer could not be brought up. [reason] says which of the two ways. */
    data class Unavailable(val reason: Reason) : MapRenderState

    /** Why the renderer is not up. Both correspond to a real failure site in the surface. */
    enum class Reason {
        /**
         * `libmap_renderer.so` did not load, so there is no renderer to create. Typically an
         * ABI the build did not produce — this module is arm64-only unless
         * `-PemulatorAbi=x86_64` was passed.
         */
        RendererLibraryMissing,

        /**
         * The library loaded but `MapNative.create` returned 0: Vulkan failed to initialise,
         * or the archive could not be opened. Details are in logcat under `MapRenderer`.
         */
        RendererStartFailed,
    }
}
