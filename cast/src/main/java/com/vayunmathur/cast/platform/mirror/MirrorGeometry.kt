package com.vayunmathur.cast.platform.mirror

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.getSystemService
import com.vayunmathur.cast.protocol.CodecLimits
import com.vayunmathur.cast.protocol.CodecSelection
import com.vayunmathur.cast.protocol.DisplayMode
import com.vayunmathur.cast.protocol.StreamConstants
import com.vayunmathur.cast.protocol.VideoCodec

private const val TAG = "MirrorGeometry"

/** The frame size to encode, the density to mirror at, and the bitrate that size needs. */
data class CaptureGeometry(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val bitRate: Int,
)

/**
 * Decides what size frame to send, from the TV's own limits for the codec that was chosen.
 *
 * **This used to force 1920x1080 landscape, and getting to stop is the clearest single gain from
 * owning the receiver.** The old reasoning was sound for a Cast receiver and every part of it is now
 * obsolete:
 *
 *  - Chrome caps mirroring at 1920x1080, but that is openscreen's *mirroring policy*
 *    (`kMaxResolution` in `mirror_settings.cc`, and `"Currently mirroring only supports 1080P"` in
 *    `capture_recommendations.h`) rather than anything a decoder requires. Our receiver reports what
 *    it actually decodes, so the ceiling is the real one.
 *  - A Cast receiver answers `scaling: "sender"` and will not letterbox, so a portrait phone had to be
 *    scaled into a landscape frame here - spending most of the encoded pixels on black bars. Our
 *    receiver pads instead, so the phone sends its own shape and every pixel carries picture.
 *
 * What remains is arithmetic: keep the phone's aspect ratio, fit inside the TV's limits for that
 * codec, and round to something the encoder will accept.
 */
object MirrorGeometry {

    /**
     * Bits per pixel per second, **for H.264**, which is now a reference rather than a rate anything
     * is sent at.
     *
     * 0.1 bpp at 30 fps is the common screen-sharing figure, and **0.2 was tried and measured worse.**
     * At native resolution it asks for 24 Mbit/s, which this link answered with 8.5% packet loss - and
     * at ~100 packets per frame, 8.5% loss means almost no frame ever completes, so the picture froze
     * rather than sharpened.
     *
     * It is kept as the reference because it is the figure that was actually measured on this link, and
     * because it makes each codec's [efficiencyFactor] a statement about the codec rather than a magic
     * number. The efficiency is spent on **reliability, not sharpness**: the target comes down per
     * codec instead of staying flat, which is what buys back the packet-loss headroom.
     */
    private const val BITS_PER_PIXEL = 0.1

    /** openscreen's `kDefaultVideoMinBitRate`, as a floor. */
    private const val MIN_BITRATE = 300_000

    /** What an app gets if it asks for nonsense; 720p is a size every decoder takes. */
    private const val DEFAULT_CONTENT_WIDTH = 1280
    private const val DEFAULT_CONTENT_HEIGHT = 720

    /**
     * The density a 1080p television is composed at, and the short edge that figure belongs to.
     *
     * `DENSITY_TV` (213) is the AOSP constant for the case, and it is what a 1280x720 panel wants.
     * A 1080p screen is 1.5x that short edge, so the reference is scaled to match rather than
     * leaving every larger panel rendering at 720p element sizes.
     */
    private const val TV_REFERENCE_DENSITY = 320
    private const val TV_REFERENCE_SHORT_EDGE = 1080

    /**
     * The phone's real screen size in pixels.
     *
     * Exposed because codec selection needs it *before* a geometry exists: which codec is viable
     * depends on the frame, and the frame depends on the codec's envelope.
     */
    fun screenSize(context: Context): Pair<Int, Int> {
        val metrics = displayMetrics(context)
        return (metrics.widthPixels.takeIf { it > 0 } ?: 1080) to
            (metrics.heightPixels.takeIf { it > 0 } ?: 1920)
    }

