package com.noxcore.noxdroid.core.vpn

sealed class NoxVpnState {
    data object Idle : NoxVpnState()
    data object Starting : NoxVpnState()
    data class RunningCapture(
        val sessionName: String,
        val totalPackets: Long,
        val malformedPackets: Long,
        val ipv4Packets: Long,
        val tcpPackets: Long,
        val activeTcpSessions: Int,
        val lastPacketSummary: String
    ) : NoxVpnState()
    data object Stopping : NoxVpnState()
    data class Error(val details: String) : NoxVpnState()
}
