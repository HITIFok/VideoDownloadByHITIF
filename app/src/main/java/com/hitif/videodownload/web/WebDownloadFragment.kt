package com.hitif.videodownload.web

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.hitif.videodownload.R
import com.hitif.videodownload.databinding.FragmentWebBinding
import com.hitif.videodownload.download.DownloadManager
import com.hitif.videodownload.download.DownloadSource
import kotlinx.coroutines.launch

class WebDownloadFragment : Fragment() {

    private var _binding: FragmentWebBinding? = null
    private val binding get() = _binding!!

    private val videoUrls = mutableSetOf<String>()
    private var currentVideoUrl: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWebBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupWebView()
        setupBottomBar()
        handleInitialUrl()
    }

    private fun setupWebView() {
        val webView = binding.webView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (WebVideoExtractor.isDirectMediaUrl(url)) {
                    showDownloadDialog(url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                binding.urlBar.setText(url ?: "")
                videoUrls.clear()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url, favicon)
                binding.progressBar.visibility = View.GONE
                updateVideoCountBadge()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                // Fullscreen video
                super.onShowCustomView(view, callback)
            }

            override fun onHideCustomView() {
                super.onHideCustomView()
            }
        }

        // Intercept video URLs from network requests
        webView.setWebViewClient(object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (WebVideoExtractor.isDirectMediaUrl(url) && url.contains("video")) {
                    videoUrls.add(url)
                    updateVideoCountBadge()
                }
                return super.shouldInterceptRequest(view, request)
            }
        })

        // Load default page
        webView.loadUrl("https://www.google.com")
    }

    private fun setupBottomBar() {
        binding.btnDownload.setOnClickListener {
            if (videoUrls.isNotEmpty()) {
                if (videoUrls.size == 1) {
                    downloadVideo(videoUrls.first())
                } else {
                    showVideoSelectionDialog()
                }
            } else {
                // Try to extract from current URL
                val currentUrl = binding.webView.url
                if (currentUrl != null) {
                    lifecycleScope.launch {
                        try {
                            val video = WebVideoExtractor.extractVideo(currentUrl)
                            if (video != null) {
                                downloadExtractedVideo(video)
                            } else {
                                Snackbar.make(binding.root, R.string.no_video_found, Snackbar.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Snackbar.make(binding.root, R.string.extraction_failed, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        binding.btnSeason.setOnClickListener {
            // Check if we can extract a season/playlist
            val currentUrl = binding.webView.url
            if (currentUrl != null) {
                val site = WebVideoExtractor.detectSite(currentUrl)
                Snackbar.make(
                    binding.root,
                    getString(R.string.season_detection, site?.name ?: currentUrl),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        binding.urlBar.setOnEditorActionListener { _, _, _ ->
            val url = binding.urlBar.text.toString().trim()
            if (url.isNotEmpty()) {
                val finalUrl = if (!url.startsWith("http")) "https://$url" else url
                binding.webView.loadUrl(finalUrl)
            }
            true
        }

        binding.btnGo.setOnClickListener {
            val url = binding.urlBar.text.toString().trim()
            if (url.isNotEmpty()) {
                val finalUrl = if (!url.startsWith("http")) "https://$url" else url
                binding.webView.loadUrl(finalUrl)
            }
        }
    }

    private fun handleInitialUrl() {
        arguments?.getString("url")?.let { url ->
            binding.webView.loadUrl(url)
        }
    }

    private fun showDownloadDialog(url: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.video_found)
            .setMessage(url.substring(url.lastIndexOf('/') + 1))
            .setPositiveButton(R.string.download) { _, _ ->
                downloadVideo(url)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showVideoSelectionDialog() {
        val urls = videoUrls.toList()
        val fileNames = urls.map { url ->
            try {
                val path = java.net.URL(url).path
                path.substring(path.lastIndexOf('/') + 1).take(50)
            } catch (e: Exception) {
                "Video"
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_video)
            .setItems(fileNames) { _, which ->
                downloadVideo(urls[which])
            }
            .setNeutralButton(R.string.download_all) { _, _ ->
                urls.forEach { downloadVideo(it) }
            }
            .show()
    }

    private fun downloadVideo(url: String) {
        val fileName = try {
            val path = java.net.URL(url).path
            path.substring(path.lastIndexOf('/') + 1).take(100)
        } catch (e: Exception) {
            "video_${System.currentTimeMillis()}.mp4"
        }

        lifecycleScope.launch {
            val downloadManager = DownloadManager.getInstance(requireContext())
            downloadManager.addDownload(
                url = url,
                fileName = fileName,
                source = DownloadSource.WEB,
                sourceSite = WebVideoExtractor.detectSite(url)?.name ?: "Web",
                format = fileName.substringAfterLast('.', "mp4"),
                mimeType = "video/mp4"
            )
            Snackbar.make(binding.root, R.string.download_started, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun downloadExtractedVideo(video: WebVideoExtractor.ExtractedVideo) {
        lifecycleScope.launch {
            val downloadManager = DownloadManager.getInstance(requireContext())
            downloadManager.addDownload(
                url = video.url,
                fileName = "${video.title ?: "video"}.${video.format}",
                title = video.title,
                thumbnailUrl = video.thumbnailUrl,
                source = DownloadSource.WEB,
                sourceSite = video.sourceSite,
                quality = video.quality,
                format = video.format,
                mimeType = video.mimeType,
                fileSize = video.fileSize
            )
            Snackbar.make(binding.root, R.string.download_started, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun updateVideoCountBadge() {
        binding.videoCountBadge.text = videoUrls.size.toString()
        binding.videoCountBadge.visibility = if (videoUrls.isNotEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
