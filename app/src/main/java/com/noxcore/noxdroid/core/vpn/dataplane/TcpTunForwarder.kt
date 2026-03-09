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
        val connectFailures: Long,
        val transientOpenDeferrals: Long
    )

    private val sessions = linkedMapOf<TcpSessionKey, ForwardSession>()
    private val synRetryStates = linkedMapOf<TcpSessionKey, SynRetryState>()
    private val quicFallbackTargets = linkedMapOf<HttpsTargetKey, Long>()
    private val ipIdCounter = AtomicInteger(Random.nextInt(0, 0xFFFF))

    private var uplinkBytes = 0L
    private var downlinkBytes = 0L
    private var droppedPackets = 0L
    private var connectFailures = 0L
    private var transientOpenDeferrals = 0L

    fun noteUdp443Fallback(destinationIp: String, destinationPort: Int = HTTPS_PORT) {
        if (destinationPort != HTTPS_PORT) {
            return
        }
        synchronized(sessions) {
            quicFallbackTargets[HttpsTargetKey(destinationIp, destinationPort)] = System.currentTimeMillis()
        }
    }

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
                DiagnosticsLog.info(
                    TAG,
                    "close decision stream=${session.streamId} initiator=client_rst send_frame=true"
                )
                closeSession(session, "client rst", sendCloseFrame = true)
                return true
            }

            var handled = false

            if (packet.payloadLength > 0) {
                handled = true
                val expectedSeq = session.clientNextSeq
                when {
                    packet.sequenceNumber == expectedSeq -> {
                        if (!forwardClientPayload(session, packet.payload)) {
                            return false
                        }
                        drainBufferedClientData(session)
                        sendTcp(
                            session = session,
                            seq = session.serverSeq,
                            ack = session.clientNextSeq,
                            flags = FLAG_ACK,
                            payload = ByteArray(0)
                        )
                    }
                    packet.sequenceNumber < expectedSeq -> {
                        // Duplicate or retransmitted segment; ack current sequence to advance sender.
                        sendTcp(
                            session = session,
                            seq = session.serverSeq,
                            ack = session.clientNextSeq,
                            flags = FLAG_ACK,
                            payload = ByteArray(0)
                        )
                        return true
                    }
                    else -> {
                        // Out-of-order segment; buffer within limits and ack current sequence.
                        val buffered = bufferOutOfOrderSegment(session, packet.sequenceNumber, packet.payload)
                        if (!buffered) {
                            droppedPackets += 1
                        }
                        sendTcp(
                            session = session,
                            seq = session.serverSeq,
                            ack = session.clientNextSeq,
                            flags = FLAG_ACK,
                            payload = ByteArray(0)
                        )
                        return true
                    }
                }
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
                connectFailures = connectFailures,
                transientOpenDeferrals = transientOpenDeferrals
            )
        }
    }

    fun handleTransportDisconnect(reason: String): Int {
        synchronized(sessions) {
            if (sessions.isEmpty()) {
                return 0
            }
            val detached = sessions.values.toList()
            detached.forEach { session ->
                initiateLocalFin(
                    session = session,
                    reason = "transport disconnected: $reason",
                    closeFrameReason = "transport disconnected: $reason",
                    sendCloseFrame = false
                )
            }
            DiagnosticsLog.warn(
                TAG,
                "transport disconnected; half-closed ${detached.size} active TCP sessions"
            )
            return detached.size
        }
    }

    fun expireIdle(nowMs: Long, idleMs: Long) {
        synchronized(sessions) {
            expireSynRetryStates(nowMs)
            expireQuicFallbackTargets(nowMs)
            val iterator = sessions.entries.iterator()
            while (iterator.hasNext()) {
                val session = iterator.next().value
                val idleDurationMs = nowMs - session.lastSeenMs
                val timeoutMs = if (session.remoteFinSent) {
                    FINAL_ACK_IDLE_TIMEOUT_MS
                } else if (session.clientHalfClosed) {
                    HALF_CLOSE_IDLE_TIMEOUT_MS
                } else {
                    idleMs
                }
                if (idleDurationMs > timeoutMs) {
                    if (!session.remoteFinSent) {
                        val reason = if (session.clientHalfClosed) {
                            "client half-close timeout"
                        } else {
                            "idle timeout"
                        }
                        DiagnosticsLog.info(
                            TAG,
                            "$reason stream=${session.streamId} ${session.key.clientIp}:${session.key.clientPort} -> ${session.key.serverIp}:${session.key.serverPort}"
                        )
                        initiateLocalFin(
                            session = session,
                            reason = reason,
                            closeFrameReason = reason,
                            sendCloseFrame = true
                        )
                        session.lastSeenMs = nowMs
                        continue
                    }
                    if (!session.closeFrameSent) {
                        DiagnosticsLog.info(
                            TAG,
                            "close decision stream=${session.streamId} initiator=final_ack_timeout send_frame=false"
                        )
                        transportClient.closeStream(session.streamId, "final ack timeout", sendFrame = false)
                        session.closeFrameSent = true
                    }
                    DiagnosticsLog.info(
                        TAG,
                        "final ack timeout stream=${session.streamId} ${session.key.clientIp}:${session.key.clientPort} -> ${session.key.serverIp}:${session.key.serverPort}"
                    )
                    iterator.remove()
                }
            }
        }
    }

    fun stop() {
        synchronized(sessions) {
            sessions.values.forEach { session ->
                DiagnosticsLog.info(
                    TAG,
                    "close decision stream=${session.streamId} initiator=forwarder_stop send_frame=true"
                )
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

        val nowMs = System.currentTimeMillis()
        if (shouldDeferSynOpenAttempt(packet, nowMs)) {
            transientOpenDeferrals += 1
            val retryState = synRetryStates[synRetryKey(packet)]
            val backoffRemainingMs = ((retryState?.backoffUntilMs ?: nowMs) - nowMs).coerceAtLeast(0L)
            DiagnosticsLog.info(
                TAG,
                "youtube-first deferring SYN open attempt target=${packet.destinationIp}:${packet.destinationPort} backoff_ms=$backoffRemainingMs"
            )
            return false
        }

        val target = "${packet.destinationIp}:${packet.destinationPort}"
        val openStartedAtMs = nowMs
        val openResult = transportClient.openStream(target)
        if (!openResult.ok) {
            connectFailures += 1
            droppedPackets += 1
            val reason = openResult.error ?: "open failed"
            Log.w(TAG, "open stream failed for $target: $reason")
            DiagnosticsLog.warn(TAG, "open stream failed target=$target reason=$reason")
            if (shouldDeferSynResetAfterOpenFailure(packet, reason, nowMs)) {
                transientOpenDeferrals += 1
                DiagnosticsLog.warn(
                    TAG,
                    "youtube-first deferring SYN reset target=$target reason=$reason"
                )
                return false
            }
            synRetryStates.remove(synRetryKey(packet))
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
        synRetryStates.remove(synRetryKey(packet))
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
            DiagnosticsLog.info(
                TAG,
                "remote close stream=${current.streamId} ${current.key.serverIp}:${current.key.serverPort} reason=$reason"
            )
            initiateLocalFin(
                session = current,
                reason = "remote close: $reason",
                closeFrameReason = reason,
                sendCloseFrame = false
            )
            current.lastSeenMs = System.currentTimeMillis()
            if (current.clientHalfClosed) {
                // Preserve a short grace window for the final ACK; cleanup falls back to idle expiry.
                Log.d(TAG, "Half-closed session awaiting final ACK: ${session.key} reason=$reason")
            }
        }
    }

    private fun initiateLocalFin(
        session: ForwardSession,
        reason: String,
        closeFrameReason: String,
        sendCloseFrame: Boolean
    ) {
        session.remoteStreamClosed = true
        if (!session.remoteFinSent) {
            sendTcp(
                session = session,
                seq = session.serverSeq,
                ack = session.clientNextSeq,
                flags = FLAG_ACK or FLAG_FIN,
                payload = ByteArray(0)
            )
            session.serverSeq = add32(session.serverSeq, 1)
            session.remoteFinSent = true
            DiagnosticsLog.info(
                TAG,
                "local FIN stream=${session.streamId} ${session.key.clientIp}:${session.key.clientPort} -> ${session.key.serverIp}:${session.key.serverPort} reason=$reason"
            )
        }
        if (!session.closeFrameSent) {
            DiagnosticsLog.info(
                TAG,
                "close decision stream=${session.streamId} initiator=initiate_local_fin send_frame=$sendCloseFrame"
            )
            transportClient.closeStream(session.streamId, closeFrameReason, sendFrame = sendCloseFrame)
            session.closeFrameSent = true
        }
    }

    private fun closeSession(session: ForwardSession, reason: String, sendCloseFrame: Boolean) {
        DiagnosticsLog.info(
            TAG,
            "close decision stream=${session.streamId} initiator=close_session send_frame=$sendCloseFrame reason=$reason"
        )
        if (sendCloseFrame) {
            if (!session.closeFrameSent) {
                transportClient.closeStream(session.streamId, reason, sendFrame = true)
                session.closeFrameSent = true
            }
        } else {
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

    private fun sendRst(session: ForwardSession) {
        sendRawTcp(
            sourceIp = session.key.serverIp,
            destinationIp = session.key.clientIp,
            sourcePort = session.key.serverPort,
            destinationPort = session.key.clientPort,
            sequenceNumber = session.serverSeq,
            acknowledgementNumber = session.clientNextSeq,
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

    private fun forwardClientPayload(session: ForwardSession, payload: ByteArray): Boolean {
        val sent = transportClient.sendData(session.streamId, payload)
        if (!sent) {
            val nowMs = System.currentTimeMillis()
            session.consecutiveWriteFailures += 1
            if (session.firstWriteFailureAtMs == 0L) {
                session.firstWriteFailureAtMs = nowMs
            }
            if (shouldTerminateForWriteFailure(session, nowMs)) {
                val connected = transportClient.isConnected()
                DiagnosticsLog.warn(
                    TAG,
                    "transport write failed terminal stream=${session.streamId} connected=$connected ${session.key.clientIp}:${session.key.clientPort} -> ${session.key.serverIp}:${session.key.serverPort}"
                )
                sendRst(session)
                closeSession(session, "transport write failed", sendCloseFrame = connected)
            } else {
                DiagnosticsLog.warn(
                    TAG,
                    "transport write failed transient stream=${session.streamId} ${session.key.clientIp}:${session.key.clientPort} -> ${session.key.serverIp}:${session.key.serverPort}"
                )
            }
            return false
        }
        session.consecutiveWriteFailures = 0
        session.firstWriteFailureAtMs = 0L
        session.clientNextSeq = add32(session.clientNextSeq, payload.size)
        uplinkBytes += payload.size.toLong()
        return true
    }

    private fun bufferOutOfOrderSegment(
        session: ForwardSession,
        sequenceNumber: Long,
        payload: ByteArray
    ): Boolean {
        if (payload.isEmpty()) {
            return true
        }
        val expected = session.clientNextSeq
        val distance = tcpSeqDistance(expected, sequenceNumber)
        if (distance < 0 || distance > MAX_OUT_OF_ORDER_BYTES) {
            return false
        }
        val existing = session.outOfOrderSegments[sequenceNumber]
        if (existing != null) {
            return true
        }
        if (session.outOfOrderSegments.size >= MAX_OUT_OF_ORDER_SEGMENTS) {
            return false
        }
        if (session.outOfOrderBytes + payload.size > MAX_OUT_OF_ORDER_BUFFER_BYTES) {
            return false
        }
        session.outOfOrderSegments[sequenceNumber] = payload
        session.outOfOrderBytes += payload.size
        return true
    }

    private fun drainBufferedClientData(session: ForwardSession) {
        while (true) {
            val nextPayload = session.outOfOrderSegments.remove(session.clientNextSeq) ?: break
            session.outOfOrderBytes -= nextPayload.size
            if (!forwardClientPayload(session, nextPayload)) {
                // If forwarding fails, keep the remainder buffered for retransmit.
                session.outOfOrderSegments[session.clientNextSeq] = nextPayload
                session.outOfOrderBytes += nextPayload.size
                break
            }
        }
    }

    private fun tcpSeqDistance(expected: Long, candidate: Long): Long {
        val diff = (candidate - expected) and 0xFFFF_FFFFL
        return if (diff and 0x8000_0000L != 0L) {
            diff - 0x1_0000_0000L
        } else {
            diff
        }
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

    private fun shouldTerminateForWriteFailure(session: ForwardSession, nowMs: Long): Boolean {
        val firstFailureMs = session.firstWriteFailureAtMs
        if (firstFailureMs == 0L) {
            return false
        }
        val failureDurationMs = nowMs - firstFailureMs
        if (transportClient.isConnected()) {
            return session.consecutiveWriteFailures >= MAX_CONNECTED_WRITE_FAILURES &&
                failureDurationMs >= CONNECTED_WRITE_FAILURE_GRACE_MS
        }
        return session.consecutiveWriteFailures >= MAX_TRANSIENT_WRITE_FAILURES &&
            failureDurationMs >= TRANSIENT_WRITE_FAILURE_GRACE_MS
    }

    private fun isTransientOpenFailure(reason: String): Boolean {
        val normalized = reason.lowercase()
        return normalized.contains("transport disconnected") ||
            normalized.contains("transport write failed") ||
            normalized.contains("transport read failed") ||
            normalized.contains("transport closed") ||
            normalized.contains("timeout")
    }

    private fun shouldDeferSynOpenAttempt(packet: TcpPacketMeta, nowMs: Long): Boolean {
        if (!isYoutubeFallbackTcp443(packet, nowMs)) {
            return false
        }
        val state = synRetryStates[synRetryKey(packet)] ?: return false
        return nowMs < state.backoffUntilMs
    }

    private fun shouldDeferSynResetAfterOpenFailure(
        packet: TcpPacketMeta,
        reason: String,
        nowMs: Long
    ): Boolean {
        if (!isYoutubeFallbackTcp443(packet, nowMs)) {
            return isTransientOpenFailure(reason)
        }
        if (!isRetryableHttpsOpenFailure(reason)) {
            return false
        }
        val key = synRetryKey(packet)
        val state = synRetryStates[key]
        if (state == null) {
            synRetryStates[key] = SynRetryState(
                firstFailureAtMs = nowMs,
                lastFailureAtMs = nowMs,
                failureCount = 1,
                backoffUntilMs = nowMs + synBackoffMs(1),
                lastReason = reason
            )
            return true
        }
        state.failureCount += 1
        state.lastFailureAtMs = nowMs
        state.lastReason = reason
        if (state.failureCount > YOUTUBE_HTTPS_SYN_MAX_DEFERRALS ||
            nowMs - state.firstFailureAtMs > YOUTUBE_HTTPS_SYN_RETRY_WINDOW_MS
        ) {
            return false
        }
        state.backoffUntilMs = nowMs + synBackoffMs(state.failureCount)
        return true
    }

    private fun expireSynRetryStates(nowMs: Long) {
        val iterator = synRetryStates.entries.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next().value
            if (nowMs - state.lastFailureAtMs > YOUTUBE_HTTPS_SYN_RETRY_STATE_IDLE_MS) {
                iterator.remove()
            }
        }
    }

    private fun expireQuicFallbackTargets(nowMs: Long) {
        val iterator = quicFallbackTargets.entries.iterator()
        while (iterator.hasNext()) {
            val markedAtMs = iterator.next().value
            if (nowMs - markedAtMs > QUIC_FALLBACK_TARGET_TTL_MS) {
                iterator.remove()
            }
        }
    }

    private fun isYoutubeFallbackTcp443(packet: TcpPacketMeta, nowMs: Long): Boolean {
        if (packet.destinationPort != HTTPS_PORT) {
            return false
        }
        val key = HttpsTargetKey(packet.destinationIp, packet.destinationPort)
        val markedAtMs = quicFallbackTargets[key]
        if (markedAtMs != null) {
            if (nowMs - markedAtMs > QUIC_FALLBACK_TARGET_TTL_MS) {
                quicFallbackTargets.remove(key)
            } else {
                return true
            }
        }
        return hasRecentFallbackSubnetMatch(packet.destinationIp, packet.destinationPort, nowMs)
    }

    private fun synRetryKey(packet: TcpPacketMeta): TcpSessionKey {
        if (packet.destinationPort == HTTPS_PORT) {
            return TcpSessionKey(
                clientIp = SYN_RETRY_ANY_CLIENT,
                clientPort = 0,
                serverIp = packet.destinationIp,
                serverPort = packet.destinationPort
            )
        }
        return sessionKey(packet)
    }

    private fun isRetryableHttpsOpenFailure(reason: String): Boolean {
        val normalized = reason.lowercase()
        if (normalized.contains("auth") || normalized.contains("unauthorized")) {
            return false
        }
        return true
    }

    private fun hasRecentFallbackSubnetMatch(destinationIp: String, destinationPort: Int, nowMs: Long): Boolean {
        val destinationPrefix = ipv4Prefix24(destinationIp) ?: return false
        for ((key, markedAtMs) in quicFallbackTargets) {
            if (key.destinationPort != destinationPort) {
                continue
            }
            if (nowMs - markedAtMs > QUIC_FALLBACK_SUBNET_MATCH_TTL_MS) {
                continue
            }
            val markedPrefix = ipv4Prefix24(key.destinationIp) ?: continue
            if (markedPrefix == destinationPrefix) {
                return true
            }
        }
        return false
    }

    private fun ipv4Prefix24(value: String): Int? {
        val octets = value.split('.')
        if (octets.size != 4) {
            return null
        }
        val o1 = octets[0].toIntOrNull() ?: return null
        val o2 = octets[1].toIntOrNull() ?: return null
        val o3 = octets[2].toIntOrNull() ?: return null
        if (o1 !in 0..255 || o2 !in 0..255 || o3 !in 0..255) {
            return null
        }
        return (o1 shl 16) or (o2 shl 8) or o3
    }

    private fun synBackoffMs(failureCount: Int): Long {
        val clamped = failureCount.coerceAtLeast(1)
        val shift = (clamped - 1).coerceAtMost(4)
        val exponential = HTTPS_SYN_BASE_BACKOFF_MS shl shift
        return minOf(exponential.toLong(), HTTPS_SYN_MAX_BACKOFF_MS)
    }

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
        var closeFrameSent: Boolean = false,
        var consecutiveWriteFailures: Int = 0,
        var firstWriteFailureAtMs: Long = 0L,
        val outOfOrderSegments: MutableMap<Long, ByteArray> = linkedMapOf(),
        var outOfOrderBytes: Int = 0
    )

    private data class SynRetryState(
        val firstFailureAtMs: Long,
        var lastFailureAtMs: Long,
        var failureCount: Int,
        var backoffUntilMs: Long,
        var lastReason: String
    )

    private data class HttpsTargetKey(
        val destinationIp: String,
        val destinationPort: Int
    )

    companion object {
        private const val TAG = "TcpTunForwarder"
        private const val TCP_PROTOCOL = 6
        private const val DEFAULT_TTL = 64
        private const val DEFAULT_WINDOW = 65535
        private const val MAX_PAYLOAD_BYTES_PER_PACKET = 1360
        private const val HALF_CLOSE_IDLE_TIMEOUT_MS = 60_000L
        private const val FINAL_ACK_IDLE_TIMEOUT_MS = 120_000L
        private const val MAX_CONNECTED_WRITE_FAILURES = 6
        private const val MAX_TRANSIENT_WRITE_FAILURES = 7
        private const val CONNECTED_WRITE_FAILURE_GRACE_MS = 2_500L
        private const val TRANSIENT_WRITE_FAILURE_GRACE_MS = 6_000L
        private const val MAX_OUT_OF_ORDER_SEGMENTS = 64
        private const val MAX_OUT_OF_ORDER_BUFFER_BYTES = 256 * 1024
        private const val MAX_OUT_OF_ORDER_BYTES = 512 * 1024
        private const val HTTPS_PORT = 443
        private const val YOUTUBE_HTTPS_SYN_MAX_DEFERRALS = 8
        private const val YOUTUBE_HTTPS_SYN_RETRY_WINDOW_MS = 40_000L
        private const val HTTPS_SYN_BASE_BACKOFF_MS = 500
        private const val HTTPS_SYN_MAX_BACKOFF_MS = 8_000L
        private const val YOUTUBE_HTTPS_SYN_RETRY_STATE_IDLE_MS = 90_000L
        private const val QUIC_FALLBACK_TARGET_TTL_MS = 120_000L
        private const val QUIC_FALLBACK_SUBNET_MATCH_TTL_MS = 30_000L
        private const val SYN_RETRY_ANY_CLIENT = "*"

        private const val FLAG_FIN = 0x01
        private const val FLAG_RST = 0x04
        private const val FLAG_PSH = 0x08
        private const val FLAG_ACK = 0x10
        private const val FLAG_SYN = 0x02
    }
}
