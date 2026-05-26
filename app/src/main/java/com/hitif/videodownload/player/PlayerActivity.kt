package com.hitif.videodownload.player

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.hitif.videodownload.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_URL = "extra_url"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Video Player"
        supportActionBar?.title = title
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val url = intent.getStringExtra(EXTRA_URL)
        val source = if (filePath != null) {
            // Use FileProvider-compatible Uri (fixes crash on API 35+)
            // Uri.fromFile() is deprecated and throws FileUriExposedException
            try {
                val file = java.io.File(filePath)
                if (file.exists()) {
                    androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "${packageName}.provider",
                        file
                    )
                } else {
                    // Fallback to content URI
                    Uri.parse("file://$filePath")
                }
            } catch (e: Exception) {
                // FileProvider not configured for this path — use content URI
                Uri.parse("file://$filePath")
            }
        } else if (url != null) {
            Uri.parse(url)
        } else {
            finish()
            return
        }

        initializePlayer(source)
    }

    private fun initializePlayer(uri: Uri) {
        // Build a custom HttpDataSource with extended timeout for slow servers
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36")

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        player?.apply {
            val mediaItem = MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val message = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                            "Network error - Check your connection"
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                            "Unsupported format"
                        else -> "Playback error: ${error.message}"
                    }
                    Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_LONG).show()
                }
            })
        }

        binding.playerView.player = player
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
