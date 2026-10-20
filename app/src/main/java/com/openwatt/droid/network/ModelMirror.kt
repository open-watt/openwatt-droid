package com.openwatt.droid.network

import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Model-plane mirror for the /sync data-model space. Kotlin port of the web
 * client's `js/api/model-mirror.js`.
 *
 * The backend serves the `device` namespace as handle-bound nodes: `type`
 * interns formats and enums before first cite, `add` binds a handle to a path
 * carrying schema, `val` feeds samples, tagged with a one-shot read's seq.
 * Views read the mirror instead of polling `/api/get`.
 *
 * Wire shapes (manager/sync/json_encoder.d):
 *   hello {ver, host, caps[], encoders[], max_frame}
 *   type  {ft, format:{type, series, count?, rate?, unit?, enum?, min?, max?, step?}}
 *   type  {name, members:{...}}
 *   add   {h, path, class, ft?, access?, mode?}
 *   val   {seq?, h, s:[[t,v],...], lost?}
 *   res   {seq, value?} | err {seq, code, text}
 *
 * Threading: frames arrive on the WebSocket reader thread; all state is
 * guarded by [lock]. Listener callbacks fire on background threads —
 * consumers post to LiveData / main as needed.
 */
class ModelMirror internal constructor(private val sync: SyncClient) {

    /** A live element node. Value fields mutate in place as `val` frames land. */
    class Node(
        val handle: Long,
        val ns: String,
        val path: String,
        val cls: String,
        val ft: Int?,
        val access: String,
        val mode: String,
        @Volatile var value: Any?,
        @Volatile var unit: String,
        @Volatile var time: Long,
        @Volatile var lost: Long,
    ) {
        val isDevice: Boolean get() = cls == "device"
    }

    data class Format(
        val type: String,
        val series: String,
        val count: Int,
        val rate: Double,
        val unit: String,
        val enumName: String,
        val min: Double?,
        val max: Double?,
        val step: Double?,
    )

    data class HelloInfo(
        val ver: Int,
        val host: String,
        val caps: List<String>,
        val maxFrame: Long,
    )

    /** One timestamped sample from a `val` frame. Time is unix ms. */
    data class Sample(val time: Long, val value: Any?)

    /** `/api/get`-shaped read of a node: value + unit + seconds since update. */
    data class SnapshotEntry(val value: Any?, val unit: String, val ageSeconds: Double?)

    /** Refcounted holder token for a live subscription. */
    inner class Subscription internal constructor(
        private val pattern: String,
        private val token: Any,
        /** Completes true once the initial burst was served, false if it never reached the wire. */
        val ready: CompletableDeferred<Boolean>,
    ) {
        fun release() = this@ModelMirror.release(pattern, token)
    }

    interface Listener {
        /** The backend announced its session capabilities. */
        fun onHello(info: HelloInfo) {}
        /** A node was introduced (or re-announced). */
        fun onAdd(node: Node) {}
        /** Samples arrived for a node (already folded into the node's latest value). */
        fun onVal(node: Node, samples: List<Sample>, lost: Long) {}
        /** Coalesced change tick: the set of nodes touched since the last one. */
        fun onChange(dirty: Set<Node>) {}
    }

    private val lock = Any()

    @Volatile
    var hello: HelloInfo? = null
        private set

    /** True once the backend has announced a model-capable session. */
    val available: Boolean get() = hello?.caps?.contains("model") == true

    private val formats = mutableMapOf<Int, Format>()
    private val enums = mutableMapOf<String, Map<String, Any?>>()
    private val nodes = mutableMapOf<Long, Node>()
    private val byPath = mutableMapOf<String, Node>()

    private class SubEntry(val from: Long, val to: Long) {
        val holders = mutableSetOf<Any>()
        var ready = CompletableDeferred<Boolean>()
    }

    private val subs = mutableMapOf<String, SubEntry>()
    private val pendingSub = mutableSetOf<String>()
    private val pendingUnsub = mutableSetOf<String>()
    private var flushJob: Job? = null

    private val listeners = CopyOnWriteArraySet<Listener>()

