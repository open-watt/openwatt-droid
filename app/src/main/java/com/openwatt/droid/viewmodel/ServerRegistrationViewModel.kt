package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.Server
import com.openwatt.droid.network.CliClient
import com.openwatt.droid.repository.ServerRepository
import kotlinx.coroutines.launch

class ServerRegistrationViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val cliClient = CliClient()

    private val _serverAdded = MutableLiveData<Server>()
    val serverAdded: LiveData<Server> = _serverAdded

    private val _connectionTestResult = MutableLiveData<Result<Boolean>>()
    val connectionTestResult: LiveData<Result<Boolean>> = _connectionTestResult

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    fun addServer(name: String, hostname: String, port: Int, useHttps: Boolean) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val server = serverRepository.addServer(name, hostname, port, useHttps)
                serverRepository.setCurrentServer(server.id)
                _serverAdded.value = server
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to add server"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun testConnection(hostname: String, port: Int, useHttps: Boolean) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val tempServer = Server(
                    id = "test",
                    name = "Test",
                    hostname = hostname,
                    port = port,
                    useHttps = useHttps
                )
                val result = cliClient.testConnection(tempServer)
                _connectionTestResult.value = result
            } catch (e: Exception) {
                _connectionTestResult.value = Result.failure(e)
            } finally {
                _isLoading.value = false
            }
        }
    }
}
