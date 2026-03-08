package com.noxcore.noxdroid.core.vpn.dataplane

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class TcpTunForwarder(
    private val writeToTun: (ByteArray) -> Unit,
    private val protectSocket: (Socket) -> Boolean
) {

    data class ForwardStats(
        val activeForwardSessions: Int,
        val uplinkBytes: Long,
        val downlinkBytes: Long,
        val droppedPackets: Long,
        val connectFailures: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                return false
            }

            if (packet.rst) {
                closeSession(session, "client rst")
                return true
            }

            var handled = false

            if (packet.payloadLength > 0) {
                handled = true
                val expectedSeq = session.clientNextSeq
                if (packet.sequenceNumber == expectedSeq) {
                    try {
                        session.serverOutput.write(packet.payload)
                        session.serverOutput.flush()
                        session.clientNextSeq = add32(session.clientNextSeq, packet.payloadLength)
                        uplinkBytes += packet.payloadLength.toLong()
                    } catch (_: Exception) {
                        closeSession(session, "upstream write failed")
                        return true
                    }
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
                session.clientNextSeq = add32(session.clientNextSeq, 1)
                try {
                    session.socket.shutdownOutput()
                } catch (_: Exception) {
                }
                sendTcp(
                    session = session,
                    seq = session.serverSeq,
                    ack = session.clientNextSeq,
                    flags = FLAG_ACK,
                    payload = ByteArray(0)
                )
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
                if (nowMs - session.lastSeenMs > idleMs) {
                    session.readerJob.cancel()
                    closeQuietly(session.socket)
                    iterator.remove()
                }
            }
        }
    }

    fun stop() {
        synchronized(sessions) {
            sessions.values.forEach { session ->
                session.readerJob.cancel()
                closeQuietly(session.socket)
            }
            sessions.clear()
        }
        scope.cancel()
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

        val socket = Socket()
        if (!protectSocket(socket)) {
            closeQuietly(socket)
            connectFailures += 1
            droppedPackets += 1
            return false
        }

        try {
            socket.tcpNoDelay = true
            socket.soTimeout = SOCKET_READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(packet.destinationIp, packet.destinationPort), CONNECT_TIMEOUT_MS)
        } catch (_: Exception) {
            closeQuietly(socket)
            connectFailures += 1
            droppedPackets += 1
            return false
        }

        val serverIsn = Random.nextInt().toLong() and 0xFFFF_FFFFL
        val session = ForwardSession(
            key = key,
            socket = socket,
            serverInput = socket.getInputStream(),
            serverOutput = socket.getOutputStream(),
            clientNextSeq = add32(packet.sequenceNumber, 1),
            serverSeq = add32(serverIsn, 1),
            serverIsn = serverIsn,
            lastSeenMs = System.currentTimeMillis(),
            readerJob = Job()
        )

        val readerJob = scope.launch {
            relayServerToTun(session)
        }
        session.readerJob = readerJob
        sessions[key] = session

        sendTcp(
            session = session,
            seq = serverIsn,
            ack = session.clientNextSeq,
            flags = FLAG_SYN or FLAG_ACK,
            payload = ByteArray(0)
        )

        return true
    }

    private fun relayServerToTun(session: ForwardSession) {
        val buffer = ByteArray(MAX_RELAY_CHUNK_BYTES)
        try {
            while (scope.isActive) {
                val read = session.serverInput.read(buffer)
                if (read < 0) {
                    break
                }
                if (read == 0) {
                    continue
                }

                val payload = buffer.copyOf(read)
                synchronized(sessions) {
                    if (!sessions.containsKey(session.key)) {
                        return
                    }
                    sendTcp(
                        session = session,
                        seq = session.serverSeq,
                        ack = session.clientNextSeq,
                        flags = FLAG_ACK or FLAG_PSH,
                        payload = payload
                    )
                    session.serverSeq = add32(session.serverSeq, read)
                    session.lastSeenMs = System.currentTimeMillis()
                    downlinkBytes += read.toLong()
                }
            }

            synchronized(sessions) {
                if (!sessions.containsKey(session.key)) {
                    return
                }
                sendTcp(
                    session = session,
                    seq = session.serverSeq,
                    ack = session.clientNextSeq,
                    flags = FLAG_ACK or FLAG_FIN,
                    payload = ByteArray(0)
                )
                session.serverSeq = add32(session.serverSeq, 1)
                closeSession(session, "upstream eof")
            }
        } catch (_: Exception) {
            synchronized(sessions) {
                if (!sessions.containsKey(session.key)) {
                    return
                }
                closeSession(session, "upstream read failed")
            }
        }
    }

    private fun closeSession(session: ForwardSession, reason: String) {
        session.readerJob.cancel()
        closeQuietly(session.socket)
        sessions.remove(session.key)
        Log.d(TAG, "Closed session ${session.key.clientIp}:${session.key.clientPort} -> ${session.key.serverIp}:${session.key.serverPort}: $reason")
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

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun add32(value: Long, delta: Int): Long = (value + delta.toLong()) and 0xFFFF_FFFFL

    private data class ForwardSession(
        val key: TcpSessionKey,
        val socket: Socket,
        val serverInput: InputStream,
        val serverOutput: OutputStream,
        var clientNextSeq: Long,
        var serverSeq: Long,
        val serverIsn: Long,
        var lastSeenMs: Long,
        var readerJob: Job
    )

    companion object {
        private const val TAG = "TcpTunForwarder"

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SOCKET_READ_TIMEOUT_MS = 30_000
        private const val MAX_RELAY_CHUNK_BYTES = 4096
        private const val TCP_PROTOCOL = 6
        private const val DEFAULT_TTL = 64
        private const val DEFAULT_WINDOW = 65535

        private const val FLAG_FIN = 0x01
        private const val FLAG_PSH = 0x08
        private const val FLAG_ACK = 0x10
        private const val FLAG_SYN = 0x02
    }
}