    /**
     * Nodes touched since the last change event. A live feed updates a
     * handful of nodes per tick out of thousands, so consumers are told
     * which rather than re-deriving everything.
     */
    private var dirty = mutableSetOf<Node>()
    private var changeJob: Job? = null

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /**
     * Arm a live subscription. Refcounted by exact pattern string because the
     * backend keys its armed set the same way: a single `unsub` disarms every
     * node the pattern covers, however many local views wanted it.
     *
     * [from] (unix ms) asks for backfill-then-follow: the window is served
     * inside the initial burst and the live feed takes over gaplessly where
     * it ends. It applies to that burst only, so a second holder of an
     * already-armed pattern inherits the feed without a fresh backfill.
     */
    fun subscribe(pattern: String, from: Long = 0, to: Long = 0): Subscription {
        val token = Any()
        val entry = synchronized(lock) {
            val entry = subs.getOrPut(pattern) {
                SubEntry(from, to).also {
                    pendingUnsub.remove(pattern)
                    pendingSub.add(pattern)
                    scheduleFlush()
                }
            }
            entry.holders.add(token)
            entry
        }
        return Subscription(pattern, token, entry.ready)
    }

    /**
     * One-shot read. `once` subscriptions never arm a live feed, so they can
     * overlap anything. Pass [from]/[to] for a history burst.
     * Returns true if the burst was served, false if the socket was down.
     */
    suspend fun fetch(patterns: List<String>, from: Long = 0, to: Long = 0): Boolean =
        sendSub(patterns, once = true, from = from, to = to)

    /** Write an element value; returns the authority's applied value. */
    suspend fun setValue(path: String, value: Any?): Result<Any?> {
        val subject = subjectOf(path)
        val node = synchronized(lock) { byPath[subject] }
        val msg = if (node != null) {
            mapOf("kind" to "set", "h" to citeHandle(node.handle), "value" to value)
        } else {
            mapOf("kind" to "set", "path" to "device:$subject", "value" to value)
        }
        return sync.request(msg).map { json -> sync.parseValue(json.get("value")) }
    }

    fun getNode(path: String): Node? = synchronized(lock) { byPath[subjectOf(path)] }

    fun getValue(path: String): Any? = getNode(path)?.value

    fun getFormat(path: String): Format? {
        val node = getNode(path) ?: return null
        return synchronized(lock) { node.ft?.let { formats[it] } }
    }

    fun getUnit(path: String): String {
        val node = getNode(path) ?: return ""
        if (node.unit.isNotEmpty()) return node.unit
        return synchronized(lock) { node.ft?.let { formats[it]?.unit } } ?: ""
    }

    /** Enum members for an element whose format cites a named type. */
    fun getEnumMembers(path: String): Map<String, Any?>? {
        val name = getFormat(path)?.enumName?.takeIf { it.isNotEmpty() } ?: return null
        return synchronized(lock) { enums[name] }
    }

    /** Nodes matching a `ns:subject` address pattern. */
    fun list(pattern: String): List<Node> {
        val address = parseAddress(pattern) ?: return emptyList()
        return synchronized(lock) {
            nodes.values.filter {
                segmentMatches(address.ns, it.ns) && matchPath(address.subject, it.path)
            }
        }
    }

    /**
     * Matching nodes in the /api/get response shape, so consumers written
     * against that endpoint can read the mirror unchanged.
     */
    fun snapshot(pattern: String, now: Long = System.currentTimeMillis()): Map<String, SnapshotEntry> {
        val result = mutableMapOf<String, SnapshotEntry>()
        for (node in list(pattern)) {
            if (node.isDevice) continue
            val unit = node.unit.ifEmpty {
                synchronized(lock) { node.ft?.let { formats[it]?.unit } } ?: ""
            }
            val age = if (node.time > 0) ((now - node.time).coerceAtLeast(0)) / 1000.0 else null
            result[node.path] = SnapshotEntry(node.value, unit, age)
        }
        return result
    }

    // -- session lifecycle, driven by SyncClient --

    /** Handles are scoped to one attach; re-arm every live pattern. */
    internal fun handleOpen() {
        synchronized(lock) {
            resetStateLocked()
            pendingSub.clear()
            pendingUnsub.clear()
            for ((pattern, entry) in subs) {
                entry.ready = CompletableDeferred()
                pendingSub.add(pattern)
            }
            if (subs.isNotEmpty()) scheduleFlush()
        }
        scheduleChange()
    }

