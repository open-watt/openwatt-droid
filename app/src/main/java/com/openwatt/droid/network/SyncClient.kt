package com.openwatt.droid.network

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.model.Server
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket client for the `/sync` API.
 *
 * Owns one persistent connection per server; subscriptions are ref-counted so
 * multiple ViewModels can request the same pattern. On disconnect we clear the
 * store (state is stale), poll `/api/health` until the server comes back, then
 * reconnect and replay all active subscriptions.
 *
 * Identity model: objects are keyed by an **even session handle** the backend
 * allocates and announces with `add_name{h, name, type}` before any bind.
 * Handles live for one WebSocket attach only — a reconnect resets the handle
 * space, so the name/handle registry and the store are dropped at every attach
 * boundary. When this client cites a handle back to the backend (set / reset /
 * destroy), the low bit is set (`h + 1`) to mark it receiver-allocated.
 *
 * Message model: informational pushes (spontaneous add_name/bind/unbind/set/
 * state) carry no seq; requests (create/destroy/cmd/enum_req) carry a seq that
 * correlates a later bind/unbind/result/enum or error reply. Seq 0 means
 * "spontaneous, no correlation".
 */
class SyncClient(private val server: Server) {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS read is long-lived
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store = SyncStore()

    /**
     * Model-plane mirror (the `device:` namespace — element values, formats,
     * live feeds). Rides the same socket and seq space; its frames are
     * delegated from [handleMessage].
     */
    val model = ModelMirror(this)

    /** Shared IO scope for the mirror's batching/coalescing coroutines. */
    internal val ioScope: CoroutineScope get() = scope

    /** Shared live energy model (one subscription + parse for all screens). */
    val energy: com.openwatt.droid.model.energy.EnergySource by lazy {
        com.openwatt.droid.model.energy.EnergySource(this)
    }

    private val _connected = MutableLiveData(false)
    val connected: LiveData<Boolean> = _connected

    private val lock = Any()
    private val subRefCounts = mutableMapOf<String, Int>()

    /**
     * Patterns already sent on the *current* socket. The server answers a
     * repeat subscription with property values but no `bind` frames, and an
     * object stays invisible until it is bound — so a redundant re-subscribe
     * could leave a view permanently empty.
     */
    private val sentPatterns = mutableSetOf<String>()

    /** Session name registry: handle -> (name, type), from `add_name` frames. */
    private val names = mutableMapOf<Long, NameEntry>()

    private val seqCounter = AtomicInteger(1)
    private val pending = mutableMapOf<Int, PendingRequest>()

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var reconnectJob: Job? = null