    /**
     * The frame to send for the codec [chosen] settled on.
     *
     * The phone's real screen shape, scaled down only as far as the TV's limits for that codec and this
     * device's own encoder require.
     */
    fun forDisplay(context: Context, chosen: CodecSelection.Chosen): CaptureGeometry {
        val metrics = displayMetrics(context)
        val (screenWidth, screenHeight) = screenSize(context)

        val (fittedWidth, fittedHeight) = chosen.receiverLimits.fit(screenWidth, screenHeight)
        val frameRate = frameRateFor(chosen.receiverLimits)
        // 4:2:0 chroma subsampling cannot represent an odd width or height, and some encoders fail
        // outright rather than rounding for you. The frame rate goes in because it is the floor:
        // resolution is what gets given up to hold it.
        val (width, height) =
            EncoderSupport.clampToEncoder(chosen.codec, fittedWidth, fittedHeight, frameRate)

        val bitRate = bitRateFor(width, height, frameRate, chosen)

        Log.i(
            TAG,
            "sending ${width}x$height @ ${frameRate}fps at ${bitRate / 1_000_000.0} Mbit/s; " +
                "the screen is ${screenWidth}x$screenHeight and the TV will letterbox it" +
                chosen.rateReasoning(),
        )
        return CaptureGeometry(
            width = width,
            height = height,
            // The phone's own density, so text scales the way it does on the screen rather than being
            // rendered for a notional tablet.
            densityDpi = metrics.densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT,
            bitRate = bitRate,
        )
    }

    /**
     * The frame to send for an app that asked for [requestedWidth] x [requestedHeight].
     *
     * Same clamping as [forDisplay], different starting point: an SDK session's shape is the content's
     * own - a 16:9 video, not the phone's screen - and the app is told what it actually got, because an
     * app that laid out for the size it asked for would be stretched.
     *
     * No density: nothing renders a `VirtualDisplay` here, the client draws into the surface directly.
     */
    fun forContent(
        requestedWidth: Int,
        requestedHeight: Int,
        chosen: CodecSelection.Chosen,
    ): CaptureGeometry {
        val safeWidth = requestedWidth.takeIf { it > 0 } ?: DEFAULT_CONTENT_WIDTH
        val safeHeight = requestedHeight.takeIf { it > 0 } ?: DEFAULT_CONTENT_HEIGHT
        val (fittedWidth, fittedHeight) = chosen.receiverLimits.fit(safeWidth, safeHeight)
        val frameRate = frameRateFor(chosen.receiverLimits)
        val (width, height) =
            EncoderSupport.clampToEncoder(chosen.codec, fittedWidth, fittedHeight, frameRate)
        val bitRate = bitRateFor(width, height, frameRate, chosen)
        Log.i(
            TAG,
            "app content: asked for ${safeWidth}x$safeHeight, sending ${width}x$height " +
                "@ ${frameRate}fps at ${bitRate / 1_000_000.0} Mbit/s" + chosen.rateReasoning(),
        )
        return CaptureGeometry(
            width = width,
            height = height,
            densityDpi = DisplayMetrics.DENSITY_DEFAULT,
            bitRate = bitRate,
        )
    }

    /**
     * The frame to send for a desktop composed on the television.
     *
     * Same clamping as [forDisplay], different starting point and a different reason for it.
     * Mirroring sends the phone's own shape because the phone is what is being shown, and the TV
     * pads it. A desktop is not a picture of the phone: the system composes it for whatever size
     * the display was created at, so creating it at the phone's `1080x2400` portrait geometry
     * produced a portrait desktop letterboxed into a landscape panel, with the wallpaper and
     * taskbar laid out for a phone.
     *
     * Takes the panel's largest mode, since a desktop wants every pixel the screen has, then fits
     * it to the decoder exactly as [forDisplay] does - [modes] describes the screen and
     * `receiverLimits` describes the decoder, and a TV can easily have a 4K decoder behind a 1080p
     * panel or the reverse.
     *
     * Falls back to [forDisplay] when the receiver advertised no modes, which is what a
     * pre-version-8 television does. That is the old, wrong-but-working behaviour rather than a
     * failure: a desktop letterboxed into the wrong shape still casts.
     */
    fun forDesktop(
        context: Context,
        chosen: CodecSelection.Chosen,
        modes: List<DisplayMode>,
    ): CaptureGeometry {
        val largest = modes.maxByOrNull { it.area }
        if (largest == null) {
            Log.w(TAG, "the TV advertised no panel modes; composing the desktop for the phone")
            return forDisplay(context, chosen)
        }

        val (fittedWidth, fittedHeight) = chosen.receiverLimits.fit(largest.width, largest.height)
        val frameRate = frameRateFor(chosen.receiverLimits)
        val (width, height) =
            EncoderSupport.clampToEncoder(chosen.codec, fittedWidth, fittedHeight, frameRate)
        val bitRate = bitRateFor(width, height, frameRate, chosen)
        val density = desktopDensityFor(width, height)

        Log.i(
            TAG,
            "desktop: panel is ${largest.width}x${largest.height}, sending ${width}x$height " +
                "@ ${frameRate}fps at ${bitRate / 1_000_000.0} Mbit/s, ${density}dpi" +
                chosen.rateReasoning(),
        )
        return CaptureGeometry(
            width = width,
            height = height,
            densityDpi = density,
            bitRate = bitRate,
        )
    }

