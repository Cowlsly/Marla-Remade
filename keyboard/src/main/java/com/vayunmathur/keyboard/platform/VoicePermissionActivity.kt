package com.vayunmathur.keyboard.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** What the user did with the microphone prompt. */
enum class VoicePermissionResult { GRANTED, DENIED, BLOCKED }

/**
 * The microphone permission, on behalf of the IME.
 *
 * An `InputMethodService` cannot show a runtime permission dialog: the framework only grants
 * a permission to an Activity's request, and the IME has no Activity. So the service starts
 * [VoicePermissionActivity] and learns the outcome from [results] — a plain in-process flow,
 * which is enough because the activity and the service share the app's process.
 */
object VoicePermission {

    private val _results = MutableSharedFlow<VoicePermissionResult>(extraBufferCapacity = 1)
    val results: SharedFlow<VoicePermissionResult> = _results

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun request(context: Context) {
        context.startActivity(
            Intent(context, VoicePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    internal fun publish(result: VoicePermissionResult) {
        _results.tryEmit(result)
    }
}

/**
 * Invisible trampoline that asks for `RECORD_AUDIO` and reports the answer to [VoicePermission].
 *
 * **Not `android:noHistory`**: that finishes an activity the moment it stops being visible, and
 * the system permission dialog does exactly that, so the result callback would never arrive.
 */
class VoicePermissionActivity : ComponentActivity() {

    private val request = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Straight after a denial, shouldShowRequestPermissionRationale is false only when
        // the user chose "don't ask again" — that is the one case a further prompt cannot fix.
        VoicePermission.publish(
            when {
                granted -> VoicePermissionResult.GRANTED
                shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                    VoicePermissionResult.DENIED
                else -> VoicePermissionResult.BLOCKED
            },
        )
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a fresh launch: registerForActivityResult restores a pending request across
        // a configuration change itself, and asking again would stack a second dialog.
        if (savedInstanceState == null) request.launch(Manifest.permission.RECORD_AUDIO)
    }
}