    internal fun handleClose() {
        synchronized(lock) { resetStateLocked() }
        scheduleChange()
    }

    private fun resetStateLocked() {
        dirty.clear()
        hello = null
        formats.clear()
        enums.clear()
        nodes.clear()
        byPath.clear()
    }

    // -- frame handling --

    /** Returns true if the frame belonged to the model plane. */
    internal fun handleFrame(kind: String, json: JsonObject): Boolean {
        when (kind) {
            "hello" -> onHello(json)
            "type" -> onType(json)
            "add" -> onAdd(json)
            "val" -> onVal(json)
            else -> return false
        }
        return true
    }

    private fun onHello(json: JsonObject) {
        val info = HelloInfo(
            ver = json.get("ver")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            host = json.get("host")?.takeIf { !it.isJsonNull }?.asString ?: "",
            caps = json.get("caps")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } ?: emptyList(),
            maxFrame = json.get("max_frame")?.takeIf { !it.isJsonNull }?.asLong ?: 0,
        )
        hello = info
        listeners.forEach { it.onHello(info) }
    }

    private fun onType(json: JsonObject) {
        val format = json.get("format")?.takeIf { it.isJsonObject }?.asJsonObject
        if (format != null) {
            val ft = json.get("ft")?.takeIf { !it.isJsonNull }?.asInt ?: return
            val parsed = Format(
                type = format.get("type")?.asString ?: "",
                series = format.get("series")?.asString ?: "",
                count = format.get("count")?.takeIf { !it.isJsonNull }?.asInt ?: 1,
                rate = format.get("rate")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0,
                unit = format.get("unit")?.takeIf { !it.isJsonNull }?.asString ?: "",
                enumName = format.get("enum")?.takeIf { !it.isJsonNull }?.asString ?: "",
                min = format.get("min")?.takeIf { !it.isJsonNull }?.asDouble,
                max = format.get("max")?.takeIf { !it.isJsonNull }?.asDouble,
                step = format.get("step")?.takeIf { !it.isJsonNull }?.asDouble,
            )
            synchronized(lock) { formats[ft] = parsed }
            return
        }
        val name = json.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return
        val members = json.get("members")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.entrySet()?.associate { (k, v) -> k to sync.parseValue(v) } ?: emptyMap()
        synchronized(lock) { enums[name] = members }
    }

    private fun onAdd(json: JsonObject) {
        val handle = json.get("h")?.takeIf { !it.isJsonNull }?.asLong ?: return
        val address = json.get("path")?.takeIf { !it.isJsonNull }?.asString
            ?.let { parseAddress(it) } ?: return

        // The wire spells access as D enum keys; normalise to the /api/list
        // vocabulary at the boundary rather than at every consumer.
        val access = when (json.get("access")?.takeIf { !it.isJsonNull }?.asString) {
            "none" -> ""
            null, "read" -> "r"
            "write" -> "w"
            "read_write" -> "rw"
            else -> "r"
        }

        // absent `v` means the element has never produced a value; a null
        // sample means NaN/unknown. Neither is zero.
        val hasValue = json.has("v")
        val carried = if (hasValue) unpackQuantity(sync.parseValue(json.get("v"))) else null

        val node: Node
        synchronized(lock) {
            val existing = nodes[handle]
            if (existing != null && existing.path != address.subject) {
                byPath.remove(existing.path)
            }
            node = Node(
                handle = handle,
                ns = address.ns,
                path = address.subject,
                cls = json.get("class")?.takeIf { !it.isJsonNull }?.asString ?: "element",
                ft = json.get("ft")?.takeIf { !it.isJsonNull }?.asInt,
                access = access,
                mode = json.get("mode")?.takeIf { !it.isJsonNull }?.asString ?: "manual",
                value = if (carried != null) carried.first else existing?.value,
                // a quantity carries its own unit, which beats the format's
                unit = carried?.second?.takeIf { it.isNotEmpty() } ?: existing?.unit ?: "",
                time = json.get("t")?.takeIf { !it.isJsonNull }?.asLong ?: existing?.time ?: 0,
                lost = existing?.lost ?: 0,
            )
            nodes[handle] = node
            byPath[node.path] = node
            dirty.add(node)
        }
        listeners.forEach { it.onAdd(node) }
        scheduleChange()
    }

    private fun onVal(json: JsonObject) {
        val handle = json.get("h")?.takeIf { !it.isJsonNull }?.asLong ?: return
        val node = synchronized(lock) { nodes[handle] } ?: return

        val samples = mutableListOf<Sample>()
        var unit = ""
        json.get("s")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { sample ->
            val arr = sample.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
            if (arr.size() < 2) return@forEach
            val time = arr.get(0).takeIf { !it.isJsonNull }?.asLong ?: return@forEach
            val (value, sampleUnit) = unpackQuantity(sync.parseValue(arr.get(1)))
            if (sampleUnit.isNotEmpty()) unit = sampleUnit
            samples.add(Sample(time, value))
        }
        if (samples.isEmpty()) return

        val lost = json.get("lost")?.takeIf { !it.isJsonNull }?.asLong ?: 0
        synchronized(lock) {
            if (lost > 0) node.lost += lost
            val latest = samples.last()
            if (latest.time >= node.time) {
                node.value = latest.value
                node.time = latest.time
                if (unit.isNotEmpty()) node.unit = unit
            }
            dirty.add(node)
        }
        listeners.forEach { it.onVal(node, samples, lost) }
        scheduleChange()
    }

    // -- internal --

    private suspend fun sendSub(patterns: List<String>, once: Boolean = false, from: Long = 0, to: Long = 0): Boolean {
        val msg = buildMap<String, Any?> {
            put("kind", "sub")
            put("patterns", patterns)
            if (once) put("once", true)
            if (from > 0) {
                put("from", from)
                // `to` without `from` serves no backfill at the authority.
                if (to > 0) put("to", to)
            }
        }
        return sync.request(msg).isSuccess
    }

    private fun release(pattern: String, token: Any) {
        synchronized(lock) {
            val entry = subs[pattern] ?: return
            if (!entry.holders.remove(token)) return
            if (entry.holders.isNotEmpty()) return

            subs.remove(pattern)
            if (pendingSub.remove(pattern)) {
                entry.ready.complete(false)     // never reached the wire
                return
            }
            pendingUnsub.add(pattern)
            scheduleFlush()
            // Nodes deliberately survive: the authority keeps the handle
            // binding, so a later re-sub re-serves `val` with no fresh `add`.
        }
    }

    /**
     * Coalesce a turn's worth of pattern changes into one frame each way.
     *
     * The authority walks every device once per pattern in a `sub`, and
     * rebuilds its entire armed set once per `unsub` *frame* — so a view
     * swapping several patterns at once (a device card opening, or the first
     * paint arming one pattern per summary path) must not spend a frame on
     * each. Batched, an expand costs one rebuild instead of one per pattern.
     */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = sync.ioScope.launch {
            delay(FLUSH_DELAY_MS)
            flushPending()
        }
    }

    private fun flushPending() {
        val unsubs: List<String>
        val groups = mutableMapOf<String, MutableList<String>>()
        val windows = mutableMapOf<String, Pair<Long, Long>>()
        synchronized(lock) {
            unsubs = pendingUnsub.toList()
            pendingUnsub.clear()
            // `from`/`to` ride the frame, so patterns wanting different
            // windows cannot share one.
            for (pattern in pendingSub) {
                val entry = subs[pattern] ?: continue
                val key = "${entry.from}:${entry.to}"
                groups.getOrPut(key) { mutableListOf() }.add(pattern)
                windows[key] = entry.from to entry.to
            }
            pendingSub.clear()
        }
        if (unsubs.isNotEmpty()) {
            sync.sendRaw(mapOf("kind" to "unsub", "patterns" to unsubs))
        }
        for ((key, patterns) in groups) {
            val (from, to) = windows.getValue(key)
            sync.ioScope.launch {
                val served = sendSub(patterns, from = from, to = to)
                synchronized(lock) {
                    for (pattern in patterns) subs[pattern]?.ready?.complete(served)
                }
            }
        }
    }

    private fun scheduleChange() {
        if (changeJob?.isActive == true) return
        changeJob = sync.ioScope.launch {
            delay(CHANGE_INTERVAL_MS)
            val snapshot: Set<Node>
            synchronized(lock) {
                snapshot = dirty
                dirty = mutableSetOf()
            }
            listeners.forEach { it.onChange(snapshot) }
        }
    }

    companion object {
        /** Coalescing interval for change events (matches the web mirror). */
        const val CHANGE_INTERVAL_MS = 100L

        /** Sub/unsub batching delay — the coroutine stand-in for a microtask. */
        private const val FLUSH_DELAY_MS = 5L

        /**
         * Split a quantity into (scalar, unit). urt's write_json serialises a
         * Quantity as {"q":scalar,"u":"unit"} (NaN as {"q":null,...}); every
         * consumer here expects value + separate unit, so unpack at the boundary.
         */
        fun unpackQuantity(value: Any?): Pair<Any?, String> {
            if (value is Map<*, *> && value.containsKey("q")) {
                return value["q"] to ((value["u"] as? String) ?: "")
            }
            return value to ""
        }

        data class Address(val ns: String, val subject: String, val exact: Boolean)

        /** Split `[=]ns:subject` into its parts, or null if there is no namespace. */
        fun parseAddress(pattern: String): Address? {
            var text = pattern
            val exact = text.startsWith("=")
            if (exact) text = text.substring(1)
            val colon = text.indexOf(':')
            if (colon < 0) return null
            return Address(text.substring(0, colon), text.substring(colon + 1), exact)
        }

        fun subjectOf(path: String): String = parseAddress(path)?.subject ?: path

        internal fun segmentMatches(pattern: String, segment: String): Boolean {
            if (pattern == "*") return true
            if ('*' !in pattern) return pattern == segment
            val source = pattern.split('*').joinToString("[^.]*") { Regex.escape(it) }
            return Regex("^$source$").matches(segment)
        }

        /**
         * Dotted segment-wise match, mirroring manager/path.d: `*` matches one
         * segment, `**` matches zero or more. The `~` optionals the backend
         * also accepts are not supported here; the client only ever matches
         * patterns it generated itself.
         */
        fun matchPath(pattern: String, path: String): Boolean {
            if (pattern.isEmpty()) return path.isEmpty()

            val patternDot = pattern.indexOf('.')
            val head = if (patternDot < 0) pattern else pattern.substring(0, patternDot)
            val patternRest = if (patternDot < 0) "" else pattern.substring(patternDot + 1)

            if (head == "**") {
                if (matchPath(patternRest, path)) return true
                if (path.isEmpty()) return false
                val dot = path.indexOf('.')
                return matchPath(pattern, if (dot < 0) "" else path.substring(dot + 1))
            }

            if (path.isEmpty()) return false
            val dot = path.indexOf('.')
            val segment = if (dot < 0) path else path.substring(0, dot)
            val rest = if (dot < 0) "" else path.substring(dot + 1)
            return segmentMatches(head, segment) && matchPath(patternRest, rest)
        }

        /**
         * Fold new samples into a time-ordered series in place.
         *
         * Backfill and the live feed overlap by design — the authority arms
         * the feed before serving the window — so identical timestamps are
         * expected and the later arrival wins. [since] trims the trailing
         * edge of a rolling window. Returns whether the series changed.
         */
        fun mergeSamples(
            series: MutableList<Sample>,
            incoming: List<Sample>,
            since: Long = 0,
            limit: Int = 5000,
        ): Boolean {
            val fresh = incoming.filter { it.time >= since }
            if (fresh.isEmpty()) return false

            val last = series.lastOrNull()
            if (last == null || fresh.first().time > last.time) {
                // ordinary case: the feed only ever moves forward
                series.addAll(fresh)
            } else {
                val byTime = LinkedHashMap<Long, Sample>(series.size + fresh.size)
                for (sample in series) byTime[sample.time] = sample
                for (sample in fresh) byTime[sample.time] = sample
                val merged = byTime.values.sortedBy { it.time }
                series.clear()
                series.addAll(merged)
            }

            val stale = series.indexOfFirst { it.time >= since }
            if (stale > 0) repeat(stale) { series.removeAt(0) }
            while (series.size > limit) series.removeAt(0)
            return true
        }

        /**
         * Citing a backend-allocated handle back sets the low bit to mark it
         * receiver-allocated. Handles are even, so `h + 1` sets the bit.
         */
        internal fun citeHandle(handle: Long): Long = handle + 1
    }
}
