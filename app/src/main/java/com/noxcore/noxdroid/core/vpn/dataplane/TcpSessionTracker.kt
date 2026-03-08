package com.noxcore.noxdroid.core.vpn.dataplane

data class TcpSessionKey(
    val clientIp: String,
    val clientPort: Int,
    val serverIp: String,
    val serverPort: Int
)

data class TcpSessionSnapshot(
    val key: TcpSessionKey,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val packetCount: Long,
    val byteCount: Long,
    val isClosed: Boolean
)

class TcpSessionTracker {
    private val sessions = linkedMapOf<TcpSessionKey, MutableTcpSession>()

    fun observe(packet: TcpPacketMeta, nowMs: Long): TcpSessionSnapshot {
        val key = TcpSessionKey(
            clientIp = packet.sourceIp,
            clientPort = packet.sourcePort,
            serverIp = packet.destinationIp,
            serverPort = packet.destinationPort
        )

        val session = sessions[key]
        if (session == null) {
            val created = MutableTcpSession(
                key = key,
                firstSeenMs = nowMs,
                lastSeenMs = nowMs,
                packetCount = 1,
                byteCount = packet.byteCount.toLong(),
                isClosed = packet.fin || packet.rst
            )
            sessions[key] = created
            return created.snapshot()
        }

        session.lastSeenMs = nowMs
        session.packetCount += 1
        session.byteCount += packet.byteCount.toLong()
        if (packet.fin || packet.rst) {
            session.isClosed = true
        }

        return session.snapshot()
    }

    fun expireClosedAndIdle(nowMs: Long, idleMs: Long): Int {
        val iterator = sessions.entries.iterator()
        var removed = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val session = entry.value
            val shouldRemove = session.isClosed || (nowMs - session.lastSeenMs) > idleMs
            if (shouldRemove) {
                iterator.remove()
                removed += 1
            }
        }
        return removed
    }

    fun activeSessionCount(): Int = sessions.size

    private data class MutableTcpSession(
        val key: TcpSessionKey,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
        var packetCount: Long,
        var byteCount: Long,
        var isClosed: Boolean
    ) {
        fun snapshot(): TcpSessionSnapshot {
            return TcpSessionSnapshot(
                key = key,
                firstSeenMs = firstSeenMs,
                lastSeenMs = lastSeenMs,
                packetCount = packetCount,
                byteCount = byteCount,
                isClosed = isClosed
            )
        }
    }
}
