package com.hitif.videodownload.history

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hitif.videodownload.R
import com.hitif.videodownload.databinding.ActivityHistoryBinding
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private lateinit var historyManager: HistoryManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.history)

        historyManager = HistoryManager.getInstance(this)

        adapter = HistoryAdapter(
            onClick = { entry ->
                // Could open the URL in the web browser fragment
            },
            onDelete = { entry ->
                lifecycleScope.launch { historyManager.delete(entry.id) }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        historyManager.getAll().observe(this) { entries ->
            adapter.submitList(entries)
            binding.emptyState.visibility = if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        binding.btnClearHistory.setOnClickListener {
            lifecycleScope.launch { historyManager.deleteAll() }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class HistoryAdapter(
    private val onClick: (HistoryEntry) -> Unit,
    private val onDelete: (HistoryEntry) -> Unit
) : androidx.recyclerview.widget.ListAdapter<HistoryEntry, HistoryAdapter.ViewHolder>(HistoryDiffCallback()) {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(currentList[position])
    }

    inner class ViewHolder(itemView: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
        fun bind(entry: HistoryEntry) {
            itemView.findViewById<android.widget.TextView>(R.id.text_history_title).text = entry.title ?: entry.url
            itemView.findViewById<android.widget.TextView>(R.id.text_history_url).text = entry.url
            itemView.findViewById<android.widget.TextView>(R.id.text_history_source).text = entry.source
            itemView.findViewById<android.widget.ImageButton>(R.id.btn_delete_history).setOnClickListener { onDelete(entry) }
            itemView.setOnClickListener { onClick(entry) }
        }
    }
}

class HistoryDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<HistoryEntry>() {
    override fun areItemsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry) = oldItem == newItem
}
