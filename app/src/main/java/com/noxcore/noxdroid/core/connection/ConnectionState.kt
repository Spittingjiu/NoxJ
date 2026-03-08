package com.noxcore.noxdroid.core.connection

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data class Connecting(val endpoint: String) : ConnectionState()
    data class Connected(val endpoint: String, val message: String) : ConnectionState()
    data class Error(val details: String) : ConnectionState()
}
