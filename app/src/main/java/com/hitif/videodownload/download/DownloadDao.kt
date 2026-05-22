package com.hitif.videodownload.download

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity): Long

    @Update
    suspend fun update(download: DownloadEntity)

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): LiveData<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY createdAt DESC")
    fun getDownloadsByStatus(vararg statuses: DownloadStatus): LiveData<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE source = :source ORDER BY createdAt DESC")
    fun getDownloadsBySource(source: DownloadSource): LiveData<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE playlistId = :playlistId ORDER BY playlistPosition ASC")
    fun getDownloadsByPlaylist(playlistId: String): LiveData<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE seriesName = :seriesName AND seasonNumber = :season ORDER BY episodeNumber ASC")
    fun getDownloadsBySeason(seriesName: String, season: Int): LiveData<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADING' OR status = 'CONNECTING' OR status = 'PENDING' OR status = 'QUEUED'")
    fun getActiveDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE url = :url")
    suspend fun getByUrl(url: String): DownloadEntity?

    @Query("UPDATE downloads SET status = :status, progress = :progress, downloadedBytes = :downloadedBytes, speed = :speed, eta = :eta WHERE id = :id")
    suspend fun updateProgress(id: Long, status: DownloadStatus, progress: Int, downloadedBytes: Long, speed: Long, eta: Long)

    @Query("UPDATE downloads SET status = :status, completedAt = :completedAt, filePath = :filePath, totalBytes = :totalBytes WHERE id = :id")
    suspend fun updateCompleted(id: Long, status: DownloadStatus, completedAt: Long, filePath: String, totalBytes: Long)

    @Query("UPDATE downloads SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateFailed(id: Long, status: DownloadStatus, errorMessage: String)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus)

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('DOWNLOADING', 'CONNECTING', 'PENDING', 'QUEUED')")
    fun getActiveCount(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'COMPLETED'")
    fun getCompletedCount(): LiveData<Int>

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted()

    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun getTotalCount(): Int
}
