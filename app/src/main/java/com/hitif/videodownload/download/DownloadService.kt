package com.hitif.videodownload.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.hitif.videodownload.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : LifecycleService() {

    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val activeDownloadsCount = AtomicInteger(0)
    private var maxConcurrentDownloads = 3

    companion object {
        const val ACTION_START_DOWNLOAD = "action_start_download"
        const val ACTION_PAUSE_DOWNLOAD = "action_pause_download"
        const val ACTION_RESUME_DOWNLOAD = "action_resume_download"
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_URL = "extra_url"

        fun startDownload(context: Context, downloadId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            startForegroundService(context, intent)
        }

        fun pauseDownload(context: Context, downloadId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            startForegroundService(context, intent)
        }

        fun resumeDownload(context: Context, downloadId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            startForegroundService(context, intent)
        }

        fun cancelDownload(context: Context, downloadId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            startForegroundService(context, intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1)
                if (downloadId != -1L) {
                    startDownloadJob(downloadId)
                }
            }
            ACTION_PAUSE_DOWNLOAD -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1)
                pauseDownloadJob(downloadId)
            }
            ACTION_RESUME_DOWNLOAD -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1)
                if (downloadId != -1L) {
                    startDownloadJob(downloadId)
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1)
                cancelDownloadJob(downloadId)
            }
        }

        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_DOWNLOAD_PROGRESS)
            .setContentTitle(getString(R.string.download_manager))
            .setContentText(getString(R.string.preparing_downloads))
            .setSmallIcon(R.drawable.ic_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(NotificationHelper.DOWNLOAD_SERVICE_NOTIFICATION_ID, notification)
    }

    private fun startDownloadJob(downloadId: Long) {
        if (activeJobs.containsKey(downloadId)) return

        val job = lifecycleScope.launch {
            try {
                val downloadManager = DownloadManager.getInstance(this@DownloadService)
                val entity = downloadManager.database.downloadDao().getById(downloadId) ?: return@launch

                // Wait if max concurrent reached
                while (activeDownloadsCount.get() >= maxConcurrentDownloads) {
                    delay(1000)
                }

                activeDownloadsCount.incrementAndGet()
                updateServiceNotification()

                // Execute download
                downloadManager.executeDownload(entity)
            } catch (e: CancellationException) {
                // Download was cancelled
            } catch (e: Exception) {
                // Error handled in executeDownload
            } finally {
                activeJobs.remove(downloadId)
                activeDownloadsCount.decrementAndGet()
                updateServiceNotification()

                // Stop service if no active downloads
                if (activeJobs.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        activeJobs[downloadId] = job
    }

    private fun pauseDownloadJob(downloadId: Long) {
        activeJobs[downloadId]?.cancel()
        lifecycleScope.launch {
            val dao = DownloadManager.getInstance(this@DownloadService).database.downloadDao()
            dao.updateStatus(downloadId, DownloadStatus.PAUSED)
        }
    }

    private fun cancelDownloadJob(downloadId: Long) {
        activeJobs[downloadId]?.cancel()
        lifecycleScope.launch {
            val dao = DownloadManager.getInstance(this@DownloadService).database.downloadDao()
            dao.updateStatus(downloadId, DownloadStatus.CANCELLED)
        }
    }

    private fun updateServiceNotification() {
        val count = activeDownloadsCount.get()
        val text = if (count > 0) {
            resources.getQuantityString(R.plurals.downloads_active, count, count)
        } else {
            getString(R.string.no_active_downloads)
        }

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_DOWNLOAD_PROGRESS)
            .setContentTitle(getString(R.string.download_manager))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

        NotificationHelper.updateNotification(this, NotificationHelper.DOWNLOAD_SERVICE_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    override fun onBind(intent: Intent?): IBinder? {
        super.onBind(intent)
        return null
    }
}
