package com.hitif.videodownload

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.hitif.videodownload.databinding.ActivityMainBinding
import com.hitif.videodownload.download.DownloadManager
import com.hitif.videodownload.notification.NotificationHelper
import com.hitif.videodownload.web.WebVideoExtractor
import com.hitif.videodownload.youtube.YouTubeExtractor
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup navigation
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Setup bottom navigation
        val bottomNav = binding.bottomNavigation
        bottomNav.setupWithNavController(navController)

        // Handle URL from intent (shared from other apps)
        handleIncomingIntent(intent)

        // Handle back press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!navController.popBackStack()) {
                    showExitDialog()
                }
            }
        })

        // Observe active downloads for badge
        lifecycleScope.launch {
            DownloadManager.getInstance(this@MainActivity).activeDownloadsCount.collect { count ->
                val badge = bottomNav.getOrCreateBadge(R.id.navigation_downloads)
                if (count > 0) {
                    badge.isVisible = true
                    badge.number = count
                } else {
                    badge.isVisible = false
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrEmpty()) {
                    processSharedUrl(sharedText)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) {
                    processSharedUrl(uri.toString())
                }
            }
        }
    }

    private fun processSharedUrl(url: String) {
        // Extract URL from text if it contains one
        val videoUrl = extractUrl(url)
        if (videoUrl == null) {
            Snackbar.make(binding.root, R.string.no_valid_url_found, Snackbar.LENGTH_LONG).show()
            return
        }

        when {
            YouTubeExtractor.isYouTubeUrl(videoUrl) -> {
                // Navigate to YouTube tab with URL
                val bundle = Bundle().apply { putString("url", videoUrl) }
                navController.navigate(R.id.navigation_youtube, bundle)
            }
            else -> {
                // Navigate to web tab with URL
                val bundle = Bundle().apply { putString("url", videoUrl) }
                navController.navigate(R.id.navigation_web, bundle)
            }
        }
    }

    private fun extractUrl(text: String): String? {
        // Extract first HTTP/HTTPS URL from text
        val urlPattern = "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)".toRegex()
        return urlPattern.find(text)?.groupValues?.get(1)
    }

    private fun showExitDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_app)
            .setMessage(R.string.exit_app_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                finishAffinity()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }
}
