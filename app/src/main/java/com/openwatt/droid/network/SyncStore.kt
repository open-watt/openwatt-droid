package com.openwatt.droid.network

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.openwatt.droid.model.SyncItem

/**
 * In-memory store of proxies learned via the sync WebSocket, keyed by
 * session handle. Publishes the full map on each mutation via [items].
 *
 * Single global store per SyncClient — subscribers filter by type name
 * (or look up a specific handle) rather than getting per-pattern channels,
 * because the server's messages don't carry pattern routing info.
 *
 * Handles are session-scoped: the client clears the store on every attach
 * boundary, so a handle never outlives the socket that introduced it.
 */
class SyncStore {
    private val lock = Any()
    private val map = mutableMapOf<Long, SyncItem>()
    /** Schema defaults, keyed by type name. Used to seed / restore unset props. */
    private var typeDefaults: Map<String, Map<String, Any?>> = emptyMap()

    private val _items = MutableLiveData<Map<Long, SyncItem>>(emptyMap())
    val items: LiveData<Map<Long, SyncItem>> = _items

    fun getItem(handle: Long): SyncItem? = synchronized(lock) { map[handle] }

    /**
     * Look an item up by its persistent identity. Used to re-acquire a fresh
     * handle after a reconnect invalidates the one a screen was holding.
     */
    fun findItem(type: String, name: String): SyncItem? = synchronized(lock) {
        map.values.firstOrNull { it.type == type && it.name == name }
    }

    /**
     * Register schema defaults per type — called once per server after `/api/schema`
     * loads, before the sync session starts. Any already-bound items are re-seeded
     * so late-arriving schemas still populate unset properties.
     */
    fun setTypeDefaults(defaults: Map<String, Map<String, Any?>>) {
        synchronized(lock) {
            typeDefaults = defaults
            if (map.isEmpty()) return
            for ((handle, item) in map.toMap()) {
                val td = defaults[item.type] ?: continue
                val setValues = item.properties.filterKeys { it in item.setProps }
                map[handle] = item.copy(properties = td + setValues)
            }
            publish()
        }
    }

    /**
     * Install or refresh a proxy with its authoritative initial property set.
     *
     * [props] is the full set of explicitly-set properties on the authoritative
     * object at bind time. Unset properties are seeded from schema defaults so
     * the UI always has something meaningful to show; [setProps] only records
     * what the backend has actually set.
     */
    fun bind(handle: Long, type: String, props: Map<String, Any?>) {
        synchronized(lock) {
            val existing = map[handle]
            val defaults = typeDefaults[type] ?: emptyMap()
            map[handle] = SyncItem(
                handle = handle,
                type = type,
                properties = defaults + props,
                setProps = props.keys.toSet(),
                state = existing?.state,
            )
            publish()
        }
    }

    fun unbind(handle: Long) {
        synchronized(lock) {
            if (map.remove(handle) != null) publish()
        }
    }

    fun setProperty(handle: Long, property: String, value: Any?) {
        synchronized(lock) {
            val item = map[handle] ?: return
            map[handle] = item.copy(
                properties = item.properties + (property to value),
                setProps = item.setProps + property,
            )
            publish()
        }
    }

    /**
     * Revert a property to its default. The server's `reset` echo carries the
     * effective default when one exists ([hasValue]); otherwise fall back to
     * the schema default, or drop the property entirely.
     */
    fun resetProperty(handle: Long, property: String, value: Any? = null, hasValue: Boolean = false) {
        synchronized(lock) {
            val item = map[handle] ?: return
            val td = typeDefaults[item.type]
            val newProps = when {
                hasValue -> item.properties + (property to value)
                td != null && property in td -> item.properties + (property to td[property])
                else -> item.properties - property
            }
            map[handle] = item.copy(
                properties = newProps,
                setProps = item.setProps - property,
            )
            publish()
        }
    }

    fun stateChange(handle: Long, signal: String) {
        synchronized(lock) {
            val item = map[handle] ?: return
            map[handle] = item.copy(state = signal)
            publish()
        }
    }

    fun clear() {
        synchronized(lock) {
            if (map.isEmpty()) return
            map.clear()
            publish()
        }
    }

    private fun publish() {
        _items.postValue(map.toMap())
    }
}
