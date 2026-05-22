package com.hitif.videodownload.download

import androidx.room.TypeConverter
import java.util.Date

class Converters {
    @TypeConverter
    fun fromDate(date: Date?): Long? = date?.time

    @TypeConverter
    fun toDate(timestamp: Long?): Date? = timestamp?.let { Date(it) }

    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus): String = status.name

    @TypeConverter
    fun toDownloadStatus(name: String): DownloadStatus = DownloadStatus.valueOf(name)

    @TypeConverter
    fun fromDownloadSource(source: DownloadSource): String = source.name

    @TypeConverter
    fun toDownloadSource(name: String): DownloadSource = DownloadSource.valueOf(name)
}
