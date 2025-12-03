package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.Server
import com.openwatt.droid.model.SystemInfo
import com.openwatt.droid.network.CliClient
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val cliClient = CliClient()

    private val _systemInfo = MutableLiveData<SystemInfo>()
    val systemInfo: LiveData<SystemInfo> = _systemInfo

    private val _serverName = MutableLiveData<String>()
    val serverName: LiveData<String> = _serverName

    private val _lastUpdated = MutableLiveData<String>()
    val lastUpdated: LiveData<String> = _lastUpdated

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isOnline = MutableLiveData<Boolean>(false)
    val isOnline: LiveData<Boolean> = _isOnline

    private val _allServers = MutableLiveData<List<Server>>()
    val allServers: LiveData<List<Server>> = _allServers

    private var currentServer: Server? = null
    private var isPolling = false
    private var consecutiveFailures = 0

    fun initialize(serverId: String) {
        viewModelScope.launch {
            currentServer = serverRepository.getServer(serverId)
            _allServers.value = serverRepository.getAllServers()
            currentServer?.let { server ->
                _serverName.value = server.name
                startPolling()
            }
        }
    }

    fun switchToServer(serverId: String) {
        stopPolling()
        consecutiveFailures = 0
        viewModelScope.launch {
            serverRepository.setCurrentServer(serverId)
            initialize(serverId)
        }
    }

    fun refreshSystemInfo() {
        val server = currentServer ?: return
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val result = cliClient.executeCommand(server, "/system/sysinfo")

                result.onSuccess { response ->
                    _systemInfo.value = parseSystemInfo(response.output)
                    updateLastUpdatedTime()
                    _isOnline.value = true
                    consecutiveFailures = 0
                }.onFailure { exception ->
                    // Mark offline immediately on first failure
                    _isOnline.value = false
                    _systemInfo.value = SystemInfo(
                        status = "Offline",
                        uptime = "--",
                        isHealthy = false,
                        rawOutput = ""
                    )
                    _lastUpdated.value = "Last updated: Connection lost"
                    consecutiveFailures++
                }
            } catch (e: Exception) {
                // Mark offline immediately on first failure
                _isOnline.value = false
                _systemInfo.value = SystemInfo(
                    status = "Offline",
                    uptime = "--",
                    isHealthy = false,
                    rawOutput = ""
                )
                _lastUpdated.value = "Last updated: Connection lost"
                consecutiveFailures++
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun startPolling() {
        if (isPolling) return
        isPolling = true

        viewModelScope.launch {
            while (isPolling) {
                refreshSystemInfo()
                delay(10000) // Poll every 10 seconds for local server
            }
        }
    }

    fun stopPolling() {
        isPolling = false
    }

    private fun parseSystemInfo(output: String): SystemInfo {
        // Simple parser - extract key info from command output
        // This will need to be adjusted based on actual /system/sysinfo format
        val lines = output.lines()
        var status = "Running"
        var uptime = "Unknown"

        lines.forEach { line ->
            when {
                line.contains("Status:", ignoreCase = true) -> {
                    status = line.substringAfter(":", "").trim()
                }
                line.contains("Uptime:", ignoreCase = true) -> {
                    uptime = line.substringAfter(":", "").trim()
                }
            }
        }

        return SystemInfo(
            status = status,
            uptime = uptime,
            isHealthy = status.equals("Running", ignoreCase = true) ||
                       status.equals("OK", ignoreCase = true),
            rawOutput = output
        )
    }

    private fun updateLastUpdatedTime() {
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        _lastUpdated.value = "Last updated: ${dateFormat.format(Date())}"
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
