package com.openwatt.droid.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.openwatt.droid.R
import com.openwatt.droid.databinding.ActivityConsoleBinding
import com.openwatt.droid.repository.ServerRepository
import com.openwatt.droid.util.ConsoleCompleter
import com.openwatt.droid.viewmodel.ConsoleViewModel

class ConsoleActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConsoleBinding
    private val viewModel: ConsoleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        if (serverId == null) {
            Toast.makeText(this, "No server selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        viewModel.initialize(serverId)
        setupViews()
        observeViewModel()
    }

    private fun setupViews() {
        binding.btnSend.setOnClickListener {
            sendCommand()
        }

        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else {
                false
            }
        }

        binding.btnClear.setOnClickListener {
            viewModel.clearOutput()
        }

        binding.etCommand.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.onInputChanged(s?.toString() ?: "")
            }
        })
    }

    /** Rebuild the predictive-text strip above the input. */
    private fun showSuggestions(state: ConsoleViewModel.SuggestionState) {
        // Suggestions were computed for a snapshot of the input; a fast typist
        // can outrun the debounce, so never apply a stale set.
        if (state.line != binding.etCommand.text?.toString()) return

        binding.suggestionStrip.removeAllViews()
        binding.suggestionScroll.visibility =
            if (state.items.isEmpty()) View.GONE else View.VISIBLE
        binding.suggestionScroll.scrollTo(0, 0)

        for (suggestion in state.items) {
            val chip = Chip(this).apply {
                text = suggestion.label
                typeface = Typeface.MONOSPACE
                isCheckable = false
                setEnsureMinTouchTargetSize(false)
                setOnClickListener { applySuggestion(suggestion, state.line) }
            }
            binding.suggestionStrip.addView(chip)
        }
    }

    /** Splice the completion into the input, keeping the keyboard up. */
    private fun applySuggestion(suggestion: ConsoleCompleter.Suggestion, forLine: String) {
        val current = binding.etCommand.text?.toString() ?: return
        if (current != forLine || suggestion.end > current.length) return
        val newText = current.replaceRange(suggestion.start, suggestion.end, suggestion.replacement)
        binding.etCommand.setText(newText)
        binding.etCommand.setSelection(suggestion.start + suggestion.replacement.length)
    }

    private fun sendCommand() {
        val command = binding.etCommand.text.toString()
        if (command.isNotBlank()) {
            viewModel.executeCommand(command)
            binding.etCommand.text?.clear()
        }
    }

    private fun observeViewModel() {
        viewModel.serverName.observe(this) { name ->
            supportActionBar?.title = name
        }

        viewModel.consoleOutput.observe(this) { output ->
            binding.tvConsoleOutput.text = output
            binding.scrollView.post {
                binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }

        viewModel.error.observe(this) { error ->
            Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.btnSend.isEnabled = !isLoading
            binding.etCommand.isEnabled = !isLoading
        }

        viewModel.connectionError.observe(this) {
            Toast.makeText(this, "Server not found", Toast.LENGTH_SHORT).show()
            finish()
        }

        viewModel.suggestions.observe(this) { state ->
            showSuggestions(state)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.console_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // Back button pressed - return to dashboard
                finish()
                true
            }
            R.id.action_disconnect -> {
                disconnect()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun disconnect() {
        val serverRepository = ServerRepository(this)
        serverRepository.clearCurrentServer()

        val intent = Intent(this, ServerRegistrationActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
    }
}
