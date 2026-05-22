package com.hitif.videodownload.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.hitif.videodownload.MainActivity
import com.hitif.videodownload.R
import com.hitif.videodownload.download.DownloadEntity
import com.hitif.videodownload.download.formatFileSize

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_DOWNLOAD_PROGRESS = "hitif_download_progress"
        const val CHANNEL_DOWNLOAD_COMPLETE = "hitif_download_complete"
        const val CHANNEL_ERROR = "hitif_error"

        const val DOWNLOAD_SERVICE_NOTIFICATION_ID = 1000
        const val DOWNLOAD_NOTIFICATION_BASE_ID = 2000

        fun updateNotification(context: Context, id: Int, notification: android.app.Notification) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(id, notification)
        }
    }

    fun showDownloadProgress(entity: DownloadEntity) {
        val notification = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD_PROGRESS)
            .setContentTitle(entity.title ?: entity.fileName)
            .setContentText(context.getString(R.string.connecting))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, 0, true)
            .setContentIntent(createMainActivityIntent())
            .build()

        notificationManager.notify(entity.id.toInt() + DOWNLOAD_NOTIFICATION_BASE_ID, notification)
    }

    fun updateDownloadProgress(
        entity: DownloadEntity,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speed: Long
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD_PROGRESS)
            .setContentTitle(entity.title ?: entity.fileName)
            .setContentText(
                "$progress% - ${formatFileSize(downloadedBytes)}/${formatFileSize(totalBytes)} - ${formatFileSize(speed)}/s"
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progress, false)
            .setContentIntent(createMainActivityIntent())
            .build()

        notificationManager.notify(entity.id.toInt() + DOWNLOAD_NOTIFICATION_BASE_ID, notification)
    }

    fun showDownloadComplete(entity: DownloadEntity) {
        val notification = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD_COMPLETE)
            .setContentTitle(context.getString(R.string.download_complete))
            .setContentText(entity.title ?: entity.fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(createMainActivityIntent())
            .setVibrate(longArrayOf(0, 200, 100, 200))
            .build()

        notificationManager.notify(entity.id.toInt() + DOWNLOAD_NOTIFICATION_BASE_ID, notification)
    }

    fun showDownloadError(entity: DownloadEntity, errorMessage: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ERROR)
            .setContentTitle(context.getString(R.string.download_failed))
            .setContentText("${entity.title ?: entity.fileName}: $errorMessage")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(createMainActivityIntent())
            .build()

        notificationManager.notify(entity.id.toInt() + DOWNLOAD_NOTIFICATION_BASE_ID + 5000, notification)
    }

    fun cancelNotification(id: Long) {
        notificationManager.cancel(id.toInt() + DOWNLOAD_NOTIFICATION_BASE_ID)
    }

    private fun createMainActivityIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

}
