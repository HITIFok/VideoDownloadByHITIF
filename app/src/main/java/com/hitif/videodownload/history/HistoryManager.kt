package com.hitif.videodownload.history

import androidx.lifecycle.LiveData
import androidx.room.*

@Entity(tableName = "history", indices = [Index(value = ["url"])])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val title: String?,
    val thumbnailUrl: String?,
    val source: String,
    val visitedAt: Long = System.currentTimeMillis()
)

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry)

    @Query("SELECT * FROM history ORDER BY visitedAt DESC")
    fun getAll(): LiveData<List<HistoryEntry>>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun deleteAll()

    @Query("SELECT * FROM history WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): HistoryEntry?

    @Query("SELECT * FROM history WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' ORDER BY visitedAt DESC")
    fun search(query: String): LiveData<List<HistoryEntry>>
}

class HistoryManager private constructor(private val context: android.content.Context) {

    private val dao: HistoryDao = com.hitif.videodownload.download.DownloadDatabase.getInstance(context).historyDao()

    suspend fun addEntry(url: String, title: String?, thumbnailUrl: String?, source: String) {
        dao.insert(HistoryEntry(url = url, title = title, thumbnailUrl = thumbnailUrl, source = source))
    }

    fun getAll(): LiveData<List<HistoryEntry>> = dao.getAll()

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun deleteAll() = dao.deleteAll()

    fun search(query: String): LiveData<List<HistoryEntry>> = dao.search(query)

    companion object {
        @Volatile
        private var INSTANCE: HistoryManager? = null

        fun getInstance(context: android.content.Context): HistoryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HistoryManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
