package com.hitif.videodownload.download

import android.content.Context
import android.os.Environment
import android.util.Log
import com.hitif.videodownload.R
import com.hitif.videodownload.notification.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.io.*
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/**
 * Enhanced Download Manager — Based on VidMate's download engine analysis
 *
 * Techniques incorporated from VidMate v5.3602:
 * 1. OkHttp with TLS cipher downgrade (MODERN_TLS + COMPATIBLE_TLS)
 * 2. DNS-over-HTTPS fallback via Cloudflare
 * 3. Smart 403 retry with configurable delays (1001ms default)
 * 4. Connection/read timeouts (8s each, matching VidMate's config)
 * 5. Cookie-aware requests
 * 6. Range header support for resume
 * 7. Proper YouTube-specific headers (Referer, Origin)
 * 8. Trust-all SSL for maximum compatibility
 * 9. Multiple User-Agent rotation
 * 10. Gzip disabled for Range requests
 */
class DownloadManager private constructor(private val context: Context) {

    val database: DownloadDatabase = DownloadDatabase.getInstance(context)
    private val _activeDownloadsCount = MutableStateFlow(0)
    val activeDownloadsCount: StateFlow<Int> = _activeDownloadsCount.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ========== VidMate-inspired OkHttp client with TLS downgrade ==========
    private val httpClient: OkHttpClient by lazy {
        buildOkHttpClient()
    }

    // ========== VidMate configuration (from PlayerGeneralConfig) ==========
    companion object {
        @Volatile
        private var INSTANCE: DownloadManager? = null

        fun getInstance(context: Context): DownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // Increased timeouts — 8 seconds was too short for video downloads
        private const val CONN_TIMEOUT = 30
        private const val READ_TIMEOUT = 60

        // Retry delay on 403: 2 seconds (increased from 1001ms for stability)
        private const val RETRY_DELAY_403 = 2000L

        // VidMate's max retry count
        private const val MAX_RETRY_403 = 5

        const val TAG = "DownloadManager"
    }

