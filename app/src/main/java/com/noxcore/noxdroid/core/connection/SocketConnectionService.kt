package com.noxcore.noxdroid.core.connection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

class SocketConnectionService(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun connect(
        serverUrl: String,
        sharedSecret: String,
        clientId: String,
        timeoutMillis: Int = 8000
    ): ConnectionState = withContext(ioDispatcher) {
        val normalizedUrl = serverUrl.trim()
        if (sharedSecret.isBlank() || clientId.isBlank()) {
            return@withContext ConnectionState.Error("Shared secret and client ID are required")
        }
        if (!normalizedUrl.startsWith("wss://")) {
            return@withContext ConnectionState.Error("Server URL must start with wss://")
        }
        if (normalizedUrl.toHttpUrlOrNull() == null) {
            return@withContext ConnectionState.Error("Server URL is not a valid WebSocket endpoint")
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val timestampMs = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString()
        val authPayload = "$clientId:$timestampMs:$nonce"
        val signature = hmacSha256Base64(sharedSecret, authPayload)

        val hello = JSONObject()
            .put("type", "hello")
            .put("client_id", clientId)
            .put("timestamp_ms", timestampMs)
            .put("nonce", nonce)
            .put("auth", JSONObject()
                .put("method", "hmac-sha256")
                .put("payload", authPayload)
                .put("signature_b64", signature)
            )
            .toString()

        return@withContext try {
            withTimeout(timeoutMillis.toLong()) {
                kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                    val finished = AtomicBoolean(false)

                    fun cleanup() {
                        okHttpClient.dispatcher.executorService.shutdown()
                        okHttpClient.connectionPool.evictAll()
                    }

                    fun finish(state: ConnectionState) {
                        if (finished.compareAndSet(false, true)) {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(state)
                            }
                        }
                    }

                    val request = Request.Builder().url(normalizedUrl).build()
                    val webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            val sent = webSocket.send(hello)
                            if (!sent) {
                                finish(ConnectionState.Error("Failed to send hello frame over WebSocket"))
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val parsed = parseServerMessage(text)
                            when (parsed) {
                                is ServerMessage.HelloAck -> {
                                    webSocket.close(1000, "handshake-complete")
                                    finish(
                                        ConnectionState.Connected(
                                            normalizedUrl,
                                            "hello -> hello_ack validated (${parsed.summary})"
                                        )
                                    )
                                }
                                is ServerMessage.Error -> {
                                    webSocket.close(1000, "handshake-error")
                                    finish(ConnectionState.Error("Server rejected hello: ${parsed.details}"))
                                }
                                ServerMessage.Other -> {
                                    // Ignore unrelated frames while waiting for hello_ack.
                                }
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            webSocket.close(1003, "binary-not-supported-yet")
                            finish(
                                ConnectionState.Error(
                                    "Received binary frame before hello_ack; binary framing not implemented"
                                )
                            )
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            val reason = t.message ?: "unknown WebSocket error"
                            finish(ConnectionState.Error("WebSocket connection failed: $reason"))
                        }
                    })

                    continuation.invokeOnCancellation {
                        webSocket.cancel()
                        cleanup()
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            ConnectionState.Error("Timed out waiting for hello_ack")
        }
    }

    private fun parseServerMessage(message: String): ServerMessage {
        return try {
            val json = JSONObject(message)
            val type = json.optString("type").ifBlank {
                json.optString("op").ifBlank { json.optString("kind") }
            }
            when (type) {
                "hello_ack" -> {
                    val status = json.optString("status", "ok")
                    val serverId = json.optString("server_id", "unknown-server")
                    ServerMessage.HelloAck("status=$status, server=$serverId")
                }
                "error", "hello_nack" -> {
                    val details = json.optString("message", json.optString("reason", "unknown error"))
                    ServerMessage.Error(details)
                }
                else -> ServerMessage.Other
            }
        } catch (_: JSONException) {
            ServerMessage.Other
        }
    }

    private fun hmacSha256Base64(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    private sealed class ServerMessage {
        data class HelloAck(val summary: String) : ServerMessage()
        data class Error(val details: String) : ServerMessage()
        data object Other : ServerMessage()
    }
}
