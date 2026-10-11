package dev.amang.claudetermux.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val hello: Hello) : ConnectionState
    /** The bridge answered but rejected our pairing token. Retrying will not help. */
    data object Unauthorized : ConnectionState
    data class Unreachable(val reason: String) : ConnectionState
}

class BridgeException(message: String) : Exception(message)

data class Endpoint(val host: String, val port: Int, val token: String)

/**
 * Single WebSocket to the bridge. Requests are correlated by id; session events arrive on [events].
 * While an endpoint is set the client keeps reconnecting, so callers only observe [state].
 */
class BridgeClient(private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state

    private val _events = MutableSharedFlow<SessionEvent>(
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val events: SharedFlow<SessionEvent> = _events

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement?>>()
    private val nextId = AtomicLong(1)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var endpoint: Endpoint? = null
    private var loop: Job? = null
    private var socketClosed = CompletableDeferred<Unit>()

    fun connect(target: Endpoint) {
        if (target == endpoint && loop?.isActive == true && _state.value !is ConnectionState.Unauthorized) return
        disconnect()
        endpoint = target
        loop = scope.launch {
            var backoffMs = 500L
            while (true) {
                _state.value = ConnectionState.Connecting
                val outcome = runOnce(target)
                failPending("Connection to the bridge was lost")
                if (outcome is ConnectionState.Unauthorized) {
                    _state.value = outcome
                    return@launch
                }
                _state.value = outcome
                backoffMs = if (outcome is ConnectionState.Idle) 500L else (backoffMs * 2).coerceAtMost(5_000L)
                delay(backoffMs)
            }
        }
    }

    /** Skip the remaining backoff, e.g. right after asking Termux to start the bridge. */
    fun retryNow() {
        val target = endpoint ?: return
        if (_state.value is ConnectionState.Connected || _state.value is ConnectionState.Connecting) return
        endpoint = null
        connect(target)
    }

    fun disconnect() {
        loop?.cancel()
        loop = null
        endpoint = null
        socket?.close(1000, null)
        socket = null
        failPending("Disconnected")
        _state.value = ConnectionState.Idle
    }

    /** Runs one connection to completion and reports why it ended. */
    private suspend fun runOnce(target: Endpoint): ConnectionState {
        val closed = CompletableDeferred<ConnectionState>()
        socketClosed = CompletableDeferred()
        val request = Request.Builder().url("ws://${target.host}:${target.port}/").build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    try {
                        val hello = requestOn(webSocket, "hello", Hello.serializer(), 10_000) { put("token", target.token) }
                        socket = webSocket
                        _state.value = ConnectionState.Connected(hello)
                    } catch (e: Exception) {
                        closed.complete(
                            if (e.message == "unauthorized") ConnectionState.Unauthorized
                            else ConnectionState.Unreachable(e.message ?: "Handshake failed"),
                        )
                        webSocket.cancel()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closed.complete(if (code == 4401) ConnectionState.Unauthorized else ConnectionState.Unreachable("Bridge closed the connection"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(ConnectionState.Unreachable("Bridge closed the connection"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(ConnectionState.Unreachable(t.message ?: t.javaClass.simpleName))
            }
        })
        try {
            return closed.await()
        } finally {
            if (socket === ws) socket = null
            ws.cancel()
        }
    }

    private fun handleMessage(text: String) {
        val message = runCatching { BridgeJson.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
        val replyTo = (message["replyTo"] as? JsonPrimitive)?.long
        if (replyTo != null) {
            val waiter = pending.remove(replyTo) ?: return
            if (message.bool("ok")) waiter.complete(message["data"])
            else waiter.completeExceptionally(BridgeException(message.str("error") ?: "Request failed"))
            return
        }
        if (message.str("type") == "event") {
            val event = message.obj("event") ?: return
            val sessionId = message.str("sessionId") ?: return
            val seq = (message["seq"] as? JsonPrimitive)?.long ?: return
            // Listener threads must preserve order, so block rather than drop when the UI is behind.
            kotlinx.coroutines.runBlocking { _events.emit(SessionEvent(sessionId, seq, event)) }
        }
    }

    private fun failPending(reason: String) {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(BridgeException(reason)) }
    }

    private suspend fun <T> requestOn(
        ws: WebSocket,
        type: String,
        deserializer: DeserializationStrategy<T>,
        timeoutMs: Long,
        params: JsonObjectBuilder.() -> Unit,
    ): T {
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<JsonElement?>()
        pending[id] = waiter
        val payload = buildJsonObject {
            put("id", id)
            put("type", type)
            params()
        }
        if (!ws.send(payload.toString())) {
            pending.remove(id)
            throw BridgeException("Not connected to the bridge")
        }
        try {
            val data = withTimeout(timeoutMs) { waiter.await() }
            return BridgeJson.decodeFromJsonElement(deserializer, data ?: JsonObject(emptyMap()))
        } finally {
            pending.remove(id)
        }
    }

    suspend fun <T> request(
        type: String,
        deserializer: DeserializationStrategy<T>,
        timeoutMs: Long = 30_000,
        params: JsonObjectBuilder.() -> Unit = {},
    ): T {
        val ws = socket ?: throw BridgeException("Not connected to the bridge")
        return requestOn(ws, type, deserializer, timeoutMs, params)
    }

    /** For requests whose reply carries nothing useful. */
    suspend fun send(type: String, timeoutMs: Long = 30_000, params: JsonObjectBuilder.() -> Unit = {}) {
        request(type, JsonObject.serializer(), timeoutMs, params)
    }

    suspend fun awaitConnected(): Hello =
        (state.first { it is ConnectionState.Connected } as ConnectionState.Connected).hello
}
