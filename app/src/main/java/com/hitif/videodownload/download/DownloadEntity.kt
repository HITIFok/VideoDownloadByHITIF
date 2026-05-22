package com.hitif.videodownload.download

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["url"], unique = true),
        Index(value = ["status"]),
        Index(value = ["source"])
    ]
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // URLs and identifiers
    val url: String,
    val fileName: String,
    val filePath: String? = null,
    val thumbnailUrl: String? = null,

    // Download state
    var status: DownloadStatus = DownloadStatus.PENDING,
    var progress: Int = 0,
    var downloadedBytes: Long = 0,
    var totalBytes: Long = 0,
    var speed: Long = 0, // bytes per second
    var eta: Long = 0, // estimated time remaining in seconds

    // Media info
    val title: String? = null,
    val artist: String? = null,
    val duration: Long = 0,
    val fileSize: Long = 0,
    val mimeType: String? = null,
    val quality: String? = null,
    val format: String? = null,
    val resolution: String? = null,

    // Source tracking
    val source: DownloadSource = DownloadSource.WEB,
    val sourceSite: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val seriesName: String? = null,
    val playlistId: String? = null,
    val playlistPosition: Int? = null,

    // Metadata
    val createdAt: Date = Date(),
    var completedAt: Date? = null,
    var errorMessage: String? = null,
    var retryCount: Int = 0,

    // Configuration
    var concurrentConnections: Int = 4,
    var isAudioOnly: Boolean = false
) {
    val formattedSize: String
        get() = formatFileSize(totalBytes)

    val formattedSpeed: String
        get() = "${formatFileSize(speed)}/s"

    val formattedProgress: String
        get() = "$progress%"

    val episodeLabel: String
        get() {
            return when {
                seasonNumber != null && episodeNumber != null ->
                    "S${String.format("%02d", seasonNumber)}E${String.format("%02d", episodeNumber)}"
                episodeNumber != null ->
                    "E${String.format("%02d", episodeNumber)}"
                else -> ""
            }
        }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(
        "%.1f %s",
        bytes / Math.pow(1024.0, digitGroups.toDouble()),
        units[digitGroups.coerceAtMost(units.size - 1)]
    )
}

enum class DownloadStatus {
    PENDING,
    CONNECTING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    QUEUED
}

enum class DownloadSource {
    WEB,
    YOUTUBE,
    PLAYLIST,
    SEASON
}
