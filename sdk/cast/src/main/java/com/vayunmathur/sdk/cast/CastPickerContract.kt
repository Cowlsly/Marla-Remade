package com.vayunmathur.sdk.cast

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Launch the Cast app's TV picker and find out whether a TV ended up connected.
 *
 * An [ActivityResultContract] rather than a plain intent helper for two reasons. The obvious one is
 * that a Compose caller then uses `rememberLauncherForActivityResult` and gets the result without an
 * `onActivityResult` override. The load-bearing one is that **launching for a result is what gives
 * Cast a `callingPackage`**: that is the identity the framework establishes, and it is the only
 * trustworthy source for "Receiving from YouPipe" on the TV. A self-reported app name would be a lie
 * an app could tell, so the SDK never sends one.
 *
 * `true` means a TV is connected and [CastClient.openSession] will work; `false` means the user
 * backed out, or pairing failed, and nothing should change in the calling app.
 */
class CastPickerContract : ActivityResultContract<Unit, Boolean>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent().setComponent(
            ComponentName(CastContract.CAST_PACKAGE, CastContract.PICKER_ACTIVITY),
        )

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK
}
