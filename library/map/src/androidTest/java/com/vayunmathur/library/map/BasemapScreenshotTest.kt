package com.vayunmathur.library.map

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File

/**
 * Renders the basemap on a real GPU at a chosen camera and writes a PNG.
 *
 * The renderer cannot be judged on a host: Vulkan needs a device, and the host-side probes
 * (`examples/probe_archive`, `examples/zoom_sweep`) only measure the CPU pipeline — they
 * report tessellated *area*, which is exactly the thing that can be right while the map still
 * looks wrong. This is the only harness that answers "what does it actually look like".
 *
 * It mounts [VectorMap] and nothing else, so the image is the basemap with no app chrome, and
 * it takes the camera from instrumentation arguments so one build can sweep many places:
 *
 * ```text
 * adb shell am instrument -w \
 *   -e class com.vayunmathur.library.map.BasemapScreenshotTest \
 *   -e lon -122.42 -e lat 37.77 -e zoom 11 -e name sf_bay -e dark false \
 *   com.vayunmathur.library.map.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * The screenshot comes from [android.app.UiAutomation.takeScreenshot] rather than Compose's
 * `captureToImage`, because the map is a `SurfaceView`: its contents belong to SurfaceFlinger
 * and are simply absent from a view-hierarchy capture. A capture that silently returns the
 * background colour would make every one of these images look like the bug being chased.
 */
class BasemapScreenshotTest {

    /** How long to let tiles arrive before capturing. Fetches are network-bound. */
    private val settleMs = 25_000L

    @Test
    fun renders_the_basemap_at_the_requested_camera() {
        val args = InstrumentationRegistry.getArguments()
        val lon = args.getString("lon")?.toDoubleOrNull() ?: 0.0
        val lat = args.getString("lat")?.toDoubleOrNull() ?: 0.0
        val zoom = args.getString("zoom")?.toDoubleOrNull() ?: 2.0
        val dark = args.getString("dark")?.toBooleanStrictOrNull() ?: false
        val muted = args.getString("muted")?.toBooleanStrictOrNull() ?: false
        val name = args.getString("name") ?: "basemap"

        val camera = CameraState(CameraPosition(GeoPoint(lon, lat), zoom))
        var frames = 0

        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                VectorMap(
                    cameraState = camera,
                    modifier = Modifier.fillMaxSize(),
                    style = if (muted) MapStyle.Muted else MapStyle.Standard,
                    darkBasemap = dark,
                    // The camera is the point of the test, so nothing may move it: the
                    // viewport floor in `CameraState.setViewport` is allowed to raise the
                    // zoom, but a gesture would silently invalidate the file name.
                    onFrame = { frames++ },
                )
            }
        }

        // No `waitForIdle`: the map presents continuously, so an idling-resource wait either
        // never returns or returns before the first tile has arrived. Time is the only honest
        // signal available here, which is why the file records what it actually got.
        Thread.sleep(settleMs)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: error("takeScreenshot returned nothing; the screen may be off")

        val dir = File(instrumentation.targetContext.filesDir, "basemap-shots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }

        println("BASEMAP_SHOT $name z$zoom @$lon,$lat frames=$frames -> ${file.absolutePath}")
        scenario.close()

        // A blank capture is the failure this test exists to make loud: if Vulkan never
        // initialised, or the surface was never composited, the image is one flat colour and
        // no amount of looking at it will say which.
        check(frames > 0) { "no frame was presented; check logcat under MapRenderer" }
        check(file.length() > 0) { "wrote an empty PNG" }
    }
}
