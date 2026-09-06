package com.snaptube.downloader.core.extractor

import com.snaptube.downloader.core.model.ResolveErrorType
import com.snaptube.downloader.core.model.ResolveResult
import com.snaptube.downloader.data.model.MediaFormat
import com.snaptube.downloader.data.model.MediaInfo
import com.snaptube.downloader.data.model.MediaType
import com.snaptube.downloader.data.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object VideoExtractorEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // High-availability Piped API instances for YouTube public stream resolution
    private val PIPED_INSTANCES = listOf(
        "https://api.piped.private.coffee",
        "https://pipedapi.leptons.xyz",
        "https://pipedapi.tokhmi.xyz"
    )

    fun detectPlatform(url: String): PlatformType {
        val lower = url.lowercase()
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> PlatformType.YOUTUBE
            lower.contains("instagram.com") -> PlatformType.INSTAGRAM
            lower.contains("tiktok.com") -> PlatformType.TIKTOK
            lower.contains("facebook.com") || lower.contains("fb.watch") -> PlatformType.FACEBOOK
            lower.contains("twitter.com") || lower.contains("x.com") -> PlatformType.TWITTER
            else -> PlatformType.OTHER
        }
    }

    fun extractUrlFromText(text: String): String {
        val trimmed = text.trim()
        val pattern = Pattern.compile("(https?://[a-zA-Z0-9.-]+(?:/[^\\s]*)?)", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(trimmed)
        return if (matcher.find()) {
            matcher.group(1)?.trim() ?: trimmed
        } else {
            trimmed
        }
    }

    fun isValidHttpUrl(candidate: String): Boolean {
        return try {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun cleanMediaUrl(url: String): String {
        if (url.contains("videoplayback")) {
            // Strip range and rn byte-range chunk parameters so Google Video serves the full stream
            return url.replace(Regex("([?&])range=[^&]+(&|$)"), "$1")
                .replace(Regex("([?&])rn=[^&]+(&|$)"), "$1")
                .trimEnd('&', '?')
        }
        return url
    }

    suspend fun resolveMedia(
        inputQueryOrUrl: String,
        directStreamUrl: String? = null
    ): ResolveResult = withContext(Dispatchers.IO) {
        val extracted = extractUrlFromText(inputQueryOrUrl)
        if (!isValidHttpUrl(extracted)) {
            return@withContext ResolveResult.Failure(
                errorType = ResolveErrorType.INVALID_URL,
                userMessage = "Please enter or paste a valid web link (e.g. https://...)",
                technicalDetails = "Input '$inputQueryOrUrl' did not produce a valid HTTP/HTTPS URL."
            )
        }

        val queryUrl = extracted
        val platform = detectPlatform(queryUrl)

        // 1. If an actual stream was captured from the in-app browser or DOM
        if (!directStreamUrl.isNullOrBlank() && isValidHttpUrl(directStreamUrl)) {
            val directMedia = createDirectStreamMedia(queryUrl, cleanMediaUrl(directStreamUrl), platform)
            return@withContext ResolveResult.Success(directMedia)
        }

        // 2. YouTube direct Innertube player & Piped resolution
        if (platform == PlatformType.YOUTUBE) {
            val ytId = extractYouTubeId(queryUrl)
            if (ytId != null) {
                // Priority 1: Direct native Innertube player extraction
                val innertubeResult = tryInnertubeExtraction(ytId, queryUrl)
                if (innertubeResult != null && innertubeResult.formats.isNotEmpty()) {
                    return@withContext ResolveResult.Success(innertubeResult)
                }

                // Priority 2: Piped API mirrors
                val pipedResult = tryPipedExtraction(ytId, queryUrl)
                if (pipedResult != null && pipedResult.formats.isNotEmpty()) {
                    return@withContext ResolveResult.Success(pipedResult)
                }
            }
        }

        // 3. TikTok extraction via TikWM
        if (platform == PlatformType.TIKTOK) {
            val tiktokResult = tryTikTokExtraction(queryUrl)
            if (tiktokResult != null && tiktokResult.formats.isNotEmpty()) {
                return@withContext ResolveResult.Success(tiktokResult)
            }
        }

        // 4. Honest fallback guiding user to built-in browser for Instagram, FB, etc.
        ResolveResult.Failure(
            errorType = ResolveErrorType.RESOLVER_UNAVAILABLE,
            userMessage = "Opening in built-in browser to play and capture video...",
            technicalDetails = "Direct extraction requires in-browser session for ${platform.displayName}"
        )
    }

    private fun tryInnertubeExtraction(videoId: String, sourceUrl: String): MediaInfo? {
        try {
            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.50.31")
                        put("deviceMake", "Oculus")
                        put("deviceModel", "Quest 3")
                        put("osName", "Android")
                        put("osVersion", "12")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)

                val status = json.optJSONObject("playabilityStatus")?.optString("status")
                if (status != "OK") return null

                val videoDetails = json.optJSONObject("videoDetails")
                val title = videoDetails?.optString("title", "YouTube Video") ?: "YouTube Video"
                val author = videoDetails?.optString("author", "YouTube Creator") ?: "YouTube Creator"
                val lengthSeconds = videoDetails?.optLong("lengthSeconds", 0L) ?: 0L
                val durationStr = formatDuration(lengthSeconds)
                val thumbnail = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

                val streamingData = json.optJSONObject("streamingData") ?: return null
                val formatsList = mutableListOf<MediaFormat>()

                // 1. Multiplexed combined video + audio streams
                val formats = streamingData.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.optJSONObject(i) ?: continue
                        val directUrl = f.optString("url")
                        if (isValidHttpUrl(directUrl) && !directUrl.contains("odycdn.com")) {
                            val quality = f.optString("qualityLabel", "360p")
                            formatsList.add(
                                MediaFormat(
                                    formatId = "yt_it_v_$i",
                                    resolutionOrQuality = quality,
                                    fileExtension = "mp4",
                                    approxSize = null,
                                    mediaType = MediaType.VIDEO,
                                    directUrl = cleanMediaUrl(directUrl)
                                )
                            )
                        }
                    }
                }

                // 2. Adaptive video + audio streams (contains 1080p, 1440p, 2160p/4K, etc.)
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                val seenVideoQualities = mutableSetOf<String>()
                val seenAudioBitrates = mutableSetOf<Int>()
                if (adaptiveFormats != null) {
                    for (i in 0 until adaptiveFormats.length()) {
                        val af = adaptiveFormats.optJSONObject(i) ?: continue
                        val mimeType = af.optString("mimeType", "")
                        val directUrl = af.optString("url")
                        if (!isValidHttpUrl(directUrl) || directUrl.contains("odycdn.com")) continue

                        if (mimeType.contains("video")) {
                            val quality = af.optString("qualityLabel", "").ifBlank { af.optString("quality", "") }
                            if (quality.isBlank()) continue
                            // Only take MP4/H.264 adaptive video streams for maximum device compatibility
                            val isVideoMp4 = mimeType.contains("mp4") || mimeType.contains("avc")
                            if (!isVideoMp4) continue
                            // Deduplicate: keep one stream per quality label (e.g. one "1080p")
                            if (!seenVideoQualities.add(quality)) continue

                            val contentLength = af.optLong("contentLength", 0L)
                            val sizeStr = if (contentLength > 0) formatFileSize(contentLength) else null

                            formatsList.add(
                                MediaFormat(
                                    formatId = "yt_it_av_$i",
                                    resolutionOrQuality = quality,
                                    fileExtension = "mp4",
                                    approxSize = sizeStr,
                                    mediaType = MediaType.VIDEO,
                                    directUrl = cleanMediaUrl(directUrl)
                                )
                            )
                        } else if (mimeType.contains("audio")) {
                            val bitrate = af.optInt("averageBitrate", af.optInt("bitrate", 0))
                            // Deduplicate: keep one stream per bitrate tier
                            val bitrateTier = (bitrate / 1000) * 1000 // round to nearest 1000
                            if (!seenAudioBitrates.add(bitrateTier)) continue

                            val bitrateLabel = if (bitrate > 0) "${bitrate / 1000}kbps" else "Original"
                            val ext = if (mimeType.contains("mp4") || mimeType.contains("m4a")) "m4a" else "webm"

                            formatsList.add(
                                MediaFormat(
                                    formatId = "yt_it_a_$i",
                                    resolutionOrQuality = "Audio ($bitrateLabel)",
                                    fileExtension = ext,
                                    approxSize = "Audio",
                                    mediaType = MediaType.AUDIO,
                                    directUrl = cleanMediaUrl(directUrl)
                                )
                            )
                        }
                    }
                }

                // Sort video formats by resolution descending (2160p > 1440p > 1080p > 720p > ...)
                val videoFormats = formatsList.filter { it.mediaType == MediaType.VIDEO }
                    .sortedByDescending { extractResolutionNumber(it.resolutionOrQuality) }
                val audioFormats = formatsList.filter { it.mediaType == MediaType.AUDIO }
                    .sortedByDescending { extractBitrateNumber(it.resolutionOrQuality) }
                formatsList.clear()
                formatsList.addAll(videoFormats)
                formatsList.addAll(audioFormats)

                if (formatsList.isNotEmpty()) {
                    return MediaInfo(
                        sourceUrl = sourceUrl,
                        title = title,
                        author = author,
                        duration = durationStr,
                        thumbnailUrl = thumbnail,
                        platform = PlatformType.YOUTUBE,
                        formats = formatsList
                    )
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun tryPipedExtraction(videoId: String, sourceUrl: String): MediaInfo? {
        for (instance in PIPED_INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("$instance/streams/$videoId")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bodyString = response.body?.string() ?: return@use
                    val json = JSONObject(bodyString)

                    val title = json.optString("title", "YouTube Video").ifBlank { "YouTube Video" }
                    val uploader = json.optString("uploader", "YouTube Creator").ifBlank { "YouTube Creator" }
                    val durationSec = json.optLong("duration", 0L)
                    val durationStr = formatDuration(durationSec)
                    val thumbnail = json.optString("thumbnailUrl", "https://img.youtube.com/vi/$videoId/hqdefault.jpg")

                    val formatsList = mutableListOf<MediaFormat>()
                    val seenVideoQualities = mutableSetOf<String>()

                    val videoStreams = json.optJSONArray("videoStreams")
                    if (videoStreams != null) {
                        for (i in 0 until videoStreams.length()) {
                            val stream = videoStreams.optJSONObject(i) ?: continue
                            val quality = stream.optString("quality", "720p")
                            val streamUrl = stream.optString("url")
                            val formatUpper = stream.optString("format", "").uppercase()
                            val mimeType = stream.optString("mimeType", "").lowercase()
                            val isAuthBlocked = streamUrl.contains("odycdn.com")
                            val videoOnly = stream.optBoolean("videoOnly", false)

                            // Accept MP4 streams (both muxed and video-only for higher resolutions)
                            val isMpeg = formatUpper.contains("MP4") || mimeType.contains("video/mp4")

                            if (isValidHttpUrl(streamUrl) && isMpeg && !isAuthBlocked) {
                                // Deduplicate by quality label; prefer muxed over video-only
                                val qualityKey = quality
                                if (qualityKey in seenVideoQualities && videoOnly) continue
                                seenVideoQualities.add(qualityKey)

                                val label = if (videoOnly) "$quality (Video Only)" else quality

                                formatsList.add(
                                    MediaFormat(
                                        formatId = "yt_v_$i",
                                        resolutionOrQuality = label,
                                        fileExtension = "mp4",
                                        approxSize = stream.optString("contentLength").toLongOrNull()
                                            ?.let { formatFileSize(it) },
                                        mediaType = MediaType.VIDEO,
                                        directUrl = cleanMediaUrl(streamUrl)
                                    )
                                )
                            }
                        }
                    }

                    // Collect ALL audio streams, not just the first one
                    val audioStreams = json.optJSONArray("audioStreams")
                    val seenAudioQualities = mutableSetOf<String>()
                    if (audioStreams != null) {
                        for (i in 0 until audioStreams.length()) {
                            val stream = audioStreams.optJSONObject(i) ?: continue
                            val streamUrl = stream.optString("url")
                            val quality = stream.optString("quality", "Audio")
                            val mimeType = stream.optString("mimeType", "").lowercase()
                            val bitrate = stream.optInt("bitrate", 0)
                            val isAuthBlocked = streamUrl.contains("odycdn.com")
                            val ext = if (mimeType.contains("mp4") || mimeType.contains("m4a")) "m4a" else "webm"

                            if (isValidHttpUrl(streamUrl) && !isAuthBlocked) {
                                val bitrateLabel = if (bitrate > 0) "${bitrate / 1000}kbps" else quality
                                if (!seenAudioQualities.add(bitrateLabel)) continue

                                formatsList.add(
                                    MediaFormat(
                                        formatId = "yt_a_$i",
                                        resolutionOrQuality = "Audio ($bitrateLabel)",
                                        fileExtension = ext,
                                        approxSize = "Audio",
                                        mediaType = MediaType.AUDIO,
                                        directUrl = cleanMediaUrl(streamUrl)
                                    )
                                )
                            }
                        }
                    }

                    // Sort: videos by resolution descending, then audio by bitrate descending
                    val sortedVideos = formatsList.filter { it.mediaType == MediaType.VIDEO }
                        .sortedByDescending { extractResolutionNumber(it.resolutionOrQuality) }
                    val sortedAudio = formatsList.filter { it.mediaType == MediaType.AUDIO }
                        .sortedByDescending { extractBitrateNumber(it.resolutionOrQuality) }
                    formatsList.clear()
                    formatsList.addAll(sortedVideos)
                    formatsList.addAll(sortedAudio)

                    if (formatsList.isNotEmpty()) {
                        return MediaInfo(
                            sourceUrl = sourceUrl,
                            title = title,
                            author = uploader,
                            duration = durationStr,
                            thumbnailUrl = thumbnail,
                            platform = PlatformType.YOUTUBE,
                            formats = formatsList
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun tryTikTokExtraction(url: String): MediaInfo? {
        try {
            val apiUrl = "https://www.tikwm.com/api/?url=" + URLEncoder.encode(url, "UTF-8")
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
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
                    val wmPlayUrl = data.optString("wmplay")
                    val music = data.optString("music")
                    val durationSec = data.optLong("duration", 0L)

                    val formats = mutableListOf<MediaFormat>()
                    if (isValidHttpUrl(playUrl)) {
                        formats.add(
                            MediaFormat(
                                formatId = "tt_hd_no_wm",
                                resolutionOrQuality = "HD (No Watermark)",
                                fileExtension = "mp4",
                                approxSize = null,
                                mediaType = MediaType.VIDEO,
                                directUrl = playUrl
                            )
                        )
                    } else if (isValidHttpUrl(wmPlayUrl)) {
                        formats.add(
                            MediaFormat(
                                formatId = "tt_wm",
                                resolutionOrQuality = "Standard MP4",
                                fileExtension = "mp4",
                                approxSize = null,
                                mediaType = MediaType.VIDEO,
                                directUrl = wmPlayUrl
                            )
                        )
                    }

                    if (isValidHttpUrl(music)) {
                        formats.add(
                            MediaFormat(
                                formatId = "tt_music",
                                resolutionOrQuality = "Original Audio",
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
        } catch (_: Exception) {}
        return null
    }

    private fun createDirectStreamMedia(
        sourceUrl: String,
        directStreamUrl: String,
        platform: PlatformType
    ): MediaInfo {
        val formats = listOf(
            MediaFormat(
                formatId = "sniffed_media",
                resolutionOrQuality = "Captured Stream",
                fileExtension = "mp4",
                approxSize = null,
                mediaType = MediaType.VIDEO,
                directUrl = directStreamUrl
            )
        )

        return MediaInfo(
            sourceUrl = sourceUrl,
            title = "${platform.displayName} Video",
            author = "@${platform.displayName.lowercase()}_creator",
            duration = "Stream",
            thumbnailUrl = getPlatformThumbnail(sourceUrl, platform),
            platform = platform,
            formats = formats
        )
    }

    fun extractYouTubeId(url: String): String? {
        val matcher = Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/)([a-zA-Z0-9_-]{11})").matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "HD"
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
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
            else -> "https://images.unsplash.com/photo-1518770660439-4636190af475?w=600&auto=format&fit=crop&q=80"
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
}

