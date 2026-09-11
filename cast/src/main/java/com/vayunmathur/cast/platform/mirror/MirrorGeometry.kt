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
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val TAG = "MirrorGeometry"

/**
 * The frame size to encode, the density to compose at, the rate to send it at, and the bitrate
 * that combination needs.
 *
 * [frameRate] is per-geometry rather than per-session because a panel offers each resolution at
 * several rates - 3840x2160 at anything from 23.976 to 60 - and which of them this phone can
 * actually encode depends on the size. It is a `Float` because those are the panel's own numbers
 * and 59.94 is not 60.
 */
data class CaptureGeometry(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val bitRate: Int,
    val frameRate: Float,
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
     * The diagonal AOSP assumes for an external panel whose physical size it does not know, in
     * inches - `DisplayDensityConfiguration.DEFAULT_DISPLAY_SIZE`. A cast receiver reports modes,
     * not millimetres, so this branch of the platform's arithmetic is the one that applies.
     */
    private const val ASSUMED_PANEL_DIAGONAL_INCHES = 24.0

    /**
     * The physical touch target AOSP sizes an external display's density around (10.4 mm, here in
     * inches), what that target is worth in dp, and the floor it will not go below. Together they
     * are what turns an estimated pixel density into a logical one.
     */
    private const val TOUCH_TARGET_INCHES = 10.4 / 25.4
    private const val TOUCH_TARGET_DP = 48.0
    private const val MIN_EXTERNAL_DENSITY = 100

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
        val frameRate = frameRateFor(
            limits = chosen.receiverLimits,
            encoderFrameRate =
                EncoderSupport.sustainableFrameRate(chosen.codec, fittedWidth, fittedHeight),
        ) ?: StreamConstants.VIDEO_MIN_FRAME_RATE
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
            frameRate = frameRate,
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
        val frameRate = frameRateFor(
            limits = chosen.receiverLimits,
            encoderFrameRate =
                EncoderSupport.sustainableFrameRate(chosen.codec, fittedWidth, fittedHeight),
        ) ?: StreamConstants.VIDEO_MIN_FRAME_RATE
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
            frameRate = frameRate,
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
    ): CaptureGeometry = desktopModes(context, chosen, modes).first()

    /**
     * Every desktop mode this phone can actually send, largest and fastest first.
     *
     * One entry per *panel mode*, not per resolution: a television offers 3840x2160 at eight
     * different rates and 1280x720 at three, and each of those is a separate thing the user can
     * choose in Settings. The first entry is what a desktop is composed at by default.
     *
     * **A mode the encoder cannot hold is left out rather than offered slowly.** The rate is not
     * negotiable once the panel has been switched to match it, so offering 4K60 on a phone whose
     * H.265 encoder measures 54fps there would put a 60 Hz panel in front of a 54 fps stream and
     * call it a choice. Asking [EncoderSupport.sustainableFrameRate] first is what keeps the list
     * honest, and it is also why 4K tops out at 50 on this device.
     *
     * Falls back to the single [forDisplay] geometry when the receiver advertised no modes (a
     * pre-version-8 television) or when nothing it offered is encodable - the old,
     * wrong-but-working behaviour, and the one mode the picker then shows.
     */
    fun desktopModes(
        context: Context,
        chosen: CodecSelection.Chosen,
        modes: List<DisplayMode>,
    ): List<CaptureGeometry> {
        val geometries = LinkedHashMap<Triple<Int, Int, Float>, CaptureGeometry>()
        for (mode in modes) {
            val (fittedWidth, fittedHeight) = chosen.receiverLimits.fit(mode.width, mode.height)
            // Asked before the clamp, so the *rate* yields to the panel's resolution rather than
            // the other way round - a desktop exists to fill the screen it is on, and
            // clampToEncoder would give up pixels to hold a rate we are free to lower instead.
            val encoderRate =
                EncoderSupport.sustainableFrameRate(chosen.codec, fittedWidth, fittedHeight)
            val frameRate = frameRateFor(chosen.receiverLimits, mode.refreshRate, encoderRate)
                ?: continue
            val (width, height) =
                EncoderSupport.clampToEncoder(chosen.codec, fittedWidth, fittedHeight, frameRate)
            if (width <= 0 || height <= 0) continue
            geometries.putIfAbsent(
                Triple(width, height, frameRate),
                CaptureGeometry(
                    width = width,
                    height = height,
                    densityDpi = desktopDensityFor(width, height),
                    bitRate = bitRateFor(width, height, frameRate, chosen),
                    frameRate = frameRate,
                ),
            )
        }
        // Largest first, then fastest: the sender composes at the head and the picker lists from
        // the top.
        val ordered = geometries.values.sortedWith(
            compareByDescending<CaptureGeometry> { it.width.toLong() * it.height }
                .thenByDescending { it.frameRate },
        )
        if (ordered.isEmpty()) {
            Log.w(TAG, "the TV advertised no encodable panel modes; composing for the phone")
            return listOf(forDisplay(context, chosen))
        }
        Log.i(
            TAG,
            "desktop: offering " +
                ordered.joinToString {
                    "${it.width}x${it.height}@${it.frameRate} (${it.densityDpi}dpi)"
                } + chosen.rateReasoning(),
        )
        return ordered
    }

    /**
     * The density to compose a desktop at.
     *
     * Explicitly not the phone's. A phone's ~420dpi describes a screen held at arm's length; used
     * on a television it renders a desktop whose text and controls are sized for a hand.
     *
     * **Nor is it a fraction of the frame.** Scaling density with resolution - the shape
     * `LocalDisplayAdapter` used to use, and what this did - holds the *dp* workspace constant, so
     * every panel from 720p to 4K composed the same ~960dp desktop and a 4K television got four
     * times the pixels spent on the same handful of enormous elements.
     *
     * This is `DisplayDensityConfiguration.calculateBaseDensity`, restated: estimate the panel's
     * physical pixel density, then pick the logical density that makes a 10.4 mm touch target come
     * out at 48dp. A receiver reports modes rather than millimetres, so the unknown-physical-size
     * branch applies and the estimate comes from [ASSUMED_PANEL_DIAGONAL_INCHES].
     *
     * **Restated rather than approximated on purpose.** `VirtualDisplayAdapter` re-derives density
     * from the same function when the user picks a different resolution, so any difference between
     * the two would show up as the desktop resizing itself the first time the picker is touched.
     * The dp workspace comes out around 2450 wide at every resolution, which is the point: a
     * resolution change is a change in sharpness, not in layout.
     */
    private fun desktopDensityFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return MIN_EXTERNAL_DENSITY
        val pixelsPerInch =
            hypot(width.toDouble(), height.toDouble()) / ASSUMED_PANEL_DIAGONAL_INCHES
        val targetPixels = pixelsPerInch * TOUCH_TARGET_INCHES
        val density = targetPixels * DisplayMetrics.DENSITY_DEFAULT / TOUCH_TARGET_DP
        return density.roundToInt().coerceAtLeast(MIN_EXTERNAL_DENSITY)
    }

    /**
     * The rate to send a panel mode at, or null when this phone cannot hold it.
     *
     * **Null rather than a slower rate, and that is the whole change.** While the panel ran at
     * whatever it liked and we sent 30 into it, lowering the rate was the kind thing to do. Now
     * the receiver switches the screen to exactly the rate named here, so a mode this phone cannot
     * encode is not a mode to be delivered slowly - it is one that must not be offered, or the
     * user picks 4K60 and gets a 60 Hz panel fed 54 fps.
     *
     * Three ceilings apply: ours ([StreamConstants.VIDEO_MAX_FRAME_RATE]), the TV's decoder, and
     * this phone's encoder at this particular size ([encoderFrameRate], `0f` when unknown). The
     * panel's rate is returned unchanged when it clears all three.
     *
     * [panelRefreshRate] is `0f` for a caller not composing for a panel at all - mirroring, and a
     * pre-version-8 television - and then there is no mode to accept or reject and the lowest
     * ceiling is the answer.
     */
    fun frameRateFor(
        limits: CodecLimits,
        panelRefreshRate: Float = 0f,
        encoderFrameRate: Float = 0f,
    ): Float? {
        val ceiling = minOf(
            StreamConstants.VIDEO_MAX_FRAME_RATE,
            limits.maxFrameRate.takeIf { it > 0 }?.toFloat()
                ?: StreamConstants.VIDEO_MAX_FRAME_RATE,
            encoderFrameRate.takeIf { it > 0f } ?: StreamConstants.VIDEO_MAX_FRAME_RATE,
        )
        if (panelRefreshRate <= 0f) return ceiling.takeIf { it > 0f }
        // A shade of tolerance, because a measured encoder rate of 59.9 against a 59.94 panel mode
        // is the same answer and refusing it would drop the mode a television is most likely to be
        // running in.
        return panelRefreshRate.takeIf { it <= ceiling + RATE_TOLERANCE }
    }

    /** How far apart two frame rates may be and still be the same rate. */
    private const val RATE_TOLERANCE = 0.5f

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
        frameRate: Float,
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
