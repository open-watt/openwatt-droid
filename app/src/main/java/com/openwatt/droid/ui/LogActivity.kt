package com.openwatt.droid.ui

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.openwatt.droid.databinding.ActivityLogsBinding
import com.openwatt.droid.network.SyncClient
import com.openwatt.droid.network.SyncClientManager
import com.openwatt.droid.repository.ServerRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live log viewer: registers the sync channel as a backend log sink
 * (`log_sub`) and tails the stream. The severity picker re-subscribes with a
 * new ceiling — filtering happens at the backend, not the client. The list
 * follows the tail unless the user has scrolled up.
 */
class LogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogsBinding
    private var client: SyncClient? = null
    private val adapter = LogAdapter()

    /** Follow the tail only while the user is already at it. */
    private var followTail = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Logs"

        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        val server = serverId?.let { ServerRepository(this).getServer(it) }
        if (server == null) {
            Toast.makeText(this, "No server selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val syncClient = SyncClientManager.get(server)
        client = syncClient

        binding.logRecycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.logRecycler.itemAnimator = null
        binding.logRecycler.adapter = adapter
        binding.logRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) followTail = !recyclerView.canScrollVertically(1)
            }
        })

        binding.severitySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, SEVERITY_CHOICES.map { it.first },
        )
        binding.severitySpinner.setSelection(SEVERITY_CHOICES.indexOfFirst { it.second == DEFAULT_SEVERITY })
        binding.severitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                syncClient.subscribeLogs(severity = SEVERITY_CHOICES[position].second)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        syncClient.subscribeLogs(severity = DEFAULT_SEVERITY)
        syncClient.logs.observe(this) { entries ->
            binding.emptyLogs.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            adapter.submitList(entries) {
                if (followTail && entries.isNotEmpty()) {
                    binding.logRecycler.scrollToPosition(entries.size - 1)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Leaving the screen stops the stream; the buffer survives in the
        // client for the next visit.
        if (isFinishing) client?.unsubscribeLogs()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CLEAR, 0, "Clear")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            MENU_CLEAR -> {
                client?.clearLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private class LogAdapter :
        ListAdapter<SyncClient.LogEntry, LogAdapter.LineHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineHolder {
            val view = TextView(parent.context).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 2, 0, 2)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
            }
            return LineHolder(view)
        }

        override fun onBindViewHolder(holder: LineHolder, position: Int) {
            holder.bind(getItem(position))
        }

        class LineHolder(private val view: TextView) : RecyclerView.ViewHolder(view) {
            private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            fun bind(entry: SyncClient.LogEntry) {
                val time = timeFormat.format(Date(entry.receivedAt))
                val tag = if (entry.tag.isNotEmpty()) " ${entry.tag}:" else ""
                view.text = "$time$tag ${entry.message.ifEmpty { entry.raw }}"
                view.setTextColor(colorFor(entry.severityCode))
            }

            private fun colorFor(severityCode: Int): Int = when (severityCode) {
                in 0..3 -> 0xFFF87171.toInt()   // emergency..error: red
                4 -> 0xFFFBBF24.toInt()          // warning: amber
                5 -> 0xFF93C5FD.toInt()          // notice: light blue
                6 -> 0xFFE5E7EB.toInt()          // info: near-white
                else -> 0xFF9CA3AF.toInt()       // debug/trace/unknown: gray
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<SyncClient.LogEntry>() {
                override fun areItemsTheSame(a: SyncClient.LogEntry, b: SyncClient.LogEntry) =
                    a.seq == b.seq

                override fun areContentsTheSame(a: SyncClient.LogEntry, b: SyncClient.LogEntry) =
                    a == b
            }
        }
    }

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
        private const val MENU_CLEAR = 1
        private const val DEFAULT_SEVERITY = "info"

        /**
         * Backend severity ceilings, least to most verbose: display label to
         * wire key (debug's key is `debug_` — a D keyword collision).
         */
        private val SEVERITY_CHOICES = listOf(
            "error" to "error",
            "warning" to "warning",
            "notice" to "notice",
            "info" to "info",
            "debug" to "debug_",
            "trace" to "trace",
        )
    }
}
