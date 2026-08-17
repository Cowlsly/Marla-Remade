package com.vayunmathur.maps.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Client for findfamily's live family-location channel (see
 * [FamilyLocationProtocol]). Binds the remote [Messenger] service, registers a
 * reply Messenger, and delivers each pushed snapshot to [onUpdate] on the main
 * thread.
 *
 * Absence handling is total: if findfamily isn't installed, the service can't be
 * resolved, the permission is denied, or binding otherwise fails, [bind] simply
 * no-ops and no members are ever delivered — the feature is absent with no crash.
 */
class FamilyLocationClient(
    context: Context,
    private val onUpdate: (List<FamilyMember>) -> Unit,
) {
    private val appContext = context.applicationContext

    private val incoming = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == FamilyLocationProtocol.MSG_LOCATIONS) {
                onUpdate(parseSnapshot(msg.data))
                true
            } else {
                false
            }
        }
    )

    private var service: Messenger? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = Messenger(binder)
            service = remote
            try {
                val msg = Message.obtain(null, FamilyLocationProtocol.MSG_REGISTER)
                msg.replyTo = incoming
                remote.send(msg)
            } catch (_: RemoteException) {
                // Service died between connect and register — nothing to stream.
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Resolve + bind the findfamily service. Safe to call once per lifecycle. */
    fun bind() {
        if (bound) return
        val intent = resolveServiceIntent() ?: return
        bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            // Permission not granted (not co-signed / not held) — feature absent.
            false
        }
    }

    /** Unregister and unbind. Idempotent; safe even if [bind] never connected. */
    fun unbind() {
        if (!bound) return
        try {
            val msg = Message.obtain(null, FamilyLocationProtocol.MSG_UNREGISTER)
            msg.replyTo = incoming
            service?.send(msg)
        } catch (_: RemoteException) {
            // Already gone — the service tears its own stream down on death.
        }
        try {
            appContext.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Not actually bound (e.g. connection never established).
        }
        bound = false
        service = null
    }

    /**
     * Build an explicit intent for the findfamily service by resolving the
     * declared [FamilyLocationProtocol.ACTION]. Resolving (rather than
     * hard-coding a component) tolerates any applicationId suffix and returns
     * null when findfamily isn't installed, so the caller degrades to "absent".
     */
    private fun resolveServiceIntent(): Intent? {
        val intent = Intent(FamilyLocationProtocol.ACTION)
        val resolved = appContext.packageManager
            .queryIntentServices(intent, 0)
            .firstOrNull()
            ?.serviceInfo
            ?: return null
        intent.component = ComponentName(resolved.packageName, resolved.name)
        return intent
    }

    private fun parseSnapshot(data: Bundle?): List<FamilyMember> {
        if (data == null) return emptyList()
        val ids = data.getLongArray(FamilyLocationProtocol.KEY_IDS) ?: return emptyList()
        val names = data.getStringArray(FamilyLocationProtocol.KEY_NAMES) ?: return emptyList()
        val lats = data.getDoubleArray(FamilyLocationProtocol.KEY_LATS) ?: return emptyList()
        val lngs = data.getDoubleArray(FamilyLocationProtocol.KEY_LNGS) ?: return emptyList()
        val timestamps = data.getLongArray(FamilyLocationProtocol.KEY_TIMESTAMPS) ?: return emptyList()
        val batteries = data.getFloatArray(FamilyLocationProtocol.KEY_BATTERIES)

        // Guard against a malformed / mismatched payload rather than trusting the
        // arrays line up — take the length they all agree on.
        val count = minOf(ids.size, names.size, lats.size, lngs.size, timestamps.size)
        val out = ArrayList<FamilyMember>(count)
        for (i in 0 until count) {
            out.add(
                FamilyMember(
                    id = ids[i],
                    name = names[i],
                    lat = lats[i],
                    lng = lngs[i],
                    timestamp = timestamps[i],
                    battery = batteries?.getOrNull(i) ?: -1f,
                )
            )
        }
        return out
    }
}

/**
 * Bind the findfamily family-location channel for exactly as long as the calling
 * composable is in the composition, returning the latest pushed members as
 * Compose state.
 *
 * Tied to the map screen's lifecycle via [DisposableEffect]: it binds when the
 * screen opens and unbinds when it closes, so findfamily only does the streaming
 * work while maps is actually showing the map. If findfamily is absent the state
 * simply stays empty.
 */
@Composable
fun rememberFamilyMembers(): State<List<FamilyMember>> {
    val context = LocalContext.current
    val members = remember { mutableStateOf<List<FamilyMember>>(emptyList()) }
    DisposableEffect(Unit) {
        val client = FamilyLocationClient(context) { members.value = it }
        client.bind()
        onDispose {
            client.unbind()
            members.value = emptyList()
        }
    }
    return members
}
