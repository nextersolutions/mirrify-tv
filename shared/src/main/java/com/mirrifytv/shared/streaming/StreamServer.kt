package com.mirrifytv.shared.streaming

import android.util.Log
import com.mirrifytv.shared.model.StreamFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * StreamSender - runs on the Mobile side.
 * Connects to the TV's WebSocket server and sends JPEG frames.
 */
class StreamSender(
    private val host: String,
    private val port: Int,
    private val sessionId: String
) {
    private val TAG = "StreamSender"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for streaming
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false

    private val _connectionState = MutableSharedFlow<Boolean>(replay = 1)
    val connectionState: SharedFlow<Boolean> = _connectionState

    fun connect(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        val url = "ws://$host:$port/stream?session=$sessionId"
        Log.d(TAG, "Connecting to $url")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                scope.launch { _connectionState.emit(true) }
                onConnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected = false
                scope.launch { _connectionState.emit(false) }
                onDisconnected()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isConnected = false
                scope.launch { _connectionState.emit(false) }
                onDisconnected()
            }
        })
    }

    fun sendFrame(frame: StreamFrame) {
        if (!isConnected) return
        try {
            val byteString = frame.data.toByteString()
            webSocket?.send(byteString)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending frame", e)
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        isConnected = false
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }
}

/**
 * StreamReceiver - runs on the TV side.
 * A lightweight HTTP + WebSocket server implemented with raw ServerSocket.
 */
class StreamReceiver(val port: Int = 8765) {
    private val TAG = "StreamReceiver"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _frameFlow = MutableSharedFlow<StreamFrame>(extraBufferCapacity = 10)
    val frameFlow: SharedFlow<StreamFrame> = _frameFlow

    private val _clientConnected = MutableSharedFlow<Boolean>(replay = 1)
    val clientConnected: SharedFlow<Boolean> = _clientConnected

    private var serverThread: WebSocketServerThread? = null

    fun start() {
        Log.d(TAG, "Starting StreamReceiver on port $port")
        serverThread = WebSocketServerThread(port, scope, _frameFlow, _clientConnected)
        serverThread?.start()
    }

    fun stop() {
        serverThread?.stopServer()
        serverThread = null
        scope.cancel()
    }
}

/**
 * Minimal WebSocket server for receiving MJPEG/binary frames.
 * Uses raw sockets + HTTP upgrade handshake.
 */
class WebSocketServerThread(
    private val port: Int,
    private val scope: CoroutineScope,
    private val frameFlow: MutableSharedFlow<StreamFrame>,
    private val clientConnected: MutableSharedFlow<Boolean>
) : Thread("WebSocketServer") {
    private val TAG = "WebSocketServerThread"

    @Volatile
    private var running = false
    private var serverSocket: java.net.ServerSocket? = null

    override fun run() {
        running = true
        try {
            serverSocket = java.net.ServerSocket(port)
            Log.d(TAG, "Listening on port $port")
            while (running) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    scope.launch(Dispatchers.IO) {
                        handleClient(socket)
                    }
                } catch (e: java.net.SocketException) {
                    if (running) Log.e(TAG, "Socket error", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error", e)
        }
    }

    private suspend fun handleClient(socket: java.net.Socket) {
        Log.d(TAG, "Client connected: ${socket.inetAddress}")
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // Read HTTP request headers
            val headers = StringBuilder()
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (true) {
                bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                headers.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                if (headers.contains("\r\n\r\n")) break
            }

            val headerStr = headers.toString()
            Log.d(TAG, "Received headers:\n${headerStr.take(300)}")

            // Extract WebSocket key
            val keyMatch = Regex("Sec-WebSocket-Key: ([^\r\n]+)").find(headerStr)
            if (keyMatch == null) {
                Log.e(TAG, "No WebSocket key found")
                socket.close()
                return
            }

            val wsKey = keyMatch.groupValues[1].trim()
            val acceptKey = generateWebSocketAcceptKey(wsKey)

            // Send WebSocket upgrade response
            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()

            clientConnected.emit(true)
            Log.d(TAG, "WebSocket handshake complete")

            // Read WebSocket frames
            val inputStream = socket.getInputStream()
            while (running && !socket.isClosed) {
                val frame = readWebSocketFrame(inputStream) ?: break
                if (frame.isNotEmpty()) {
                    frameFlow.emit(StreamFrame(data = frame))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error", e)
        } finally {
            clientConnected.emit(false)
            try { socket.close() } catch (_: Exception) {}
            Log.d(TAG, "Client disconnected")
        }
    }

    private fun readWebSocketFrame(input: java.io.InputStream): ByteArray? {
        try {
            val b0 = input.read()
            val b1 = input.read()
            if (b0 == -1 || b1 == -1) return null

            val masked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()

            if (payloadLen == 126L) {
                val ext = ByteArray(2)
                if (input.read(ext) < 2) return null
                payloadLen = ((ext[0].toLong() and 0xFF) shl 8) or (ext[1].toLong() and 0xFF)
            } else if (payloadLen == 127L) {
                val ext = ByteArray(8)
                if (input.read(ext) < 8) return null
                payloadLen = 0L
                for (i in 0 until 8) {
                    payloadLen = (payloadLen shl 8) or (ext[i].toLong() and 0xFF)
                }
            }

            val mask = if (masked) {
                val m = ByteArray(4)
                if (input.read(m) < 4) return null
                m
            } else null

            val payload = ByteArray(payloadLen.toInt())
            var totalRead = 0
            while (totalRead < payloadLen) {
                val read = input.read(payload, totalRead, (payloadLen - totalRead).toInt())
                if (read == -1) return null
                totalRead += read
            }

            if (masked && mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }

            return payload
        } catch (e: Exception) {
            Log.e("WSFrameReader", "Error reading frame: ${e.message}")
            return null
        }
    }

    private fun generateWebSocketAcceptKey(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val combined = key + magic
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest(combined.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
    }

    fun stopServer() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
