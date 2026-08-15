@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.vpn.service

import kotlin.concurrent.atomics.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.data.VpnDatabase
import com.vayunmathur.vpn.data.endpointHost
import com.vayunmathur.vpn.data.endpointPort
import com.vayunmathur.vpn.data.toModel
import com.vayunmathur.vpn.platform.BypassList
import com.vayunmathur.vpn.util.VpnNative
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Foreground VpnService that bridges Android TUN fd <-> UDP socket.
 *
 * Crypto is all in Rust/gotatun via VpnNative (Mullvad's BoringTun fork):
 * X25519 + Noise IK + ChaCha20Poly1305 + BLAKE2s, plus rekey/keepalive timers.
 *
 * Kotlin handles TUN<->UDP plumbing plus per-flow logging (packet inspection,
 * DNS snooping, SNI, per-app attribution via getConnectionOwnerUid).
 */
class VpnTunnelService : VpnService() {

    companion object {
        private const val TAG = "VpnTunnelService"
        const val ACTION_CONNECT = "com.vayunmathur.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.vayunmathur.vpn.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val NOTIFICATION_ID = 42
        const val CHANNEL_ID = "vpn_tunnel"
        @Volatile var isRunning: Boolean = false
        @Volatile var runningConfigName: String = ""
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var job: Job? = null
    private var flushJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stopFlag = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        try { VpnNative.init() } catch (_: Throwable) {}
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when {
            intent == null || intent.action == null -> {
                // Null intent/action = system start (Always-On VPN, or sticky restart).
                // Go foreground straight away — the config lookup below is async and the
                // system will not wait for it before enforcing the background-start timeout.
                Log.i(TAG, "onStartCommand system start — attempting Always-On restore")
                goForeground(notification("VPN (WireGuard/gotatun)", "Connecting…"))
                scope.launch {
                    try {
                        val all = VpnDatabase.get(this@VpnTunnelService).vpnConfigDao().getAll()
                        val lastUsed = all.maxByOrNull { it.lastUsed }
                        if (lastUsed != null) {
                            Log.i(TAG, "Always-On restoring ${lastUsed.name}")
                            startVpn(lastUsed.toModel())
                        } else {
                            Log.i(TAG, "Always-On restore: no configs found")
                            stopVpn()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Always-On restore failed", e)
                        stopVpn()
                    }
                }
                START_STICKY
            }
            intent.action == ACTION_DISCONNECT -> {
                stopVpn()
                START_NOT_STICKY
            }
            intent.action == ACTION_CONNECT -> {
                val j = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: return START_STICKY
                runCatching { Json.decodeFromString<VpnConfig>(j) }.onSuccess { startVpn(it) }
                START_STICKY
            }
            else -> START_STICKY
        }
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
    override fun onRevoke() { Log.i(TAG, "onRevoke"); stopVpn() }

    private fun notification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build()
    }

    private fun goForeground(notif: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else startForeground(NOTIFICATION_ID, notif)
        } catch (e: Exception) { Log.e(TAG, "foreground", e) }
    }

    private fun startVpn(config: VpnConfig) {
        if (isRunning) stopVpn()
        stopFlag.store(false)
        runningConfigName = config.name
        goForeground(notification("VPN (WireGuard/gotatun) — ${config.name}", config.peerEndpoint))

        job = scope.launch { runBlocking { runTunnel(config) } }
    }

    private fun stopVpn() {
        stopFlag.store(true)
        job?.cancel(); job = null
        flushJob?.cancel(); flushJob = null
        isRunning = false
        runningConfigName = ""
        try { tunPfd?.close() } catch (_: Exception) {}
        tunPfd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runTunnel(config: VpnConfig) {
        isRunning = true

        val handle = VpnNative.newTunnel(
            config.privateKey, config.peerPublicKey, config.peerPresharedKey, config.peerKeepalive
        )
        if (handle <= 0) { Log.e(TAG, "newTunnel failed $handle"); stopVpn(); return }

        fun parseCsvCidrs(csv: String): List<Pair<String, Int>> =
            csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { cidr ->
                val parts = cidr.split('/')
                val ip = parts[0].trim()
                val mask = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    ?: if (ip.contains(':')) 128 else 32
                ip to mask
            }

        val localAddrs = parseCsvCidrs(config.address.ifBlank { "10.0.0.2/32" })
        val allowed = parseCsvCidrs(config.peerAllowedIPs.ifBlank { "0.0.0.0/0" })

        val b = Builder().setSession(config.name.ifBlank { "WireGuard" })
            .setMtu(config.mtu.coerceIn(1280, 1500)).setBlocking(false)

        for ((ip, mask) in localAddrs) { try { b.addAddress(ip, mask) } catch (e: Exception) { Log.w(TAG, "addr $ip/$mask", e) } }

        // IPv6 leak guard. Our own default AllowedIPs is "0.0.0.0/0, ::/0", but plenty of
        // real .conf files from IPv4-only providers say just "0.0.0.0/0". Android only
        // diverts the families it has a route for, so on a dual-stack network every IPv6
        // connection would keep using the physical interface while the user believes they
        // are tunnelled — a silent deanonymisation, not a degraded experience.
        //
        // Claiming ::/0 without an IPv6 address on the tun means IPv6 source-address
        // selection fails and connections fail immediately, so Happy Eyeballs falls back to
        // IPv4 through the tunnel. Traffic is blocked rather than leaked. Only applied when
        // the config routes a v4 default and names no v6 route at all: a config that
        // deliberately splits (say 10.0.0.0/8 only) is left exactly as written.
        val routes = allowed.toMutableList()
        val hasV6Route = allowed.any { (ip, _) -> ip.contains(':') }
        val hasV4Default = allowed.any { (ip, mask) -> mask == 0 && !ip.contains(':') }
        if (hasV4Default && !hasV6Route) {
            Log.i(TAG, "AllowedIPs has no IPv6 route; adding ::/0 to stop IPv6 leaking around the tunnel")
            routes.add("::" to 0)
        }
        for ((ip, mask) in routes) { try { b.addRoute(ip, mask) } catch (e: Exception) { Log.w(TAG, "route $ip/$mask", e) } }
        if (config.dns.isNotBlank()) {
            for (d in config.dns.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                try { b.addDnsServer(d) } catch (_: Exception) {}
            }
        }
        try { b.setUnderlyingNetworks(null) } catch (_: Exception) {}

        // Split tunnelling. addDisallowedApplication throws if the package is gone (user
        // uninstalled it after adding it to the list), so each one is guarded individually
        // rather than losing the whole tunnel to one stale entry.
        for (pkg in BypassList.load(applicationContext)) {
            try {
                b.addDisallowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "bypass: $pkg not installed, skipping")
            } catch (e: Exception) {
                Log.w(TAG, "bypass: $pkg", e)
            }
        }

        val pfd = try { b.establish() } catch (e: Exception) {
            Log.e(TAG, "establish", e); VpnNative.freeTunnel(handle); stopVpn(); return
        }
        if (pfd == null) { Log.e(TAG, "establish null"); VpnNative.freeTunnel(handle); stopVpn(); return }
        tunPfd = pfd

        val host = config.endpointHost()
        val port = config.endpointPort()
        if (host.isEmpty()) { Log.e(TAG, "no endpoint"); pfd.close(); VpnNative.freeTunnel(handle); stopVpn(); return }

        val channel = try {
            val ch = DatagramChannel.open()
            ch.configureBlocking(false)
            try { protect(ch.socket()) } catch (_: Exception) {}
            ch.connect(java.net.InetSocketAddress(host, port))
            ch
        } catch (e: Exception) {
            Log.e(TAG, "UDP connect $host:$port", e)
            pfd.close(); VpnNative.freeTunnel(handle); stopVpn(); return
        }

        // --- Logging infrastructure ---
        val dnsCache = DnsCache()
        val tracker = ConnectionTracker.getOrCreate()
        tracker.setDnsCache(dnsCache)
        val appResolver = AppResolver(this)

        // Batched Room upsert job (1.5s) — tracker keeps cumulative TX/RX in memory,
        // so each drain returns full accumulated totals for dirty flows.
        val logDao = VpnDatabase.get(this).connectionLogDao()
        flushJob = scope.launch(Dispatchers.IO) {
            while (isActive && !stopFlag.load()) {
                delay(1500)
                try {
                    val batch = tracker.drainDirty()
                    if (batch.isNotEmpty()) {
                        val toUpsert = mutableListOf<com.vayunmathur.vpn.data.ConnectionLogEntity>()
                        for (entity in batch) {
                            if (entity.id != 0L) {
                                toUpsert.add(entity)
                            } else {
                                val existing = logDao.findIdentical(
                                    remoteIp = entity.remoteIp,
                                    remotePort = entity.remotePort,
                                    protocol = entity.protocol,
                                    localPort = entity.localPort,
                                )
                                if (existing != null) {
                                    val attributed = entity.uid >= 0
                                    toUpsert.add(
                                        entity.copy(
                                            id = existing.id,
                                            timestampStart = minOf(existing.timestampStart, entity.timestampStart),
                                            txBytes = maxOf(existing.txBytes, entity.txBytes),
                                            rxBytes = maxOf(existing.rxBytes, entity.rxBytes),
                                            domain = entity.domain ?: existing.domain,
                                            uid = if (attributed) entity.uid else existing.uid,
                                            packageName = if (attributed) entity.packageName else existing.packageName,
                                            appLabel = if (attributed) entity.appLabel else existing.appLabel,
                                        )
                                    )
                                } else {
                                    toUpsert.add(entity)
                                }
                            }
                        }
                        if (toUpsert.isNotEmpty()) {
                            logDao.upsertAll(toUpsert)
                            tracker.updateIds(toUpsert)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "flush logs", e)
                }
            }
        }

        fun handleLoggingForPacket(
            ipBytes: ByteArray,
            direction: ConnectionTracker.Direction,
            rawBytesLen: Int,
        ) {
            try {
                val parsed = PacketInspector.parse(ipBytes) ?: return

                // DNS snooping populates IP->domain LRU
                if (parsed.protocol == "UDP" && (parsed.srcPort == 53 || parsed.dstPort == 53)) {
                    try { dnsCache.onPacket(parsed, ipBytes) } catch (_: Exception) {}
                }

                // Domain resolution: DNS cache then SNI for TLS 443
                var domain: String? = dnsCache.get(
                    if (direction == ConnectionTracker.Direction.TX) parsed.dstIp else parsed.srcIp
                )
                if (domain == null && parsed.protocol == "TCP") {
                    val isTlsPort = if (direction == ConnectionTracker.Direction.TX) parsed.dstPort == 443 else parsed.srcPort == 443
                    if (isTlsPort && parsed.payloadLength > 0) {
                        domain = SniParser.extractSni(ipBytes, parsed.payloadOffset, parsed.payloadLength)
                        if (domain != null) {
                            val ipForCache = if (direction == ConnectionTracker.Direction.TX) parsed.dstIp else parsed.srcIp
                            dnsCache.put(ipForCache, domain)
                        }
                    }
                }

                val resolved = appResolver.resolve(parsed, direction)
                tracker.onPacket(
                    parsed = parsed,
                    direction = direction,
                    bytes = rawBytesLen,
                    domainOverride = domain,
                    uid = resolved.uid,
                    packageName = resolved.packageName,
                    appLabel = resolved.appLabel,
                )
            } catch (e: Exception) {
                Log.w(TAG, "logging parse", e)
            }
        }

        val tunIn = FileInputStream(pfd.fileDescriptor).channel
        val tunOut = FileOutputStream(pfd.fileDescriptor).channel

        try {
            VpnNative.formatHandshakeInit(handle)?.let { hs ->
                try { channel.write(ByteBuffer.wrap(hs)) } catch (_: Exception) {}
                Log.i(TAG, "Sent HandshakeInit ${hs.size} to $host:$port (gotatun)")
            }

            val udpBuf = ByteBuffer.allocate(65535)
            val tunBuf = ByteBuffer.allocate(65535)
            var lastTimer = System.currentTimeMillis()

            while (!stopFlag.load() && scope.isActive) {
                try {
                    while (tunIn.read(tunBuf) > 0) {
                        tunBuf.flip()
                        val ip = ByteArray(tunBuf.remaining()); tunBuf.get(ip); tunBuf.clear()
                        handleLoggingForPacket(ip, ConnectionTracker.Direction.TX, ip.size)
                        val enc = VpnNative.encapsulate(handle, ip)
                        if (enc != null && enc.isNotEmpty()) {
                            try { channel.write(ByteBuffer.wrap(enc)) } catch (e: Exception) { Log.w(TAG, "udp write", e) }
                        } else {
                            VpnNative.formatHandshakeInit(handle)?.let { h ->
                                try { channel.write(ByteBuffer.wrap(h)) } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) { if (!stopFlag.load()) Log.w(TAG, "tun read", e) }

                try {
                    udpBuf.clear()
                    while (channel.read(udpBuf) > 0) {
                        udpBuf.flip()
                        val wg = ByteArray(udpBuf.remaining()); udpBuf.get(wg); udpBuf.clear()
                        val tagged = VpnNative.consumeIncomingPacketDetailed(handle, wg) ?: continue
                        if (tagged.isEmpty()) continue
                        val tag = tagged[0].toInt()
                        val payload = tagged.copyOfRange(1, tagged.size)
                        when (tag) {
                            1 -> if (payload.isNotEmpty()) { try { channel.write(ByteBuffer.wrap(payload)) } catch (_: Exception) {} }
                            2 -> if (payload.isNotEmpty()) {
                                handleLoggingForPacket(payload, ConnectionTracker.Direction.RX, payload.size)
                                try { tunOut.write(ByteBuffer.wrap(payload)) } catch (e: Exception) { if (!stopFlag.load()) Log.w(TAG, "tun write", e) }
                            }
                            3 -> { /* keepalive absorbed */ }
                        }
                    }
                } catch (e: Exception) { if (!stopFlag.load()) Log.w(TAG, "udp read", e) }

                val now = System.currentTimeMillis()
                if (now - lastTimer >= 100) {
                    lastTimer = now
                    try {
                        val t = VpnNative.tickTimersDetailed(handle)
                        if (t != null && t.isNotEmpty() && t[0].toInt() == 1) {
                            val p = t.copyOfRange(1, t.size)
                            if (p.isNotEmpty()) try { channel.write(ByteBuffer.wrap(p)) } catch (_: Exception) {}
                        }
                    } catch (e: Exception) { Log.w(TAG, "timer", e) }
                }
                delay(10)
            }
        } finally {
            try {
                val finalBatch = tracker.drainDirty()
                if (finalBatch.isNotEmpty()) logDao.upsertAll(finalBatch)
            } catch (_: Exception) {}
            flushJob?.cancel(); flushJob = null
            try { tunIn.close() } catch (_: Exception) {}
            try { tunOut.close() } catch (_: Exception) {}
            try { channel.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
            VpnNative.freeTunnel(handle)
            isRunning = false
            try { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
}
