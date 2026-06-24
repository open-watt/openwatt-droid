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
 * Protocol model: messages split across two axes — authoritative vs proxy, and
 * request vs informational. Informational pushes (spontaneous bind/unbind/set/
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

    private val _connected = MutableLiveData(false)
    val connected: LiveData<Boolean> = _connected

    private val lock = Any()
    private val subRefCounts = mutableMapOf<String, Int>()

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
                for (pattern in subRefCounts.keys) {
                    sendJson(webSocket, mapOf("kind" to "sub", "pattern" to pattern))
                }
            }
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
            if (c == 0) {
                ws?.let { sendJson(it, mapOf("kind" to "sub", "pattern" to pattern)) }
            }
        }
    }

    fun unsubscribe(pattern: String) {
        synchronized(lock) {
            val c = subRefCounts.getOrDefault(pattern, 0)
            if (c <= 1) {
                subRefCounts.remove(pattern)
                ws?.let { sendJson(it, mapOf("kind" to "unsub", "pattern" to pattern)) }
            } else {
                subRefCounts[pattern] = c - 1
            }
        }
    }

    fun setProperty(cid: Int, property: String, value: Any?) {
        val socket = ws ?: return
        sendJson(socket, mapOf(
            "kind" to "set",
            "target" to cidWire(cid),
            "prop" to property,
            "value" to value,
        ))
    }

    fun resetProperty(cid: Int, property: String) {
        val socket = ws ?: return
        sendJson(socket, mapOf(
            "kind" to "reset",
            "target" to cidWire(cid),
            "prop" to property,
        ))
    }

    /**
     * Ask the server to instantiate an authoritative object of [type] with
     * [props] as its initial properties. `name` must be present in props.
     *
     * Suspends until `bind{seq}` (success) or `error{seq}` (rejection) arrives.
     * On success returns the new CID; by the time this returns, `store.bind`
     * has already installed the proxy, so the caller can look it up
     * immediately via the store.
     */
    suspend fun create(
        type: String,
        props: Map<String, Any?>,
        timeoutMs: Long = 5_000,
    ): Result<Int> {
        val socket = ws ?: return Result.failure(IOException("sync socket not connected"))
        val seq = seqCounter.getAndIncrement()
        val deferred = CompletableDeferred<Result<Int>>()
        synchronized(lock) {
            pending[seq] = PendingRequest(
                onReply = { json ->
                    val cid = json.get("target")?.takeIf { !it.isJsonNull }?.asLong?.toInt()
                    deferred.complete(
                        if (cid != null) Result.success(cid)
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
     * Ask the server to destroy the authoritative object behind [cid].
     * Suspends until `unbind{seq}` or `error{seq}` arrives.
     *
     * Typical failure: "not my object" — the target is a proxy on the server
     * side, so authority lives elsewhere (hub forwarding isn't implemented).
     */
    suspend fun destroy(cid: Int, timeoutMs: Long = 5_000): Result<Unit> {
        val socket = ws ?: return Result.failure(IOException("sync socket not connected"))
        val seq = seqCounter.getAndIncrement()
        val deferred = CompletableDeferred<Result<Unit>>()
        synchronized(lock) {
            pending[seq] = PendingRequest(
                onReply = { _ -> deferred.complete(Result.success(Unit)) },
                onError = { text -> deferred.complete(Result.failure(IOException(text))) },
            )
        }
        sendJson(socket, mapOf("kind" to "destroy", "seq" to seq, "target" to cidWire(cid)))
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
        _connected.postValue(false)
        synchronized(lock) {
            pending.values.forEach { it.onError("client shutdown") }
            pending.clear()
        }
    }

    private fun sendJson(socket: WebSocket, msg: Map<String, Any?>) {
        socket.send(gson.toJson(msg))
    }

    /**
     * CIDs are uint32, but a high collection_id sets bit 31 and makes the Kotlin
     * Int negative. Serialize the unsigned value so the backend receives the
     * correct CID. Returned as a Long (not UInt) because gson can't serialize
     * Kotlin's UInt value class — it would reflect into it and emit the wrong
     * (signed) number.
     */
    private fun cidWire(cid: Int): Long = cid.toUInt().toLong()

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
            // Everything received on this channel is orphaned; drop proxies and
            // fail any pending requests so callers can retry.
            store.clear()
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
        val target = json.get("target")?.takeIf { !it.isJsonNull }?.asLong?.toInt()
        val seq = json.get("seq")?.takeIf { !it.isJsonNull }?.asInt ?: 0

        when (kind) {
            "bind" -> {
                val cid = target ?: return
                val type = json.get("type")?.asString ?: ""
                val props = json.get("props")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.entrySet()
                    ?.associate { (k, v) -> k to parseJsonValue(v) }
                    ?: emptyMap()
                store.bind(cid, type, props)
                if (seq != 0) resolveReply(seq, json)
            }
            "unbind" -> {
                val cid = target ?: return
                store.unbind(cid)
                if (seq != 0) resolveReply(seq, json)
            }
            "set" -> {
                val cid = target ?: return
                val prop = json.get("prop")?.asString ?: return
                val value = parseJsonValue(json.get("value"))
                store.setProperty(cid, prop, value)
            }
            "reset" -> {
                val cid = target ?: return
                val prop = json.get("prop")?.asString ?: return
                store.resetProperty(cid, prop)
            }
            "state" -> {
                val cid = target ?: return
                val signal = json.get("signal")?.asString ?: return
                store.stateChange(cid, signal)
            }
            "result", "enum" -> {
                if (seq != 0) resolveReply(seq, json)
            }
            "error" -> {
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