    /**
     * The density to compose a desktop at.
     *
     * Explicitly not the phone's. A phone's ~420dpi describes a screen held at arm's length; used
     * on a television it renders a desktop whose text and controls are sized for a hand, which on a
     * 55-inch panel across a room is enormous and fits almost nothing on screen.
     *
     * Scaled from the frame's own height against a 1080p reference at [TV_REFERENCE_DENSITY], so a
     * 4K panel gets proportionally more density rather than four times as many equally-tiny
     * elements - the same shape of arithmetic AOSP uses for external displays.
     */
    private fun desktopDensityFor(width: Int, height: Int): Int {
        val shortEdge = minOf(width, height).takeIf { it > 0 } ?: return TV_REFERENCE_DENSITY
        val scaled = TV_REFERENCE_DENSITY * shortEdge / TV_REFERENCE_SHORT_EDGE
        return scaled.coerceIn(DisplayMetrics.DENSITY_LOW, DisplayMetrics.DENSITY_XXHIGH)
    }

    /**
     * The TV's cap for the chosen codec, or ours, whichever is lower.
     *
     * A TV that caps out below 30 fps lowers the session rather than being refused - it genuinely
     * cannot go faster, and there is nothing to gain by declining to mirror to it. That is why the
     * floor in `CodecNegotiation.choose` is enforced against the *sender's* envelope only.
     */
    fun frameRateFor(limits: CodecLimits): Int = minOf(
        StreamConstants.VIDEO_MAX_FRAME_RATE,
        limits.maxFrameRate.takeIf { it > 0 } ?: StreamConstants.VIDEO_MAX_FRAME_RATE,
    )

    /**
     * How much of [BITS_PER_PIXEL] each codec actually needs for the same picture.
     *
     * The dial this whole change exists to turn, and it is per codec so it can be tuned without
     * touching selection. At 1344x2992@30 the H.264 reference asks for 12 Mbit/s; these bring that to
     * ~7.2 for H.265 and ~6 for AV1, which is headroom on a link that was losing 8.5% of its packets.
     *
     * Conservative rather than the ~0.5/~0.4 the codecs are usually credited with: the figures are for
     * camera content, and screen content with sharp text is where they hold up least well.
     */
    private fun efficiencyFactor(codec: VideoCodec): Double = when (codec) {
        VideoCodec.Hevc -> 0.6
        VideoCodec.Av1 -> 0.5
    }

    private fun bitRateFor(
        width: Int,
        height: Int,
        frameRate: Int,
        chosen: CodecSelection.Chosen,
    ): Int {
        val factor = efficiencyFactor(chosen.codec)
        var bitRate = (width.toLong() * height * frameRate * BITS_PER_PIXEL * factor)
            .toLong()
            .coerceAtLeast(MIN_BITRATE.toLong())
        // The tighter of the two ends' ceilings, worked out during selection. Clamping to the TV's
        // alone would let a phone be configured above what its own encoder said it would take.
        chosen.bitRateCeiling.takeIf { it > 0 }?.let { bitRate = minOf(bitRate, it.toLong()) }
        return bitRate.toInt()
    }

    /**
     * Why the rate is what it is, in the same line as the rate itself.
     *
     * All three parts are needed to diagnose an under-spend from one line: [bitRateFor] clamps to the
     * ceiling silently, so a decoder reporting a low `bitrateRange.upper` would make changing either
     * [BITS_PER_PIXEL] or [efficiencyFactor] do nothing at all - and there would be no way to tell that
     * apart from the encoder simply choosing not to spend its allowance.
     */
    private fun CodecSelection.Chosen.rateReasoning(): String =
        "; ${codec.label} at ${efficiencyFactor(codec)}x the H.264 reference" +
            if (bitRateCeiling <= 0) {
                ""
            } else {
                ", under a ${bitRateCeiling / 1_000_000.0} Mbit/s ceiling"
            }

    private fun displayMetrics(context: Context): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService<WindowManager>()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        return metrics
    }
}
