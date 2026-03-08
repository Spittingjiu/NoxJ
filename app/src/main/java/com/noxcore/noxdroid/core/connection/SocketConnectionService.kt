package com.noxcore.noxdroid.core.connection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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

        val uri = parseAndValidateServerUrl(normalizedUrl)
            ?: return@withContext ConnectionState.Error("Server URL must be a valid wss:// endpoint")

        val host = uri.host ?: return@withContext ConnectionState.Error("Server URL must include a host")
        val authority = if (uri.port != -1) "$host:${uri.port}" else host
        val port = if (uri.port != -1) uri.port else 443
        val pathWithQuery = buildPathWithQuery(uri)

        var socket: SSLSocket? = null
        try {
            socket = (SSLSocketFactory.getDefault().createSocket() as SSLSocket).apply {
                soTimeout = timeoutMillis
                connect(InetSocketAddress(host, port), timeoutMillis)
                startHandshake()
            }

            val input = BufferedInputStream(socket.inputStream)
            val output = BufferedOutputStream(socket.outputStream)

            performWebSocketUpgrade(
                input = input,
                output = output,
                authority = authority,
                pathWithQuery = pathWithQuery,
                origin = "https://$host"
            )

            val helloContext = sendHelloAndValidateAck(
                input = input,
                output = output,
                sharedSecret = sharedSecret,
                clientId = clientId
            )

            return@withContext ConnectionState.Connected(
                endpoint = normalizedUrl,
                message = "hello -> hello_ack validated (server_ts=${helloContext.serverTimestamp})"
            )
        } catch (_: SocketTimeoutException) {
            return@withContext ConnectionState.Error("Timed out while waiting for server response")
        } catch (e: Exception) {
            return@withContext ConnectionState.Error(e.message ?: "Handshake failed")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseAndValidateServerUrl(serverUrl: String): URI? {
        return try {
            val uri = URI(serverUrl)
            if (uri.scheme != "wss") {
                null
            } else {
                uri
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPathWithQuery(uri: URI): String {
        val path = if (uri.rawPath.isNullOrEmpty()) "/ws" else uri.rawPath
        val query = uri.rawQuery
        return if (query.isNullOrEmpty()) path else "$path?$query"
    }

    private fun performWebSocketUpgrade(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        authority: String,
        pathWithQuery: String,
        origin: String
    ) {
        val wsKey = randomWebSocketKey()
        val request = buildString {
            append("GET $pathWithQuery HTTP/1.1\r\n")
            append("Host: $authority\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Key: $wsKey\r\n")
            append("Sec-WebSocket-Protocol: http/1.1\r\n")
            append("User-Agent: Mozilla/5.0\r\n")
            append("Pragma: no-cache\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Origin: $origin\r\n")
            append("\r\n")
        }

        output.write(request.toByteArray(Charsets.UTF_8))
        output.flush()

        val statusLine = readHttpLine(input)
            ?: throw IllegalStateException("WebSocket upgrade failed: missing HTTP status line")
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        if (statusCode != 101) {
            throw IllegalStateException("WebSocket upgrade failed: $statusLine")
        }

        val headers = mutableMapOf<String, MutableList<String>>()
        while (true) {
            val line = readHttpLine(input)
                ?: throw IllegalStateException("WebSocket upgrade failed: malformed HTTP headers")
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            headers.getOrPut(name) { mutableListOf() }.add(value)
        }

        if (!headerContainsToken(headers, "connection", "upgrade") ||
            !headerContainsToken(headers, "upgrade", "websocket")
        ) {
            throw IllegalStateException("Invalid WebSocket upgrade response headers")
        }

        val accept = headers["sec-websocket-accept"]?.firstOrNull().orEmpty()
        val expectedAccept = websocketAccept(wsKey)
        if (accept != expectedAccept) {
            throw IllegalStateException("Invalid WebSocket accept key")
        }
    }

    private fun sendHelloAndValidateAck(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        sharedSecret: String,
        clientId: String
    ): HelloAckContext {
        val ts = System.currentTimeMillis() / 1000L
        val nonce = randomHexNonce()

        val helloPayload = JSONObject()
            .put("client_id", clientId)
            .put("ts", ts)
            .put("nonce", nonce)
            .put("auth", sign(sharedSecret, listOf("hello", clientId, ts.toString(), nonce)))

        val helloEnvelope = JSONObject()
            .put("type", "hello")
            .put("payload", helloPayload)

        writeNoxMessage(output, helloEnvelope)

        val ackEnvelope = readNoxMessage(input)
        val messageType = ackEnvelope.optString("type")
        if (messageType != "hello_ack") {
            throw IllegalStateException("Unexpected handshake message type: $messageType")
        }

        val payload = ackEnvelope.optJSONObject("payload")
            ?: throw IllegalStateException("hello_ack payload missing")

        val serverTs = payload.optLong("server_ts", Long.MIN_VALUE)
        if (serverTs == Long.MIN_VALUE) {
            throw IllegalStateException("hello_ack missing server_ts")
        }
        val serverNonce = payload.optString("server_nonce")
        if (serverNonce.isBlank()) {
            throw IllegalStateException("hello_ack missing server_nonce")
        }
        val auth = payload.optString("auth")
        if (auth.isBlank()) {
            throw IllegalStateException("hello_ack missing auth")
        }

        val expectedAckAuth = sign(
            sharedSecret,
            listOf("ack", clientId, ts.toString(), nonce, serverTs.toString(), serverNonce)
        )
        if (!constantTimeEquals(expectedAckAuth, auth)) {
            throw IllegalStateException("hello_ack auth invalid")
        }

        return HelloAckContext(serverTimestamp = serverTs)
    }

    private fun writeNoxMessage(output: BufferedOutputStream, message: JSONObject) {
        val body = message.toString().toByteArray(Charsets.UTF_8)
        if (body.isEmpty() || body.size > MAX_CONTROL_SIZE) {
            throw IllegalStateException("invalid control message size: ${body.size}")
        }
        val header = byteArrayOf(
            ((body.size ushr 24) and 0xFF).toByte(),
            ((body.size ushr 16) and 0xFF).toByte(),
            ((body.size ushr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte()
        )
        output.write(header)
        output.write(body)
        output.flush()
    }

    private fun readNoxMessage(input: BufferedInputStream): JSONObject {
        val header = readExactly(input, 4)
        val length =
            ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

        if (length <= 0 || length > MAX_CONTROL_SIZE) {
            throw IllegalStateException("invalid control length: $length")
        }

        val payload = readExactly(input, length)
        val raw = payload.toString(Charsets.UTF_8)
        return try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw IllegalStateException("invalid JSON control message", e)
        }
    }

    private fun readHttpLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                bytes.add(b.toByte())
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun readExactly(input: BufferedInputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(out, offset, length - offset)
            if (n < 0) {
                throw EOFException("unexpected EOF")
            }
            offset += n
        }
        return out
    }

    private fun headerContainsToken(
        headers: Map<String, List<String>>,
        headerName: String,
        token: String
    ): Boolean {
        return headers[headerName].orEmpty().any { value ->
            value.split(',').any { part -> part.trim().equals(token, ignoreCase = true) }
        }
    }

    private fun randomWebSocketKey(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun randomHexNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append("%02x".format(b.toInt() and 0xFF))
        }
        return out.toString()
    }

    private fun websocketAccept(key: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val digest = sha1.digest((key + WS_GUID).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun sign(secret: String, parts: List<String>): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val data = parts.joinToString("|").toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(mac.doFinal(data))
    }

    private fun constantTimeEquals(expected: String, received: String): Boolean {
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            received.toByteArray(Charsets.UTF_8)
        )
    }

    private data class HelloAckContext(val serverTimestamp: Long)

    private companion object {
        private const val MAX_CONTROL_SIZE = 64 * 1024
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val secureRandom = SecureRandom()
    }
}
