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
        val transportConnected: Boolean,
        val reconnectAttempts: Long,
        val reconnectSuccesses: Long,
        val uplinkBytes: Long,
        val downlinkBytes: Long,
        val droppedForwardPackets: Long,
        val connectFailures: Long,
        val quicFallbackSignals: Long,
        val youtubeFallbackOpenSuccesses: Long,
        val youtubeFallbackOpenFailures: Long,
        val youtubeFallbackDownlinkBytes: Long,
        val youtubeFallbackCompletedFlows: Long,
        val youtubeFallbackSuccessfulFlows: Long,
        val youtubeFallbackEarlyCloseFlows: Long,
        val youtubeFallbackVerdict: String,
        val lastPacketSummary: String
    ) : NoxVpnState()
    data object Stopping : NoxVpnState()
    data class Error(val details: String) : NoxVpnState()
}
