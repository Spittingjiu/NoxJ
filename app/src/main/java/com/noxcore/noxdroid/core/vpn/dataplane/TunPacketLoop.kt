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
        val transportConnected: Boolean,
        val reconnectAttempts: Long,
        val reconnectSuccesses: Long,
        val uplinkBytes: Long,
        val downlinkBytes: Long,
        val droppedForwardPackets: Long,
        val connectFailures: Long,
        val transientOpenDeferrals: Long,
        val quicFallbackSignals: Long,
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
            var reconnectAttempts = 0L
            var reconnectSuccesses = 0L
            var lastReconnectAttemptMs = 0L
            var lastTransportConnected = false

            if (!attemptTransportConnect(transportClient, "startup")) {
                reconnectAttempts += 1
                lastReconnectAttemptMs = System.currentTimeMillis()
                DiagnosticsLog.warn(TAG, "startup transport connect failed; VPN loop will keep retrying")
            } else {
                reconnectSuccesses += 1
                lastTransportConnected = true
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
            var lastLoggedOpenDeferrals = 0L
            var quicFallbackSignals = 0L
            var lastLoggedQuicFallbackSignals = 0L

            try {
                while (isActive) {
                    val read = input.read(buffer)
                    if (read <= 0) {
                        continue
                    }

                    totalPackets += 1
                    val parsed = PacketParser.parse(buffer, read)
                    val nowMs = System.currentTimeMillis()
                    val urgentReconnect = parsed is ParsedPacket.Ipv4Tcp &&
                        parsed.meta.syn &&
                        !parsed.meta.ack

                    var transportConnected = transportClient.isConnected()
                    if (!transportConnected &&
                        shouldAttemptReconnect(nowMs, lastReconnectAttemptMs, urgentReconnect)
                    ) {
                        val sinceLastAttemptMs = if (lastReconnectAttemptMs == 0L) {
                            -1L
                        } else {
                            nowMs - lastReconnectAttemptMs
                        }
                        DiagnosticsLog.info(
                            TAG,
                            "runtime reconnect attempt trigger urgent_syn=$urgentReconnect since_last_ms=$sinceLastAttemptMs"
                        )
                        reconnectAttempts += 1
                        lastReconnectAttemptMs = nowMs
                        val connected = attemptTransportConnect(transportClient, "runtime")
                        if (connected) {
                            reconnectSuccesses += 1
                            transportConnected = true
                        }
                    }
                    if (!lastTransportConnected && transportConnected) {
                        DiagnosticsLog.info(TAG, "transport continuity restored")
                    } else if (lastTransportConnected && !transportConnected) {
                        val resetCount = forwarder.handleTransportDisconnect("transport unavailable")
                        if (resetCount > 0) {
                            lastSummary = "transport down: reset $resetCount sessions"
                        }
                    }
                    lastTransportConnected = transportConnected

                    when (parsed) {
                        is ParsedPacket.NonIpv4 -> {
                            lastSummary = parsed.meta.summary
                        }

                        is ParsedPacket.Ipv4NonTcp -> {
                            ipv4Packets += 1
                            lastSummary = parsed.meta.summary
                        }

                        is ParsedPacket.Ipv4Udp -> {
                            ipv4Packets += 1
                            if (parsed.meta.destinationPort == HTTPS_PORT) {
                                val sent = sendUdpPortUnreachable(
                                    originalPacket = buffer,
                                    originalLength = read,
                                    meta = parsed.meta,
                                    writeToTun = { packet ->
                                        synchronized(outputLock) {
                                            output.write(packet)
                                            output.flush()
                                        }
                                    }
                                )
                                if (sent) {
                                    quicFallbackSignals += 1
                                    lastSummary = "udp/443 fallback signaled: ${parsed.meta.summary}"
                                } else {
                                    lastSummary = "udp/443 fallback signal failed: ${parsed.meta.summary}"
                                }
                            } else {
                                lastSummary = parsed.meta.summary
                            }
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
                        if (forwardStats.transientOpenDeferrals > lastLoggedOpenDeferrals) {
                            DiagnosticsLog.warn(
                                TAG,
                                "transient open deferrals increased to ${forwardStats.transientOpenDeferrals} last=$lastSummary"
                            )
                            lastLoggedOpenDeferrals = forwardStats.transientOpenDeferrals
                        }
                        if (quicFallbackSignals >= lastLoggedQuicFallbackSignals + 10L) {
                            DiagnosticsLog.info(
                                TAG,
                                "youtube-first udp/443 fallback signals=$quicFallbackSignals last=$lastSummary"
                            )
                            lastLoggedQuicFallbackSignals = quicFallbackSignals
                        }
                        onStats(
                            TunLoopStats(
                                totalPackets = totalPackets,
                                malformedPackets = malformedPackets,
                                ipv4Packets = ipv4Packets,
                                tcpPackets = tcpPackets,
                                activeTcpSessions = tracker.activeSessionCount(),
                                activeForwardSessions = forwardStats.activeForwardSessions,
                                transportConnected = transportClient.isConnected(),
                                reconnectAttempts = reconnectAttempts,
                                reconnectSuccesses = reconnectSuccesses,
                                uplinkBytes = forwardStats.uplinkBytes,
                                downlinkBytes = forwardStats.downlinkBytes,
                                droppedForwardPackets = forwardStats.droppedPackets,
                                connectFailures = forwardStats.connectFailures,
                                transientOpenDeferrals = forwardStats.transientOpenDeferrals,
                                quicFallbackSignals = quicFallbackSignals,
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
                if (isActive) {
                    onError(message)
                }
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
                        transportConnected = transportClient.isConnected(),
                        reconnectAttempts = reconnectAttempts,
                        reconnectSuccesses = reconnectSuccesses,
                        uplinkBytes = forwardStats.uplinkBytes,
                        downlinkBytes = forwardStats.downlinkBytes,
                        droppedForwardPackets = forwardStats.droppedPackets,
                        connectFailures = forwardStats.connectFailures,
                        transientOpenDeferrals = forwardStats.transientOpenDeferrals,
                        quicFallbackSignals = quicFallbackSignals,
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

    private fun attemptTransportConnect(
        transportClient: NoxTransportClient,
        phase: String
    ): Boolean {
        val connectResult = transportClient.connect()
        if (connectResult.isSuccess) {
            DiagnosticsLog.info(TAG, "transport connect succeeded phase=$phase")
            return true
        }
        val reason = connectResult.exceptionOrNull()?.message ?: "transport connect failed"
        DiagnosticsLog.warn(TAG, "transport connect failed phase=$phase reason=$reason; control path remains disconnected")
        return false
    }

    private fun shouldAttemptReconnect(
        nowMs: Long,
        lastReconnectAttemptMs: Long,
        urgentReconnect: Boolean
    ): Boolean {
        val intervalMs = if (urgentReconnect) {
            SYN_RECONNECT_INTERVAL_MS
        } else {
            TRANSPORT_RECONNECT_INTERVAL_MS
        }
        return nowMs - lastReconnectAttemptMs >= intervalMs
    }

    private fun sendUdpPortUnreachable(
        originalPacket: ByteArray,
        originalLength: Int,
        meta: UdpPacketMeta,
        writeToTun: (ByteArray) -> Unit
    ): Boolean {
        val totalLength = ((originalPacket[2].toInt() and 0xFF) shl 8) or (originalPacket[3].toInt() and 0xFF)
        if (totalLength <= 0 || totalLength > originalLength) {
            return false
        }
        val quoteBytes = minOf(totalLength, meta.ipHeaderLength + UDP_HEADER_BYTES)
        if (quoteBytes <= 0 || quoteBytes > totalLength) {
            return false
        }
        val icmpPayload = ByteArray(4 + quoteBytes)
        System.arraycopy(originalPacket, 0, icmpPayload, 4, quoteBytes)

        val ipHeaderLength = IPV4_HEADER_BYTES
        val icmpHeaderLength = ICMP_HEADER_BYTES
        val responseTotal = ipHeaderLength + icmpHeaderLength + icmpPayload.size
        val response = ByteArray(responseTotal)

        response[0] = 0x45.toByte()
        response[1] = 0
        write16(response, 2, responseTotal)
        write16(response, 4, 0)
        write16(response, 6, 0)
        response[8] = DEFAULT_TTL.toByte()
        response[9] = ICMP_PROTOCOL.toByte()
        write16(response, 10, 0)

        writeIpv4(response, 12, meta.destinationIp)
        writeIpv4(response, 16, meta.sourceIp)
        write16(response, 10, checksum(response, 0, ipHeaderLength))

        val icmpOffset = ipHeaderLength
        response[icmpOffset] = ICMP_DEST_UNREACHABLE.toByte()
        response[icmpOffset + 1] = ICMP_PORT_UNREACHABLE_CODE.toByte()
        write16(response, icmpOffset + 2, 0)
        write16(response, icmpOffset + 4, 0)
        write16(response, icmpOffset + 6, 0)
        System.arraycopy(icmpPayload, 0, response, icmpOffset + icmpHeaderLength, icmpPayload.size)
        write16(response, icmpOffset + 2, checksum(response, icmpOffset, icmpHeaderLength + icmpPayload.size))

        writeToTun(response)
        return true
    }

    private fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        while (index + 1 < offset + length) {
            val word = ((buffer[index].toInt() and 0xFF) shl 8) or (buffer[index + 1].toInt() and 0xFF)
            sum += word.toLong()
            sum = (sum and 0xFFFF) + (sum ushr 16)
            index += 2
        }
        if (index < offset + length) {
            sum += ((buffer[index].toInt() and 0xFF) shl 8).toLong()
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun write16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeIpv4(buffer: ByteArray, offset: Int, ip: String) {
        val parts = ip.split('.')
        if (parts.size != 4) {
            throw IllegalArgumentException("invalid IPv4 address: $ip")
        }
        for (i in 0 until 4) {
            val octet = parts[i].toIntOrNull() ?: throw IllegalArgumentException("invalid IPv4 address: $ip")
            if (octet !in 0..255) {
                throw IllegalArgumentException("invalid IPv4 address: $ip")
            }
            buffer[offset + i] = octet.toByte()
        }
    }

    companion object {
        private const val TAG = "TunPacketLoop"
        private const val STATS_EMIT_INTERVAL_MS = 1_000L
        private const val SESSION_CLEANUP_INTERVAL_MS = 10_000L
        private const val SESSION_IDLE_TIMEOUT_MS = 60_000L
        private const val FORWARD_IDLE_TIMEOUT_MS = 180_000L
        private const val TRANSPORT_RECONNECT_INTERVAL_MS = 3_000L
        private const val SYN_RECONNECT_INTERVAL_MS = 800L
        private const val HTTPS_PORT = 443
        private const val IPV4_HEADER_BYTES = 20
        private const val UDP_HEADER_BYTES = 8
        private const val ICMP_HEADER_BYTES = 8
        private const val ICMP_PROTOCOL = 1
        private const val ICMP_DEST_UNREACHABLE = 3
        private const val ICMP_PORT_UNREACHABLE_CODE = 3
        private const val DEFAULT_TTL = 64
    }
}
