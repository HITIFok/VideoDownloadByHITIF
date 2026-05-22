package com.hitif.videodownload.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hitif.videodownload.R
import com.hitif.videodownload.databinding.FragmentDownloadsBinding
import com.hitif.videodownload.download.*
import com.hitif.videodownload.player.PlayerActivity
import com.hitif.videodownload.ui.adapters.DownloadItemAdapter
import com.hitif.videodownload.ui.viewmodel.MainViewModel

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var activeAdapter: DownloadItemAdapter
    private lateinit var completedAdapter: DownloadItemAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAdapters()
        setupSwipeRefresh()
        setupObservers()
    }

    private fun setupAdapters() {
        activeAdapter = DownloadItemAdapter(
            onPlayClick = {},
            onPauseClick = { viewModel.pauseDownload(it.id) },
            onResumeClick = { viewModel.resumeDownload(it.id) },
            onCancelClick = { viewModel.cancelDownload(it.id) },
            onRetryClick = { viewModel.retryDownload(it) },
            onDeleteClick = { viewModel.deleteDownload(it) }
        )

        completedAdapter = DownloadItemAdapter(
            onPlayClick = { playVideo(it) },
            onPauseClick = {},
            onResumeClick = {},
            onCancelClick = {},
            onRetryClick = { viewModel.retryDownload(it) },
            onDeleteClick = { viewModel.deleteDownload(it) }
        )

        binding.recyclerViewActive.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewActive.adapter = activeAdapter

        binding.recyclerViewCompleted.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewCompleted.adapter = completedAdapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            viewModel.activeDownloads.collect { downloads ->
                activeAdapter.submitList(downloads)
                binding.textActiveCount.text = getString(R.string.active_downloads_count, downloads.size)
                binding.emptyStateActive.visibility = if (downloads.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewActive.visibility = if (downloads.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            viewModel.completedDownloads.collect { downloads ->
                completedAdapter.submitList(downloads)
                binding.textCompletedCount.text = getString(R.string.completed_downloads_count, downloads.size)
                binding.emptyStateCompleted.visibility = if (downloads.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewCompleted.visibility = if (downloads.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun playVideo(entity: DownloadEntity) {
        entity.filePath?.let { path ->
            val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_FILE_PATH, path)
                putExtra(PlayerActivity.EXTRA_TITLE, entity.title ?: entity.fileName)
                putExtra(PlayerActivity.EXTRA_MIME_TYPE, entity.mimeType)
            }
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
