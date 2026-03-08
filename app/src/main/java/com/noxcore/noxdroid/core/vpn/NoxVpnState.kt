package com.noxcore.noxdroid.core.vpn

sealed class NoxVpnState {
    data object Idle : NoxVpnState()
    data object Starting : NoxVpnState()
    data class RunningNoForwarding(val sessionName: String) : NoxVpnState()
    data object Stopping : NoxVpnState()
    data class Error(val details: String) : NoxVpnState()
}
