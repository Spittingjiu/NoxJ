package com.noxcore.noxdroid.core.vpn.dataplane

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.IOException

class TunPacketLoop(
    private val onStats: (TunLoopStats) -> Unit,
    private val onError: (String) -> Unit
) {

    data class TunLoopStats(
        val totalPackets: Long,
        val malformedPackets: Long,
        val ipv4Packets: Long,
        val tcpPackets: Long,
        val activeTcpSessions: Int,
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
            val buffer = ByteArray(32767)

            var totalPackets = 0L
            var malformedPackets = 0L
            var ipv4Packets = 0L
            var tcpPackets = 0L
            var lastSummary = "waiting for packets"
            var lastStatsEmitMs = 0L
            var lastCleanupMs = 0L

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
                            lastSummary = parsed.meta.summary
                        }

                        is ParsedPacket.Malformed -> {
                            malformedPackets += 1
                            lastSummary = "malformed: ${parsed.reason}"
                        }
                    }

                    if (nowMs - lastCleanupMs >= SESSION_CLEANUP_INTERVAL_MS) {
                        tracker.expireClosedAndIdle(nowMs, SESSION_IDLE_TIMEOUT_MS)
                        lastCleanupMs = nowMs
                    }

                    if (nowMs - lastStatsEmitMs >= STATS_EMIT_INTERVAL_MS) {
                        onStats(
                            TunLoopStats(
                                totalPackets = totalPackets,
                                malformedPackets = malformedPackets,
                                ipv4Packets = ipv4Packets,
                                tcpPackets = tcpPackets,
                                activeTcpSessions = tracker.activeSessionCount(),
                                lastPacketSummary = lastSummary
                            )
                        )
                        lastStatsEmitMs = nowMs
                    }
                }
            } catch (e: IOException) {
                val message = e.message ?: "tunnel read failed"
                Log.w(TAG, "TUN read loop stopped: $message")
                onError(message)
            } finally {
                try {
                    input.close()
                } catch (_: Exception) {
                }

                onStats(
                    TunLoopStats(
                        totalPackets = totalPackets,
                        malformedPackets = malformedPackets,
                        ipv4Packets = ipv4Packets,
                        tcpPackets = tcpPackets,
                        activeTcpSessions = tracker.activeSessionCount(),
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
    }
}
