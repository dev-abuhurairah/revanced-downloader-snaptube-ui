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
import java.io.IOException
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

    // High-availability Cobalt API instances for universal extraction
    private val COBALT_INSTANCES = listOf(
        "https://api.cobalt.tools",
        "https://cobalt.api.scav.top",
        "https://api.wuk.sh",
        "https://cobalt-api.kwiatekm.tokyo"
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

        // 1. If an actual stream was captured from the in-app browser
        if (!directStreamUrl.isNullOrBlank() && isValidHttpUrl(directStreamUrl)) {
            val directMedia = createDirectStreamMedia(queryUrl, directStreamUrl, platform)
            return@withContext ResolveResult.Success(directMedia)
        }

        // 2. YouTube extraction via Piped / Cobalt
        if (platform == PlatformType.YOUTUBE) {
            val ytId = extractYouTubeId(queryUrl)
            if (ytId != null) {
                val pipedResult = tryPipedExtraction(ytId, queryUrl)
                if (pipedResult != null && pipedResult.formats.isNotEmpty()) {
                    return@withContext ResolveResult.Success(pipedResult)
                }
            }
            val cobaltResult = tryCobaltExtraction(queryUrl, platform)
            if (cobaltResult != null && cobaltResult.formats.isNotEmpty()) {
                return@withContext ResolveResult.Success(cobaltResult)
            }
        }

        // 3. TikTok extraction via TikWM
        if (platform == PlatformType.TIKTOK) {
            val tiktokResult = tryTikTokExtraction(queryUrl)
            if (tiktokResult != null && tiktokResult.formats.isNotEmpty()) {
                return@withContext ResolveResult.Success(tiktokResult)
            }
        }

        // 4. Social media extraction via Cobalt (Instagram, FB, X, etc.)
        val socialResult = tryCobaltExtraction(queryUrl, platform)
        if (socialResult != null && socialResult.formats.isNotEmpty()) {
            return@withContext ResolveResult.Success(socialResult)
        }

        // 5. No fake fallbacks! Return honest failure
        ResolveResult.Failure(
            errorType = ResolveErrorType.RESOLVER_UNAVAILABLE,
            userMessage = "Could not extract video stream automatically. Open in the in-app browser to play and download.",
            technicalDetails = "All public resolution endpoints failed or returned no stream for platform: ${platform.displayName}"
        )
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
                    val videoStreams = json.optJSONArray("videoStreams")
                    if (videoStreams != null) {
                        for (i in 0 until videoStreams.length()) {
                            val stream = videoStreams.optJSONObject(i) ?: continue
                            val quality = stream.optString("quality", "720p")
                            val streamUrl = stream.optString("url")
                            val formatUpper = stream.optString("format", "").uppercase()
                            val mimeType = stream.optString("mimeType", "").lowercase()
                            val isMpeg = formatUpper.contains("MP4") || mimeType.contains("video/mp4")

                            if (isValidHttpUrl(streamUrl) && isMpeg) {
                                formatsList.add(
                                    MediaFormat(
                                        formatId = "yt_v_$i",
                                        resolutionOrQuality = quality,
                                        fileExtension = "mp4",
                                        approxSize = stream.optString("approxSize").takeIf { it.isNotBlank() },
                                        mediaType = MediaType.VIDEO,
                                        directUrl = streamUrl
                                    )
                                )
                            }
                        }
                    }

                    // Only add real audio stream if genuinely present
                    val audioStreams = json.optJSONArray("audioStreams")
                    if (audioStreams != null) {
                        for (i in 0 until audioStreams.length()) {
                            val stream = audioStreams.optJSONObject(i) ?: continue
                            val streamUrl = stream.optString("url")
                            val quality = stream.optString("quality", "Audio")
                            val mimeType = stream.optString("mimeType", "").lowercase()
                            val ext = if (mimeType.contains("mp4") || mimeType.contains("m4a")) "m4a" else "mp3"

                            if (isValidHttpUrl(streamUrl)) {
                                formatsList.add(
                                    MediaFormat(
                                        formatId = "yt_a_$i",
                                        resolutionOrQuality = "Audio ($quality)",
                                        fileExtension = ext,
                                        approxSize = "Audio",
                                        mediaType = MediaType.AUDIO,
                                        directUrl = streamUrl
                                    )
                                )
                                break // Add best single audio stream
                            }
                        }
                    }

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
            } catch (_: Exception) {
                // Continue to next mirror
            }
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

                    // Only include music if a valid audio stream exists
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
        } catch (_: Exception) {
            // Failure falls back
        }
        return null
    }

    private fun tryCobaltExtraction(url: String, platform: PlatformType): MediaInfo? {
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        for (instance in COBALT_INSTANCES) {
            try {
                val payload = JSONObject().apply {
                    put("url", url)
                    put("videoQuality", "1080")
                    put("downloadMode", "auto")
                }
                val request = Request.Builder()
                    .url("$instance/api/json")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val status = json.optString("status")

                    var videoUrl: String? = null
                    val filename = json.optString("filename").takeIf { it.isNotBlank() }

                    if (status == "stream" || status == "tunnel" || status == "redirect") {
                        videoUrl = json.optString("url")
                    } else if (status == "picker") {
                        val pickerArray = json.optJSONArray("picker")
                        if (pickerArray != null && pickerArray.length() > 0) {
                            for (p in 0 until pickerArray.length()) {
                                val item = pickerArray.optJSONObject(p) ?: continue
                                val itemUrl = item.optString("url")
                                if (isValidHttpUrl(itemUrl)) {
                                    videoUrl = itemUrl
                                    break
                                }
                            }
                        }
                    }

                    if (!videoUrl.isNullOrBlank() && isValidHttpUrl(videoUrl)) {
                        val cleanTitle = filename?.replace(Regex("\\.(mp4|mp3|mkv|webm)$", RegexOption.IGNORE_CASE), "")
                            ?: "${platform.displayName} Video"

                        // Single honest format: do NOT fake multiple qualities or fake MP3 conversion
                        val formats = listOf(
                            MediaFormat(
                                formatId = "cobalt_best",
                                resolutionOrQuality = "Original Video",
                                fileExtension = "mp4",
                                approxSize = null,
                                mediaType = MediaType.VIDEO,
                                directUrl = videoUrl
                            )
                        )

                        return MediaInfo(
                            sourceUrl = url,
                            title = cleanTitle,
                            author = "@${platform.displayName.lowercase()}_creator",
                            duration = "HD",
                            thumbnailUrl = getPlatformThumbnail(url, platform),
                            platform = platform,
                            formats = formats
                        )
                    }
                }
            } catch (_: Exception) {
                // Try next mirror
            }
        }
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
}
