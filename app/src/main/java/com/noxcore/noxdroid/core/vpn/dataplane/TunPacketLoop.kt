package com.noxcore.noxdroid.core.vpn.dataplane

import android.os.ParcelFileDescriptor
import android.util.Log
import com.noxcore.noxdroid.core.connection.NoxClientConfig
import com.noxcore.noxdroid.core.connection.NoxTransportClient
import com.noxcore.noxdroid.core.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.Socket

class TunPacketLoop(
    private val config: NoxClientConfig,
    private val onStats: (TunLoopStats) -> Unit,
    private val onError: (String) -> Unit,
    private val protectSocket: (Socket) -> Boolean
) {

    data class TunLoopStats(
        val totalPackets: Long,
        val malformedPackets: Long,
        val ipv4Packets: Long,
        val tcpPackets: Long,
        val activeTcpSessions: Int,
        val activeForwardSessions: Int,
        val uplinkBytes: Long,
        val downlinkBytes: Long,
        val droppedForwardPackets: Long,
        val connectFailures: Long,
        val lastPacketSummary: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private val tracker = TcpSessionTracker()

    fun start(tunnelFd: ParcelFileDescriptor) {
        if (loopJob != null) {
            return
        }

        loopJob = scope.launch {
            val input = FileInputStream(tunnelFd.fileDescriptor)
            val output = FileOutputStream(tunnelFd.fileDescriptor)
            val outputLock = Any()
            val buffer = ByteArray(32767)
            val transportClient = NoxTransportClient(
                config = config,
                protectSocket = protectSocket
            )
            val connectResult = transportClient.connect()
            if (connectResult.isFailure) {
                val reason = connectResult.exceptionOrNull()?.message ?: "transport connect failed"
                DiagnosticsLog.error(TAG, "transport connect failed: $reason")
                onError("Nox transport connect failed: $reason")
                try {
                    input.close()
                } catch (_: Exception) {
                }
                try {
                    output.close()
                } catch (_: Exception) {
                }
                return@launch
            }

            val forwarder = TcpTunForwarder(
                writeToTun = { packet ->
                    synchronized(outputLock) {
                        output.write(packet)
                        output.flush()
                    }
                },
                transportClient = transportClient
            )
            DiagnosticsLog.info(TAG, "transport connected; entering TUN read loop")

            var totalPackets = 0L
            var malformedPackets = 0L
            var ipv4Packets = 0L
            var tcpPackets = 0L
            var lastSummary = "waiting for packets"
            var lastStatsEmitMs = 0L
            var lastCleanupMs = 0L
            var lastLoggedConnectFailures = 0L
            var lastLoggedDroppedPackets = 0L

            try {
                while (isActive) {
                    val read = input.read(buffer)
                    if (read <= 0) {
                        continue
                    }

                    totalPackets += 1
                    val parsed = PacketParser.parse(buffer, read)
                    val nowMs = System.currentTimeMillis()

                    when (parsed) {
                        is ParsedPacket.NonIpv4 -> {
                            lastSummary = parsed.meta.summary
                        }

                        is ParsedPacket.Ipv4NonTcp -> {
                            ipv4Packets += 1
                            lastSummary = parsed.meta.summary
                        }

                        is ParsedPacket.Ipv4Tcp -> {
                            ipv4Packets += 1
                            tcpPackets += 1
                            tracker.observe(parsed.meta, nowMs)
                            val forwarded = forwarder.handleClientPacket(parsed.meta)
                            lastSummary = if (forwarded) {
                                "forwarded: ${parsed.meta.summary}"
                            } else {
                                parsed.meta.summary
                            }
                        }

                        is ParsedPacket.Malformed -> {
                            malformedPackets += 1
                            lastSummary = "malformed: ${parsed.reason}"
                        }
                    }

                    if (nowMs - lastCleanupMs >= SESSION_CLEANUP_INTERVAL_MS) {
                        tracker.expireClosedAndIdle(nowMs, SESSION_IDLE_TIMEOUT_MS)
                        forwarder.expireIdle(nowMs, FORWARD_IDLE_TIMEOUT_MS)
                        lastCleanupMs = nowMs
                    }

                    if (nowMs - lastStatsEmitMs >= STATS_EMIT_INTERVAL_MS) {
                        val forwardStats = forwarder.stats()
                        if (forwardStats.connectFailures > lastLoggedConnectFailures) {
                            DiagnosticsLog.warn(
                                TAG,
                                "stream open failures increased to ${forwardStats.connectFailures} last=$lastSummary"
                            )
                            lastLoggedConnectFailures = forwardStats.connectFailures
                        }
                        if (forwardStats.droppedPackets > lastLoggedDroppedPackets) {
                            DiagnosticsLog.warn(
                                TAG,
                                "dropped forwarded packets increased to ${forwardStats.droppedPackets} last=$lastSummary"
                            )
                            lastLoggedDroppedPackets = forwardStats.droppedPackets
                        }
                        onStats(
                            TunLoopStats(
                                totalPackets = totalPackets,
                                malformedPackets = malformedPackets,
                                ipv4Packets = ipv4Packets,
                                tcpPackets = tcpPackets,
                                activeTcpSessions = tracker.activeSessionCount(),
                                activeForwardSessions = forwardStats.activeForwardSessions,
                                uplinkBytes = forwardStats.uplinkBytes,
                                downlinkBytes = forwardStats.downlinkBytes,
                                droppedForwardPackets = forwardStats.droppedPackets,
                                connectFailures = forwardStats.connectFailures,
                                lastPacketSummary = lastSummary
                            )
                        )
                        lastStatsEmitMs = nowMs
                    }
                }
            } catch (e: IOException) {
                val message = e.message ?: "tunnel read failed"
                Log.w(TAG, "TUN read loop stopped: $message")
                DiagnosticsLog.warn(TAG, "TUN read loop stopped: $message")
                onError(message)
            } finally {
                DiagnosticsLog.info(TAG, "leaving TUN read loop; stopping forwarder")
                forwarder.stop()

                try {
                    input.close()
                } catch (_: Exception) {
                }

                try {
                    output.close()
                } catch (_: Exception) {
                }

                val forwardStats = forwarder.stats()
                onStats(
                    TunLoopStats(
                        totalPackets = totalPackets,
                        malformedPackets = malformedPackets,
                        ipv4Packets = ipv4Packets,
                        tcpPackets = tcpPackets,
                        activeTcpSessions = tracker.activeSessionCount(),
                        activeForwardSessions = forwardStats.activeForwardSessions,
                        uplinkBytes = forwardStats.uplinkBytes,
                        downlinkBytes = forwardStats.downlinkBytes,
                        droppedForwardPackets = forwardStats.droppedPackets,
                        connectFailures = forwardStats.connectFailures,
                        lastPacketSummary = lastSummary
                    )
                )
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    companion object {
        private const val TAG = "TunPacketLoop"
        private const val STATS_EMIT_INTERVAL_MS = 1_000L
        private const val SESSION_CLEANUP_INTERVAL_MS = 10_000L
        private const val SESSION_IDLE_TIMEOUT_MS = 60_000L
        private const val FORWARD_IDLE_TIMEOUT_MS = 60_000L
    }
}
