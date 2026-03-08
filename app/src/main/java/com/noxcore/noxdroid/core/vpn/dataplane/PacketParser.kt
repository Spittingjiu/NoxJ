package com.noxcore.noxdroid.core.vpn.dataplane

import java.net.InetAddress

data class PacketMeta(
    val byteCount: Int,
    val summary: String
)

data class TcpPacketMeta(
    val byteCount: Int,
    val sourceIp: String,
    val destinationIp: String,
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Long,
    val acknowledgementNumber: Long,
    val syn: Boolean,
    val ack: Boolean,
    val fin: Boolean,
    val rst: Boolean,
    val payloadLength: Int,
    val ipHeaderLength: Int,
    val tcpHeaderLength: Int,
    val payloadOffset: Int,
    val payload: ByteArray,
    val summary: String
)

sealed class ParsedPacket {
    data class NonIpv4(val meta: PacketMeta) : ParsedPacket()
    data class Ipv4NonTcp(val meta: PacketMeta, val protocol: Int) : ParsedPacket()
    data class Ipv4Tcp(val meta: TcpPacketMeta) : ParsedPacket()
    data class Malformed(val reason: String) : ParsedPacket()
}

object PacketParser {

    fun parse(buffer: ByteArray, length: Int): ParsedPacket {
        if (length <= 0) {
            return ParsedPacket.Malformed("empty packet")
        }

        val version = (buffer[0].toInt() ushr 4) and 0x0F
        if (version != IPV4_VERSION) {
            return ParsedPacket.NonIpv4(PacketMeta(length, "non-ipv4 version=$version"))
        }

        if (length < IPV4_MIN_HEADER_BYTES) {
            return ParsedPacket.Malformed("short ipv4 header: $length")
        }

        val ihlWords = buffer[0].toInt() and 0x0F
        val ipv4HeaderBytes = ihlWords * 4
        if (ihlWords < 5 || ipv4HeaderBytes > length) {
            return ParsedPacket.Malformed("invalid ipv4 ihl=$ihlWords")
        }

        val totalLength = u16(buffer, 2)
        if (totalLength < ipv4HeaderBytes || totalLength > length) {
            return ParsedPacket.Malformed(
                "invalid ipv4 total_length=$totalLength packet_bytes=$length"
            )
        }

        val protocol = u8(buffer, 9)
        val sourceIp = ipv4(buffer, 12)
        val destinationIp = ipv4(buffer, 16)

        if (protocol != TCP_PROTOCOL_NUMBER) {
            return ParsedPacket.Ipv4NonTcp(
                meta = PacketMeta(
                    byteCount = totalLength,
                    summary = "ipv4 proto=$protocol $sourceIp->$destinationIp"
                ),
                protocol = protocol
            )
        }

        val tcpOffset = ipv4HeaderBytes
        if (totalLength < tcpOffset + TCP_MIN_HEADER_BYTES) {
            return ParsedPacket.Malformed("short tcp header")
        }

        val sourcePort = u16(buffer, tcpOffset)
        val destinationPort = u16(buffer, tcpOffset + 2)
        val sequenceNumber = u32(buffer, tcpOffset + 4)
        val acknowledgementNumber = u32(buffer, tcpOffset + 8)
        val dataOffsetWords = (u8(buffer, tcpOffset + 12) ushr 4) and 0x0F
        val tcpHeaderBytes = dataOffsetWords * 4
        if (dataOffsetWords < 5 || tcpOffset + tcpHeaderBytes > totalLength) {
            return ParsedPacket.Malformed("invalid tcp data offset=$dataOffsetWords")
        }

        val flags = u8(buffer, tcpOffset + 13)
        val syn = flags and 0x02 != 0
        val ack = flags and 0x10 != 0
        val fin = flags and 0x01 != 0
        val rst = flags and 0x04 != 0

        val payloadOffset = tcpOffset + tcpHeaderBytes
        val payloadLength = totalLength - payloadOffset
        val payload = if (payloadLength > 0) {
            buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength)
        } else {
            ByteArray(0)
        }

        val summary =
            "tcp $sourceIp:$sourcePort->$destinationIp:$destinationPort " +
                "syn=$syn ack=$ack fin=$fin rst=$rst payload=$payloadLength"

        return ParsedPacket.Ipv4Tcp(
            TcpPacketMeta(
                byteCount = totalLength,
                sourceIp = sourceIp,
                destinationIp = destinationIp,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = acknowledgementNumber,
                syn = syn,
                ack = ack,
                fin = fin,
                rst = rst,
                payloadLength = payloadLength,
                ipHeaderLength = ipv4HeaderBytes,
                tcpHeaderLength = tcpHeaderBytes,
                payloadOffset = payloadOffset,
                payload = payload,
                summary = summary
            )
        )
    }

    private fun ipv4(buffer: ByteArray, offset: Int): String {
        val bytes = byteArrayOf(
            buffer[offset],
            buffer[offset + 1],
            buffer[offset + 2],
            buffer[offset + 3]
        )
        return InetAddress.getByAddress(bytes).hostAddress ?: "0.0.0.0"
    }

    private fun u8(buffer: ByteArray, offset: Int): Int = buffer[offset].toInt() and 0xFF

    private fun u16(buffer: ByteArray, offset: Int): Int {
        return (u8(buffer, offset) shl 8) or u8(buffer, offset + 1)
    }

    private fun u32(buffer: ByteArray, offset: Int): Long {
        return (
            (u8(buffer, offset).toLong() shl 24) or
                (u8(buffer, offset + 1).toLong() shl 16) or
                (u8(buffer, offset + 2).toLong() shl 8) or
                u8(buffer, offset + 3).toLong()
            ) and 0xFFFF_FFFFL
    }

    private const val IPV4_VERSION = 4
    private const val IPV4_MIN_HEADER_BYTES = 20
    private const val TCP_MIN_HEADER_BYTES = 20
    private const val TCP_PROTOCOL_NUMBER = 6
}
