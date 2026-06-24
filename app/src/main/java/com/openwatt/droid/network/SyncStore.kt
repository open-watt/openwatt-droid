package com.openwatt.droid.network

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.openwatt.droid.model.SyncItem

/**
 * In-memory store of proxies learned via the sync WebSocket, keyed by CID.
 * Publishes the full map on each mutation via [items].
 *
 * Single global store per SyncClient — subscribers filter by collectionId
 * (or look up a specific CID) rather than getting per-pattern channels,
 * because the server's messages don't carry pattern routing info.
 */
class SyncStore {
    private val lock = Any()
    private val map = mutableMapOf<Int, SyncItem>()
    /** Schema defaults, keyed by type name. Used to seed / restore unset props. */
    private var typeDefaults: Map<String, Map<String, Any?>> = emptyMap()

    private val _items = MutableLiveData<Map<Int, SyncItem>>(emptyMap())
    val items: LiveData<Map<Int, SyncItem>> = _items

    fun getItem(cid: Int): SyncItem? = synchronized(lock) { map[cid] }

    /**
     * Register schema defaults per type — called once per server after `/api/schema`
     * loads, before the sync session starts. Any already-bound items are re-seeded
     * so late-arriving schemas still populate unset properties.
     */
    fun setTypeDefaults(defaults: Map<String, Map<String, Any?>>) {
        synchronized(lock) {
            typeDefaults = defaults
            if (map.isEmpty()) return
            for ((cid, item) in map.toMap()) {
                val td = defaults[item.type] ?: continue
                val setValues = item.properties.filterKeys { it in item.setProps }
                map[cid] = item.copy(properties = td + setValues)
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
    fun bind(cid: Int, type: String, props: Map<String, Any?>) {
        synchronized(lock) {
            val existing = map[cid]
            val defaults = typeDefaults[type] ?: emptyMap()
            map[cid] = SyncItem(
                cid = cid,
                type = type,
                properties = defaults + props,
                setProps = props.keys.toSet(),
                state = existing?.state,
            )
            publish()
        }
    }

    fun unbind(cid: Int) {
        synchronized(lock) {
            if (map.remove(cid) != null) publish()
        }
    }

    fun setProperty(cid: Int, property: String, value: Any?) {
        synchronized(lock) {
            val item = map[cid] ?: return
            map[cid] = item.copy(
                properties = item.properties + (property to value),
                setProps = item.setProps + property,
            )
            publish()
        }
    }

    /** Revert a property to its schema default (or drop it if no default). */
    fun resetProperty(cid: Int, property: String) {
        synchronized(lock) {
            val item = map[cid] ?: return
            val td = typeDefaults[item.type]
            val newProps = if (td != null && property in td) {
                item.properties + (property to td[property])
            } else {
                item.properties - property
            }
            map[cid] = item.copy(
                properties = newProps,
                setProps = item.setProps - property,
            )
            publish()
        }
    }

    fun stateChange(cid: Int, signal: String) {
        synchronized(lock) {
            val item = map[cid] ?: return
            map[cid] = item.copy(state = signal)
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
