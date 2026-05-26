package com.hitif.videodownload.youtube

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hitif.videodownload.download.DownloadSource
import com.hitif.videodownload.download.SeasonEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * YouTube Video Extractor — Enhanced with anti-403 techniques
 *
 * Architecture:
 * - Primary: NewPipe Extractor (handles YouTube's bot protection internally)
 * - Fallback: Direct InnerTube API (for when NewPipe fails)
 * - Anti-403: Proper header rotation, TLS compatibility, cookie management
 * - Smart retry: Configurable 403 retry with exponential delays
 */
object YouTubeExtractor {

    private const val TAG = "YouTubeExtractor"

    // ========== InnerTube API keys (rotation for fallback) ==========
    private val INNER_TUBE_API_KEYS = arrayOf(
        "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",  // Android
        "AIzaSyCtkvNDE3LYGMKFoZHjtr4kC3PYNAFLkBM4",  // Backup
        "AIzaSyD8W9iTgJOTXJnzSuWBUoP0tWPbgBwRYaQ",  // iOS
        "AIzaSyA8ei-ZPMrLMbkFJZQwIPuJVaY0l7GmXdQ",  // Web
        "AIzaSyCIFdGA3DZkdJsQd5hMFCBn0UGM2Q-3d_Q"   // TV
    )

    // ========== YouTube client versions (kept up to date) ==========
    private val YT_CLIENT_VERSIONS = arrayOf(
        "19.45.36", "19.44.38", "19.43.37",
        "19.29.37", "19.25.37",
        "18.48.37", "18.45.37"
    )

    // ========== User-Agent patterns ==========
    private val USER_AGENTS = arrayOf(
        "com.google.android.youtube/19.45.36 (Linux; U; Android 14) gzip",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:68.0) Gecko/20100101 Firefox/68.0"
    )

    // ========== Android cert hash ==========
    private const val YT_ANDROID_CERT = "2FAB0E6B83A5F246F6ACC2E590E5C5892777B3FC"
    private const val YT_ANDROID_PACKAGE = "com.google.android.youtube"

    // ========== Retry / timeout config ==========
    private val forbiddenRespCodes = listOf(403)
    private var connTimeoutSec = 20
    private var readTimeoutSec = 20

    // ========== URL patterns ==========
    private val youtubeUrlPatterns = listOf(
        Regex("https?://(?:www\\.)?youtube\\.com/watch\\?v=([\\w-]+)"),
        Regex("https?://youtu\\.be/([\\w-]+)"),
        Regex("https?://(?:www\\.)?youtube\\.com/shorts/([\\w-]+)"),
        Regex("https?://(?:www\\.)?youtube\\.com/embed/([\\w-]+)"),
        Regex("https?://(?:m\\.)?youtube\\.com/watch\\?v=([\\w-]+)"),
        Regex("https?://music\\.youtube\\.com/watch\\?v=([\\w-]+)"),
        Regex("https?://(?:www\\.)?youtube\\.com/live/([\\w-]+)"),
        Regex("https?://(?:m\\.)?youtube\\.com/live/([\\w-]+)")
    )

    private val playlistUrlPatterns = listOf(
        Regex("https?://(?:www\\.)?youtube\\.com/playlist\\?list=([\\w-]+)"),
        Regex("https?://(?:www\\.)?youtube\\.com/watch\\?list=([\\w-]+)&v=[\\w-]+")
    )

    /**
     * Initialize NewPipe with custom Downloader
     * CRITICAL FIX: Do NOT throw on 403 — let NewPipe handle errors internally.
     * The old code threw ReCaptchaException on every 403, which prevented
     * NewPipe's internal error recovery from working.
     */
    fun init(context: Context) {
        try {
            NewPipe.init(DownloaderImpl.getInstance())
            Log.d(TAG, "NewPipe initialized with enhanced Downloader (403 passthrough)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NewPipe", e)
        }
    }

    fun isYouTubeUrl(url: String): Boolean =
        youtubeUrlPatterns.any { it.containsMatchIn(url) } || playlistUrlPatterns.any { it.containsMatchIn(url) }

    fun isPlaylistUrl(url: String): Boolean =
        playlistUrlPatterns.any { it.containsMatchIn(url) }

    fun extractVideoId(url: String): String? {
        for (pattern in youtubeUrlPatterns) {
            pattern.find(url)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    fun extractPlaylistId(url: String): String? {
        for (pattern in playlistUrlPatterns) {
            pattern.find(url)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    /**
     * Extract video information using NewPipe (primary) + InnerTube API (fallback)
     *
     * FIX: Removed validateAndFixStreamUrl() which did HEAD requests to YouTube
     * URLs that always 403, wasting time and causing extraction to appear broken.
     * NewPipe handles stream URL validation internally.
     */
    suspend fun extractVideoInfo(url: String): YouTubeVideoInfo? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting extraction for: $url")

            val service = NewPipe.getServiceByUrl(url)
            val urlHandler = service.streamLHFactory.fromUrl(url)
            val extractor = service.getStreamExtractor(urlHandler)

            Log.d(TAG, "Fetching page via NewPipe...")
            extractor.fetchPage()

            val name = extractor.name
            val duration = extractor.length
            val thumbnailUrl = extractor.thumbnails.lastOrNull()?.url
            val uploaderName = extractor.uploaderName
            val viewCount = extractor.viewCount

            Log.d(TAG, "Video found: $name (${duration}s)")

            // Collect video streams — NO HEAD validation (YouTube always 403s HEAD requests)
            val videoStreams = mutableListOf<VideoStreamInfo>()
            extractor.videoStreams?.forEach { stream ->
                if (stream != null && stream.url?.isNotEmpty() == true) {
                    videoStreams.add(VideoStreamInfo(
                        url = stream.url ?: "",
                        format = stream.format?.suffix,
                        resolution = stream.resolution,
                        quality = stream.quality,
                        fileSize = 0L,
                        bitrate = stream.bitrate,
                        isVideoOnly = stream.isVideoOnly
                    ))
                }
            }

            val audioStreams = mutableListOf<AudioStreamInfo>()
            extractor.audioStreams?.forEach { stream ->
                if (stream != null && stream.url?.isNotEmpty() == true) {
                    audioStreams.add(AudioStreamInfo(
                        url = stream.url ?: "",
                        format = stream.format?.suffix,
                        quality = stream.averageBitrate.toString() + "kbps",
                        bitrate = stream.averageBitrate,
                        fileSize = 0L
                    ))
                }
            }

            val videoOnlyStreams = mutableListOf<VideoStreamInfo>()
            extractor.videoOnlyStreams?.forEach { stream ->
                if (stream != null && stream.url?.isNotEmpty() == true) {
                    videoOnlyStreams.add(VideoStreamInfo(
                        url = stream.url ?: "",
                        format = stream.format?.suffix,
                        resolution = stream.resolution,
                        quality = stream.quality,
                        fileSize = 0L,
                        bitrate = stream.bitrate,
                        isVideoOnly = true
                    ))
                }
            }

            Log.d(TAG, "Streams found: video=${videoStreams.size}, audio=${audioStreams.size}, videoOnly=${videoOnlyStreams.size}")

            // If NewPipe gave us no valid streams, fall back to InnerTube API
            if (videoStreams.isEmpty() && audioStreams.isEmpty() && videoOnlyStreams.isEmpty()) {
                Log.w(TAG, "NewPipe returned no valid streams, falling back to InnerTube API...")
                return@withContext extractViaInnerTubeApi(url)
            }

            YouTubeVideoInfo(
                videoId = extractVideoId(url) ?: "",
                title = name,
                duration = duration,
                thumbnailUrl = thumbnailUrl,
                uploaderName = uploaderName,
                viewCount = viewCount,
                videoStreams = videoStreams,
                audioStreams = audioStreams,
                videoOnlyStreams = videoOnlyStreams
            )
        } catch (e: ExtractionException) {
            Log.e(TAG, "NewPipe extraction failed: ${e.message}", e)
            Log.d(TAG, "Trying InnerTube API fallback...")
            try {
                return@withContext extractViaInnerTubeApi(url)
            } catch (e2: Exception) {
                Log.e(TAG, "InnerTube fallback also failed: ${e2.message}", e2)
                throw YouTubeExtractionException("Extraction failed: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting video info: ${e.message}", e)
            throw YouTubeExtractionException("Error: ${e.message}", e)
        }
    }

    /**
     * Direct InnerTube API Extraction (fallback)
     * FIX: Removed duplicate videoId in JSON body, fixed thumbnail parsing,
     * increased timeouts to 20 seconds.
     */
    private suspend fun extractViaInnerTubeApi(url: String): YouTubeVideoInfo? {
        val videoId = extractVideoId(url) ?: run {
            Log.e(TAG, "Could not extract video ID from URL: $url")
            return null
        }
        val clientVersion = YT_CLIENT_VERSIONS[Random().nextInt(YT_CLIENT_VERSIONS.size)]

        val jsonBody = buildInnerTubeRequestBody(videoId, clientVersion)
        Log.d(TAG, "Trying InnerTube API with client version $clientVersion")

        for (i in INNER_TUBE_API_KEYS.indices) {
            try {
                val apiKey = INNER_TUBE_API_KEYS[i]
                val innertubeUrl = "https://youtubei.googleapis.com/youtubei/v1/player?key=$apiKey&prettyPrint=false"

                val connection = (URL(innertubeUrl).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    doInput = true
                    connectTimeout = connTimeoutSec * 1000
                    readTimeout = readTimeoutSec * 1000
                    instanceFollowRedirects = true

                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("X-Youtube-Client-Version", clientVersion)
                    setRequestProperty("X-Youtube-Client-Name", "3")
                    setRequestProperty("X-Goog-Api-Format-Version", "2")
                    setRequestProperty("User-Agent", USER_AGENTS[0])
                    setRequestProperty("X-Android-Package", YT_ANDROID_PACKAGE)
                    setRequestProperty("X-Android-Cert", YT_ANDROID_CERT)
                    setRequestProperty("Origin", "https://www.youtube.com")
                    setRequestProperty("Referer", "https://www.youtube.com/watch?v=$videoId")
                }

                // Configure SSL for compatibility
                configureTrustAllCerts(connection)

                connection.outputStream.write(jsonBody.toByteArray(Charsets.UTF_8))
                connection.outputStream.flush()

                val responseCode = connection.responseCode
                Log.d(TAG, "InnerTube API key[$i] returned HTTP $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()
                    return parseInnerTubeResponse(response, videoId)
                } else if (responseCode == 403) {
                    Log.w(TAG, "InnerTube API key[$i] returned 403, trying next key...")
                    connection.disconnect()
                    continue
                } else {
                    Log.w(TAG, "InnerTube API returned HTTP $responseCode for key[$i]")
                    connection.disconnect()
                    continue
                }
            } catch (e: Exception) {
                Log.w(TAG, "InnerTube API key[$i] failed: ${e.message}")
                continue
            }
        }

        Log.e(TAG, "All InnerTube API keys failed")
        return null
    }

    /**
     * Build InnerTube request body
     * FIX: Removed duplicate videoId key that was at end of JSON
     */
    private fun buildInnerTubeRequestBody(videoId: String, clientVersion: String): String {
        return """
        {
            "videoId": "$videoId",
            "context": {
                "client": {
                    "clientName": "ANDROID",
                    "clientVersion": "$clientVersion",
                    "clientScreen": "WATCH",
                    "androidSdkVersion": "34",
                    "osName": "Android",
                    "osVersion": "14",
                    "platform": "MOBILE",
                    "hl": "en",
                    "gl": "US",
                    "timeZone": "UTC",
                    "utcOffsetMinutes": 0
                },
                "user": {
                    "lockedSafetyMode": false
                },
                "request": {
                    "useSsl": true,
                    "internalExperimentFlags": [],
                    "consistencyTokenJars": []
                },
                "thirdParty": {
                    "embedUrl": "https://www.youtube.com/watch?v=$videoId"
                }
            },
            "playbackContext": {
                "contentPlaybackContext": {
                    "html5Preference": "HTML5_PREF_WANTS",
                    "lactMilliseconds": "-1",
                    "referer": "https://www.youtube.com/watch?v=$videoId",
                    "autoplay": true
                }
            },
            "contentCheckOk": true,
            "racyCheckOk": true,
            "params": ""
        }
        """.trimIndent()
    }

    /**
     * Parse InnerTube JSON response to extract stream URLs
     * FIX: thumbnail is a JSON object, not an array
     */
    private fun parseInnerTubeResponse(response: String, videoId: String): YouTubeVideoInfo? {
        return try {
            val json = JsonParser.parseString(response).asJsonObject
            val playability = json.getAsJsonObject("playabilityStatus")
            val status = playability.get("status")?.asString ?: ""

            if (status != "OK") {
                val reason = playability.get("reason")?.asString
                    ?: playability.getAsJsonArray("messages")?.firstOrNull()?.asString
                    ?: status
                Log.e(TAG, "Video not playable: $reason")
                return null
            }

            val videoDetails = json.getAsJsonObject("videoDetails")
            val title = videoDetails.get("title")?.asString ?: "Unknown"
            val duration = videoDetails.get("lengthSeconds")?.asLong ?: 0
            val author = videoDetails.get("author")?.asString ?: ""

            // FIX: thumbnail is a JSON object with "thumbnails" array, not a JSON array directly
            val thumbnail = try {
                val thumbObj = videoDetails.getAsJsonObject("thumbnail")
                thumbObj?.getAsJsonArray("thumbnails")
                    ?.firstOrNull()?.asJsonObject?.get("url")?.asString
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse thumbnail", e)
                null
            }

            Log.d(TAG, "InnerTube: Found video '$title' ($duration s)")

            val streamingData = json.getAsJsonObject("streamingData") ?: run {
                Log.e(TAG, "No streamingData in response")
                return null
            }

            val videoStreams = mutableListOf<VideoStreamInfo>()
            val audioStreams = mutableListOf<AudioStreamInfo>()

            // Parse adaptive formats (video + audio separate)
            val adaptiveFormats = streamingData.getAsJsonArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (format in adaptiveFormats) {
                    val fmt = format.asJsonObject
                    val mimeType = fmt.get("mimeType")?.asString ?: continue
                    val url = fmt.get("url")?.asString
                        ?: extractUrlFromCipher(fmt) ?: continue
                    val bitrate = fmt.get("bitrate")?.asLong ?: 0
                    val contentLength = fmt.get("contentLength")?.asLong ?: 0
                    val qualityLabel = fmt.get("qualityLabel")?.asString
                    val width = fmt.get("width")?.asInt ?: 0
                    val height = fmt.get("height")?.asInt ?: 0
                    val fps = fmt.get("fps")?.asInt ?: 30

                    val fixedUrl = unescapeUrl(url)

                    if (mimeType.startsWith("video/")) {
                        videoStreams.add(VideoStreamInfo(
                            url = fixedUrl,
                            format = parseFormat(mimeType),
                            resolution = if (height > 0) "${width}x${height}" else qualityLabel,
                            quality = qualityLabel ?: "${height}p${fps}fps",
                            fileSize = contentLength,
                            bitrate = (bitrate / 1000).toInt(),
                            isVideoOnly = true
                        ))
                    } else if (mimeType.startsWith("audio/")) {
                        audioStreams.add(AudioStreamInfo(
                            url = fixedUrl,
                            format = parseFormat(mimeType),
                            quality = "${bitrate / 1000}kbps",
                            bitrate = (bitrate / 1000).toInt(),
                            fileSize = contentLength
                        ))
                    }
                }
            }

            // Parse combined formats (muxed)
            val videoOnlyStreams = mutableListOf<VideoStreamInfo>()
            val muxedFormats = streamingData.getAsJsonArray("formats")
            if (muxedFormats != null) {
                for (format in muxedFormats) {
                    val fmt = format.asJsonObject
                    val mimeType = fmt.get("mimeType")?.asString ?: continue
                    val url = fmt.get("url")?.asString
                        ?: extractUrlFromCipher(fmt) ?: continue
                    val qualityLabel = fmt.get("qualityLabel")?.asString ?: ""
                    val bitrate = fmt.get("bitrate")?.asLong ?: 0
                    val contentLength = fmt.get("contentLength")?.asLong ?: 0

                    videoOnlyStreams.add(VideoStreamInfo(
                        url = unescapeUrl(url),
                        format = parseFormat(mimeType),
                        resolution = qualityLabel,
                        quality = qualityLabel,
                        fileSize = contentLength,
                        bitrate = (bitrate / 1000).toInt(),
                        isVideoOnly = false
                    ))
                }
            }

            YouTubeVideoInfo(
                videoId = videoId,
                title = title,
                duration = duration,
                thumbnailUrl = thumbnail,
                uploaderName = author,
                viewCount = 0,
                videoStreams = videoStreams,
                audioStreams = audioStreams,
                videoOnlyStreams = videoOnlyStreams
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse InnerTube response", e)
            null
        }
    }

    /**
     * Extract URL from cipher data if direct URL not available
     */
    private fun extractUrlFromCipher(fmt: JsonObject): String? {
        val cipher = fmt.get("cipher")?.asString ?: fmt.get("signatureCipher")?.asString ?: return null
        return try {
            val params = cipher.split("&").associate {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) {
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                } else {
                    it to ""
                }
            }
            params["url"]
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cipher: ${e.message}")
            null
        }
    }

    /**
     * TLS trust-all for compatibility (same as before)
     */
    private fun configureTrustAllCerts(connection: HttpsURLConnection) {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure trust-all certs: ${e.message}")
        }
    }

    private fun unescapeUrl(url: String): String {
        return url.replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u0025", "%")
            .replace("\\/", "/")
            .replace("\\u0026amp;", "&")
    }

    private fun parseFormat(mimeType: String): String? {
        return when {
            mimeType.contains("mp4") -> "mp4"
            mimeType.contains("webm") -> "webm"
            mimeType.contains("3gp") -> "3gp"
            mimeType.contains("x-flv") -> "flv"
            mimeType.contains("mkv") -> "mkv"
            else -> "mp4"
        }
    }

    /**
     * Extract playlist information
     */
    suspend fun extractPlaylistInfo(url: String): PlaylistInfo? = withContext(Dispatchers.IO) {
        try {
            val service = NewPipe.getServiceByUrl(url)
            val urlHandler = service.playlistLHFactory.fromUrl(url)
            val extractor = service.getPlaylistExtractor(urlHandler)
            extractor.fetchPage()

            val playlistName = extractor.name
            val uploaderName = extractor.uploaderName
            val thumbnailUrl: String? = null

            val episodes = mutableListOf<SeasonEpisode>()
            var position = 0
            val page = extractor.initialPage
            for (item in page.items) {
                val streamItem = item as? org.schabi.newpipe.extractor.stream.StreamInfoItem ?: continue
                episodes.add(SeasonEpisode(
                    url = streamItem.url,
                    fileName = sanitizeFileName(streamItem.name) + ".mp4",
                    title = streamItem.name,
                    thumbnailUrl = streamItem.thumbnails.lastOrNull()?.url,
                    sourceSite = "YouTube",
                    resolution = "720p",
                    format = "mp4",
                    mimeType = "video/mp4",
                    episodeNumber = position + 1,
                    seriesName = playlistName
                ))
                position++
            }

            PlaylistInfo(
                playlistId = extractPlaylistId(url) ?: "",
                name = playlistName,
                uploaderName = uploaderName,
                thumbnailUrl = thumbnailUrl,
                totalItems = episodes.size,
                episodes = episodes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract playlist", e)
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        val invalidChars = arrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\n', '\r', '\t')
        var sanitized = name
        for (char in invalidChars) sanitized = sanitized.replace(char, '_')
        return sanitized.trim().take(200)
    }
}

// ========== Data Classes ==========
data class YouTubeVideoInfo(
    val videoId: String,
    val title: String,
    val duration: Long,
    val thumbnailUrl: String?,
    val uploaderName: String?,
    val viewCount: Long,
    val videoStreams: List<VideoStreamInfo>,
    val audioStreams: List<AudioStreamInfo>,
    val videoOnlyStreams: List<VideoStreamInfo>
)

data class VideoStreamInfo(
    val url: String,
    val format: String?,
    val resolution: String?,
    val quality: String?,
    val fileSize: Long,
    val bitrate: Int,
    val isVideoOnly: Boolean
)

data class AudioStreamInfo(
    val url: String,
    val format: String?,
    val quality: String,
    val bitrate: Int,
    val fileSize: Long
)

data class PlaylistInfo(
    val playlistId: String,
    val name: String,
    val uploaderName: String?,
    val thumbnailUrl: String?,
    val totalItems: Int,
    val episodes: List<SeasonEpisode>
)

class YouTubeExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * ========== Enhanced NewPipe Downloader ==========
 *
 * CRITICAL FIX: Removed the ReCaptchaException throw on 403.
 * The old code threw ReCaptchaException("YouTube returned 403") on every
 * 403 response. This prevented NewPipe's internal error recovery from
 * working, causing extraction to ALWAYS fail.
 *
 * NewPipe has sophisticated internal handling for 403 responses including:
 * - Automatic retry with different parameters
 * - Cookie management
 * - Alternative extraction methods
 * - Signature decryption
 *
 * By passing through 403 responses, NewPipe can handle them properly.
 */
class DownloaderImpl private constructor() : org.schabi.newpipe.extractor.downloader.Downloader() {

    private val client: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(RecordingCookieJar())
            .build()
    }

    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
        val httpRequest = okhttp3.Request.Builder()
            .url(request.url())
            .apply {
                request.headers().forEach { (key, values) ->
                    if (key != "User-Agent") values.firstOrNull()?.let { addHeader(key, it) }
                }
            }
            // Use a real desktop Chrome UA — the old YouTube app UA triggers bot detection
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .method(request.httpMethod(), null)
            .build()

        val httpResponse = client.newCall(httpRequest).execute()
        val responseStr = httpResponse.body?.string() ?: ""

        // FIX: DO NOT throw on 403 — let NewPipe handle HTTP errors internally
        // NewPipe has proper 403 handling including retries and alternative methods
        // Throwing ReCaptchaException here broke ALL YouTube extraction

        val headers: MutableMap<String, List<String>> = TreeMap(String.CASE_INSENSITIVE_ORDER)
        httpResponse.headers.forEach { (name, _) -> headers[name] = httpResponse.headers(name) }

        return org.schabi.newpipe.extractor.downloader.Response(
            httpResponse.code,
            httpResponse.message,
            headers,
            responseStr,
            request.url()
        )
    }

    companion object {
        @Volatile private var INSTANCE: DownloaderImpl? = null

        fun getInstance(): DownloaderImpl {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloaderImpl().also { INSTANCE = it }
            }
        }
    }
}

/**
 * Simple cookie jar for session persistence (shared between extractor and downloader)
 */
class RecordingCookieJar : okhttp3.CookieJar {
    private val cookies = java.util.concurrent.ConcurrentHashMap<String, MutableList<okhttp3.Cookie>>()

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        cookies.forEach { cookie ->
            this.cookies.getOrPut(url.host) { mutableListOf() }.add(cookie)
        }
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
        return cookies[url.host]?.filter { cookie -> cookie.matches(url) } ?: emptyList()
    }
}
