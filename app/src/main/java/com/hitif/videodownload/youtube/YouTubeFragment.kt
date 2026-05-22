package com.hitif.videodownload.youtube

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.hitif.videodownload.HITIFApplication
import com.hitif.videodownload.R
import com.hitif.videodownload.databinding.FragmentYoutubeBinding
import com.hitif.videodownload.download.DownloadManager
import com.hitif.videodownload.download.DownloadSource
import kotlinx.coroutines.launch

class YouTubeFragment : Fragment() {

    private var _binding: FragmentYoutubeBinding? = null
    private val binding get() = _binding!!

    private var currentVideoInfo: YouTubeVideoInfo? = null
    private var isExtracting = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentYoutubeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        YouTubeExtractor.init(requireContext())
        setupUI()
        handleInitialUrl()
    }

    private fun setupUI() {
        // URL input and extract button
        binding.btnExtract.setOnClickListener {
            extractVideo()
        }

        binding.editTextUrl.setOnEditorActionListener { _, _, _ ->
            extractVideo()
            true
        }

        // Paste from clipboard
        binding.btnPaste.setOnClickListener {
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!clip.isNullOrEmpty()) {
                binding.editTextUrl.setText(clip)
            }
        }

        // Download buttons
        binding.btnDownloadVideo.setOnClickListener {
            currentVideoInfo?.let { info ->
                showQualitySelectionDialog(info.videoStreams, isAudioOnly = false)
            }
        }

        binding.btnDownloadAudio.setOnClickListener {
            currentVideoInfo?.let { info ->
                showQualitySelectionDialog(info.audioStreams, isAudioOnly = true)
            }
        }

        binding.btnDownloadPlaylist.setOnClickListener {
            extractPlaylist()
        }

        // Clear results
        binding.cardResult.visibility = View.GONE
        binding.shimmerLayout.visibility = View.GONE
    }

    private fun handleInitialUrl() {
        arguments?.getString("url")?.let { url ->
            binding.editTextUrl.setText(url)
            extractVideo()
        }
    }

    private fun extractVideo() {
        val url = binding.editTextUrl.text.toString().trim()
        if (url.isEmpty()) {
            binding.editTextUrl.error = getString(R.string.enter_url)
            return
        }

        if (!YouTubeExtractor.isYouTubeUrl(url)) {
            binding.editTextUrl.error = getString(R.string.invalid_youtube_url)
            return
        }

        if (YouTubeExtractor.isPlaylistUrl(url)) {
            extractPlaylist()
            return
        }

        if (isExtracting) return
        isExtracting = true

        // Show loading
        binding.shimmerLayout.visibility = View.VISIBLE
        binding.shimmerLayout.startShimmer()
        binding.cardResult.visibility = View.GONE
        binding.textError.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val info = YouTubeExtractor.extractVideoInfo(url)
                if (info != null) {
                    currentVideoInfo = info
                    showVideoInfo(info)
                } else {
                    showError(getString(R.string.extraction_failed))
                }
            } catch (e: YouTubeExtractionException) {
                showError(e.message ?: getString(R.string.extraction_failed))
            } catch (e: Exception) {
                showError(getString(R.string.extraction_failed) + ": ${e.message}")
            } finally {
                isExtracting = false
                binding.shimmerLayout.stopShimmer()
                binding.shimmerLayout.visibility = View.GONE
            }
        }
    }

    private fun showVideoInfo(info: YouTubeVideoInfo) {
        binding.cardResult.visibility = View.VISIBLE
        binding.textError.visibility = View.GONE

        binding.textVideoTitle.text = info.title
        binding.textVideoUploader.text = info.uploaderName ?: ""
        binding.textVideoDuration.text = formatDuration(info.duration)
        binding.textVideoViews.text = formatViews(info.viewCount)
        binding.textVideoId.text = info.videoId
        binding.textStreamCount.text = getString(R.string.available_streams, info.videoStreams.size, info.audioStreams.size)

        // Show download buttons if streams available
        binding.btnDownloadVideo.visibility = if (info.videoStreams.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnDownloadAudio.visibility = if (info.audioStreams.isNotEmpty()) View.VISIBLE else View.GONE

        // Load thumbnail with Coil
        info.thumbnailUrl?.let { thumbUrl ->
            lifecycleScope.launch {
                try {
                    val drawable = coil.request.ImageRequest.Builder(requireContext())
                        .data(thumbUrl)
                        .target { drawable -> binding.imageThumbnail.setImageDrawable(drawable) }
                        .build()
                    coil.ImageLoader(requireContext()).enqueue(drawable)
                } catch (e: Exception) {
                    // Thumbnail loading failed, not critical
                }
            }
        }
    }

    private fun showQualitySelectionDialog(
        streams: List<VideoStreamInfo>,
        isAudioOnly: Boolean
    ) {
        if (streams.isEmpty()) {
            Snackbar.make(binding.root, R.string.no_streams_available, Snackbar.LENGTH_SHORT).show()
            return
        }

        // Sort streams by quality
        val sortedStreams = when {
            isAudioOnly -> streams.sortedByDescending { it.bitrate }
            else -> streams.sortedByDescending { parseResolution(it.resolution ?: "0p") }
        }

        val labels = sortedStreams.map { stream ->
            when {
                isAudioOnly -> "${stream.quality} (${formatFileSize(stream.fileSize)})"
                else -> "${stream.resolution ?: "?"} (${formatFileSize(stream.fileSize)})"
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isAudioOnly) R.string.select_audio_quality else R.string.select_video_quality)
            .setItems(labels) { _, which ->
                val selectedStream = sortedStreams[which]
                downloadVideo(selectedStream, isAudioOnly)
            }
            .show()
    }

    private fun showQualitySelectionDialog(
        streams: List<AudioStreamInfo>,
        isAudioOnly: Boolean
    ) {
        if (streams.isEmpty()) {
            Snackbar.make(binding.root, R.string.no_streams_available, Snackbar.LENGTH_SHORT).show()
            return
        }

        val sortedStreams = streams.sortedByDescending { it.bitrate }

        val labels = sortedStreams.map { stream ->
            "${stream.quality} (${formatFileSize(stream.fileSize)})"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_audio_quality)
            .setItems(labels) { _, which ->
                val selectedStream = sortedStreams[which]
                downloadAudio(selectedStream)
            }
            .show()
    }

    private fun downloadVideo(stream: VideoStreamInfo, isAudioOnly: Boolean) {
        val info = currentVideoInfo ?: return

        lifecycleScope.launch {
            val downloadManager = DownloadManager.getInstance(requireContext())
            val extension = if (stream.format?.name?.contains("webm") == true) "webm" else "mp4"
            val fileName = sanitizeFileName("${info.title} [${stream.resolution}].$extension")

            downloadManager.addDownload(
                url = stream.url,
                fileName = fileName,
                title = info.title,
                thumbnailUrl = info.thumbnailUrl,
                source = DownloadSource.YOUTUBE,
                sourceSite = "YouTube",
                quality = stream.resolution,
                format = extension,
                mimeType = "video/$extension",
                fileSize = stream.fileSize,
                isAudioOnly = isAudioOnly
            )

            Snackbar.make(
                binding.root,
                getString(R.string.download_started),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun downloadAudio(stream: AudioStreamInfo) {
        val info = currentVideoInfo ?: return

        lifecycleScope.launch {
            val downloadManager = DownloadManager.getInstance(requireContext())
            val extension = if (stream.format?.name?.contains("webm") == true) "webm" else "m4a"
            val fileName = sanitizeFileName("${info.title} [${stream.quality}].$extension")

            downloadManager.addDownload(
                url = stream.url,
                fileName = fileName,
                title = info.title,
                thumbnailUrl = info.thumbnailUrl,
                source = DownloadSource.YOUTUBE,
                sourceSite = "YouTube",
                quality = stream.quality,
                format = extension,
                mimeType = "audio/$extension",
                fileSize = stream.fileSize,
                isAudioOnly = true
            )

            Snackbar.make(
                binding.root,
                getString(R.string.download_started),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun extractPlaylist() {
        val url = binding.editTextUrl.text.toString().trim()
        if (url.isEmpty() || !YouTubeExtractor.isPlaylistUrl(url)) {
            Snackbar.make(binding.root, R.string.invalid_playlist_url, Snackbar.LENGTH_SHORT).show()
            return
        }

        if (isExtracting) return
        isExtracting = true

        binding.shimmerLayout.visibility = View.VISIBLE
        binding.shimmerLayout.startShimmer()

        lifecycleScope.launch {
            try {
                val playlistInfo = YouTubeExtractor.extractPlaylistInfo(url)
                if (playlistInfo != null) {
                    showPlaylistDialog(playlistInfo)
                } else {
                    Snackbar.make(binding.root, R.string.playlist_extraction_failed, Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(binding.root, R.string.playlist_extraction_failed, Snackbar.LENGTH_SHORT).show()
            } finally {
                isExtracting = false
                binding.shimmerLayout.stopShimmer()
                binding.shimmerLayout.visibility = View.GONE
            }
        }
    }

    private fun showPlaylistDialog(playlistInfo: PlaylistInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(playlistInfo.name)
            .setMessage(
                getString(
                    R.string.playlist_info,
                    playlistInfo.uploaderName ?: "",
                    playlistInfo.totalItems,
                    formatFileSize(playlistInfo.episodes.sumOf { it.fileSize })
                )
            )
            .setPositiveButton(R.string.download_all) { _, _ ->
                lifecycleScope.launch {
                    val downloadManager = DownloadManager.getInstance(requireContext())
                    downloadManager.addSeasonDownloads(playlistInfo.episodes, DownloadSource.PLAYLIST)
                    Snackbar.make(
                        binding.root,
                        getString(R.string.added_to_queue, playlistInfo.totalItems),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showError(message: String) {
        binding.textError.visibility = View.VISIBLE
        binding.textError.text = message
        binding.cardResult.visibility = View.GONE
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }

    private fun formatViews(views: Long): String {
        return when {
            views >= 1_000_000_000 -> String.format("%.1fB", views / 1_000_000_000.0)
            views >= 1_000_000 -> String.format("%.1fM", views / 1_000_000.0)
            views >= 1_000 -> String.format("%.1fK", views / 1_000.0)
            else -> views.toString()
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "N/A"
        return com.hitif.videodownload.download.formatFileSize(bytes)
    }

    private fun parseResolution(resolution: String): Int {
        return try {
            resolution.replace("[^0-9]".toRegex(), "").toInt()
        } catch (e: Exception) {
            0
        }
    }

    private fun sanitizeFileName(name: String): String {
        val invalidChars = arrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\n', '\r', '\t')
        var sanitized = name
        for (char in invalidChars) {
            sanitized = sanitized.replace(char, '_')
        }
        return sanitized.trim().take(200)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
