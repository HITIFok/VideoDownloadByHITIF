package com.hitif.videodownload.player

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
            Uri.fromFile(java.io.File(filePath))
        } else if (url != null) {
            Uri.parse(url)
        } else {
            finish()
            return
        }

        initializePlayer(source)
    }

    private fun initializePlayer(uri: Uri) {
        player = ExoPlayer.Builder(this).build().apply {
            val mediaItem = MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@PlayerActivity, "Playback error: ${error.message}", Toast.LENGTH_SHORT).show()
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
