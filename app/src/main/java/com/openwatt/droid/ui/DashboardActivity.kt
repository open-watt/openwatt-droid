package com.openwatt.droid.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.openwatt.droid.R
import com.openwatt.droid.databinding.ActivityDashboardBinding
import com.openwatt.droid.databinding.ToolbarTitleWithStatusBinding
import com.openwatt.droid.model.Server
import com.openwatt.droid.network.CliClient
import com.openwatt.droid.repository.ServerRepository
import com.openwatt.droid.ui.fragments.HomeFragment
import com.openwatt.droid.viewmodel.DashboardViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDashboardBinding
    private lateinit var toolbarTitleBinding: ToolbarTitleWithStatusBinding
    private val viewModel: DashboardViewModel by viewModels()
    private var currentServerId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        currentServerId = intent.getStringExtra(EXTRA_SERVER_ID)
        if (currentServerId == null) {
            Toast.makeText(this, "No server selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupCustomToolbarTitle()
        viewModel.initialize(currentServerId!!)
        observeViewModel()
        setupBottomNavigation()

        // Load HomeFragment by default
        if (savedInstanceState == null) {
            loadFragment(HomeFragment.newInstance(currentServerId!!))
        }
    }

    private fun setupCustomToolbarTitle() {
        toolbarTitleBinding = ToolbarTitleWithStatusBinding.inflate(layoutInflater)
        binding.toolbar.addView(toolbarTitleBinding.root)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowHomeEnabled(false)

        toolbarTitleBinding.root.setOnClickListener {
            showServerPicker()
        }
    }

    private fun observeViewModel() {
        viewModel.serverName.observe(this) { name ->
            toolbarTitleBinding.serverNameText.text = name
        }

        viewModel.isOnline.observe(this) { isOnline ->
            val color = if (isOnline) {
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            } else {
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
            }
            toolbarTitleBinding.statusIndicator.setTextColor(color)
        }

        viewModel.allServers.observe(this) { servers ->
            // Show dropdown arrow only if there are multiple servers
            if (servers.size > 1) {
                toolbarTitleBinding.dropdownArrow.visibility = android.view.View.VISIBLE
            } else {
                toolbarTitleBinding.dropdownArrow.visibility = android.view.View.GONE
            }
        }
    }

    private fun showServerPicker() {
        val servers = viewModel.allServers.value ?: return

        val dialogView = layoutInflater.inflate(R.layout.dialog_server_picker, null)
        val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.rg_servers)
        val btnAddServer = dialogView.findViewById<android.widget.Button>(R.id.btn_add_server)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Populate server list
        servers.forEachIndexed { index, server ->
            val itemView = layoutInflater.inflate(R.layout.item_server_picker, radioGroup, false)
            val radioButton = itemView.findViewById<android.widget.RadioButton>(R.id.rb_server)
            val deleteButton = itemView.findViewById<android.widget.ImageButton>(R.id.btn_delete)

            radioButton.text = server.name
            radioButton.id = index
            radioButton.isChecked = server.id == currentServerId

            // Handle server selection
            radioButton.setOnClickListener {
                if (server.id != currentServerId) {
                    currentServerId = server.id
                    viewModel.switchToServer(server.id)
                    loadFragment(HomeFragment.newInstance(server.id))
                }
                dialog.dismiss()
            }

            // Handle server deletion
            deleteButton.setOnClickListener {
                showDeleteConfirmation(server, dialog)
            }

            // Disable delete if only one server
            if (servers.size == 1) {
                deleteButton.isEnabled = false
                deleteButton.alpha = 0.3f
            }

            radioGroup.addView(itemView)
        }

        // Handle "Add Server" button
        btnAddServer.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, ServerRegistrationActivity::class.java)
            startActivity(intent)
        }

        dialog.show()
    }

    private fun showDeleteConfirmation(server: com.openwatt.droid.model.Server, pickerDialog: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle("Delete Server")
            .setMessage("Are you sure you want to delete \"${server.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                val serverRepository = ServerRepository(this)
                val allServers = serverRepository.getAllServers()

                // If we're deleting the current server, find a replacement
                if (server.id == currentServerId) {
                    val currentIndex = allServers.indexOfFirst { it.id == server.id }

                    // Delete the server first
                    serverRepository.deleteServer(server.id)

                    // Get remaining servers after deletion
                    val remainingServers = serverRepository.getAllServers()

                    // Try to get next server: prefer same index (which is now the next server down),
                    // otherwise get previous (index - 1)
                    val nextServer = when {
                        remainingServers.isEmpty() -> null
                        currentIndex < remainingServers.size -> remainingServers[currentIndex]  // Next down
                        else -> remainingServers[currentIndex - 1]  // Previous (now last)
                    }

                    if (nextServer != null) {
                        // Switch to next available server
                        currentServerId = nextServer.id
                        serverRepository.setCurrentServer(nextServer.id)
                        viewModel.switchToServer(nextServer.id)
                        loadFragment(HomeFragment.newInstance(nextServer.id))
                        pickerDialog.dismiss()
                    } else {
                        // No servers left, go to registration
                        pickerDialog.dismiss()
                        disconnect()
                    }
                } else {
                    // Just delete the non-current server
                    serverRepository.deleteServer(server.id)
                    viewModel.initialize(currentServerId!!)
                    // Close and reopen the picker dialog to show updated list
                    pickerDialog.dismiss()
                    showServerPicker()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    loadFragment(HomeFragment.newInstance(currentServerId!!))
                    true
                }
                R.id.nav_energy -> {
                    Toast.makeText(this, "Energy tab coming soon", Toast.LENGTH_SHORT).show()
                    false
                }
                R.id.nav_devices -> {
                    Toast.makeText(this, "Devices tab coming soon", Toast.LENGTH_SHORT).show()
                    false
                }
                R.id.nav_config -> {
                    Toast.makeText(this, "Config tab coming soon", Toast.LENGTH_SHORT).show()
                    false
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.dashboard_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_system_info -> {
                showSystemInfo()
                true
            }
            R.id.action_console -> {
                openConsole()
                true
            }
            R.id.action_disconnect -> {
                disconnect()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSystemInfo() {
        val serverRepository = ServerRepository(this)
        val server = currentServerId?.let { serverRepository.getServer(it) } ?: return
        val cliClient = CliClient()

        // Inflate dialog view
        val dialogView = layoutInflater.inflate(R.layout.dialog_system_info, null)
        val textView = dialogView.findViewById<TextView>(R.id.tv_system_info)

        // Create dialog
        val dialog = AlertDialog.Builder(this)
            .setTitle("System Info")
            .setView(dialogView)
            .setNegativeButton("Close", null)
            .create()

        // Start polling coroutine
        var pollingJob: Job? = null
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val result = cliClient.executeCommand(server, "/system/sysinfo")
                    result.onSuccess { response ->
                        textView.text = response.output
                    }.onFailure { exception ->
                        textView.text = "Error: ${exception.message}"
                    }
                } catch (e: Exception) {
                    textView.text = "Error: ${e.message}"
                }
                delay(1000) // Poll every 1 second
            }
        }

        // Cancel polling when dialog is dismissed
        dialog.setOnDismissListener {
            pollingJob?.cancel()
        }

        dialog.show()
    }

    private fun openConsole() {
        val intent = Intent(this, ConsoleActivity::class.java).apply {
            putExtra(ConsoleActivity.EXTRA_SERVER_ID, currentServerId)
        }
        startActivity(intent)
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
