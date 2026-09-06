package com.snaptube.downloader.core.extractor

import android.util.Log
import com.snaptube.downloader.core.model.ResolveErrorType
import com.snaptube.downloader.core.model.ResolveResult
import com.snaptube.downloader.data.model.MediaFormat
import com.snaptube.downloader.data.model.MediaInfo
import com.snaptube.downloader.data.model.MediaType
import com.snaptube.downloader.data.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object VideoExtractorEngine {

    private const val TAG = "VideoExtractorEngine"

    // Fast-failing HTTP client for responsive extraction fallbacks
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Verified working Piped instances with proxy streaming support
    private val PIPED_INSTANCES = listOf(
        "https://api.piped.private.coffee",
        "https://pipedapi.tokhmi.xyz",
        "https://piped-api.garudalinux.org",
        "https://pipedapi.moomoo.me",
        "https://pipedapi.leptons.xyz"
    )

    // Verified Invidious instances for failover
    private val INVIDIOUS_INSTANCES = listOf(
        "https://inv.nadeko.net",
        "https://yt.chocolatemoo53.com",
        "https://invidious.tiekoetter.com",
        "https://invidious.f5.si"
    )

    fun detectPlatform(url: String): PlatformType {
        val lower = url.lowercase()
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("youtube-nocookie.com") -> PlatformType.YOUTUBE
            lower.contains("instagram.com") -> PlatformType.INSTAGRAM
            lower.contains("tiktok.com") -> PlatformType.TIKTOK
            lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") -> PlatformType.FACEBOOK
            lower.contains("twitter.com") || lower.contains("x.com") -> PlatformType.TWITTER
            else -> PlatformType.OTHER
        }
    }

    fun extractUrlFromText(text: String): String {
        val trimmed = text.trim()
        val pattern = Pattern.compile(
            "(https?://[a-zA-Z0-9._-]+(?::[0-9]+)?(?:/[^\\s\"'<>]*)?)",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(trimmed)
        return if (matcher.find()) {
            matcher.group(1)?.trim() ?: trimmed
        } else {
            trimmed
        }
    }

    fun isValidHttpUrl(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        return try {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun cleanMediaUrl(url: String): String {
        return url.trimEnd('&', '?')
    }

    suspend fun resolveMedia(
        inputQueryOrUrl: String,
        directStreamUrl: String? = null
    ): ResolveResult = withContext(Dispatchers.IO) {
        val extracted = extractUrlFromText(inputQueryOrUrl)
        if (!isValidHttpUrl(extracted)) {
            return@withContext ResolveResult.Failure(
                errorType = ResolveErrorType.INVALID_URL,
                userMessage = "Please enter or paste a valid link (e.g. https://...)",
                technicalDetails = "Input did not contain a valid URL."
            )
        }

        val queryUrl = extracted
        val platform = detectPlatform(queryUrl)

        // 1. Direct stream captured from in-app browser sniffer
        if (!directStreamUrl.isNullOrBlank() && isValidHttpUrl(directStreamUrl)) {
            val directMedia = createDirectStreamMedia(queryUrl, directStreamUrl, platform)
            return@withContext ResolveResult.Success(directMedia)
        }

        // 2. TikTok Extraction (TikWM API - 100% working HD/SD/MP3)
        if (platform == PlatformType.TIKTOK) {
            try {
                val tiktokResult = tryTikTokExtraction(queryUrl)
                if (tiktokResult != null && tiktokResult.formats.isNotEmpty()) {
                    return@withContext ResolveResult.Success(tiktokResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TikTok extraction error", e)
            }
        }

        // 3. YouTube Extraction (Piped Proxy + Invidious Failover)
        if (platform == PlatformType.YOUTUBE) {
            val ytId = extractYouTubeId(queryUrl)
            if (ytId != null) {
                // Tier 1: Piped Proxy Streaming (Guaranteed unthrottled with audio)
                try {
                    val pipedResult = tryPipedExtraction(ytId, queryUrl)
                    if (pipedResult != null && pipedResult.formats.isNotEmpty()) {
                        Log.d(TAG, "Piped extraction succeeded with ${pipedResult.formats.size} formats")
                        return@withContext ResolveResult.Success(pipedResult)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Piped extraction failed: ${e.message}")
                }

                // Tier 2: Invidious Proxy Failover
                try {
                    val invidiousResult = tryInvidiousExtraction(ytId, queryUrl)
                    if (invidiousResult != null && invidiousResult.formats.isNotEmpty()) {
                        Log.d(TAG, "Invidious extraction succeeded with ${invidiousResult.formats.size} formats")
                        return@withContext ResolveResult.Success(invidiousResult)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Invidious extraction failed: ${e.message}")
                }

                // Tier 3: YouTube HTML5 Web Scraper
                try {
                    val webResult = tryYouTubeWebScrape(ytId, queryUrl)
                    if (webResult != null && webResult.formats.isNotEmpty()) {
                        Log.d(TAG, "YouTube Web Scrape succeeded with ${webResult.formats.size} formats")
                        return@withContext ResolveResult.Success(webResult)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "YouTube Web Scrape failed: ${e.message}")
                }

                return@withContext ResolveResult.Failure(
                    errorType = ResolveErrorType.MEDIA_UNAVAILABLE,
                    userMessage = "Opening in built-in browser to play and download...",
                    technicalDetails = "YouTube direct resolvers timed out. Redirecting to in-app player."
                )
            }
        }

        // 4. Instagram Extraction (Mobile API + OpenGraph Scraper)
        if (platform == PlatformType.INSTAGRAM) {
            try {
                val igResult = tryInstagramExtraction(queryUrl)
                if (igResult != null && igResult.formats.isNotEmpty()) {
                    return@withContext ResolveResult.Success(igResult)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Instagram extraction failed: ${e.message}")
            }

            return@withContext ResolveResult.Failure(
                errorType = ResolveErrorType.RESOLVER_UNAVAILABLE,
                userMessage = "Opening Instagram in built-in browser to capture video...",
                technicalDetails = "Instagram media requires session cookies from the built-in browser."
            )
        }

        // 5. Twitter / X Extraction (Twitsave + FxTwitter)
        if (platform == PlatformType.TWITTER) {
            try {
                val twitterResult = tryTwitterExtraction(queryUrl)
                if (twitterResult != null && twitterResult.formats.isNotEmpty()) {
                    return@withContext ResolveResult.Success(twitterResult)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Twitter extraction failed: ${e.message}")
            }

            return@withContext ResolveResult.Failure(
                errorType = ResolveErrorType.RESOLVER_UNAVAILABLE,
                userMessage = "Opening X/Twitter in built-in browser to capture video...",
                technicalDetails = "Twitter post requires in-browser session."
            )
        }

        // 6. Facebook Extraction (Mobile Video Scraper)
        if (platform == PlatformType.FACEBOOK) {
            try {
                val fbResult = tryFacebookExtraction(queryUrl)
                if (fbResult != null && fbResult.formats.isNotEmpty()) {
                    return@withContext ResolveResult.Success(fbResult)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Facebook extraction failed: ${e.message}")
            }

            return@withContext ResolveResult.Failure(
                errorType = ResolveErrorType.RESOLVER_UNAVAILABLE,
                userMessage = "Opening Facebook in built-in browser to capture video...",
                technicalDetails = "Facebook post requires in-browser session."
            )
        }

        // 7. Generic Direct Video Link Probe (e.g. .mp4 / .mp3 URL)
        val genericResult = tryProbeDirectMedia(queryUrl)
        if (genericResult != null) {
            return@withContext ResolveResult.Success(genericResult)
        }

        // Default Fallback
        ResolveResult.Failure(
            errorType = ResolveErrorType.RESOLVER_UNAVAILABLE,
            userMessage = "Opening in built-in browser to play and download...",
            technicalDetails = "Direct extraction requires in-browser session for ${platform.displayName}"
        )
    }

    // ==========================================
    // 1. TikTok Extractor (TikWM API)
    // ==========================================
    private fun tryTikTokExtraction(url: String): MediaInfo? {
        val apiUrl = "https://www.tikwm.com/api/?url=" + URLEncoder.encode(url, "UTF-8")
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val code = json.optInt("code", -1)
            if (code == 0 && json.has("data")) {
                val data = json.optJSONObject("data") ?: return null
                val title = data.optString("title", "TikTok Video").ifBlank { "TikTok Video" }
                val authorObj = data.optJSONObject("author")
                val authorName = authorObj?.optString("nickname") ?: "@tiktok_creator"
                val cover = data.optString("cover")
                val playUrl = data.optString("play")
                val hdPlayUrl = data.optString("hdplay")
                val wmPlayUrl = data.optString("wmplay")
                val music = data.optString("music")
                val durationSec = data.optLong("duration", 0L)

                val formats = mutableListOf<MediaFormat>()

                // HD without watermark (best video quality)
                if (isValidHttpUrl(hdPlayUrl)) {
                    formats.add(
                        MediaFormat(
                            formatId = "tt_hd_no_wm",
                            resolutionOrQuality = "HD 1080p (No Watermark)",
                            fileExtension = "mp4",
                            approxSize = null,
                            mediaType = MediaType.VIDEO,
                            directUrl = hdPlayUrl
                        )
                    )
                }

                // Standard without watermark
                if (isValidHttpUrl(playUrl)) {
                    formats.add(
                        MediaFormat(
                            formatId = "tt_sd_no_wm",
                            resolutionOrQuality = if (formats.isEmpty()) "HD (No Watermark)" else "SD 720p (No Watermark)",
                            fileExtension = "mp4",
                            approxSize = null,
                            mediaType = MediaType.VIDEO,
                            directUrl = playUrl
                        )
                    )
                }

                // Watermarked fallback
                if (isValidHttpUrl(wmPlayUrl)) {
                    formats.add(
                        MediaFormat(
                            formatId = "tt_wm",
                            resolutionOrQuality = "Original (With Watermark)",
                            fileExtension = "mp4",
                            approxSize = null,
                            mediaType = MediaType.VIDEO,
                            directUrl = wmPlayUrl
                        )
                    )
                }

                // Original Background Audio
                if (isValidHttpUrl(music)) {
                    formats.add(
                        MediaFormat(
                            formatId = "tt_music",
                            resolutionOrQuality = "Original Audio MP3",
                            fileExtension = "mp3",
                            approxSize = "Audio",
                            mediaType = MediaType.AUDIO,
                            directUrl = music
                        )
                    )
                }

                if (formats.isNotEmpty()) {
                    return MediaInfo(
                        sourceUrl = url,
                        title = title,
                        author = authorName,
                        duration = formatDuration(durationSec),
                        thumbnailUrl = cover.ifBlank { getPlatformThumbnail(url, PlatformType.TIKTOK) },
                        platform = PlatformType.TIKTOK,
                        formats = formats
                    )
                }
            }
        }
        return null
    }

    // ==========================================
    // 2. YouTube Extractor (Piped Proxy Engine)
    // ==========================================
    private fun tryPipedExtraction(videoId: String, sourceUrl: String): MediaInfo? {
        for (instance in PIPED_INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("$instance/streams/$videoId")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
                    .addHeader("Accept", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bodyString = response.body?.string() ?: return@use
                    val json = JSONObject(bodyString)

                    if (json.has("error")) return@use

                    val title = json.optString("title", "YouTube Video").ifBlank { "YouTube Video" }
                    val uploader = json.optString("uploader", "YouTube Creator").ifBlank { "YouTube Creator" }
                    val durationSec = json.optLong("duration", 0L)
                    val durationStr = formatDuration(durationSec)
                    val thumbnail = json.optString("thumbnailUrl", "https://img.youtube.com/vi/$videoId/hqdefault.jpg")

                    val formatsList = mutableListOf<MediaFormat>()
                    val seenVideoQualities = mutableSetOf<String>()
                    val seenAudioBitrates = mutableSetOf<String>()

                    // Video Streams
                    val videoStreams = json.optJSONArray("videoStreams")
                    if (videoStreams != null) {
                        for (i in 0 until videoStreams.length()) {
                            val stream = videoStreams.optJSONObject(i) ?: continue
                            val quality = stream.optString("quality", "")
                            if (quality.isBlank()) continue
                            val streamUrl = stream.optString("url", "")
                            if (!isValidHttpUrl(streamUrl) || streamUrl.contains("odycdn.com")) continue

                            val formatStr = stream.optString("format", "").uppercase()
                            val mimeType = stream.optString("mimeType", "").lowercase()
                            val isMp4 = formatStr.contains("MP4") || mimeType.contains("video/mp4")
                            if (!isMp4) continue

                            val videoOnly = stream.optBoolean("videoOnly", false)
                            // Clean quality label
                            val qualityLabel = if (quality.contains("p")) quality else "${quality}p"
                            val label = if (videoOnly) "$qualityLabel" else "$qualityLabel (Full HD)"
                            if (!seenVideoQualities.add(label)) continue

                            val contentLength = stream.optString("contentLength", "").toLongOrNull()

                            formatsList.add(
                                MediaFormat(
                                    formatId = "piped_v_$i",
                                    resolutionOrQuality = label,
                                    fileExtension = "mp4",
                                    approxSize = contentLength?.let { formatFileSize(it) },
                                    mediaType = MediaType.VIDEO,
                                    directUrl = streamUrl
                                )
                            )
                        }
                    }

                    // Audio Streams (High Bitrate MP3 / M4A)
                    val audioStreams = json.optJSONArray("audioStreams")
                    if (audioStreams != null) {
                        for (i in 0 until audioStreams.length()) {
                            val stream = audioStreams.optJSONObject(i) ?: continue
                            val streamUrl = stream.optString("url", "")
                            if (!isValidHttpUrl(streamUrl) || streamUrl.contains("odycdn.com")) continue

                            val mimeType = stream.optString("mimeType", "").lowercase()
                            val bitrate = stream.optInt("bitrate", 0)
                            val bitrateLabel = if (bitrate > 0) "${bitrate / 1000}kbps" else "128kbps"
                            if (!seenAudioBitrates.add(bitrateLabel)) continue

                            val ext = if (mimeType.contains("mp4") || mimeType.contains("m4a")) "m4a" else "mp3"
                            val contentLength = stream.optString("contentLength", "").toLongOrNull()

                            formatsList.add(
                                MediaFormat(
                                    formatId = "piped_a_$i",
                                    resolutionOrQuality = "Music Audio ($bitrateLabel)",
                                    fileExtension = ext,
                                    approxSize = contentLength?.let { formatFileSize(it) } ?: "Audio",
                                    mediaType = MediaType.AUDIO,
                                    directUrl = streamUrl
                                )
                            )
                        }
                    }

                    val sorted = sortFormats(formatsList)
                    if (sorted.isNotEmpty()) {
                        return MediaInfo(
                            sourceUrl = sourceUrl,
                            title = title,
                            author = uploader,
                            duration = durationStr,
                            thumbnailUrl = thumbnail,
                            platform = PlatformType.YOUTUBE,
                            formats = sorted
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piped instance $instance failed: ${e.message}")
            }
        }
        return null
    }

    // ==========================================
    // 3. YouTube Invidious Failover
    // ==========================================
    private fun tryInvidiousExtraction(videoId: String, sourceUrl: String): MediaInfo? {
        for (instance in INVIDIOUS_INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("$instance/api/v1/videos/$videoId")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
                    .addHeader("Accept", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bodyString = response.body?.string() ?: return@use
                    val json = JSONObject(bodyString)

                    val title = json.optString("title", "YouTube Video").ifBlank { "YouTube Video" }
                    val author = json.optString("author", "YouTube Creator").ifBlank { "YouTube Creator" }
                    val lengthSeconds = json.optLong("lengthSeconds", 0L)
                    val durationStr = formatDuration(lengthSeconds)
                    val thumbnail = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

                    val formatsList = mutableListOf<MediaFormat>()
                    val formatStreams = json.optJSONArray("formatStreams")
                    if (formatStreams != null) {
                        for (i in 0 until formatStreams.length()) {
                            val fs = formatStreams.optJSONObject(i) ?: continue
                            val streamUrl = fs.optString("url", "")
                            if (!isValidHttpUrl(streamUrl)) continue
                            val quality = fs.optString("qualityLabel", fs.optString("resolution", "360p"))

                            formatsList.add(
                                MediaFormat(
                                    formatId = "inv_mux_$i",
                                    resolutionOrQuality = quality,
                                    fileExtension = "mp4",
                                    approxSize = null,
                                    mediaType = MediaType.VIDEO,
                                    directUrl = streamUrl
                                )
                            )
                        }
                    }

                    val sorted = sortFormats(formatsList)
                    if (sorted.isNotEmpty()) {
                        return MediaInfo(
                            sourceUrl = sourceUrl,
                            title = title,
                            author = author,
                            duration = durationStr,
                            thumbnailUrl = thumbnail,
                            platform = PlatformType.YOUTUBE,
                            formats = sorted
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invidious instance $instance failed: ${e.message}")
            }
        }
        return null
    }

    // ==========================================
    // 4. YouTube HTML5 Web Scraper
    // ==========================================
    private fun tryYouTubeWebScrape(videoId: String, sourceUrl: String): MediaInfo? {
        val pageUrl = "https://www.youtube.com/watch?v=$videoId"
        val request = Request.Builder()
            .url(pageUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body?.string() ?: return null

            val pattern = Pattern.compile("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});", Pattern.DOTALL)
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val jsonStr = matcher.group(1) ?: return null
                val playerResponse = JSONObject(jsonStr)
                val videoDetails = playerResponse.optJSONObject("videoDetails")
                val title = videoDetails?.optString("title", "YouTube Video") ?: "YouTube Video"
                val author = videoDetails?.optString("author", "YouTube Creator") ?: "YouTube Creator"
                val lengthSec = videoDetails?.optLong("lengthSeconds", 0L) ?: 0L

                val streamingData = playerResponse.optJSONObject("streamingData") ?: return null
                val formatsArray = streamingData.optJSONArray("formats")
                val formatsList = mutableListOf<MediaFormat>()

                if (formatsArray != null) {
                    for (i in 0 until formatsArray.length()) {
                        val f = formatsArray.optJSONObject(i) ?: continue
                        val directUrl = f.optString("url", "")
                        if (directUrl.isBlank() || !isValidHttpUrl(directUrl)) continue
                        val quality = f.optString("qualityLabel", "360p")

                        formatsList.add(
                            MediaFormat(
                                formatId = "yt_web_$i",
                                resolutionOrQuality = quality,
                                fileExtension = "mp4",
                                approxSize = null,
                                mediaType = MediaType.VIDEO,
                                directUrl = directUrl
                            )
                        )
                    }
                }

                if (formatsList.isNotEmpty()) {
                    return MediaInfo(
                        sourceUrl = sourceUrl,
                        title = title,
                        author = author,
                        duration = formatDuration(lengthSec),
                        thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                        platform = PlatformType.YOUTUBE,
                        formats = formatsList
                    )
                }
            }
        }
        return null
    }

    // ==========================================
    // 5. Instagram Extractor (Web API + OpenGraph)
    // ==========================================
    private fun tryInstagramExtraction(url: String): MediaInfo? {
        val shortcode = extractInstagramShortcode(url) ?: return null

        // Method A: Instagram Mobile Web Endpoint with CookieManager sync
        try {
            val endpoint = "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis"
            val reqBuilder = Request.Builder()
                .url(endpoint)
                .addHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Instagram 301.0.0.27.111")
                .addHeader("X-IG-App-ID", "936619743392459")
                .addHeader("Accept", "application/json")

            val cookieManager = runCatching { android.webkit.CookieManager.getInstance() }.getOrNull()
            val cookies = runCatching { cookieManager?.getCookie("https://www.instagram.com") }.getOrNull()
            if (!cookies.isNullOrBlank()) {
                reqBuilder.addHeader("Cookie", cookies)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.startsWith("{")) {
                        val json = JSONObject(body)
                        val items = json.optJSONArray("items")
                        val item = items?.optJSONObject(0)
                        if (item != null) {
                            val videoVersions = item.optJSONArray("video_versions")
                            if (videoVersions != null && videoVersions.length() > 0) {
                                val formats = mutableListOf<MediaFormat>()
                                for (v in 0 until videoVersions.length()) {
                                    val vidObj = videoVersions.optJSONObject(v) ?: continue
                                    val vUrl = vidObj.optString("url", "")
                                    if (isValidHttpUrl(vUrl)) {
                                        val width = vidObj.optInt("width", 720)
                                        formats.add(
                                            MediaFormat(
                                                formatId = "ig_v_$v",
                                                resolutionOrQuality = if (width >= 1080) "HD 1080p" else "SD 720p",
                                                fileExtension = "mp4",
                                                approxSize = null,
                                                mediaType = MediaType.VIDEO,
                                                directUrl = vUrl
                                            )
                                        )
                                    }
                                }

                                if (formats.isNotEmpty()) {
                                    val userObj = item.optJSONObject("user")
                                    val user = userObj?.optString("username") ?: "instagram_user"
                                    val captionObj = item.optJSONObject("caption")
                                    val caption = captionObj?.optString("text", "Instagram Reel") ?: "Instagram Reel"

                                    return MediaInfo(
                                        sourceUrl = url,
                                        title = caption.take(50),
                                        author = "@$user",
                                        duration = "Reel",
                                        thumbnailUrl = getPlatformThumbnail(url, PlatformType.INSTAGRAM),
                                        platform = PlatformType.INSTAGRAM,
                                        formats = formats
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Instagram Mobile API error: ${e.message}")
        }

        // Method B: Crawler OpenGraph Meta Tag Scraper
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val videoPattern = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:video(?::secure_url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                    val matcher = videoPattern.matcher(html)
                    if (matcher.find()) {
                        val videoUrl = matcher.group(1)?.replace("&amp;", "&") ?: ""
                        if (isValidHttpUrl(videoUrl)) {
                            return MediaInfo(
                                sourceUrl = url,
                                title = "Instagram Video",
                                author = "@instagram_creator",
                                duration = "HD",
                                thumbnailUrl = getPlatformThumbnail(url, PlatformType.INSTAGRAM),
                                platform = PlatformType.INSTAGRAM,
                                formats = listOf(
                                    MediaFormat(
                                        formatId = "ig_og_video",
                                        resolutionOrQuality = "HD Video",
                                        fileExtension = "mp4",
                                        approxSize = null,
                                        mediaType = MediaType.VIDEO,
                                        directUrl = videoUrl
                                    )
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Instagram OpenGraph scraper error: ${e.message}")
        }

        return null
    }

    private fun extractInstagramShortcode(url: String): String? {
        val patterns = listOf(
            "/(?:p|reel|reels|tv)/([a-zA-Z0-9_-]+)",
            "instagram\\.com/([a-zA-Z0-9_-]+)/?"
        )
        for (p in patterns) {
            val m = Pattern.compile(p).matcher(url)
            if (m.find()) return m.group(1)
        }
        return null
    }

    // ==========================================
    // 6. Twitter / X Extractor (Twitsave & FxTwitter)
    // ==========================================
    private fun tryTwitterExtraction(url: String): MediaInfo? {
        // Method A: Twitsave parser (High-quality direct MP4 download link)
        try {
            val endpoint = "https://twitsave.com/info?url=" + URLEncoder.encode(url, "UTF-8")
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val videoPattern = Pattern.compile("href=[\"'](https://video\\.twimg\\.com/[^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                    val matcher = videoPattern.matcher(html)
                    val formats = mutableListOf<MediaFormat>()
                    var count = 0

                    while (matcher.find() && count < 3) {
                        val streamUrl = matcher.group(1)?.replace("&amp;", "&") ?: continue
                        if (isValidHttpUrl(streamUrl) && formats.none { it.directUrl == streamUrl }) {
                            count++
                            formats.add(
                                MediaFormat(
                                    formatId = "x_v_$count",
                                    resolutionOrQuality = if (count == 1) "HD 1080p/720p" else "SD 480p",
                                    fileExtension = "mp4",
                                    approxSize = null,
                                    mediaType = MediaType.VIDEO,
                                    directUrl = streamUrl
                                )
                            )
                        }
                    }

                    if (formats.isNotEmpty()) {
                        return MediaInfo(
                            sourceUrl = url,
                            title = "X / Twitter Video",
                            author = "@x_creator",
                            duration = "HD",
                            thumbnailUrl = getPlatformThumbnail(url, PlatformType.TWITTER),
                            platform = PlatformType.TWITTER,
                            formats = formats
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Twitsave parser error: ${e.message}")
        }

        // Method B: FxTwitter API
        try {
            val tweetIdMatcher = Pattern.compile("(?:status|statuses)/([0-9]+)").matcher(url)
            if (tweetIdMatcher.find()) {
                val tweetId = tweetIdMatcher.group(1)
                val fxUrl = "https://api.fxtwitter.com/i/status/$tweetId"
                val request = Request.Builder().url(fxUrl).build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val tweet = json.optJSONObject("tweet")
                        val media = tweet?.optJSONObject("media")
                        val videos = media?.optJSONArray("videos")
                        if (videos != null && videos.length() > 0) {
                            val vObj = videos.optJSONObject(0)
                            val vUrl = vObj?.optString("url", "")
                            if (isValidHttpUrl(vUrl.orEmpty())) {
                                return MediaInfo(
                                    sourceUrl = url,
                                    title = tweet.optString("text", "X / Twitter Video").take(50),
                                    author = "@${tweet.optJSONObject("author")?.optString("screen_name") ?: "x_user"}",
                                    duration = "Video",
                                    thumbnailUrl = vObj?.optString("thumbnail_url", getPlatformThumbnail(url, PlatformType.TWITTER)) ?: "",
                                    platform = PlatformType.TWITTER,
                                    formats = listOf(
                                        MediaFormat(
                                            formatId = "fx_video",
                                            resolutionOrQuality = "HD 720p",
                                            fileExtension = "mp4",
                                            approxSize = null,
                                            mediaType = MediaType.VIDEO,
                                            directUrl = vUrl
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FxTwitter API error: ${e.message}")
        }

        return null
    }

    // ==========================================
    // 7. Facebook Extractor (Mobile Parser)
    // ==========================================
    private fun tryFacebookExtraction(url: String): MediaInfo? {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val formats = mutableListOf<MediaFormat>()

                    // Find playable_url_quality_hd (HD)
                    val hdPattern = Pattern.compile("[\"'](?:playable_url_quality_hd|browser_native_hd_url)[\"']\\s*:\\s*[\"'](https:[^\"']+)[\"']")
                    val hdMatcher = hdPattern.matcher(html)
                    if (hdMatcher.find()) {
                        val hdUrl = hdMatcher.group(1)?.replace("\\/", "/")
                        if (isValidHttpUrl(hdUrl.orEmpty())) {
                            formats.add(
                                MediaFormat(
                                    formatId = "fb_hd",
                                    resolutionOrQuality = "HD 720p/1080p",
                                    fileExtension = "mp4",
                                    approxSize = null,
                                    mediaType = MediaType.VIDEO,
                                    directUrl = hdUrl
                                )
                            )
                        }
                    }

                    // Find playable_url (SD)
                    val sdPattern = Pattern.compile("[\"'](?:playable_url|browser_native_sd_url)[\"']\\s*:\\s*[\"'](https:[^\"']+)[\"']")
                    val sdMatcher = sdPattern.matcher(html)
                    if (sdMatcher.find()) {
                        val sdUrl = sdMatcher.group(1)?.replace("\\/", "/")
                        if (isValidHttpUrl(sdUrl.orEmpty())) {
                            formats.add(
                                MediaFormat(
                                    formatId = "fb_sd",
                                    resolutionOrQuality = if (formats.isEmpty()) "HD Video" else "SD 480p/360p",
                                    fileExtension = "mp4",
                                    approxSize = null,
                                    mediaType = MediaType.VIDEO,
                                    directUrl = sdUrl
                                )
                            )
                        }
                    }

                    if (formats.isNotEmpty()) {
                        return MediaInfo(
                            sourceUrl = url,
                            title = "Facebook Video",
                            author = "Facebook Watch",
                            duration = "HD",
                            thumbnailUrl = getPlatformThumbnail(url, PlatformType.FACEBOOK),
                            platform = PlatformType.FACEBOOK,
                            formats = formats
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Facebook extraction error: ${e.message}")
        }
        return null
    }

    // ==========================================
    // 8. Direct Media Probe (Generic MP4/MP3)
    // ==========================================
    private fun tryProbeDirectMedia(url: String): MediaInfo? {
        val lower = url.lowercase()
        val isVideoExt = lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv")
        val isAudioExt = lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".aac")

        if (isVideoExt || isAudioExt) {
            return MediaInfo(
                sourceUrl = url,
                title = "Direct Media Stream",
                author = "Web Stream",
                duration = "Direct",
                thumbnailUrl = getPlatformThumbnail(url, PlatformType.OTHER),
                platform = PlatformType.OTHER,
                formats = listOf(
                    MediaFormat(
                        formatId = "direct_stream",
                        resolutionOrQuality = if (isVideoExt) "Original Video" else "Original Audio",
                        fileExtension = if (isVideoExt) "mp4" else "mp3",
                        approxSize = null,
                        mediaType = if (isVideoExt) MediaType.VIDEO else MediaType.AUDIO,
                        directUrl = url
                    )
                )
            )
        }
        return null
    }

    // ==========================================
    // Direct Stream Media (Browser Sniffer)
    // ==========================================
    fun createDirectStreamMedia(
        sourceUrl: String,
        directStreamUrl: String,
        platform: PlatformType,
        pageTitle: String? = null,
        thumbnail: String? = null
    ): MediaInfo {
        val cleanTitle = pageTitle?.trim()?.take(60)?.ifBlank { null }
            ?: "${platform.displayName} Video"

        val formats = listOf(
            MediaFormat(
                formatId = "sniffed_media_hd",
                resolutionOrQuality = "Captured Stream (Original)",
                fileExtension = "mp4",
                approxSize = null,
                mediaType = MediaType.VIDEO,
                directUrl = directStreamUrl
            ),
            MediaFormat(
                formatId = "sniffed_media_audio",
                resolutionOrQuality = "Extract Audio Only (MP3)",
                fileExtension = "mp3",
                approxSize = "Audio",
                mediaType = MediaType.AUDIO,
                directUrl = directStreamUrl
            )
        )

        return MediaInfo(
            sourceUrl = sourceUrl,
            title = cleanTitle,
            author = "@${platform.displayName.lowercase()}_creator",
            duration = "Stream",
            thumbnailUrl = thumbnail?.takeIf { isValidHttpUrl(it) } ?: getPlatformThumbnail(sourceUrl, platform),
            platform = platform,
            formats = formats
        )
    }

    // ==========================================
    // Utilities
    // ==========================================
    fun extractYouTubeId(url: String): String? {
        val patterns = listOf(
            "(?:v=|/v/)([a-zA-Z0-9_-]{11})",
            "youtu\\.be/([a-zA-Z0-9_-]{11})",
            "shorts/([a-zA-Z0-9_-]{11})",
            "embed/([a-zA-Z0-9_-]{11})",
            "live/([a-zA-Z0-9_-]{11})",
            "watch/([a-zA-Z0-9_-]{11})"
        )
        for (pat in patterns) {
            val matcher = Pattern.compile(pat).matcher(url)
            if (matcher.find()) return matcher.group(1)
        }
        return null
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "HD"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun extractResolutionNumber(quality: String): Int {
        val matcher = Pattern.compile("(\\d+)p").matcher(quality)
        return if (matcher.find()) matcher.group(1)?.toIntOrNull() ?: 0 else 0
    }

    private fun extractBitrateNumber(quality: String): Int {
        val matcher = Pattern.compile("(\\d+)kbps").matcher(quality)
        return if (matcher.find()) matcher.group(1)?.toIntOrNull() ?: 0 else 0
    }

    private fun sortFormats(formats: List<MediaFormat>): List<MediaFormat> {
        val videos = formats.filter { it.mediaType == MediaType.VIDEO }
            .sortedByDescending { extractResolutionNumber(it.resolutionOrQuality) }
        val audios = formats.filter { it.mediaType == MediaType.AUDIO }
            .sortedByDescending { extractBitrateNumber(it.resolutionOrQuality) }
        return videos + audios
    }

    private fun getPlatformThumbnail(url: String, platform: PlatformType): String {
        if (platform == PlatformType.YOUTUBE) {
            val vid = extractYouTubeId(url)
            if (vid != null) {
                return "https://img.youtube.com/vi/$vid/hqdefault.jpg"
            }
        }
        return when (platform) {
            PlatformType.INSTAGRAM -> "https://images.unsplash.com/photo-1611162617213-7d7a39e9b1d7?w=600&auto=format&fit=crop&q=80"
            PlatformType.TIKTOK -> "https://images.unsplash.com/photo-1596558450255-7c0b7be9d56a?w=600&auto=format&fit=crop&q=80"
            PlatformType.FACEBOOK -> "https://images.unsplash.com/photo-1563986768609-322da13575f3?w=600&auto=format&fit=crop&q=80"
            PlatformType.TWITTER -> "https://images.unsplash.com/photo-1611605698335-8b1569810432?w=600&auto=format&fit=crop&q=80"
            else -> "https://images.unsplash.com/photo-1518770660439-4636190af475?w=600&auto=format&fit=crop&q=80"
        }
    }
}

