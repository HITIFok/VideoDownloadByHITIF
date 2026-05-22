package com.hitif.videodownload.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hitif.videodownload.download.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val downloadManager: DownloadManager = DownloadManager.getInstance(
        com.hitif.videodownload.HITIFApplication.instance
    )

    // All downloads
    val allDownloads: StateFlow<List<DownloadEntity>> = downloadManager.database.downloadDao()
        .getAllDownloads()
        .toStateFlow(viewModelScope, listOf())

    // Active downloads
    val activeDownloads: StateFlow<List<DownloadEntity>> = downloadManager.database.downloadDao()
        .getDownloadsByStatus(DownloadStatus.DOWNLOADING, DownloadStatus.CONNECTING, DownloadStatus.PENDING, DownloadStatus.QUEUED)
        .toStateFlow(viewModelScope, listOf())

    // Completed downloads
    val completedDownloads: StateFlow<List<DownloadEntity>> = downloadManager.database.downloadDao()
        .getDownloadsByStatus(DownloadStatus.COMPLETED)
        .toStateFlow(viewModelScope, listOf())

    // Failed downloads
    val failedDownloads: StateFlow<List<DownloadEntity>> = downloadManager.database.downloadDao()
        .getDownloadsByStatus(DownloadStatus.FAILED, DownloadStatus.CANCELLED)
        .toStateFlow(viewModelScope, listOf())

    // Active count
    val activeCount: StateFlow<Int> = downloadManager.database.downloadDao()
        .getActiveCount()
        .toStateFlow(viewModelScope, 0)

    // Completed count
    val completedCount: StateFlow<Int> = downloadManager.database.downloadDao()
        .getCompletedCount()
        .toStateFlow(viewModelScope, 0)

    fun pauseDownload(downloadId: Long) {
        downloadManager.pauseDownload(downloadId)
    }

    fun resumeDownload(downloadId: Long) {
        downloadManager.resumeDownload(downloadId)
    }

    fun cancelDownload(downloadId: Long) {
        downloadManager.cancelDownload(downloadId)
    }

    fun retryDownload(entity: DownloadEntity) {
        viewModelScope.launch {
            downloadManager.database.downloadDao().updateStatus(entity.id, DownloadStatus.PENDING)
            downloadManager.database.downloadDao().update(
                entity.copy(
                    status = DownloadStatus.PENDING,
                    progress = 0,
                    downloadedBytes = 0,
                    errorMessage = null,
                    retryCount = 0
                )
            )
            DownloadService.startDownload(
                com.hitif.videodownload.HITIFApplication.instance,
                entity.id
            )
        }
    }

    fun deleteDownload(entity: DownloadEntity) {
        downloadManager.deleteDownload(entity)
    }

    fun deleteCompleted() {
        viewModelScope.launch {
            downloadManager.database.downloadDao().deleteCompleted()
        }
    }
}

data class DownloadsTab(
    val title: String,
    val fragment: androidx.fragment.app.Fragment
)

// Extension function to convert LiveData to StateFlow
fun <T> androidx.lifecycle.LiveData<T>.toStateFlow(
    scope: kotlinx.coroutines.CoroutineScope,
    initial: T
): kotlinx.coroutines.flow.StateFlow<T> {
    return kotlinx.coroutines.flow.MutableStateFlow(initial).also { stateFlow ->
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            observeForever { value ->
                stateFlow.value = value
            }
        }
    }.asStateFlow()
}
