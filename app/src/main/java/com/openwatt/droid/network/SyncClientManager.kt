package com.openwatt.droid.network

import com.openwatt.droid.model.Server

/**
 * Process-wide registry of SyncClients, one per server.
 *
 * Clients are created lazily and kept alive for the lifetime of the process;
 * the WebSocket is cheap to leave open and dropping/reopening it per view
 * would thrash the subscription state. If a server is removed from the
 * app entirely, call [remove] to close its connection.
 */
object SyncClientManager {
    private val clients = mutableMapOf<String, SyncClient>()

    fun get(server: Server): SyncClient {
        return synchronized(clients) {
            clients.getOrPut(server.id) { SyncClient(server) }
        }
    }

    fun remove(serverId: String) {
        synchronized(clients) {
            clients.remove(serverId)?.shutdown()
        }
    }
}
