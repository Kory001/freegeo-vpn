package org.freegeo.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.freegeo.vpn.MainActivity
import org.freegeo.vpn.R
import org.freegeo.vpn.data.Node
import org.freegeo.vpn.data.WarpAccount
import org.freegeo.vpn.engine.TunnelEngine

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

class FreeGeoVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: TunnelEngine? = null
    private var tunInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        engine = TunnelEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
            else -> connect()
        }
        return START_STICKY
    }

    private fun connect() {
        setState(ConnectionState.CONNECTING)
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_connecting)))

        val isWarp = useWarp && currentWarpAccount != null
        val node = currentConfiguredNode
        val warp = currentWarpAccount

        if (!isWarp && node == null) {
            fail("No node selected")
            return
        }
        if (isWarp && warp == null) {
            fail("WARP account not ready")
            return
        }

        scope.launch {
            try {
                val fd = establishInterface() ?: error("VPN permission not granted")
                tunInterface = fd
                val result = if (isWarp) {
                    engine!!.startWarp(warp!!, fd.detachFd())
                } else {
                    engine!!.start(node!!, fd.detachFd())
                }
                result.onFailure { throw it }

                setState(ConnectionState.CONNECTED)
                val label = if (isWarp) {
                    "WARP · Cloudflare"
                } else {
                    "${node!!.flag} ${node.name} · ${node.latencyMs ?: "?"} ms"
                }
                updateNotification(getString(R.string.notif_connected, label))
                monitorLoop()
            } catch (e: Throwable) {
                fail(e.message ?: "Connection failed: ${e::class.simpleName}")
            }
        }
    }

    private suspend fun monitorLoop() {
        while (scope.isActive && state.value == ConnectionState.CONNECTED) {
            delay(3000)
            try {
                val alive = engine?.isAlive() ?: false
                val xray = try { org.freegeo.vpn.engine.LibXrayBridge.isRunning() } catch (_: Throwable) { false }
                if (!alive || !xray) {
                    fail("Tunnel dropped (tunAlive=$alive xray=$xray)")
                    return
                }
            } catch (e: Throwable) {
                fail("Monitor error: ${e.message}")
                return
            }
        }
    }

    private fun establishInterface(): ParcelFileDescriptor? {
        return Builder()
            .setSession("FreeGeo VPN")
            .setMtu(8500)
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fc00::1", 128)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .also { builder ->
                bypassApps.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
            .setBlocking(false)
            .establish()
    }

    private fun disconnect() {
        scope.launch {
            runCatching {
                tunInterface?.close()
                tunInterface = null
            }
            engine?.stop()
            setState(ConnectionState.DISCONNECTED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(message: String) {
        lastError = message
        setState(ConnectionState.ERROR)
        runCatching {
            tunInterface?.close()
            tunInterface = null
        }
        engine?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { tunInterface?.close() }
        engine?.stop()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification = notification(text)
    private fun updateNotification(text: String) =
        notifyManager().notify(NOTIF_ID, notification(text))

    private fun notification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FreeGeoVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("FreeGeo VPN")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(0, "Disconnect", disconnectIntent)
            .setOngoing(true)
            .build()
    }

    private fun notifyManager(): NotificationManager {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return nm
    }

    companion object {
        const val CHANNEL_ID = "tunnel"
        const val NOTIF_ID = 42
        const val ACTION_DISCONNECT = "org.freegeo.vpn.DISCONNECT"

        val state = kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.DISCONNECTED)
        var currentConfiguredNode: Node? = null
        var currentWarpAccount: WarpAccount? = null
        var useWarp: Boolean = false
        var bypassApps: List<String> = emptyList()
        var lastError: String? = null
            private set

        fun reportError(message: String) {
            lastError = message
            state.value = ConnectionState.ERROR
        }

        @Synchronized
        private fun setState(s: ConnectionState) {
            state.value = s
            if (s == ConnectionState.DISCONNECTED || s == ConnectionState.CONNECTING) {
                lastError = null
            }
        }
    }
}
