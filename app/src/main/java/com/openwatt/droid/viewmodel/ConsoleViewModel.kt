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

class ConsoleViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val cliClient = CliClient()
    private var currentServer: Server? = null

    private val _serverName = MutableLiveData<String>()
    val serverName: LiveData<String> = _serverName

    private val _consoleOutput = MutableLiveData<String>()
    val consoleOutput: LiveData<String> = _consoleOutput

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _connectionError = MutableLiveData<Unit>()
    val connectionError: LiveData<Unit> = _connectionError

    private val outputBuffer = StringBuilder()

    fun initialize(serverId: String) {
        currentServer = serverRepository.getServer(serverId)
        if (currentServer == null) {
            _connectionError.value = Unit
            return
        }

        _serverName.value = currentServer?.name ?: "Console"
        appendOutput("Connected to ${currentServer?.name} (${currentServer?.baseUrl})\n")
        appendOutput("Type commands and press Send\n\n")
    }

    fun executeCommand(command: String) {
        val server = currentServer ?: return

        viewModelScope.launch {
            try {
                _isLoading.value = true
                appendOutput("> $command\n")

                val result = cliClient.executeCommand(server, command)

                result.onSuccess { response ->
                    if (response.error != null) {
                        appendOutput("ERROR: ${response.error}\n")
                    } else {
                        appendOutput(response.output)
                        if (!response.output.endsWith("\n")) {
                            appendOutput("\n")
                        }
                    }
                }.onFailure { error ->
                    appendOutput("ERROR: ${error.message}\n")
                    _error.value = error.message ?: "Command execution failed"
                }

                appendOutput("\n")
            } catch (e: Exception) {
                appendOutput("ERROR: ${e.message}\n\n")
                _error.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearOutput() {
        outputBuffer.clear()
        _consoleOutput.value = ""
    }

    private fun appendOutput(text: String) {
        outputBuffer.append(text)
        _consoleOutput.value = outputBuffer.toString()
    }
}
