package com.openwatt.droid.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.openwatt.droid.R
import com.openwatt.droid.databinding.ActivityConsoleBinding
import com.openwatt.droid.repository.ServerRepository
import com.openwatt.droid.viewmodel.ConsoleViewModel

class ConsoleActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConsoleBinding
    private val viewModel: ConsoleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.console_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
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
