package com.noxcore.noxdroid.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.noxcore.noxdroid.R
import com.noxcore.noxdroid.core.connection.NoxClientConfigStore
import com.noxcore.noxdroid.core.vpn.dataplane.TunPacketLoop
import com.noxcore.noxdroid.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NoxVpnService : VpnService() {

    private var tunnelFd: ParcelFileDescriptor? = null
    private var tunPacketLoop: TunPacketLoop? = null
    private var isStopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn("Stopped by user")
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn("VPN permission revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnel()
        super.onDestroy()
    }

    private fun startVpn() {
        if (tunnelFd != null) {
            updateState(
                NoxVpnState.RunningForwarding(
                    sessionName = SESSION_NAME,
                    totalPackets = 0,
                    malformedPackets = 0,
                    ipv4Packets = 0,
                    tcpPackets = 0,
                    activeTcpSessions = 0,
                    activeForwardSessions = 0,
                    uplinkBytes = 0,
                    downlinkBytes = 0,
                    droppedForwardPackets = 0,
                    connectFailures = 0,
                    lastPacketSummary = "already running"
                )
            )
            return
        }

        isStopping = false
        updateState(NoxVpnState.Starting)
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting VPN session (safe split-route mode)"))

        val clientConfig = NoxClientConfigStore.load(this)
        if (clientConfig == null) {
            updateState(NoxVpnState.Error("Missing Nox transport config. Run handshake with valid fields first."))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val configureIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(DEFAULT_MTU)
            .addAddress(TUN_IPV4_ADDRESS, TUN_PREFIX)
            .setConfigureIntent(configureIntent)

        // Prevent the app's own control/handshake sockets from ever entering the VPN.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
            updateState(NoxVpnState.Error("Failed to apply VPN app bypass for control path"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Safe mode: avoid global 0.0.0.0/0 capture until forwarding supports full internet traffic.
        SAFE_SPLIT_TUNNEL_ROUTES.forEach { (network, prefix) ->
            builder.addRoute(network, prefix)
        }

        val tunnel = builder.establish()

        if (tunnel == null) {
            updateState(NoxVpnState.Error("Failed to establish VPN interface"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        tunnelFd = tunnel

        val loop = TunPacketLoop(
            config = clientConfig,
            onStats = { stats ->
                val runningState = NoxVpnState.RunningForwarding(
                    sessionName = SESSION_NAME,
                    totalPackets = stats.totalPackets,
                    malformedPackets = stats.malformedPackets,
                    ipv4Packets = stats.ipv4Packets,
                    tcpPackets = stats.tcpPackets,
                    activeTcpSessions = stats.activeTcpSessions,
                    activeForwardSessions = stats.activeForwardSessions,
                    uplinkBytes = stats.uplinkBytes,
                    downlinkBytes = stats.downlinkBytes,
                    droppedForwardPackets = stats.droppedForwardPackets,
                    connectFailures = stats.connectFailures,
                    lastPacketSummary = stats.lastPacketSummary
                )
                updateState(runningState)
                updateNotification(
                    "Forwarding tcp sessions=${stats.activeForwardSessions} " +
                        "up=${stats.uplinkBytes}B down=${stats.downlinkBytes}B " +
                        "connect_fail=${stats.connectFailures}"
                )
            },
            onError = { reason ->
                if (!isStopping) {
                    updateState(NoxVpnState.Error("TUN loop failed: $reason"))
                    stopVpn("TUN loop failed: $reason")
                }
            },
            protectSocket = { socket -> protect(socket) }
        )

        tunPacketLoop = loop
        loop.start(tunnel)

        updateState(
            NoxVpnState.RunningForwarding(
                sessionName = SESSION_NAME,
                totalPackets = 0,
                malformedPackets = 0,
                ipv4Packets = 0,
                tcpPackets = 0,
                activeTcpSessions = 0,
                activeForwardSessions = 0,
                uplinkBytes = 0,
                downlinkBytes = 0,
                droppedForwardPackets = 0,
                connectFailures = 0,
                lastPacketSummary = "waiting for packets"
            )
        )

        updateNotification("VPN active (safe split-route mode). Constrained IPv4/TCP via Nox transport.")
    }

    private fun stopVpn(reason: String) {
        isStopping = true
        updateState(NoxVpnState.Stopping)
        closeTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        updateState(
            if (reason == "Stopped by user") {
                NoxVpnState.Idle
            } else {
                NoxVpnState.Error(reason)
            }
        )
    }

    private fun closeTunnel() {
        tunPacketLoop?.stop()
        tunPacketLoop = null

        try {
            tunnelFd?.close()
        } catch (_: Exception) {
        }
        tunnelFd = null
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, NoxVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("NoxJ VPN")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_stop_vpn), stopIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) {
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "NoxJ VPN",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun updateState(state: NoxVpnState) {
        _vpnState.value = state
    }

    companion object {
        const val ACTION_START = "com.noxcore.noxdroid.vpn.START"
        const val ACTION_STOP = "com.noxcore.noxdroid.vpn.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "noxj_vpn"
        private const val NOTIFICATION_ID = 1401
        private const val SESSION_NAME = "NoxJ"
        private const val DEFAULT_MTU = 1400
        private const val TUN_IPV4_ADDRESS = "10.77.0.2"
        private const val TUN_PREFIX = 32
        private val SAFE_SPLIT_TUNNEL_ROUTES = listOf(
            "10.0.0.0" to 8,
            "172.16.0.0" to 12,
            "192.168.0.0" to 16,
            "100.64.0.0" to 10
        )

        private val _vpnState = MutableStateFlow<NoxVpnState>(NoxVpnState.Idle)
        val vpnState: StateFlow<NoxVpnState> = _vpnState.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, NoxVpnService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NoxVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
