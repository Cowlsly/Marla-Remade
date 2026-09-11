package com.vayunmathur.safefamily.service

import android.app.supervision.Policy
import android.app.supervision.SupervisionAppService
import android.util.Log
import com.vayunmathur.safefamily.platform.Enforcer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "SafeFamilyAppService"

/**
 * The connection the platform keeps open to a supervision role holder.
 *
 * `AppBindingService` binds this on every `ROLE_SYSTEM_SUPERVISION` / `ROLE_SUPERVISION` holder,
 * keeps it in the foreground, and rebinds after a backoff if the process dies. **It was already
 * trying to bind us and failing** before this existed - the finder is registered
 * unconditionally and enabled by default - so its absence was a logged error and no callbacks,
 * not an inert feature.
 *
 * There is deliberately very little here. The service's job is to be bound and to notice state
 * changes; the decisions live in [Enforcer], which every other trigger also calls, so that a
 * policy change and a bedtime alarm cannot reach different conclusions.
 *
 * [onBind] is final upstream and returns an `ISupervisionListener` that marshals these callbacks
 * onto the main thread, which is why the work below is moved straight back off it.
 */
class SafeFamilyAppService : SupervisionAppService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onSupervisionEnabled() {
        Log.i(TAG, "supervision enabled")
        reconcile()
    }

    override fun onSupervisionDisabled() {
        // Nothing to undo here. The platform clears our policies itself on disable
        // (clearAllDevicePoliciesAndSuspendedPackages / clearAllPolicies), so re-applying or
        // explicitly allowing would race that cleanup. The stored rules stay, so re-enabling
        // supervision restores the parent's configuration rather than starting blank.
        Log.i(TAG, "supervision disabled; platform is clearing policy state")
    }

    override fun onPolicyChanged(policy: Policy) {
        // Policies can be changed by something other than us. Re-deriving from our own rules is
        // the honest response: if an external change disagrees with what the parent configured,
        // the parent's configuration is the one that should survive.
        Log.i(TAG, "policy changed: ${runCatching { policy.identifier }.getOrNull()}")
        reconcile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun reconcile() {
        scope.launch {
            runCatching { Enforcer(applicationContext).reconcile() }
                .onFailure { Log.e(TAG, "reconcile failed", it) }
        }
    }
}
