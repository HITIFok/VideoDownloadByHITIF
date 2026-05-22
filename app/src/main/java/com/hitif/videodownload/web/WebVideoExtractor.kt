package com.hitif.videodownload.web

import android.util.Log
import com.hitif.videodownload.download.DownloadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URL

object WebVideoExtractor {

    private const val TAG = "WebVideoExtractor"

    private val supportedSites = mapOf(
        "facebook.com" to SiteConfig("Facebook", "facebook.com", "www.facebook.com"),
        "fb.watch" to SiteConfig("Facebook", "fb.watch", "www.facebook.com"),
        "instagram.com" to SiteConfig("Instagram", "instagram.com", "www.instagram.com"),
        "vimeo.com" to SiteConfig("Vimeo", "vimeo.com", "player.vimeo.com"),
        "dailymotion.com" to SiteConfig("Dailymotion", "dailymotion.com", "www.dailymotion.com"),
        "dai.ly" to SiteConfig("Dailymotion", "dai.ly", "www.dailymotion.com"),
        "twitter.com" to SiteConfig("X (Twitter)", "twitter.com", "x.com"),
        "x.com" to SiteConfig("X (Twitter)", "x.com", "x.com"),
        "twitch.tv" to SiteConfig("Twitch", "twitch.tv", "www.twitch.tv"),
        "tiktok.com" to SiteConfig("TikTok", "tiktok.com", "www.tiktok.com"),
        "vm.tiktok.com" to SiteConfig("TikTok", "vm.tiktok.com", "www.tiktok.com"),
        "pinterest.com" to SiteConfig("Pinterest", "pinterest.com", "www.pinterest.com"),
        "reddit.com" to SiteConfig("Reddit", "reddit.com", "www.reddit.com"),
        "tumblr.com" to SiteConfig("Tumblr", "tumblr.com", "www.tumblr.com"),
        "snapchat.com" to SiteConfig("Snapchat", "snapchat.com", "story.snapchat.com"),
        "streamable.com" to SiteConfig("Streamable", "streamable.com", "streamable.com"),
        "ok.ru" to SiteConfig("OK.ru", "ok.ru", "ok.ru"),
        "liveleak.com" to SiteConfig("LiveLeak", "liveleak.com", "www.liveleak.com"),
        "rumble.com" to SiteConfig("Rumble", "rumble.com", "rumble.com")
    )

    data class SiteConfig(
        val name: String,
        val domain: String,
        val refererDomain: String
    )

    data class ExtractedVideo(
        val url: String,
        val title: String?,
        val thumbnailUrl: String?,
        val quality: String?,
        val duration: Long,
        val format: String,
        val mimeType: String,
        val fileSize: Long,
        val sourceSite: String,
        val episodeNumber: Int? = null,
        val seasonNumber: Int? = null,
        val seriesName: String? = null
    )

    fun getSupportedSites(): List<SiteConfig> = supportedSites.values.toList()

    fun detectSite(url: String): SiteConfig? {
        try {
            val host = URL(url).host.lowercase()
            // Check for subdomain matches
            for ((key, config) in supportedSites) {
                if (host.contains(key)) return config
            }
        } catch (e: Exception) {
            // Invalid URL
        }
        return null
    }

    fun isSupportedUrl(url: String): Boolean {
        return detectSite(url) != null || isDirectMediaUrl(url)
    }

    fun isDirectMediaUrl(url: String): Boolean {
        val directMediaExtensions = listOf(
            ".mp4", ".m4v", ".webm", ".mkv", ".avi", ".mov", ".flv", ".wmv",
            ".3gp", ".ts", ".m3u8", ".mpd",
            ".mp3", ".m4a", ".aac", ".ogg", ".wma", ".flac", ".wav",
            ".jpg", ".jpeg", ".png", ".gif", ".webp"
        )
        val lowerUrl = url.lowercase()
        return directMediaExtensions.any { lowerUrl.contains(it) }
    }

    /**
     * Extract video URL from a web page
     */
    suspend fun extractVideo(url: String): ExtractedVideo? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val site = detectSite(url)
            val userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

            when {
                isDirectMediaUrl(url) -> {
                    ExtractedVideo(
                        url = url,
                        title = getFileNameFromUrl(url),
                        thumbnailUrl = null,
                        quality = null,
                        duration = 0,
                        format = getExtension(url),
                        mimeType = getMimeType(url),
                        fileSize = getContentLength(url, userAgent),
                        sourceSite = "Direct"
                    )
                }
                site != null -> extractFromSite(url, site, userAgent)
                else -> extractGeneric(url, userAgent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract video from $url", e)
            null
        }
    }

