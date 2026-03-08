package com.noxcore.noxdroid.core.vpn.dataplane

import android.util.Log
import com.noxcore.noxdroid.core.connection.NoxTransportClient
import com.noxcore.noxdroid.core.diagnostics.DiagnosticsLog
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class TcpTunForwarder(
    private val writeToTun: (ByteArray) -> Unit,
    private val transportClient: NoxTransportClient
) {

    data class ForwardStats(
        val activeForwardSessions: Int,
        val uplinkBytes: Long,
        val downlinkBytes: Long,
        val droppedPackets: Long,
        val connectFailures: Long
    )

    private val sessions = linkedMapOf<TcpSessionKey, ForwardSession>()
    private val ipIdCounter = AtomicInteger(Random.nextInt(0, 0xFFFF))

    private var uplinkBytes = 0L
    private var downlinkBytes = 0L
    private var droppedPackets = 0L
    private var connectFailures = 0L

    fun handleClientPacket(packet: TcpPacketMeta): Boolean {
        synchronized(sessions) {
            if (packet.syn && !packet.ack) {
                return handleSyn(packet)
            }

            val key = sessionKey(packet)
            val session = sessions[key] ?: run {
                droppedPackets += 1
                val shouldSendRst = !packet.rst && (packet.syn || packet.fin || packet.payloadLength > 0)
                if (shouldSendRst) {
                    sendRstForUnknownSession(packet)
                }
                return false
            }

            if (packet.rst) {
                closeSession(session, "client rst", sendCloseFrame = true)
                return true
            }

            var handled = false

            if (packet.payloadLength > 0) {
                handled = true
                val expectedSeq = session.clientNextSeq
                if (packet.sequenceNumber == expectedSeq) {
                    val sent = transportClient.sendData(session.streamId, packet.payload)
                    if (!sent) {
                        DiagnosticsLog.warn(
                            TAG,
                            "transport write failed stream=${session.streamId} ${session.key.clientIp}:${session.key.clientPort} -> ${session.key.serverIp}:${session.key.serverPort}"
                        )
                        closeSession(session, "transport write failed", sendCloseFrame = true)
                        return true
                    }
                    session.clientNextSeq = add32(session.clientNextSeq, packet.payloadLength)
                    uplinkBytes += packet.payloadLength.toLong()
                }
                sendTcp(
                    session = session,
                    seq = session.serverSeq,
                    ack = session.clientNextSeq,
                    flags = FLAG_ACK,
                    payload = ByteArray(0)
                )
            }

            if (packet.fin) {
                handled = true
                if (!session.clientHalfClosed) {
                    session.clientHalfClosed = true
                    session.clientNextSeq = add32(session.clientNextSeq, 1)
                    sendTcp(
                        session = session,
                        seq = session.serverSeq,
                        ack = session.clientNextSeq,
                        flags = FLAG_ACK,
                        payload = ByteArray(0)
                    )
                    DiagnosticsLog.info(
                        TAG,
                        "client half-close stream=${session.streamId} ${session.key.clientIp}:${session.key.clientPort} -> ${session.key.serverIp}:${session.key.serverPort}"
                    )
                }
            }

            if (packet.ack && session.remoteFinSent &&
                packet.acknowledgementNumber == session.serverSeq
            ) {
                closeSession(session, "client acked remote fin", sendCloseFrame = false)
                return true
            }

            session.lastSeenMs = System.currentTimeMillis()
            return handled || packet.ack
        }
    }

    fun stats(): ForwardStats {
        synchronized(sessions) {
            return ForwardStats(
                activeForwardSessions = sessions.size,
                uplinkBytes = uplinkBytes,
                downlinkBytes = downlinkBytes,
                droppedPackets = droppedPackets,
                connectFailures = connectFailures
            )
        }
    }

    fun expireIdle(nowMs: Long, idleMs: Long) {
        synchronized(sessions) {
            val iterator = sessions.entries.iterator()
            while (iterator.hasNext()) {
                val session = iterator.next().value
                val idleDurationMs = nowMs - session.lastSeenMs
                val timeoutMs = if (session.clientHalfClosed) {
                    HALF_CLOSE_IDLE_TIMEOUT_MS
                } else {
                    idleMs
                }
                if (idleDurationMs > timeoutMs) {
                    if (!session.closeFrameSent) {
                        val reason = if (session.clientHalfClosed) {
                            "client half-close timeout"
                        } else {
                            "idle timeout"
                        }
                        DiagnosticsLog.info(
                            TAG,
                            "$reason stream=${session.streamId} ${session.key.clientIp}:${session.key.clientPort} -> ${session.key.serverIp}:${session.key.serverPort}"
                        )
                        transportClient.closeStream(session.streamId, reason, sendFrame = true)
                        session.closeFrameSent = true
                    }
                    iterator.remove()
                }
            }
        }
    }

    fun stop() {
        synchronized(sessions) {
            sessions.values.forEach { session ->
                transportClient.closeStream(session.streamId, "forwarder stopped", sendFrame = true)
            }
            sessions.clear()
        }
        DiagnosticsLog.info(TAG, "forwarder stopped")
        transportClient.close()
    }

    private fun handleSyn(packet: TcpPacketMeta): Boolean {
        val key = sessionKey(packet)
        val existing = sessions[key]
        if (existing != null) {
            sendTcp(
                session = existing,
                seq = existing.serverIsn,
                ack = existing.clientNextSeq,
                flags = FLAG_SYN or FLAG_ACK,
                payload = ByteArray(0)
            )
            return true
        }

        val target = "${packet.destinationIp}:${packet.destinationPort}"
        val openStartedAtMs = System.currentTimeMillis()
        val openResult = transportClient.openStream(target)
        if (!openResult.ok) {
            connectFailures += 1
            droppedPackets += 1
            val reason = openResult.error ?: "open failed"
            Log.w(TAG, "open stream failed for $target: $reason")
            DiagnosticsLog.warn(TAG, "open stream failed target=$target reason=$reason")
            sendRstForFailedOpen(packet)
            return false
        }
        val openLatencyMs = System.currentTimeMillis() - openStartedAtMs

        val serverIsn = Random.nextInt().toLong() and 0xFFFF_FFFFL
        val session = ForwardSession(
            key = key,
            streamId = openResult.streamId,
            clientNextSeq = add32(packet.sequenceNumber, 1),
            serverSeq = add32(serverIsn, 1),
            serverIsn = serverIsn,
            lastSeenMs = System.currentTimeMillis()
        )

        sessions[key] = session
        DiagnosticsLog.info(
            TAG,
            "stream opened stream=${session.streamId} target=$target open_latency_ms=$openLatencyMs"
        )
        transportClient.registerCallbacks(
            streamId = session.streamId,
            onData = { data -> onTransportData(session, data) },
            onClose = { reason -> onTransportClose(session, reason) }
        )

        sendTcp(
            session = session,
            seq = serverIsn,
            ack = session.clientNextSeq,
            flags = FLAG_SYN or FLAG_ACK,
            payload = ByteArray(0)
        )

        return true
    }

    private fun onTransportData(session: ForwardSession, data: ByteArray) {
        if (data.isEmpty()) {
            return
        }
        synchronized(sessions) {
            val current = sessions[session.key] ?: return
            if (current.streamId != session.streamId) {
                return
            }
            if (current.remoteStreamClosed) {
                return
            }
            var offset = 0
            while (offset < data.size) {
                val chunkSize = minOf(data.size - offset, MAX_PAYLOAD_BYTES_PER_PACKET)
                val chunk = data.copyOfRange(offset, offset + chunkSize)
                sendTcp(
                    session = current,
                    seq = current.serverSeq,
                    ack = current.clientNextSeq,
                    flags = FLAG_ACK or FLAG_PSH,
                    payload = chunk
                )
                current.serverSeq = add32(current.serverSeq, chunkSize)
                offset += chunkSize
            }
            current.lastSeenMs = System.currentTimeMillis()
            downlinkBytes += data.size.toLong()
        }
    }

    private fun onTransportClose(session: ForwardSession, reason: String) {
        synchronized(sessions) {
            val current = sessions[session.key] ?: return
            if (current.streamId != session.streamId) {
                return
            }
            current.remoteStreamClosed = true
            if (!current.remoteFinSent) {
                DiagnosticsLog.info(
                    TAG,
                    "remote close stream=${current.streamId} ${current.key.serverIp}:${current.key.serverPort} reason=$reason"
                )
                sendTcp(
                    session = current,
                    seq = current.serverSeq,
                    ack = current.clientNextSeq,
                    flags = FLAG_ACK or FLAG_FIN,
                    payload = ByteArray(0)
                )
                current.serverSeq = add32(current.serverSeq, 1)
                current.remoteFinSent = true
            }
            current.lastSeenMs = System.currentTimeMillis()
            if (current.clientHalfClosed) {
                // Preserve a short grace window for the final ACK; cleanup falls back to idle expiry.
                Log.d(TAG, "Half-closed session awaiting final ACK: ${session.key} reason=$reason")
            }
        }
    }

    private fun closeSession(session: ForwardSession, reason: String, sendCloseFrame: Boolean) {
        if (sendCloseFrame && !session.closeFrameSent) {
            transportClient.closeStream(session.streamId, reason, sendFrame = true)
            session.closeFrameSent = true
        } else if (!sendCloseFrame) {
            transportClient.closeStream(session.streamId, reason, sendFrame = false)
        }
        sessions.remove(session.key)
        Log.d(
            TAG,
            "Closed session ${session.key.clientIp}:${session.key.clientPort} -> " +
                "${session.key.serverIp}:${session.key.serverPort}: $reason"
        )
        DiagnosticsLog.info(
            TAG,
            "closed stream=${session.streamId} ${session.key.clientIp}:${session.key.clientPort} -> ${session.key.serverIp}:${session.key.serverPort} reason=$reason"
        )
    }

    private fun sendRstForUnknownSession(packet: TcpPacketMeta) {
        val ackValue = if (packet.payloadLength > 0 || packet.fin || packet.syn) {
            add32(packet.sequenceNumber, packet.payloadLength + if (packet.fin || packet.syn) 1 else 0)
        } else {
            packet.sequenceNumber
        }
        val rstFlags = if (packet.ack) FLAG_RST else FLAG_RST or FLAG_ACK
        val rstAck = if (packet.ack) 0L else ackValue
        sendRawTcp(
            sourceIp = packet.destinationIp,
            destinationIp = packet.sourceIp,
            sourcePort = packet.destinationPort,
            destinationPort = packet.sourcePort,
            sequenceNumber = if (packet.ack) packet.acknowledgementNumber else 0L,
            acknowledgementNumber = rstAck,
            flags = rstFlags,
            payload = ByteArray(0)
        )
    }

    private fun sendRstForFailedOpen(packet: TcpPacketMeta) {
        sendRawTcp(
            sourceIp = packet.destinationIp,
            destinationIp = packet.sourceIp,
            sourcePort = packet.destinationPort,
            destinationPort = packet.sourcePort,
            sequenceNumber = 0L,
            acknowledgementNumber = add32(packet.sequenceNumber, 1),
            flags = FLAG_RST or FLAG_ACK,
            payload = ByteArray(0)
        )
    }

    private fun sendRawTcp(
        sourceIp: String,
        destinationIp: String,
        sourcePort: Int,
        destinationPort: Int,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        flags: Int,
        payload: ByteArray
    ) {
        val packet = buildTcpIpv4Packet(
            sourceIp = sourceIp,
            destinationIp = destinationIp,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            flags = flags,
            payload = payload
        )
        writeToTun(packet)
    }

    private fun sendTcp(
        session: ForwardSession,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray
    ) {
        val packet = buildTcpIpv4Packet(
            sourceIp = session.key.serverIp,
            destinationIp = session.key.clientIp,
            sourcePort = session.key.serverPort,
            destinationPort = session.key.clientPort,
            sequenceNumber = seq,
            acknowledgementNumber = ack,
            flags = flags,
            payload = payload
        )
        writeToTun(packet)
    }

    private fun buildTcpIpv4Packet(
        sourceIp: String,
        destinationIp: String,
        sourcePort: Int,
        destinationPort: Int,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLength = 20
        val tcpHeaderLength = 20
        val totalLength = ipHeaderLength + tcpHeaderLength + payload.size
        val packet = ByteArray(totalLength)

        val src = InetAddress.getByName(sourceIp).address
        val dst = InetAddress.getByName(destinationIp).address

        packet[0] = 0x45.toByte()
        packet[1] = 0
        write16(packet, 2, totalLength)
        write16(packet, 4, nextIpId())
        write16(packet, 6, 0x4000)
        packet[8] = DEFAULT_TTL.toByte()
        packet[9] = TCP_PROTOCOL.toByte()
        write16(packet, 10, 0)
        System.arraycopy(src, 0, packet, 12, 4)
        System.arraycopy(dst, 0, packet, 16, 4)

        write16(packet, 20, sourcePort)
        write16(packet, 22, destinationPort)
        write32(packet, 24, sequenceNumber)
        write32(packet, 28, acknowledgementNumber)
        packet[32] = (tcpHeaderLength shl 2).toByte()
        packet[33] = flags.toByte()
        write16(packet, 34, DEFAULT_WINDOW)
        write16(packet, 36, 0)
        write16(packet, 38, 0)

        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, ipHeaderLength + tcpHeaderLength, payload.size)
        }

        val ipChecksum = checksum(packet, 0, ipHeaderLength)
        write16(packet, 10, ipChecksum)

        val tcpChecksum = tcpChecksum(src, dst, packet, 20, tcpHeaderLength + payload.size)
        write16(packet, 36, tcpChecksum)

        return packet
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

    private fun tcpChecksum(src: ByteArray, dst: ByteArray, tcpSegment: ByteArray, offset: Int, length: Int): Int {
        val pseudo = ByteArray(12 + length)
        System.arraycopy(src, 0, pseudo, 0, 4)
        System.arraycopy(dst, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = TCP_PROTOCOL.toByte()
        write16(pseudo, 10, length)
        System.arraycopy(tcpSegment, offset, pseudo, 12, length)
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun write16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private fun write32(buffer: ByteArray, offset: Int, value: Long) {
        val v = value and 0xFFFF_FFFFL
        buffer[offset] = ((v ushr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        buffer[offset + 3] = (v and 0xFF).toByte()
    }

    private fun nextIpId(): Int = ipIdCounter.incrementAndGet() and 0xFFFF

    private fun sessionKey(packet: TcpPacketMeta): TcpSessionKey {
        return TcpSessionKey(
            clientIp = packet.sourceIp,
            clientPort = packet.sourcePort,
            serverIp = packet.destinationIp,
            serverPort = packet.destinationPort
        )
    }

    private fun add32(value: Long, delta: Int): Long = (value + delta.toLong()) and 0xFFFF_FFFFL

    private data class ForwardSession(
        val key: TcpSessionKey,
        val streamId: Long,
        var clientNextSeq: Long,
        var serverSeq: Long,
        val serverIsn: Long,
        var lastSeenMs: Long,
        var clientHalfClosed: Boolean = false,
        var remoteStreamClosed: Boolean = false,
        var remoteFinSent: Boolean = false,
        var closeFrameSent: Boolean = false
    )

    companion object {
        private const val TAG = "TcpTunForwarder"
        private const val TCP_PROTOCOL = 6
        private const val DEFAULT_TTL = 64
        private const val DEFAULT_WINDOW = 65535
        private const val MAX_PAYLOAD_BYTES_PER_PACKET = 1360
        private const val HALF_CLOSE_IDLE_TIMEOUT_MS = 15_000L

        private const val FLAG_FIN = 0x01
        private const val FLAG_RST = 0x04
        private const val FLAG_PSH = 0x08
        private const val FLAG_ACK = 0x10
        private const val FLAG_SYN = 0x02
    }
}
