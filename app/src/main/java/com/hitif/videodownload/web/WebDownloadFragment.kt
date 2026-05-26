package com.hitif.videodownload.web

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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

    // ===== Fullscreen video handling (fixes Sibnet / embedded player crash) =====
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility: Int = 0
    private var originalOrientation: Int = 0

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
            // Enable hardware acceleration for video playback (Sibnet, etc.)
        }

        // Ensure hardware layer for video rendering
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Add JavaScript interface for SPA URL change detection
        webView.addJavascriptInterface(SpaUrlBridge(), "SpaUrlBridge")

        // Single unified WebViewClient
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Only intercept direct media URLs that are NOT from embedded players
                // Sibnet and similar embeds must be allowed to load their own URLs
                val host = request.url.host ?: ""
                val isEmbeddedPlayer = host.contains("sibnet") ||
                    host.contains("vk.com") ||
                    host.contains("coub.com") ||
                    host.contains("dailymotion") ||
                    host.contains("youtube") ||
                    host.contains("vimeo") ||
                    host.contains("ok.ru")

                if (!isEmbeddedPlayer && WebVideoExtractor.isDirectMediaUrl(url)) {
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
                injectSpaUrlMonitor(view)
                startUrlPolling()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                // Only capture actual video files, not player pages or segments
                if (WebVideoExtractor.isDirectMediaUrl(url) &&
                    url.contains("video") &&
                    !url.contains("sibnet") &&
                    !url.contains("vk.com") &&
                    !url.contains("player")) {
                    videoUrls.add(url)
                    updateVideoCountBadge()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                // Log error but don't crash — many sites have minor resource errors
                if (request?.isForMainFrame == true) {
                    android.util.Log.w("WebDownloadFragment", "Main frame error: ${error?.description}")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                // Don't crash on HTTP errors — common for tracking pixels, ads, etc.
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                // FIX: Reload the actual page instead of loading about:blank
                // Sibnet and other embedded players can cause render process crashes
                val currentUrl = view?.url
                android.util.Log.e("WebDownloadFragment", "Render process gone! didCrash=${detail?.didCrash()}, reloading: $currentUrl")
                if (currentUrl != null && currentUrl != "about:blank") {
                    // Small delay to let the system clean up
                    view.postDelayed({ view?.loadUrl(currentUrl) }, 500)
                } else {
                    view?.loadUrl("about:blank")
                }
                return true // Return true to suppress the crash
            }
        }

        // WebChromeClient with proper fullscreen video handling (Sibnet fix)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.progressBar.visibility = View.GONE
                }
            }

            /**
             * Called when the page requests fullscreen video (e.g., Sibnet player).
             * We MUST attach the custom view to the window — calling super crashes
             * or does nothing because there's no default container.
             */
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (customView != null) {
                    // Already in fullscreen — exit first
                    exitFullscreenVideo()
                }

                if (view == null || callback == null) return

                customView = view
                customViewCallback = callback

                // Save original state
                originalSystemUiVisibility = activity?.window?.decorView?.systemUiVisibility ?: 0
                originalOrientation = activity?.requestedOrientation ?: 0

                // Hide system bars for immersive fullscreen
                activity?.window?.apply {
                    setFlags(
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN
                    )
                    decorView?.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
                }
                activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

                // Add the video view on top of everything
                val parent = customView?.parent as? ViewGroup
                parent?.removeView(customView)

                val decorView = activity?.window?.decorView as? FrameLayout ?: return
                val layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                decorView.addView(customView, layoutParams)
            }

            /**
             * Called when fullscreen video should be dismissed.
             */
            override fun onHideCustomView() {
                if (customView == null) return

                // Remove the custom view from the window
                val decorView = activity?.window?.decorView as? FrameLayout
                decorView?.removeView(customView)

                // Clear fullscreen flags
                activity?.window?.apply {
                    clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    decorView?.systemUiVisibility = originalSystemUiVisibility
                }
                activity?.requestedOrientation = originalOrientation

                // Notify the page we exited fullscreen
                customViewCallback?.onCustomViewHidden()

                customView = null
                customViewCallback = null
            }

            /** Console logging — prevents JS errors from causing silent crashes */
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                if (message?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    android.util.Log.w(
                        "WebViewJS",
                        "${message?.sourceId()}:${message?.lineNumber()} - ${message?.message()}"
                    )
                }
                return true
            }
        }

        // Load default page
        webView.loadUrl("https://www.google.com")
    }

    /**
     * JavaScript bridge for SPA URL change detection
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

    private fun injectSpaUrlMonitor(webView: WebView?) {
        webView?.evaluateJavascript(URL_MONITOR_JS, null)
    }

    companion object {
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

            var origPushState = history.pushState;
            history.pushState = function() {
                var result = origPushState.apply(this, arguments);
                notifyNative();
                return result;
            };

            var origReplaceState = history.replaceState;
            history.replaceState = function() {
                var result = origReplaceState.apply(this, arguments);
                notifyNative();
                return result;
            };

            window.addEventListener('popstate', function() {
                setTimeout(notifyNative, 100);
            });

            var lastTitle = document.title;
            var titleObserver = new MutationObserver(function() {
                if (document.title !== lastTitle) {
                    lastTitle = document.title;
                    setTimeout(notifyNative, 150);
                }
            });
            var titleEl = document.querySelector('title');
            if (titleEl) {
                titleObserver.observe(titleEl, { childList: true, subtree: true, characterData: true });
            }

            setInterval(function() {
                try {
                    var currentUrl = window.location.href;
                    if (currentUrl && window.__lastSpaUrl !== currentUrl) {
                        window.__lastSpaUrl = currentUrl;
                        notifyNative();
                    }
                } catch(e) {}
            }, 800);

            window.__lastSpaUrl = window.location.href;
        })();
        """.trimIndent()
    }

    private val urlPollingRunnable = object : Runnable {
        override fun run() {
            try {
                val webView = binding.webView
                webView.evaluateJavascript("(function(){try{return window.location.href;}catch(e){return '';}})()") { result ->
                    val cleanedUrl = result?.trim('"') ?: ""
                    if (cleanedUrl.isNotBlank() && cleanedUrl != lastKnownUrl && !isUserTypingUrl) {
                        lastKnownUrl = cleanedUrl
                        binding.urlBar.setText(cleanedUrl)
                    }
                }
            } catch (_: Exception) {}
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

        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                isUserTypingUrl = true
            } else {
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

    /** Helper to safely exit fullscreen video from anywhere in the fragment */
    private fun exitFullscreenVideo() {
        if (customView == null) return
        try {
            val decorView = activity?.window?.decorView as? FrameLayout
            decorView?.removeView(customView)
            activity?.window?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                decorView?.systemUiVisibility = originalSystemUiVisibility
            }
            activity?.requestedOrientation = originalOrientation
            customViewCallback?.onCustomViewHidden()
        } catch (_: Exception) {}
        customView = null
        customViewCallback = null
    }

    override fun onDestroyView() {
        exitFullscreenVideo()
        stopUrlPolling()
        _binding = null
        super.onDestroyView()
    }

    override fun onDetach() {
        // Safety: clean up fullscreen state when fragment is detached
        if (customView != null) {
            try {
                (customView?.parent as? ViewGroup)?.removeView(customView)
                customViewCallback?.onCustomViewHidden()
            } catch (_: Exception) {}
            customView = null
            customViewCallback = null
        }
        super.onDetach()
    }
}