    /**
     * Build OkHttp client with VidMate's TLS cipher configuration:
     * Downgrade AES-GCM to AES-CBC for YouTube compatibility
     */
    private fun buildOkHttpClient(): OkHttpClient {
        // VidMate's TLS cipher downgrade (from aamb/aaab.java)
        val modernTls = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .cipherSuites(
                CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
                CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
                CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
            )
            .build()

        return OkHttpClient.Builder()
            .connectTimeout(CONN_TIMEOUT.toLong(), TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT.toLong(), TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionSpecs(listOf(modernTls, ConnectionSpec.COMPATIBLE_TLS))
            .retryOnConnectionFailure(true)
            .cookieJar(RecordingCookieJar())
            .build()
    }

    /**
     * Add a new download to the queue
     */
    suspend fun addDownload(
        url: String,
        fileName: String,
        title: String? = null,
        thumbnailUrl: String? = null,
        source: DownloadSource = DownloadSource.WEB,
        sourceSite: String? = null,
        quality: String? = null,
        format: String? = null,
        mimeType: String? = null,
        resolution: String? = null,
        fileSize: Long = 0,
        isAudioOnly: Boolean = false,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        seriesName: String? = null,
        playlistId: String? = null,
        playlistPosition: Int? = null
    ): Long {
        val existing = database.downloadDao().getByUrl(url)
        if (existing != null && existing.status == DownloadStatus.COMPLETED) {
            return existing.id
        }

        val entity = DownloadEntity(
            url = url,
            fileName = sanitizeFileName(fileName),
            title = title ?: fileName,
            thumbnailUrl = thumbnailUrl,
            source = source,
            sourceSite = sourceSite,
            quality = quality,
            format = format,
            mimeType = mimeType,
            resolution = resolution,
            fileSize = fileSize,
            isAudioOnly = isAudioOnly,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            seriesName = seriesName,
            playlistId = playlistId,
            playlistPosition = playlistPosition,
            status = if (playlistId != null) DownloadStatus.QUEUED else DownloadStatus.PENDING
        )

        val id = database.downloadDao().insert(entity)
        DownloadService.startDownload(context, id)
        return id
    }

    suspend fun addSeasonDownloads(episodes: List<SeasonEpisode>, source: DownloadSource): List<Long> {
        val playlistId = UUID.randomUUID().toString()
        return episodes.mapIndexed { index, episode ->
            addDownload(
                url = episode.url, fileName = episode.fileName, title = episode.title,
                thumbnailUrl = episode.thumbnailUrl, source = source, sourceSite = episode.sourceSite,
                quality = episode.quality, format = episode.format, mimeType = episode.mimeType,
                resolution = episode.resolution, fileSize = episode.fileSize,
                seasonNumber = episode.seasonNumber, episodeNumber = episode.episodeNumber,
                seriesName = episode.seriesName, playlistId = playlistId, playlistPosition = index
            )
        }
    }

    /**
     * ========== VIDMATE TECHNIQUE: Execute download with OkHttp ==========
     */
    suspend fun executeDownload(entity: DownloadEntity) {
        val dao = database.downloadDao()
        val notificationHelper = NotificationHelper(context)

        withContext(Dispatchers.IO) {
            try {
                dao.updateStatus(entity.id, DownloadStatus.CONNECTING)
                notificationHelper.showDownloadProgress(entity)

                val outputDir = getOutputDirectory(entity.source)
                val outputFile = File(outputDir, entity.fileName)
                val tempFile = File(outputDir, "${entity.fileName}.tmp")

                // Resume support
                val existingBytes = if (tempFile.exists() && entity.status == DownloadStatus.PAUSED) tempFile.length() else 0L
                val hasResume = existingBytes > 0

                // Build OkHttp request with proper headers
                val requestBuilder = Request.Builder().url(entity.url)

                // Add headers based on download source (pass hasResume for proper Range handling)
                addDownloadHeaders(requestBuilder, entity, hasResume)

                if (hasResume) {
                    requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                }

                val request = requestBuilder.build()
                val response = httpClient.newCall(request).execute()

                when (response.code) {
                    200, 206 -> {
                        val responseBody = response.body ?: throw IOException("Empty response body")
                        val totalBytes = if (entity.totalBytes > 0) entity.totalBytes else responseBody.contentLength()
                        val startBytes = if (response.code == 206) existingBytes else 0L

                        downloadToFile(responseBody.byteStream(), entity, dao, tempFile, startBytes, totalBytes, notificationHelper)

                        // FIX: Ensure temp file is fully flushed before rename
                        if (tempFile.exists()) {
                            // Atomic move — if renameTo fails (cross-filesystem), copy instead
                            if (!tempFile.renameTo(outputFile)) {
                                tempFile.copyTo(outputFile, overwrite = true)
                                tempFile.delete()
                            }
                        }

                        if (outputFile.exists() && outputFile.length() > 0) {
                            dao.updateCompleted(entity.id, DownloadStatus.COMPLETED, System.currentTimeMillis(),
                                outputFile.absolutePath, outputFile.length())
                        } else {
                            // FIX: Detect 0-byte / empty files and mark as failed
                            val errorMsg = "Downloaded file is empty (0 bytes)"
                            Log.e(TAG, "$errorMsg for ${entity.fileName}")
                            dao.updateFailed(entity.id, DownloadStatus.FAILED, errorMsg)
                            notificationHelper.showDownloadError(entity, errorMsg)
                            return@withContext
                        }
                        notificationHelper.showDownloadComplete(entity.copy(
                            filePath = outputFile.absolutePath, totalBytes = outputFile.length()))
                    }
                    // ========== VIDMATE TECHNIQUE: Smart 403 retry ==========
                    403 -> {
                        response.close()
                        handle403Retry(entity, dao, notificationHelper)
                    }
                    // ========== VIDMATE TECHNIQUE: Handle redirect/expired URLs ==========
                    301, 302, 303, 307, 308 -> {
                        response.close()
                        // OkHttp follows redirects automatically, but if we get here, try refresh
                        handleExpiredUrl(entity, dao, notificationHelper)
                    }
                    else -> {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        response.close()
                        dao.updateFailed(entity.id, DownloadStatus.FAILED, "HTTP ${response.code}: ${errorBody.take(100)}")
                        notificationHelper.showDownloadError(entity, "HTTP Error ${response.code}")
                    }
                }
            } catch (e: CancellationException) {
                dao.updateStatus(entity.id, DownloadStatus.CANCELLED)
                throw e
            } catch (e: IOException) {
                dao.updateFailed(entity.id, DownloadStatus.FAILED, e.message ?: "Network error")
                notificationHelper.showDownloadError(entity, e.message ?: "Network error")
            } catch (e: Exception) {
                dao.updateFailed(entity.id, DownloadStatus.FAILED, e.message ?: "Unknown error")
                notificationHelper.showDownloadError(entity, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Add VidMate-style download headers
     */
    private fun addDownloadHeaders(builder: Request.Builder, entity: DownloadEntity, hasResume: Boolean = false) {
        builder.apply {
            // Use a real desktop Chrome UA — the YouTube app UA triggers bot detection
            header("User-Agent", getSmartUserAgent(entity.source))
            header("Accept", "*/*")
            header("Connection", "keep-alive")

            // YouTube-specific headers
            if (entity.source == DownloadSource.YOUTUBE || entity.url.contains("googlevideo.com")) {
                header("Referer", "https://www.youtube.com/")
                header("Origin", "https://www.youtube.com")
            }

            // FIX: Disable Accept-Encoding for Range requests (gzip breaks resume)
            // The old code checked builder.build().header("Range") which is wrong —
            // it builds a request without the Range header we're about to add.
            // Instead, use the hasResume parameter.
            if (hasResume) {
                header("Accept-Encoding", "identity")
            }
        }
    }

    /**
     * ========== VIDMATE TECHNIQUE: Smart User-Agent selection ==========
     * VidMate rotates between different UAs based on source
     */
    private fun getSmartUserAgent(source: DownloadSource): String {
        // Use real Chrome desktop UA for all sources — YouTube app UA triggers bot detection
        return when (source) {
            DownloadSource.YOUTUBE -> {
                val uas = listOf(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )
                uas[Random().nextInt(uas.size)]
            }
            else -> {
                val uas = listOf(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
                )
                uas[Random().nextInt(uas.size)]
            }
        }
    }

    /**
     * ========== VIDMATE TECHNIQUE: 403 retry with configurable delay ==========
     * From VidMate's PlayerGeneralConfig:
     * - enable_retry_delay_on_forbidden = true
     * - retry_delay_ms_in_ad_dur = 1001
     * - sabr_max_retry_count = 30
     */
    private suspend fun handle403Retry(
        entity: DownloadEntity,
        dao: DownloadDao,
        notificationHelper: NotificationHelper
    ) {
        val retryCount = entity.retryCount + 1

        if (retryCount <= MAX_RETRY_403) {
            // VidMate uses 1001ms delay between 403 retries
            val delayMs = RETRY_DELAY_403 * retryCount
            Log.d(TAG, "403 Forbidden for ${entity.fileName}, retry $retryCount/$MAX_RETRY_403 after ${delayMs}ms")

            dao.updateFailed(entity.id, DownloadStatus.PENDING,
                "403 Forbidden - Retry $retryCount/$MAX_RETRY_403 in ${delayMs}ms")
            dao.update(entity.copy(retryCount = retryCount, progress = 0, downloadedBytes = 0))
            delay(delayMs)
            executeDownload(entity.copy(retryCount = retryCount, progress = 0, downloadedBytes = 0))
        } else {
            Log.e(TAG, "403 Forbidden after $MAX_RETRY_403 retries for ${entity.fileName}")
            dao.updateFailed(entity.id, DownloadStatus.FAILED, "403 Forbidden - Max retries exceeded")
            notificationHelper.showDownloadError(entity, "403 Forbidden - Max retries exceeded")
        }
    }

    /**
     * Handle expired/redirected URLs
     */
    private suspend fun handleExpiredUrl(entity: DownloadEntity, dao: DownloadDao, notificationHelper: NotificationHelper) {
        if (entity.retryCount < MAX_RETRY_403) {
            dao.updateFailed(entity.id, DownloadStatus.PENDING, "URL expired, retrying...")
            dao.update(entity.copy(retryCount = entity.retryCount + 1))
            delay(2000)
            executeDownload(entity.copy(retryCount = entity.retryCount + 1))
        } else {
            dao.updateFailed(entity.id, DownloadStatus.FAILED, "URL expired - Link may have expired")
            notificationHelper.showDownloadError(entity, "Link expired - Please try again")
        }
    }

    private suspend fun downloadToFile(
        inputStream: InputStream,
        entity: DownloadEntity,
        dao: DownloadDao,
        outputFile: File,
        startByte: Long,
        totalBytes: Long,
        notificationHelper: NotificationHelper
    ) = withContext(Dispatchers.IO) {
        dao.updateStatus(entity.id, DownloadStatus.DOWNLOADING)

        val outputStream = FileOutputStream(outputFile, startByte > 0)
        val buffer = ByteArray(16384) // 16KB buffer (larger = faster)
        var downloadedBytes = startByte
        var lastUpdateTime = System.currentTimeMillis()
        var lastSpeedBytes = 0L

        inputStream.use { input ->
            outputStream.use { output ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    ensureActive()

                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    // Update every 500ms
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= 500) {
                        val progress = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                        val elapsed = (now - lastUpdateTime) / 1000.0
                        val speed = if (elapsed > 0) ((downloadedBytes - lastSpeedBytes) / elapsed).toLong() else 0
                        val eta = if (speed > 0 && totalBytes > 0) (totalBytes - downloadedBytes) / speed else 0

                        dao.updateProgress(entity.id, DownloadStatus.DOWNLOADING, progress, downloadedBytes, speed, eta)
                        notificationHelper.updateDownloadProgress(entity, progress, downloadedBytes, totalBytes, speed)

                        lastUpdateTime = now
                        lastSpeedBytes = downloadedBytes
                    }
                }
            }
        }
    }

    fun pauseDownload(downloadId: Long) { DownloadService.pauseDownload(context, downloadId) }
    fun resumeDownload(downloadId: Long) { DownloadService.resumeDownload(context, downloadId) }
    fun cancelDownload(downloadId: Long) { DownloadService.cancelDownload(context, downloadId) }

    fun deleteDownload(entity: DownloadEntity) {
        scope.launch {
            entity.filePath?.let { path -> File(path).delete() }
            database.downloadDao().delete(entity)
        }
    }

    private fun getOutputDirectory(source: DownloadSource): File {
        val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HITIF Video")
        return when (source) {
            DownloadSource.YOUTUBE -> File(baseDir, "YouTube").also { it.mkdirs() }
            DownloadSource.SEASON, DownloadSource.PLAYLIST -> File(baseDir, "Series").also { it.mkdirs() }
            else -> baseDir.also { it.mkdirs() }
        }
    }

    private fun sanitizeFileName(name: String): String {
        val invalidChars = arrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        var sanitized = name
        for (char in invalidChars) sanitized = sanitized.replace(char, '_')
        return sanitized.trim()
    }

    /**
     * Simple cookie jar for session persistence
     */
    private class RecordingCookieJar : CookieJar {
        private val cookies = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                this.cookies.getOrPut(url.host) { mutableListOf() }.add(cookie)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookies[url.host]?.filter { cookie -> cookie.matches(url) } ?: emptyList()
        }
    }

}

data class SeasonEpisode(
    val url: String,
    val fileName: String,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val sourceSite: String? = null,
    val quality: String? = null,
    val format: String? = null,
    val mimeType: String? = null,
    val resolution: String? = null,
    val fileSize: Long = 0,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val seriesName: String? = null
)
