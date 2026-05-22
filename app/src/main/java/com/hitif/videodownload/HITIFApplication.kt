package com.hitif.videodownload

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.multidex.MultiDexApplication
import com.hitif.videodownload.download.DownloadDatabase
import com.hitif.videodownload.download.DownloadManager
import com.hitif.videodownload.history.HistoryManager
import com.hitif.videodownload.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HITIFApplication : MultiDexApplication() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var downloadDatabase: DownloadDatabase
        private set

    lateinit var downloadManager: DownloadManager
        private set

    lateinit var historyManager: HistoryManager
        private set

    lateinit var notificationHelper: NotificationHelper
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize database
        downloadDatabase = DownloadDatabase.getInstance(this)

        // Initialize download manager
        downloadManager = DownloadManager.getInstance(this)

        // Initialize history manager
        historyManager = HistoryManager.getInstance(this)

        // Initialize notification helper
        notificationHelper = NotificationHelper(this)

        // Create notification channels
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        // Download progress channel
        val downloadChannel = NotificationChannel(
            NotificationHelper.CHANNEL_DOWNLOAD_PROGRESS,
            getString(R.string.notification_download_progress),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows download progress"
            setShowBadge(false)
        }

        // Download complete channel
        val completeChannel = NotificationChannel(
            NotificationHelper.CHANNEL_DOWNLOAD_COMPLETE,
            getString(R.string.notification_download_complete),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shows download completion"
            setShowBadge(true)
            enableVibration(true)
        }

        // Error channel
        val errorChannel = NotificationChannel(
            NotificationHelper.CHANNEL_ERROR,
            getString(R.string.notification_error),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shows download errors"
            setShowBadge(true)
            enableVibration(true)
        }

        manager.createNotificationChannel(downloadChannel)
        manager.createNotificationChannel(completeChannel)
        manager.createNotificationChannel(errorChannel)
    }

    companion object {
        lateinit var instance: HITIFApplication
            private set
    }
}
