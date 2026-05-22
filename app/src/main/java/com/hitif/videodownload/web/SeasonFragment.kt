package com.hitif.videodownload.web

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.hitif.videodownload.R
import com.hitif.videodownload.databinding.FragmentSeasonBinding
import com.hitif.videodownload.download.DownloadManager
import com.hitif.videodownload.download.DownloadSource
import com.hitif.videodownload.download.SeasonEpisode

class SeasonFragment : Fragment() {

    private var _binding: FragmentSeasonBinding? = null
    private val binding get() = _binding!!

    private lateinit var seasonAdapter: SeasonAdapter

    private val seasonEpisodes = mutableListOf<SeasonEpisode>()
    private var seriesName: String = ""
    private var currentSeason: Int = 1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSeasonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSeasonSelector()
        setupDownloadAllButton()
        setupAddEpisodesButton()

        // Load sample data if no arguments
        loadSeasonData()
    }

    private fun setupRecyclerView() {
        seasonAdapter = SeasonAdapter(
            onEpisodeClick = { episode -> showEpisodeOptions(episode) },
            onEpisodeDownloadClick = { episode -> downloadEpisode(episode) }
        )
        binding.recyclerViewEpisodes.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = seasonAdapter
        }
    }

    private fun setupSeasonSelector() {
        binding.spinnerSeason.minValue = 1
        binding.spinnerSeason.maxValue = 50
        binding.spinnerSeason.wrapSelectorWheel = true
        binding.spinnerSeason.setOnValueChangedListener { _, _, newVal ->
            currentSeason = newVal
            updateSeasonLabel()
        }
        updateSeasonLabel()
    }

    private fun setupDownloadAllButton() {
        binding.btnDownloadAll.setOnClickListener {
            if (seasonEpisodes.isEmpty()) {
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root, R.string.no_episodes, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            lifecycleScope.launchWhenStarted {
                val downloadManager = DownloadManager.getInstance(requireContext())

                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.download_season)
                    .setMessage(getString(R.string.download_season_confirm, seasonEpisodes.size, seriesName, currentSeason))
                    .setPositiveButton(R.string.download_all) { _, _ ->
                        launch { 
                            downloadManager.addSeasonDownloads(seasonEpisodes, DownloadSource.SEASON)
                            com.google.android.material.snackbar.Snackbar.make(
                                binding.root,
                                getString(R.string.added_to_queue, seasonEpisodes.size),
                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun setupAddEpisodesButton() {
        binding.btnAddEpisodes.setOnClickListener {
            showAddEpisodesDialog()
        }
    }

    private fun showAddEpisodesDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_episodes, null)
        val editTextUrl = dialogView.findViewById<android.widget.EditText>(R.id.editTextEpisodeUrl)
        val editTextTitle = dialogView.findViewById<android.widget.EditText>(R.id.editTextEpisodeTitle)
        val spinnerSeason = dialogView.findViewById<android.widget.NumberPicker>(R.id.spinnerEpisodeSeason)
        val spinnerEpisode = dialogView.findViewById<android.widget.NumberPicker>(R.id.spinnerEpisodeNumber)

        spinnerSeason.minValue = 1
        spinnerSeason.maxValue = 50
        spinnerSeason.value = currentSeason

        spinnerEpisode.minValue = 1
        spinnerEpisode.maxValue = 500
        spinnerEpisode.value = seasonEpisodes.size + 1

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_episode)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val url = editTextUrl.text.toString().trim()
                val title = editTextTitle.text.toString().trim()
                val season = spinnerSeason.value
                val episode = spinnerEpisode.value

                if (url.isNotEmpty()) {
                    val newEpisode = SeasonEpisode(
                        url = url,
                        fileName = "${seriesName.ifEmpty { "Video" }}_S${String.format("%02d", season)}E${String.format("%02d", episode)}.mp4",
                        title = title.ifEmpty { "Episode $episode" },
                        seasonNumber = season,
                        episodeNumber = episode,
                        seriesName = seriesName.ifEmpty { "Series" },
                        sourceSite = WebVideoExtractor.detectSite(url)?.name
                    )
                    seasonEpisodes.add(newEpisode)
                    seasonAdapter.submitList(seasonEpisodes.toList())
                    updateEpisodeCount()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEpisodeOptions(episode: SeasonEpisode) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(episode.title ?: episode.episodeLabel)
            .setItems(arrayOf(getString(R.string.download), getString(R.string.remove))) { _, which ->
                when (which) {
                    0 -> downloadEpisode(episode)
                    1 -> {
                        seasonEpisodes.remove(episode)
                        seasonAdapter.submitList(seasonEpisodes.toList())
                        updateEpisodeCount()
                    }
                }
            }
            .show()
    }

    private fun downloadEpisode(episode: SeasonEpisode) {
        lifecycleScope.launchWhenStarted {
            val downloadManager = DownloadManager.getInstance(requireContext())
            downloadManager.addDownload(
                url = episode.url,
                fileName = episode.fileName,
                title = episode.title,
                thumbnailUrl = episode.thumbnailUrl,
                source = DownloadSource.SEASON,
                sourceSite = episode.sourceSite,
                quality = episode.quality,
                format = episode.format,
                mimeType = episode.mimeType,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                seriesName = episode.seriesName
            )
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, R.string.download_started, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun loadSeasonData() {
        // Show empty state
        binding.emptyState.visibility = View.VISIBLE
        binding.recyclerViewEpisodes.visibility = View.GONE
        updateEpisodeCount()
    }

    private fun updateSeasonLabel() {
        binding.textSeasonLabel.text = getString(R.string.season_number, currentSeason)
    }

    private fun updateEpisodeCount() {
        binding.textEpisodeCount.text = getString(R.string.episode_count, seasonEpisodes.size)
        binding.emptyState.visibility = if (seasonEpisodes.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerViewEpisodes.visibility = if (seasonEpisodes.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun launch(block: suspend () -> Unit) {
        androidx.lifecycle.lifecycleScope.launchWhenStarted {
            block()
        }
    }
}

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SeasonAdapter(
    private val onEpisodeClick: (SeasonEpisode) -> Unit,
    private val onEpisodeDownloadClick: (SeasonEpisode) -> Unit
) : androidx.recyclerview.widget.ListAdapter<SeasonEpisode, SeasonAdapter.EpisodeViewHolder>(SeasonDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val episode = currentList[position]
        holder.bind(episode)
    }

    inner class EpisodeViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
        fun bind(episode: SeasonEpisode) {
            itemView.findViewById<android.widget.TextView>(R.id.textEpisodeTitle).text =
                episode.title ?: "Episode ${episode.episodeNumber ?: "?"}"
            itemView.findViewById<android.widget.TextView>(R.id.textEpisodeLabel).text =
                episode.episodeLabel.ifEmpty { "E${episode.episodeNumber ?: "?"}" }
            itemView.findViewById<android.widget.TextView>(R.id.textEpisodeSource).text =
                episode.sourceSite ?: "Web"
            itemView.findViewById<android.widget.ImageButton>(R.id.btnDownloadEpisode).setOnClickListener {
                onEpisodeDownloadClick(episode)
            }
            itemView.setOnClickListener { onEpisodeClick(episode) }
        }
    }
}

class SeasonDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<SeasonEpisode>() {
    override fun areItemsTheSame(oldItem: SeasonEpisode, newItem: SeasonEpisode): Boolean {
        return oldItem.url == newItem.url
    }

    override fun areContentsTheSame(oldItem: SeasonEpisode, newItem: SeasonEpisode): Boolean {
        return oldItem == newItem
    }
}