    // Declared before init{} so openSocket() sees a non-null listener — Kotlin
    // runs init blocks and property initializers in source order, and
    // newWebSocket rejects null listeners.
    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connected.postValue(true)
            synchronized(lock) {
                // Handles are scoped to this attach — anything surviving from
                // a previous one is stale.
                names.clear()
                sentPatterns.clear()
                store.clear()
                for (pattern in subRefCounts.keys) {
                    sentPatterns.add(pattern)
                    sendJson(webSocket, mapOf("kind" to "sub", "pattern" to pattern))
                }
            }
            model.handleOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(text)
            } catch (_: Exception) {
                // Ignore malformed messages — don't tear down the connection.
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect()
        }
    }

    init {
        openSocket()
    }

    /**
     * Prime the store's per-type default map from the schema. Call this once
     * after `/api/schema` loads, before opening a sync session, so that
     * incoming `bind` messages can seed unset properties with their defaults.
     */
    fun setSchemas(schemas: Map<String, CollectionSchema>) {
        val typeDefaults = schemas.mapValues { (_, s) ->
            s.properties.mapValues { (_, p) -> p.default }
        }
        store.setTypeDefaults(typeDefaults)
    }

    fun subscribe(pattern: String) {
        synchronized(lock) {
            val c = subRefCounts.getOrDefault(pattern, 0)
            subRefCounts[pattern] = c + 1
            if (c == 0 && pattern !in sentPatterns) {
                ws?.let {
                    sentPatterns.add(pattern)
                    sendJson(it, mapOf("kind" to "sub", "pattern" to pattern))
                }
            }
        }
    }

    fun unsubscribe(pattern: String) {
        synchronized(lock) {
            val c = subRefCounts.getOrDefault(pattern, 0)
            if (c <= 1) {
                subRefCounts.remove(pattern)
                sentPatterns.remove(pattern)
                ws?.let { sendJson(it, mapOf("kind" to "unsub", "pattern" to pattern)) }
            } else {
                subRefCounts[pattern] = c - 1
            }
        }
    }

    fun setProperty(handle: Long, property: String, value: Any?) {
        val socket = ws ?: return
        sendJson(socket, mapOf(
            "kind" to "set",
            "target" to citeHandle(handle),
            "prop" to property,
            "value" to value,
        ))
    }

    fun resetProperty(handle: Long, property: String) {
        val socket = ws ?: return
        sendJson(socket, mapOf(
            "kind" to "reset",
            "target" to citeHandle(handle),
            "prop" to property,
        ))
    }

    /**
     * Ask the server to instantiate an authoritative object of [type] with
     * [props] as its initial properties. `name` must be present in props.
     *
     * Suspends until `bind{seq}` (success) or `error{seq}` (rejection)
     * arrives. On success returns the new session handle; by the time this
     * returns, `store.bind` has already installed the proxy, so the caller
     * can look it up immediately via the store.
     */
    suspend fun create(
        type: String,
        props: Map<String, Any?>,
        timeoutMs: Long = 5_000,
    ): Result<Long> {
        val socket = ws ?: return Result.failure(IOException("sync socket not connected"))
        val seq = seqCounter.getAndIncrement()
        val deferred = CompletableDeferred<Result<Long>>()
        synchronized(lock) {
            pending[seq] = PendingRequest(
                onReply = { json ->
                    val handle = json.get("target")?.takeIf { !it.isJsonNull }?.asLong
                    deferred.complete(
                        if (handle != null) Result.success(handle)
                        else Result.failure(IOException("create reply missing target"))
                    )
                },
                onError = { text -> deferred.complete(Result.failure(IOException(text))) },
            )
        }
        sendJson(socket, mapOf(
            "kind" to "create",
            "seq" to seq,
            "type" to type,
            "props" to props,
        ))
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            synchronized(lock) { pending.remove(seq) }
            Result.failure(IOException("create timed out"))
        }
    }

    /**
     * Ask the server to destroy the authoritative object behind [handle].
     * Suspends until `unbind{seq}` or `error{seq}` arrives.
     *
     * Typical failure: "not my object" — the target is a proxy on the server
     * side, so authority lives elsewhere (hub forwarding isn't implemented).
     */
    suspend fun destroy(handle: Long, timeoutMs: Long = 5_000): Result<Unit> {
        val socket = ws ?: return Result.failure(IOException("sync socket not connected"))
        val seq = seqCounter.getAndIncrement()
        val deferred = CompletableDeferred<Result<Unit>>()
        synchronized(lock) {
            pending[seq] = PendingRequest(
                onReply = { _ -> deferred.complete(Result.success(Unit)) },
                onError = { text -> deferred.complete(Result.failure(IOException(text))) },
            )
        }
        sendJson(socket, mapOf("kind" to "destroy", "seq" to seq, "target" to citeHandle(handle)))
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            synchronized(lock) { pending.remove(seq) }
            Result.failure(IOException("destroy timed out"))
        }
    }

    /**
     * Request enum metadata by name. Returns a map of member-name → value
     * (values are numeric for numeric enums, strings otherwise).
     *
     * Suspends until the server replies with `enum{seq}` or `error{seq}`;
     * returns failure on timeout, disconnect, or if the socket isn't open.
     */
    suspend fun getEnum(type: String, timeoutMs: Long = 5_000): Result<Map<String, Any>> {
        val socket = ws ?: return Result.failure(IOException("sync socket not connected"))
        val seq = seqCounter.getAndIncrement()
        val deferred = CompletableDeferred<Result<Map<String, Any>>>()
        synchronized(lock) {
            pending[seq] = PendingRequest(
                onReply = { json ->
                    val members = json.get("members")
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.entrySet()
                        ?.associate { (k, v) -> k to (parseJsonValue(v) ?: v.toString()) }
                        ?: emptyMap()
                    deferred.complete(Result.success(members))
                },
                onError = { text -> deferred.complete(Result.failure(IOException(text))) },
            )
        }
        sendJson(socket, mapOf("kind" to "enum_req", "seq" to seq, "type" to type))
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            synchronized(lock) { pending.remove(seq) }
            Result.failure(IOException("enum_req timed out"))
        }
    }

    fun shutdown() {
        scope.cancel()
        ws?.close(1000, "client shutdown")
        ws = null
        model.handleClose()
        _connected.postValue(false)
        synchronized(lock) {
            pending.values.forEach { it.onError("client shutdown") }
            pending.clear()
        }
    }

    private fun sendJson(socket: WebSocket, msg: Map<String, Any?>) {
        socket.send(gson.toJson(msg))
    }

    /** Send a frame if the socket is open. Returns false when it isn't. */
    internal fun sendRaw(msg: Map<String, Any?>): Boolean {
        val socket = ws ?: return false
        sendJson(socket, msg)
        return true
    }

    /**
     * Send a seq-correlated request and await its reply frame. Used by the
     * model plane (acked by `res`/`err`) and other generic requests.
     */
    internal suspend fun request(
        msg: Map<String, Any?>,
        timeoutMs: Long = 10_000,
    ): Result<JsonObject> {
        val socket = ws ?: return Result.failure(IOException("sync socket not connected"))
        val seq = seqCounter.getAndIncrement()
        val deferred = CompletableDeferred<Result<JsonObject>>()
        synchronized(lock) {
            pending[seq] = PendingRequest(
                onReply = { json -> deferred.complete(Result.success(json)) },
                onError = { text -> deferred.complete(Result.failure(IOException(text))) },
            )
        }
        sendJson(socket, msg + ("seq" to seq))
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            synchronized(lock) { pending.remove(seq) }
            Result.failure(IOException("request timed out"))
        }
    }

    /**
     * Format a backend-allocated session handle for citing back to the
     * backend: the low bit marks it as receiver-allocated. Backend handles
     * are always even, so `h + 1` sets the bit without signed-bitwise traps.
     */
    private fun citeHandle(handle: Long): Long = handle + 1

    /** Backend session handles are even, non-negative, and fit in a uint32. */
    private fun isBackendHandle(handle: Long): Boolean =
        handle in 0..0xFFFF_FFFFL && handle % 2 == 0L

    private fun openSocket() {
        val url = buildWsUrl()
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, listener)
    }

    private fun buildWsUrl(): String {
        val scheme = if (server.useHttps) "wss" else "ws"
        return "$scheme://${server.hostname}:${server.port}/sync"
    }

    private fun scheduleReconnect() {
        synchronized(lock) {
            if (reconnectJob?.isActive == true) return
            ws = null
            _connected.postValue(false)
            // Everything received on this channel is orphaned; drop proxies,
            // the handle registry, and fail pending requests so callers retry.
            names.clear()
            sentPatterns.clear()
            store.clear()
            model.handleClose()
            pending.values.forEach { it.onError("disconnected") }
            pending.clear()
            reconnectJob = scope.launch {
                while (true) {
                    delay(2000)
                    if (checkHealth()) break
                }
                openSocket()
            }
        }
    }

    private fun checkHealth(): Boolean {
        return try {
            val req = Request.Builder().url("${server.baseUrl}/api/health").get().build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun handleMessage(text: String) {
        val json = gson.fromJson(text, JsonObject::class.java) ?: return
        val kind = json.get("kind")?.asString ?: return
        if (model.handleFrame(kind, json)) return
        val target = json.get("target")?.takeIf { !it.isJsonNull }?.asLong
        val seq = json.get("seq")?.takeIf { !it.isJsonNull }?.asInt ?: 0

        when (kind) {
            "add_name" -> {
                val handle = json.get("h")?.takeIf { !it.isJsonNull }?.asLong ?: return
                if (!isBackendHandle(handle)) return
                val name = json.get("name")?.asString ?: return
                val type = json.get("type")?.asString ?: return
                synchronized(lock) { names[handle] = NameEntry(name, type) }
            }
            "bind" -> {
                // The authority's invalid_handle is ulong.max — a bind citing
                // it means the object was never introduced. Drop the frame and
                // fail any request waiting on it.
                if (target == null || !isBackendHandle(target)) {
                    if (seq != 0) resolveError(seq, "bind cited an invalid handle")
                    return
                }
                val entry = synchronized(lock) { names[target] }
                val type = json.get("type")?.asString ?: entry?.type ?: ""
                val props = json.get("props")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.entrySet()
                    ?.associate { (k, v) -> k to parseJsonValue(v) }
                    ?: emptyMap()
                val named = if (entry != null) mapOf("name" to entry.name) + props else props
                store.bind(target, type, named)
                if (seq != 0) resolveReply(seq, json)
            }
            "unbind" -> {
                val handle = target ?: return
                store.unbind(handle)
                if (seq != 0) resolveReply(seq, json)
            }
            "set" -> {
                val handle = target ?: return
                val prop = json.get("prop")?.asString ?: return
                val value = parseJsonValue(json.get("value"))
                store.setProperty(handle, prop, value)
            }
            "reset" -> {
                val handle = target ?: return
                val prop = json.get("prop")?.asString ?: return
                // The echo carries the effective default when one exists;
                // its absence means the property has no default at all.
                val hasValue = json.has("value")
                val value = if (hasValue) parseJsonValue(json.get("value")) else null
                store.resetProperty(handle, prop, value, hasValue)
            }
            "state" -> {
                val handle = target ?: return
                val signal = json.get("signal")?.asString ?: return
                store.stateChange(handle, signal)
            }
            "result", "enum", "res", "history" -> {
                if (seq != 0) resolveReply(seq, json)
            }
            "error", "err" -> {
                if (seq != 0) {
                    val textMsg = json.get("text")?.asString ?: "unknown error"
                    resolveError(seq, textMsg)
                }
            }
        }
    }

    private fun resolveReply(seq: Int, json: JsonObject) {
        val req = synchronized(lock) { pending.remove(seq) } ?: return
        req.onReply(json)
    }

    private fun resolveError(seq: Int, text: String) {
        val req = synchronized(lock) { pending.remove(seq) } ?: return
        req.onError(text)
    }

    private class PendingRequest(
        val onReply: (JsonObject) -> Unit,
        val onError: (String) -> Unit,
    )

    private class NameEntry(val name: String, val type: String)

    internal fun parseValue(element: JsonElement?): Any? = parseJsonValue(element)

    private fun parseJsonValue(element: JsonElement?): Any? {
        if (element == null) return null
        return when {
            element.isJsonNull -> null
            element.isJsonPrimitive -> {
                val prim = element.asJsonPrimitive
                when {
                    prim.isBoolean -> prim.asBoolean
                    prim.isNumber -> {
                        val num = prim.asDouble
                        if (num == num.toLong().toDouble()) num.toLong() else num
                    }
                    else -> prim.asString
                }
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                // Quantity objects {q, u} are preserved as-is so unit-aware UI can format them.
                if (obj.has("q")) {
                    val q = obj.get("q")?.let { parseJsonValue(it) }
                    val u = obj.get("u")?.takeIf { !it.isJsonNull }?.asString
                    if (u != null) mapOf("q" to q, "u" to u) else q
                } else {
                    obj.entrySet().associate { (k, v) -> k to parseJsonValue(v) }
                }
            }
            element.isJsonArray -> element.asJsonArray.map { parseJsonValue(it) }
            else -> element.toString()
        }
    }
}
