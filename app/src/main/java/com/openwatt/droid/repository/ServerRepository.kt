package com.openwatt.droid.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openwatt.droid.model.Server
import java.util.UUID

class ServerRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getAllServers(): List<Server> {
        val json = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        val type = object : TypeToken<List<Server>>() {}.type
        return gson.fromJson(json, type)
    }

    fun getServer(id: String): Server? {
        return getAllServers().find { it.id == id }
    }

    fun addServer(name: String, hostname: String, port: Int = 80, useHttps: Boolean = false): Server {
        val server = Server(
            id = UUID.randomUUID().toString(),
            name = name,
            hostname = hostname,
            port = port,
            useHttps = useHttps
        )

        val servers = getAllServers().toMutableList()
        servers.add(server)
        saveServers(servers)

        return server
    }

    fun updateServer(server: Server) {
        val servers = getAllServers().toMutableList()
        val index = servers.indexOfFirst { it.id == server.id }
        if (index != -1) {
            servers[index] = server
            saveServers(servers)
        }
    }

    fun deleteServer(id: String) {
        val servers = getAllServers().toMutableList()
        servers.removeAll { it.id == id }
        saveServers(servers)

        if (getCurrentServerId() == id) {
            clearCurrentServer()
        }
    }

    fun getCurrentServerId(): String? {
        return prefs.getString(KEY_CURRENT_SERVER, null)
    }

    fun getCurrentServer(): Server? {
        val id = getCurrentServerId() ?: return null
        return getServer(id)
    }

    fun setCurrentServer(id: String) {
        prefs.edit().putString(KEY_CURRENT_SERVER, id).apply()
    }

    fun clearCurrentServer() {
        prefs.edit().remove(KEY_CURRENT_SERVER).apply()
    }

    private fun saveServers(servers: List<Server>) {
        val json = gson.toJson(servers)
        prefs.edit().putString(KEY_SERVERS, json).apply()
    }

    companion object {
        private const val PREFS_NAME = "openwatt_servers"
        private const val KEY_SERVERS = "servers"
        private const val KEY_CURRENT_SERVER = "current_server"
    }
}
