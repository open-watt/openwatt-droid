package com.openwatt.droid.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.openwatt.droid.databinding.ActivityServerRegistrationBinding
import com.openwatt.droid.viewmodel.ServerRegistrationViewModel

class ServerRegistrationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityServerRegistrationBinding
    private val viewModel: ServerRegistrationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityServerRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        observeViewModel()
    }

    private fun setupViews() {
        binding.btnAddServer.setOnClickListener {
            val name = binding.etServerName.text.toString()
            val hostname = binding.etHostname.text.toString()
            val portStr = binding.etPort.text.toString()
            val useHttps = binding.switchHttps.isChecked

            if (name.isBlank()) {
                binding.etServerName.error = "Name is required"
                return@setOnClickListener
            }

            if (hostname.isBlank()) {
                binding.etHostname.error = "Hostname is required"
                return@setOnClickListener
            }

            val port = portStr.toIntOrNull() ?: 80
            viewModel.addServer(name, hostname, port, useHttps)
        }

        binding.btnTestConnection.setOnClickListener {
            val hostname = binding.etHostname.text.toString()
            val portStr = binding.etPort.text.toString()
            val useHttps = binding.switchHttps.isChecked

            if (hostname.isBlank()) {
                binding.etHostname.error = "Hostname is required"
                return@setOnClickListener
            }

            val port = portStr.toIntOrNull() ?: 80
            viewModel.testConnection(hostname, port, useHttps)
        }
    }

    private fun observeViewModel() {
        viewModel.serverAdded.observe(this) { server ->
            Toast.makeText(this, "Server '${server.name}' added successfully", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, DashboardActivity::class.java)
            intent.putExtra(DashboardActivity.EXTRA_SERVER_ID, server.id)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        viewModel.connectionTestResult.observe(this) { result ->
            result.onSuccess {
                Toast.makeText(this, "Connection successful!", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(this, "Connection failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }

        viewModel.error.observe(this) { error ->
            Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.btnAddServer.isEnabled = !isLoading
            binding.btnTestConnection.isEnabled = !isLoading
        }
    }
}