    private suspend fun extractFromSite(url: String, site: SiteConfig, userAgent: String): ExtractedVideo? {
        return when (site.name) {
            "Facebook" -> extractFacebook(url, userAgent)
            "Instagram" -> extractInstagram(url, userAgent)
            "Vimeo" -> extractVimeo(url, userAgent)
            "Dailymotion" -> extractDailymotion(url, userAgent)
            "X (Twitter)" -> extractTwitter(url, userAgent)
            "TikTok" -> extractTikTok(url, userAgent)
            else -> extractGeneric(url, userAgent)
        }
    }

    private fun extractFacebook(url: String, userAgent: String): ExtractedVideo? {
        try {
            val doc = Jsoup.connect(url)
                .userAgent(userAgent)
                .followRedirects(true)
                .timeout(15000)
                .get()

            // Try og:video meta tag
            val ogVideo = doc.selectFirst("meta[property=og:video:secure_url]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:video:url]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:video]")?.attr("content")

            if (ogVideo != null) {
                val title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "Facebook Video"
                val thumbnail = doc.selectFirst("meta[property=og:image]")?.attr("content")

                return ExtractedVideo(
                    url = ogVideo,
                    title = title,
                    thumbnailUrl = thumbnail,
                    quality = "HD",
                    duration = 0,
                    format = "mp4",
                    mimeType = "video/mp4",
                    fileSize = 0,
                    sourceSite = "Facebook"
                )
            }

            // Try to find HD quality SD URL
            val sdUrl = doc.selectFirst("meta[property=og:video:url]")?.attr("content")
            if (sdUrl != null) {
                return ExtractedVideo(
                    url = sdUrl,
                    title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "Facebook Video",
                    thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content"),
                    quality = "SD",
                    duration = 0,
                    format = "mp4",
                    mimeType = "video/mp4",
                    fileSize = 0,
                    sourceSite = "Facebook"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Facebook extraction failed", e)
        }
        return null
    }

    private fun extractInstagram(url: String, userAgent: String): ExtractedVideo? {
        try {
            val doc = Jsoup.connect(url)
                .userAgent(userAgent)
                .followRedirects(true)
                .timeout(15000)
                .get()

            // Try og:video
            val ogVideo = doc.selectFirst("meta[property=og:video]")?.attr("content")
            if (ogVideo != null) {
                return ExtractedVideo(
                    url = ogVideo,
                    title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "Instagram Video",
                    thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content"),
                    quality = "HD",
                    duration = 0,
                    format = "mp4",
                    mimeType = "video/mp4",
                    fileSize = 0,
                    sourceSite = "Instagram"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Instagram extraction failed", e)
        }
        return null
    }

    private fun extractVimeo(url: String, userAgent: String): ExtractedVideo? {
        try {
            // Use Vimeo's oEmbed API
            val apiUrl = "https://vimeo.com/api/oembed.json?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                // Try to get the video URL from the JSON response
                val titlePattern = """"title"\s*:\s*"([^"]*)"""".toRegex()
                val thumbnailPattern = """"thumbnail_url"\s*:\s*"([^"]*)"""".toRegex()
                val title = titlePattern.find(response)?.groupValues?.get(1)
                val thumbnail = thumbnailPattern.find(response)?.groupValues?.get(1)

                // The actual video URL is typically in the page source
                val doc = Jsoup.connect(url).userAgent(userAgent).timeout(15000).get()
                val scriptTags = doc.select("script")

                for (script in scriptTags) {
                    val scriptContent = script.html()
                    if (scriptContent.contains("\"progressive\"")) {
                        // Extract progressive (MP4) URL
                        val urlPattern = """"url"\s*:\s*"([^"]*\.mp4[^"]*)"""".toRegex()
                        val qualityPattern = """"quality"\s*:\s*"([^"]*)"""".toRegex()
                        val match = urlPattern.find(scriptContent)
                        if (match != null) {
                            return ExtractedVideo(
                                url = match.groupValues[1].replace("\\/", "/"),
                                title = title,
                                thumbnailUrl = thumbnail,
                                quality = qualityPattern.find(scriptContent)?.groupValues?.get(1) ?: "HD",
                                duration = 0,
                                format = "mp4",
                                mimeType = "video/mp4",
                                fileSize = 0,
                                sourceSite = "Vimeo"
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vimeo extraction failed", e)
        }
        return null
    }

    private fun extractDailymotion(url: String, userAgent: String): ExtractedVideo? {
        try {
            val doc = Jsoup.connect(url)
                .userAgent(userAgent)
                .followRedirects(true)
                .timeout(15000)
                .get()

            // Check for og:video
            val ogVideo = doc.selectFirst("meta[property=og:video:url]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:video:secure_url]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:video]")?.attr("content")

            if (ogVideo != null) {
                return ExtractedVideo(
                    url = ogVideo,
                    title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "Dailymotion Video",
                    thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content"),
                    quality = "HD",
                    duration = 0,
                    format = "mp4",
                    mimeType = "video/mp4",
                    fileSize = 0,
                    sourceSite = "Dailymotion"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dailymotion extraction failed", e)
        }
        return null
    }

    private fun extractTwitter(url: String, userAgent: String): ExtractedVideo? {
        try {
            val doc = Jsoup.connect(url)
                .userAgent(userAgent)
                .followRedirects(true)
                .timeout(15000)
                .get()

            // Check og:video
            val ogVideo = doc.selectFirst("meta[property=og:video:url]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:video]")?.attr("content")

            if (ogVideo != null) {
                return ExtractedVideo(
                    url = ogVideo,
                    title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "X Video",
                    thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content"),
                    quality = "HD",
                    duration = 0,
                    format = "mp4",
                    mimeType = "video/mp4",
                    fileSize = 0,
                    sourceSite = "X (Twitter)"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Twitter extraction failed", e)
        }
        return null
    }

    private fun extractTikTok(url: String, userAgent: String): ExtractedVideo? {
        try {
            val doc = Jsoup.connect(url)
                .userAgent(userAgent)
                .followRedirects(true)
                .timeout(15000)
                .get()

            // Check for video content in scripts
            val scripts = doc.select("script")
            for (script in scripts) {
                val content = script.html()
                if (content.contains("\"playApi\"") || content.contains("\"download_addr\"")) {
                    val urlPattern = """"(?:playApi|download_addr)"\s*:\s*"([^"]*)"""".toRegex()
                    val match = urlPattern.find(content)
                    if (match != null) {
                        return ExtractedVideo(
                            url = match.groupValues[1].replace("\\u002F", "/"),
                            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "TikTok Video",
                            thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content"),
                            quality = "HD",
                            duration = 0,
                            format = "mp4",
                            mimeType = "video/mp4",
                            fileSize = 0,
                            sourceSite = "TikTok"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TikTok extraction failed", e)
        }
        return null
    }

    private fun extractGeneric(url: String, userAgent: String): ExtractedVideo? {
        try {
            val doc = Jsoup.connect(url)
                .userAgent(userAgent)
                .followRedirects(true)
                .timeout(15000)
                .get()

            // Try Open Graph video
            val ogVideo = doc.selectFirst("meta[property=og:video:url]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:video:secure_url]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:video]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:video:secure_url]")?.attr("content")

            if (ogVideo != null) {
                return ExtractedVideo(
                    url = ogVideo,
                    title = doc.selectFirst("meta[property=og:title]")?.attr("content"),
                    thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content"),
                    quality = null,
                    duration = 0,
                    format = "mp4",
                    mimeType = "video/mp4",
                    fileSize = 0,
                    sourceSite = URL(url).host
                )
            }

            // Search for video sources in HTML
            val videoSources = doc.select("video source, video[src]")
            for (source in videoSources) {
                val src = source.attr("src") ?: source.attr("data-src")
                if (src != null && (src.startsWith("http") || src.startsWith("//"))) {
                    val finalSrc = if (src.startsWith("//")) "https:$src" else src
                    return ExtractedVideo(
                        url = finalSrc,
                        title = doc.title(),
                        thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content"),
                        quality = source.attr("label") ?: source.attr("data-quality"),
                        duration = 0,
                        format = getExtension(finalSrc),
                        mimeType = "video/${getExtension(finalSrc)}",
                        fileSize = 0,
                        sourceSite = URL(url).host
                    )
                }
            }

            // Search for iframe embeds (embedded players)
            val iframes = doc.select("iframe[src]")
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src != null && (src.contains("youtube") || src.contains("vimeo") || src.contains("dailymotion"))) {
                    return extractVideo(src)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Generic extraction failed for $url", e)
        }
        return null
    }

    private fun getFileNameFromUrl(url: String): String {
        return try {
            val path = URL(url).path
            path.substring(path.lastIndexOf('/') + 1)
        } catch (e: Exception) {
            "video.mp4"
        }
    }

    private fun getExtension(url: String): String {
        return try {
            val path = URL(url).path
            val ext = path.substring(path.lastIndexOf('.') + 1)
            if (ext.length <= 5) ext else "mp4"
        } catch (e: Exception) {
            "mp4"
        }
    }

    private fun getMimeType(url: String): String {
        val ext = getExtension(url)
        return when (ext.lowercase()) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            else -> "video/mp4"
        }
    }

    private fun getContentLength(url: String, userAgent: String): Long {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", userAgent)
            val length = connection.contentLength.toLong()
            connection.disconnect()
            if (length > 0) length else 0
        } catch (e: Exception) {
            0
        }
    }
}
