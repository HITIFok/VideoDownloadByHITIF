package com.hitif.videodownload.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hitif.videodownload.R
import com.hitif.videodownload.download.DownloadEntity
import com.hitif.videodownload.download.DownloadStatus
import com.hitif.videodownload.download.formatFileSize

class DownloadItemAdapter(
    private val onPlayClick: (DownloadEntity) -> Unit,
    private val onPauseClick: (DownloadEntity) -> Unit,
    private val onResumeClick: (DownloadEntity) -> Unit,
    private val onCancelClick: (DownloadEntity) -> Unit,
    private val onRetryClick: (DownloadEntity) -> Unit,
    private val onDeleteClick: (DownloadEntity) -> Unit
) : ListAdapter<DownloadEntity, DownloadItemAdapter.ViewHolder>(DownloadDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = currentList[position]
        holder.bind(item)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textTitle: TextView = itemView.findViewById(R.id.text_download_title)
        private val textInfo: TextView = itemView.findViewById(R.id.text_download_info)
        private val textProgress: TextView = itemView.findViewById(R.id.text_download_progress)
        private val textSpeed: TextView = itemView.findViewById(R.id.text_download_speed)
        private val progressBar: android.widget.ProgressBar = itemView.findViewById(R.id.progress_bar)
        private val btnAction: ImageView = itemView.findViewById(R.id.btn_action)
        private val textSourceBadge: TextView = itemView.findViewById(R.id.text_source_badge)
        private val textEpisodeBadge: TextView = itemView.findViewById(R.id.text_episode_badge)

        fun bind(item: DownloadEntity) {
            textTitle.text = item.title ?: item.fileName
            textSourceBadge.text = when (item.source) {
                com.hitif.videodownload.download.DownloadSource.YOUTUBE -> "YT"
                com.hitif.videodownload.download.DownloadSource.PLAYLIST -> "PL"
                com.hitif.videodownload.download.DownloadSource.SEASON -> "S"
                else -> "Web"
            }

            // Episode badge
            if (item.episodeLabel.isNotEmpty()) {
                textEpisodeBadge.text = item.episodeLabel
                textEpisodeBadge.visibility = View.VISIBLE
            } else {
                textEpisodeBadge.visibility = View.GONE
            }

            when (item.status) {
                DownloadStatus.PENDING -> {
                    progressBar.isIndeterminate = true
                    textProgress.text = itemView.context.getString(R.string.pending)
                    textSpeed.text = ""
                    btnAction.setImageResource(R.drawable.ic_cancel)
                    btnAction.setOnClickListener { onCancelClick(item) }
                }
                DownloadStatus.CONNECTING -> {
                    progressBar.isIndeterminate = true
                    textProgress.text = itemView.context.getString(R.string.connecting)
                    textSpeed.text = ""
                    btnAction.setImageResource(R.drawable.ic_cancel)
                    btnAction.setOnClickListener { onCancelClick(item) }
                }
                DownloadStatus.DOWNLOADING -> {
                    progressBar.isIndeterminate = false
                    progressBar.progress = item.progress
                    textProgress.text = "${item.progress}%"
                    textSpeed.text = if (item.speed > 0) "${formatFileSize(item.speed)}/s" else ""
                    textInfo.text = "${formatFileSize(item.downloadedBytes)} / ${formatFileSize(item.totalBytes)}"
                    btnAction.setImageResource(R.drawable.ic_pause)
                    btnAction.setOnClickListener { onPauseClick(item) }
                }
                DownloadStatus.PAUSED -> {
                    progressBar.isIndeterminate = false
                    progressBar.progress = item.progress
                    textProgress.text = "${item.progress}% (Paused)"
                    textSpeed.text = ""
                    textInfo.text = "${formatFileSize(item.downloadedBytes)} / ${formatFileSize(item.totalBytes)}"
                    btnAction.setImageResource(R.drawable.ic_play)
                    btnAction.setOnClickListener { onResumeClick(item) }
                }
                DownloadStatus.COMPLETED -> {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 100
                    textProgress.text = "100%"
                    textSpeed.text = ""
                    textInfo.text = formatFileSize(item.totalBytes)
                    btnAction.setImageResource(R.drawable.ic_play)
                    btnAction.setOnClickListener { onPlayClick(item) }
                }
                DownloadStatus.FAILED -> {
                    progressBar.isIndeterminate = false
                    progressBar.progress = item.progress
                    textProgress.text = "Failed"
                    textSpeed.text = item.errorMessage ?: ""
                    textInfo.text = ""
                    btnAction.setImageResource(R.drawable.ic_retry)
                    btnAction.setOnClickListener { onRetryClick(item) }
                }
                DownloadStatus.CANCELLED -> {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 0
                    textProgress.text = "Cancelled"
                    textSpeed.text = ""
                    btnAction.setImageResource(R.drawable.ic_retry)
                    btnAction.setOnClickListener { onRetryClick(item) }
                }
                DownloadStatus.QUEUED -> {
                    progressBar.isIndeterminate = true
                    textProgress.text = "Queued"
                    textSpeed.text = ""
                    btnAction.setImageResource(R.drawable.ic_cancel)
                    btnAction.setOnClickListener { onCancelClick(item) }
                }
            }

            // Long press for delete
            itemView.setOnLongClickListener {
                showContextMenu(item)
                true
            }

            // Set quality info
            val qualityInfo = mutableListOf<String>()
            item.quality?.let { qualityInfo.add(it) }
            item.format?.let { qualityInfo.add(it.uppercase()) }
            if (qualityInfo.isNotEmpty()) {
                textInfo.text = "${textInfo.text} - ${qualityInfo.joinToString(" ")}"
            }
        }

        private fun showContextMenu(item: DownloadEntity) {
            PopupMenu(itemView.context, itemView).apply {
                menuInflater.inflate(R.menu.menu_download_item, menu)
                menu.findItem(R.id.action_play).isVisible = item.status == DownloadStatus.COMPLETED
                menu.findItem(R.id.action_pause).isVisible = item.status == DownloadStatus.DOWNLOADING
                menu.findItem(R.id.action_resume).isVisible = item.status == DownloadStatus.PAUSED
                menu.findItem(R.id.action_retry).isVisible = item.status == DownloadStatus.FAILED || item.status == DownloadStatus.CANCELLED
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_play -> onPlayClick(item)
                        R.id.action_pause -> onPauseClick(item)
                        R.id.action_resume -> onResumeClick(item)
                        R.id.action_retry -> onRetryClick(item)
                        R.id.action_delete -> onDeleteClick(item)
                    }
                    true
                }
            }.show()
        }
    }
}

class DownloadDiffCallback : DiffUtil.ItemCallback<DownloadEntity>() {
    override fun areItemsTheSame(oldItem: DownloadEntity, newItem: DownloadEntity): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: DownloadEntity, newItem: DownloadEntity): Boolean {
        return oldItem == newItem
    }
}
