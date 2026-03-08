package com.noxcore.noxdroid.core.vpn

sealed class NoxVpnState {
    data object Idle : NoxVpnState()
    data object Starting : NoxVpnState()
    data class RunningForwarding(
        val sessionName: String,
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
    ) : NoxVpnState()
    data object Stopping : NoxVpnState()
    data class Error(val details: String) : NoxVpnState()
}
