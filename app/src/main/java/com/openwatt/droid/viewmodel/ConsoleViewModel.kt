package com.openwatt.droid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.openwatt.droid.model.CollectionSchema
import com.openwatt.droid.model.Server
import com.openwatt.droid.network.CliClient
import com.openwatt.droid.network.OpenWattClient
import com.openwatt.droid.network.SyncClient
import com.openwatt.droid.network.SyncClientManager
import com.openwatt.droid.repository.ServerRepository
import com.openwatt.droid.util.ConsoleCompleter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ConsoleViewModel(application: Application) : AndroidViewModel(application) {
    private val serverRepository = ServerRepository(application)
    private val cliClient = CliClient()
    private val apiClient = OpenWattClient()
    private var currentServer: Server? = null
    private var syncClient: SyncClient? = null

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

    // -- completion --

    /** Suggestions for [line]; the line is carried so stale sets can't apply. */
    data class SuggestionState(val line: String, val items: List<ConsoleCompleter.Suggestion>)

    private val _suggestions = MutableLiveData(SuggestionState("", emptyList()))
    val suggestions: LiveData<SuggestionState> = _suggestions

    private var schemas: Map<String, CollectionSchema> = emptyMap()
    private var suggestJob: Job? = null

    /** Live item names per scope, briefly cached so keystrokes don't spam the backend. */
    private val itemCache = mutableMapOf<String, Pair<Long, List<String>>>()
    private val enumCache = mutableMapOf<String, List<String>>()

    private val completer = ConsoleCompleter(object : ConsoleCompleter.DataSource {
        override fun schemas() = this@ConsoleViewModel.schemas.values

        override suspend fun listItems(scopePath: String): List<String> {
            val now = System.currentTimeMillis()
            itemCache[scopePath]?.let { (at, names) ->
                if (now - at < ITEM_CACHE_MS) return names
            }
            val names = syncClient?.cmd("$scopePath/list")?.getOrNull()
                ?.let { (it.value as? List<*>)?.filterIsInstance<String>() }
                ?: return itemCache[scopePath]?.second ?: emptyList()
            itemCache[scopePath] = now to names
            return names
        }

        override suspend fun enumMembers(enumName: String): List<String> {
            enumCache[enumName]?.let { return it }
            val members = syncClient?.getEnum(enumName)?.getOrNull()?.keys?.toList()
                ?: return emptyList()
            enumCache[enumName] = members
            return members
        }
    })

    fun initialize(serverId: String) {
        currentServer = serverRepository.getServer(serverId)
        val server = currentServer
        if (server == null) {
            _connectionError.value = Unit
            return
        }
        syncClient = SyncClientManager.get(server)

        _serverName.value = server.name
        appendOutput("Connected to ${server.name} (${server.baseUrl})\n")
        appendOutput("Type commands and press Send\n\n")

        // Completion vocabulary: scope paths + property schemas.
        viewModelScope.launch {
            apiClient.getSchema(server).onSuccess { schemas = it }
        }
    }

    /**
     * Whether the backend serves the `suggest` verb. Unknown until first use;
     * latched false after a timeout (older decoders drop the frame silently)
     * so every keystroke doesn't stall waiting on a backend that can't answer.
     */
    private var wireSuggestSupported: Boolean? = null

    /** Recompute completion suggestions for the current input, debounced. */
    fun onInputChanged(line: String) {
        suggestJob?.cancel()
        if (line.isBlank()) {
            _suggestions.value = SuggestionState(line, emptyList())
            return
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            val items = try {
                wireSuggest(line) ?: completer.suggest(line)
            } catch (_: Exception) {
                emptyList()
            }
            _suggestions.value = SuggestionState(line, items)
        }
    }

    /**
     * Completion from the backend's real console engine via the sync
     * `suggest` verb — it sees everything the telnet console does (custom
     * commands, TabComplete hooks, live value suggestions). Returns null to
     * fall back to the client-side [completer].
     */
    private suspend fun wireSuggest(line: String): List<ConsoleCompleter.Suggestion>? {
        if (wireSuggestSupported == false) return null
        val client = syncClient ?: return null
        return client.suggest(line).fold(
            onSuccess = { reply ->
                wireSuggestSupported = true
                buildWireSuggestions(line, reply)
            },
            onFailure = { e ->
                if (e.message == "request timed out") wireSuggestSupported = false
                null
            },
        )
    }

    private fun buildWireSuggestions(
        line: String,
        reply: com.openwatt.droid.network.SyncClient.SuggestReply,
    ): List<ConsoleCompleter.Suggestion> {
        val items = mutableListOf<ConsoleCompleter.Suggestion>()

        // Lead with the tab-complete action when the common prefix extends
        // the line — one tap does what Tab does on the terminal.
        val complete = reply.complete
        if (complete != null && complete.length > line.length && complete.startsWith(line)) {
            val tail = complete.substringAfterLast(' ')
            items.add(ConsoleCompleter.Suggestion("⇥ $tail", complete, 0, line.length))
        }

        // Candidate tokens replace the token under the cursor. A lone
        // candidate is already covered by the complete chip above.
        if (reply.suggestions.size > 1 || items.isEmpty()) {
            val tokenStart = tokenStartOf(line)
            for (candidate in reply.suggestions.take(ConsoleCompleter.MAX_SUGGESTIONS)) {
                items.add(ConsoleCompleter.Suggestion(candidate, candidate, tokenStart, line.length))
            }
        }
        return items
    }

    /** Start of the token under the cursor (path segment, argument, or value). */
    private fun tokenStartOf(line: String): Int =
        line.lastIndexOfAny(charArrayOf(' ', '/', '=', ':')) + 1

    fun executeCommand(command: String) {
        val server = currentServer ?: return

        viewModelScope.launch {
            try {
                _isLoading.value = true
                appendOutput("> $command\n")

                // Commands ride the sync socket (latent commands answer when
                // they finish); REST /api/cli/execute remains as a fallback
                // while the socket is down.
                val sync = SyncClientManager.get(server)
                val result = sync.cmd(command).map { it.text }.recoverCatching { e ->
                    if (sync.connected.value == true) throw e
                    cliClient.executeCommand(server, command).getOrThrow().let { response ->
                        response.error?.let { throw java.io.IOException(it) }
                        response.output
                    }
                }

                result.onSuccess { output ->
                    appendOutput(output)
                    if (!output.endsWith("\n")) {
                        appendOutput("\n")
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

    companion object {
        private const val SUGGEST_DEBOUNCE_MS = 120L
        private const val ITEM_CACHE_MS = 15_000L
    }
}
