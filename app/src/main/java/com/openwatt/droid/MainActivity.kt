package com.openwatt.droid

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.openwatt.droid.repository.ServerRepository
import com.openwatt.droid.ui.ConsoleActivity
import com.openwatt.droid.ui.ServerRegistrationActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serverRepository = ServerRepository(this)
        val currentServer = serverRepository.getCurrentServer()

        if (currentServer != null) {
            val intent = Intent(this, ConsoleActivity::class.java)
            intent.putExtra(ConsoleActivity.EXTRA_SERVER_ID, currentServer.id)
            startActivity(intent)
        } else {
            val intent = Intent(this, ServerRegistrationActivity::class.java)
            startActivity(intent)
        }

        finish()
    }
}
