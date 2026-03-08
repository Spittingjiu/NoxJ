package com.noxcore.noxdroid.core.connection

import android.util.Log
import com.noxcore.noxdroid.core.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class NoxTransportClient(
    private val config: NoxClientConfig,
    private val protectSocket: (Socket) -> Boolean
) {
    enum class StreamCloseSource {
        LOCAL_API,
        LOCAL_OPEN_TIMEOUT,
        LOCAL_OPEN_REJECTED,
        LOCAL_BUFFER_OVERFLOW,
        REMOTE_CLOSE_FRAME,
        TRANSPORT_SHUTDOWN
    }

    data class OpenStreamResult(
        val streamId: Long,
        val error: String?
    ) {
        val ok: Boolean get() = error == null
    }

    private data class PendingOpen(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var accepted: Boolean = false,
        @Volatile var error: String = "open failed"
    )

    private data class StreamBinding(
        var onData: ((ByteArray) -> Unit)? = null,
        var onClose: ((String) -> Unit)? = null,
        val queuedData: ArrayDeque<ByteArray> = ArrayDeque(),
        var queuedClose: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val writerLock = Any()
    private val secureRandom = SecureRandom()
    private val nextStreamId = AtomicLong(1L)

    private var socket: SSLSocket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var readerJob: Job? = null
    private var keepaliveJob: Job? = null
    private var terminated = false

    private val streams = linkedMapOf<Long, StreamBinding>()
    private val pendingOpen = linkedMapOf<Long, PendingOpen>()

    fun connect(): Result<Unit> {
        synchronized(lock) {
            if (readerJob?.isActive == true) {
                return Result.success(Unit)
            }
            if (terminated) {
                return Result.failure(IllegalStateException("transport closed"))
            }
            closeQuietly(socket)
            socket = null
            input = null
            output = null
        }

        val uri = parseAndValidateServerUrl(config.serverUrl)
            ?: return Result.failure(IllegalArgumentException("Server URL must be a valid wss:// endpoint"))
        val host = uri.host ?: return Result.failure(IllegalArgumentException("Server URL must include a host"))
        val authority = if (uri.port != -1) "$host:${uri.port}" else host
        val port = if (uri.port != -1) uri.port else 443
        val pathWithQuery = buildPathWithQuery(uri)

        val createdSocket = (SSLSocketFactory.getDefault().createSocket() as SSLSocket).apply {
            soTimeout = CONNECT_TIMEOUT_MS
        }
        if (!protectSocket(createdSocket)) {
            closeQuietly(createdSocket)
            return Result.failure(IllegalStateException("Failed to protect control socket"))
        }

        return try {
            DiagnosticsLog.info(TAG, "connecting transport to $host:$port")
            createdSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            createdSocket.startHandshake()
            val createdInput = BufferedInputStream(createdSocket.inputStream)
            val createdOutput = BufferedOutputStream(createdSocket.outputStream)

            performWebSocketUpgrade(
                input = createdInput,
                output = createdOutput,
                authority = authority,
                pathWithQuery = pathWithQuery,
                origin = "https://$host"
            )
            sendHelloAndValidateAck(createdInput, createdOutput)
            createdSocket.soTimeout = 0

            synchronized(lock) {
                socket = createdSocket
                input = createdInput
                output = createdOutput
                readerJob = scope.launch {
                    readLoop()
                }
                keepaliveJob = scope.launch {
                    keepaliveLoop()
                }
            }
            DiagnosticsLog.info(TAG, "transport connected and hello_ack validated")
            Result.success(Unit)
        } catch (e: Exception) {
            DiagnosticsLog.error(TAG, "transport connect failed: ${e.message ?: "unknown"}")
            closeQuietly(createdSocket)
            Result.failure(e)
        }
    }

    fun openStream(target: String): OpenStreamResult {
        if (target.isBlank()) {
            return OpenStreamResult(streamId = 0L, error = "target is blank")
        }

        if (!isConnected()) {
            return OpenStreamResult(streamId = 0L, error = "transport disconnected")
        }

        val streamId = nextStreamId.getAndIncrement()
        val openWait = PendingOpen()
        val nonce = randomHexNonce()
        val ts = System.currentTimeMillis() / 1000L
        val openPayload = JSONObject()
            .put("stream_id", streamId)
            .put("target", target)
            .put("ts", ts)
            .put("nonce", nonce)
            .put("auth", sign(config.sharedSecret, listOf("open", streamId.toString(), target, ts.toString(), nonce)))

        val openEnvelope = JSONObject()
            .put("type", "open")
            .put("payload", openPayload)

        synchronized(lock) {
            streams[streamId] = StreamBinding()
            pendingOpen[streamId] = openWait
        }

        if (!writeNoxMessage(openEnvelope)) {
            synchronized(lock) {
                pendingOpen.remove(streamId)
                streams.remove(streamId)
            }
            DiagnosticsLog.warn(TAG, "open stream write failed stream=$streamId target=$target")
            return OpenStreamResult(streamId = streamId, error = "transport write failed")
        }

        var completed = openWait.latch.await(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed && isConnected()) {
            DiagnosticsLog.warn(
                TAG,
                "open stream still pending stream=$streamId target=$target after=${CONTROL_TIMEOUT_MS}ms; entering timeout grace=${OPEN_TIMEOUT_GRACE_MS}ms"
            )
            completed = openWait.latch.await(OPEN_TIMEOUT_GRACE_MS, TimeUnit.MILLISECONDS)
            if (completed) {
                DiagnosticsLog.info(TAG, "open stream completed during grace stream=$streamId target=$target")
            }
        }
        synchronized(lock) {
            pendingOpen.remove(streamId)
        }

        if (!completed) {
            closeStream(
                streamId = streamId,
                reason = "open timeout",
                sendFrame = false,
                source = StreamCloseSource.LOCAL_OPEN_TIMEOUT
            )
            DiagnosticsLog.warn(TAG, "open stream timeout stream=$streamId target=$target")
            return OpenStreamResult(streamId = streamId, error = "open stream timeout")
        }

        if (!openWait.accepted) {
            closeStream(
                streamId = streamId,
                reason = openWait.error,
                sendFrame = false,
                source = StreamCloseSource.LOCAL_OPEN_REJECTED
            )
            DiagnosticsLog.warn(TAG, "open stream rejected stream=$streamId target=$target reason=${openWait.error}")
            return OpenStreamResult(streamId = streamId, error = openWait.error)
        }

        return OpenStreamResult(streamId = streamId, error = null)
    }

    fun registerCallbacks(
        streamId: Long,
        onData: (ByteArray) -> Unit,
        onClose: (String) -> Unit
    ) {
        var queuedData = emptyList<ByteArray>()
        var queuedClose: String? = null
        synchronized(lock) {
            val stream = streams[streamId] ?: return
            stream.onData = onData
            stream.onClose = onClose
            if (stream.queuedData.isNotEmpty()) {
                queuedData = stream.queuedData.toList()
                stream.queuedData.clear()
            }
            queuedClose = stream.queuedClose
            stream.queuedClose = null
            if (queuedClose != null) {
                streams.remove(streamId)
            }
        }
        queuedData.forEach(onData)
        queuedClose?.let(onClose)
    }

    fun sendData(streamId: Long, data: ByteArray): Boolean {
        if (data.isEmpty()) {
            return true
        }
        synchronized(lock) {
            if (!streams.containsKey(streamId)) {
                return false
            }
        }
        val payload = JSONObject()
            .put("stream_id", streamId)
            .put("data", Base64.getEncoder().encodeToString(data))
        val envelope = JSONObject()
            .put("type", "data")
            .put("payload", payload)
        return writeNoxMessage(envelope)
    }

    fun closeStream(
        streamId: Long,
        reason: String,
        sendFrame: Boolean = true,
        source: StreamCloseSource = StreamCloseSource.LOCAL_API,
        notifyLocalCallback: Boolean = false
    ) {
        var callback: ((String) -> Unit)? = null
        synchronized(lock) {
            val stream = streams.remove(streamId)
            if (notifyLocalCallback) {
                callback = stream?.onClose
            }
        }
        DiagnosticsLog.info(
            TAG,
            "closing stream=$streamId source=$source send_frame=$sendFrame reason=$reason"
        )

        if (sendFrame) {
            val payload = JSONObject()
                .put("stream_id", streamId)
                .put("error", reason)
            val envelope = JSONObject()
                .put("type", "close")
                .put("payload", payload)
            writeNoxMessage(envelope)
        }
        if (notifyLocalCallback) {
            DiagnosticsLog.info(TAG, "delivering close callback stream=$streamId source=$source reason=$reason")
            callback?.invoke(reason)
        }
    }

    fun isConnected(): Boolean {
        synchronized(lock) {
            return readerJob?.isActive == true
        }
    }

    fun close() {
        shutdown("client closed", terminal = true)
        scope.cancel()
    }

    private fun readLoop() {
        val localInput = synchronized(lock) { input } ?: return
        try {
            while (true) {
                val message = readNoxMessage(localInput)
                val type = message.optString("type")
                val payload = message.optJSONObject("payload") ?: JSONObject()
                when (type) {
                    "open_resp" -> {
                        val streamId = payload.optLong("stream_id", 0L)
                        synchronized(lock) {
                            val pending = pendingOpen[streamId]
                            if (pending != null) {
                                pending.accepted = payload.optBoolean("ok", false)
                                pending.error = payload.optString("error").ifBlank { "open failed" }
                                pending.latch.countDown()
                                if (!pending.accepted) {
                                    DiagnosticsLog.warn(TAG, "open_resp rejected stream=$streamId reason=${pending.error}")
                                }
                            }
                        }
                    }

                    "data" -> {
                        val streamId = payload.optLong("stream_id", 0L)
                        val encoded = payload.optString("data")
                        val decoded = try {
                            if (encoded.isBlank()) {
                                ByteArray(0)
                            } else {
                                Base64.getDecoder().decode(encoded)
                            }
                        } catch (_: Exception) {
                            ByteArray(0)
                        }
                        if (decoded.isNotEmpty()) {
                            deliverData(streamId, decoded)
                        }
                    }

                    "close" -> {
                        val streamId = payload.optLong("stream_id", 0L)
                        val reason = payload.optString("error")
                        DiagnosticsLog.info(
                            TAG,
                            "received remote close stream=$streamId source=${StreamCloseSource.REMOTE_CLOSE_FRAME} reason=${reason.ifBlank { "remote closed" }}"
                        )
                        deliverClose(
                            streamId = streamId,
                            reason = reason.ifBlank { "remote closed" },
                            source = StreamCloseSource.REMOTE_CLOSE_FRAME
                        )
                    }

                    "ping" -> {
                        val ts = payload.optLong("ts", System.currentTimeMillis() / 1000L)
                        val pong = JSONObject()
                            .put("type", "pong")
                            .put("payload", JSONObject().put("ts", ts))
                        writeNoxMessage(pong)
                    }

                    "pong" -> {
                        // no-op
                    }

                    else -> {
                        Log.w(TAG, "Ignoring unknown Nox message type: $type")
                    }
                }
            }
        } catch (e: Exception) {
            val message = e.message ?: "read failed"
            DiagnosticsLog.warn(TAG, "transport read loop stopped: $message")
            shutdown("transport read failed: $message", terminal = false)
        }
    }

    private fun deliverData(streamId: Long, data: ByteArray) {
        val callback: ((ByteArray) -> Unit)?
        var shouldForceClose = false
        synchronized(lock) {
            val stream = streams[streamId] ?: return
            callback = stream.onData
            if (callback == null) {
                if (stream.queuedData.size >= MAX_QUEUED_DATA_CHUNKS) {
                    shouldForceClose = true
                } else {
                    stream.queuedData.addLast(data)
                }
            }
        }
        if (shouldForceClose) {
            closeStream(
                streamId = streamId,
                reason = "stream buffer overflow",
                sendFrame = true,
                source = StreamCloseSource.LOCAL_BUFFER_OVERFLOW,
                notifyLocalCallback = true
            )
            return
        }
        callback?.invoke(data)
    }

    private fun deliverClose(streamId: Long, reason: String, source: StreamCloseSource) {
        var callback: ((String) -> Unit)? = null
        synchronized(lock) {
            val stream = streams[streamId] ?: return
            callback = stream.onClose
            if (callback == null) {
                stream.queuedClose = reason
                return
            }
            streams.remove(streamId)
        }
        DiagnosticsLog.info(TAG, "delivering close callback stream=$streamId source=$source reason=$reason")
        callback?.invoke(reason)
    }

    private suspend fun keepaliveLoop() {
        while (true) {
            delay(KEEPALIVE_INTERVAL_MS)
            if (!isConnected()) {
                continue
            }
            val ping = JSONObject()
                .put("type", "ping")
                .put("payload", JSONObject().put("ts", System.currentTimeMillis() / 1000L))
            writeNoxMessage(ping)
        }
    }

    private fun shutdown(reason: String, terminal: Boolean) {
        val callbacks = mutableListOf<Pair<Long, (String) -> Unit>>()
        synchronized(lock) {
            if (terminal) {
                terminated = true
            }
            val alreadyShutDown = socket == null &&
                input == null &&
                output == null &&
                readerJob == null &&
                keepaliveJob == null &&
                pendingOpen.isEmpty() &&
                streams.isEmpty()
            if (alreadyShutDown) {
                return
            }
            DiagnosticsLog.warn(TAG, "transport shutdown: $reason")

            pendingOpen.values.forEach { pending ->
                pending.accepted = false
                pending.error = reason
                pending.latch.countDown()
            }
            pendingOpen.clear()

            streams.forEach { (streamId, stream) ->
                stream.onClose?.let { callbacks += streamId to it }
            }
            streams.clear()

            readerJob?.cancel()
            readerJob = null
            keepaliveJob?.cancel()
            keepaliveJob = null

            closeQuietly(socket)
            socket = null
            input = null
            output = null
        }

        callbacks.forEach { (streamId, callback) ->
            DiagnosticsLog.info(
                TAG,
                "delivering close callback stream=$streamId source=${StreamCloseSource.TRANSPORT_SHUTDOWN} reason=$reason"
            )
            callback(reason)
        }
    }

    private fun writeNoxMessage(message: JSONObject): Boolean {
        val localOutput = synchronized(lock) { output } ?: return false
        val body = message.toString().toByteArray(Charsets.UTF_8)
        if (body.isEmpty() || body.size > MAX_CONTROL_SIZE) {
            return false
        }
        val header = byteArrayOf(
            ((body.size ushr 24) and 0xFF).toByte(),
            ((body.size ushr 16) and 0xFF).toByte(),
            ((body.size ushr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte()
        )

        return try {
            synchronized(writerLock) {
                localOutput.write(header)
                localOutput.write(body)
                localOutput.flush()
            }
            true
        } catch (_: Exception) {
            shutdown("transport write failed", terminal = false)
            false
        }
    }

    private fun parseAndValidateServerUrl(serverUrl: String): URI? {
        return try {
            val uri = URI(serverUrl.trim())
            if (uri.scheme == "wss") {
                uri
            } else {
                null
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
        output: BufferedOutputStream
    ) {
        val ts = System.currentTimeMillis() / 1000L
        val nonce = randomHexNonce()

        val helloPayload = JSONObject()
            .put("client_id", config.clientId)
            .put("ts", ts)
            .put("nonce", nonce)
            .put("auth", sign(config.sharedSecret, listOf("hello", config.clientId, ts.toString(), nonce)))

        val helloEnvelope = JSONObject()
            .put("type", "hello")
            .put("payload", helloPayload)

        val helloBody = helloEnvelope.toString().toByteArray(Charsets.UTF_8)
        val helloHeader = byteArrayOf(
            ((helloBody.size ushr 24) and 0xFF).toByte(),
            ((helloBody.size ushr 16) and 0xFF).toByte(),
            ((helloBody.size ushr 8) and 0xFF).toByte(),
            (helloBody.size and 0xFF).toByte()
        )
        output.write(helloHeader)
        output.write(helloBody)
        output.flush()

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
            config.sharedSecret,
            listOf("ack", config.clientId, ts.toString(), nonce, serverTs.toString(), serverNonce)
        )
        if (!constantTimeEquals(expectedAckAuth, auth)) {
            throw IllegalStateException("hello_ack auth invalid")
        }
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

    private fun closeQuietly(socket: Socket?) {
        if (socket == null) {
            return
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "NoxTransportClient"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val CONTROL_TIMEOUT_MS = 8_000L
        private const val MAX_CONTROL_SIZE = 64 * 1024
        private const val MAX_QUEUED_DATA_CHUNKS = 32
        private const val OPEN_TIMEOUT_GRACE_MS = 4_000L
        private const val KEEPALIVE_INTERVAL_MS = 20_000L
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
