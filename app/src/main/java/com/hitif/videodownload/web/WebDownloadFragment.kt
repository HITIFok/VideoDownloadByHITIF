package com.hitif.videodownload.web

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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

    // Tracks the last known URL to detect SPA navigation changes
    private var lastKnownUrl: String = ""
    private val urlPollingHandler = Handler(Looper.getMainLooper())
    private var isUserTypingUrl = false

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

        // Add JavaScript interface for SPA URL change detection
        webView.addJavascriptInterface(SpaUrlBridge(), "SpaUrlBridge")

        // Single unified WebViewClient (fixes the double-setWebViewClient bug)
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
                val finalUrl = url ?: ""
                lastKnownUrl = finalUrl
                if (!isUserTypingUrl) {
                    binding.urlBar.setText(finalUrl)
                }
                videoUrls.clear()
                // Stop URL polling during page load to avoid stale readings
                urlPollingHandler.removeCallbacks(urlPollingRunnable)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                updateVideoCountBadge()
                val finalUrl = url ?: ""
                lastKnownUrl = finalUrl
                if (!isUserTypingUrl) {
                    binding.urlBar.setText(finalUrl)
                }
                // Inject SPA URL monitoring script after every page load
                injectSpaUrlMonitor(view)
                // Start URL polling as fallback for sites that don't use pushState
                startUrlPolling()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (WebVideoExtractor.isDirectMediaUrl(url) && url.contains("video")) {
                    videoUrls.add(url)
                    updateVideoCountBadge()
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                // Fullscreen video
                super.onShowCustomView(view, callback)
            }

            override fun onHideCustomView() {
                super.onHideCustomView()
            }
        }

        // Load default page
        webView.loadUrl("https://www.google.com")
    }

    /**
     * JavaScript bridge that receives URL change notifications from the
     * injected SPA monitoring script. YouTube (and many other SPAs) use
     * history.pushState / replaceState to navigate without a full page reload,
     * so onPageStarted / onPageFinished are never called.
     */
    private inner class SpaUrlBridge {
        @JavascriptInterface
        fun onUrlChanged(url: String) {
            if (url.isNotBlank() && url != lastKnownUrl) {
                lastKnownUrl = url
                activity?.runOnUiThread {
                    if (!isUserTypingUrl) {
                        binding.urlBar.setText(url)
                    }
                }
            }
        }
    }

    /**
     * Injects JavaScript that overrides history.pushState and
     * history.replaceState so every SPA navigation notifies our
     * native bridge. Also listens for the popstate event (back/forward).
     */
    private fun injectSpaUrlMonitor(webView: WebView?) {
        webView?.evaluateJavascript(URL_MONITOR_JS, null)
    }

    companion object {
        /**
         * Self-invoking JS that:
         * 1. Wraps the native pushState / replaceState to fire our bridge after each call
         * 2. Listens on popstate (browser back / forward)
         * 3. Also uses a MutationObserver on <title> as an additional signal
         * 4. Monitors document.title changes via a periodic interval
         *
         * The script is idempotent: the override wrappers are installed only once
         * per page lifetime.
         */
        private val URL_MONITOR_JS = """
        (function() {
            if (window.__spaUrlMonitorInstalled) return;
            window.__spaUrlMonitorInstalled = true;

            function notifyNative() {
                try {
                    var url = window.location.href;
                    if (url && typeof SpaUrlBridge !== 'undefined') {
                        SpaUrlBridge.onUrlChanged(url);
                    }
                } catch(e) {}
            }

            // Override pushState
            var origPushState = history.pushState;
            history.pushState = function() {
                var result = origPushState.apply(this, arguments);
                notifyNative();
                return result;
            };

            // Override replaceState
            var origReplaceState = history.replaceState;
            history.replaceState = function() {
                var result = origReplaceState.apply(this, arguments);
                notifyNative();
                return result;
            };

            // Listen for popstate (back / forward)
            window.addEventListener('popstate', function() {
                setTimeout(notifyNative, 100);
            });

            // YouTube specifically updates the DOM without always calling pushState
            // Watch for title changes as a signal
            var lastTitle = document.title;
            var titleObserver = new MutationObserver(function() {
                if (document.title !== lastTitle) {
                    lastTitle = document.title;
                    // Small delay to let the URL update settle
                    setTimeout(notifyNative, 150);
                }
            });
            titleObserver.observe(document.querySelector('title'), { childList: true, subtree: true, characterData: true });

            // Periodic check as a last resort (every 800ms)
            setInterval(function() {
                try {
                    var currentUrl = window.location.href;
                    if (currentUrl && window.__lastSpaUrl !== currentUrl) {
                        window.__lastSpaUrl = currentUrl;
                        notifyNative();
                    }
                } catch(e) {}
            }, 800);

            // Set initial URL
            window.__lastSpaUrl = window.location.href;
        })();
        """.trimIndent()
    }

    // Periodic polling from native side as an extra safety net
    private val urlPollingRunnable = object : Runnable {
        override fun run() {
            try {
                val webView = binding.webView
                webView.evaluateJavascript("(function(){try{return window.location.href;}catch(e){return '';}})()") { result ->
                    // result comes wrapped in quotes, e.g. "https://youtube.com/watch?v=..."
                    val cleanedUrl = result?.trim('"') ?: ""
                    if (cleanedUrl.isNotBlank() && cleanedUrl != lastKnownUrl && !isUserTypingUrl) {
                        lastKnownUrl = cleanedUrl
                        binding.urlBar.setText(cleanedUrl)
                    }
                }
            } catch (_: Exception) {}
            // Poll every 1 second
            urlPollingHandler.postDelayed(this, 1000)
        }
    }

    private fun startUrlPolling() {
        urlPollingHandler.removeCallbacks(urlPollingRunnable)
        urlPollingHandler.postDelayed(urlPollingRunnable, 1000)
    }

    private fun stopUrlPolling() {
        urlPollingHandler.removeCallbacks(urlPollingRunnable)
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
                isUserTypingUrl = false
                val finalUrl = if (!url.startsWith("http")) "https://$url" else url
                binding.webView.loadUrl(finalUrl)
            }
            true
        }

        binding.btnGo.setOnClickListener {
            val url = binding.urlBar.text.toString().trim()
            if (url.isNotEmpty()) {
                isUserTypingUrl = false
                val finalUrl = if (!url.startsWith("http")) "https://$url" else url
                binding.webView.loadUrl(finalUrl)
            }
        }

        // Track when user is manually editing the URL bar so we don't overwrite it
        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                isUserTypingUrl = true
            } else {
                // Small delay so the Go button / Enter action can set isUserTypingUrl = false first
                binding.urlBar.postDelayed({ isUserTypingUrl = false }, 300)
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
        stopUrlPolling()
        _binding = null
        super.onDestroyView()
    }
}
