package com.noxcore.noxdroid.core.connection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class SocketConnectionService(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun connect(
        host: String,
        port: Int,
        sharedSecret: String,
        clientId: String,
        timeoutMillis: Int = 5000
    ): ConnectionState = withContext(ioDispatcher) {
        if (sharedSecret.isBlank() || clientId.isBlank()) {
            return@withContext ConnectionState.Error("Shared secret and client ID are required")
        }

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            // Trial handshake marker only; full protocol to be implemented later.
            val greeting = "trial-connect:$clientId"
            socket.getOutputStream().write(greeting.toByteArray())
            socket.getOutputStream().flush()
            ConnectionState.Connected("$host:$port", "TCP reachable; trial greeting sent")
        } catch (e: IOException) {
            ConnectionState.Error("Connection failed: ${e.message ?: "unknown IO error"}")
        } catch (e: IllegalArgumentException) {
            ConnectionState.Error("Invalid endpoint: ${e.message ?: "bad host or port"}")
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }
}
