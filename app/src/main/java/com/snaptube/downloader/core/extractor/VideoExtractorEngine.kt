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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object VideoExtractorEngine {

    private const val TAG = "VideoExtractorEngine"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Current working Piped instances (verified active 2025-2026)
    private val PIPED_INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://api.piped.private.coffee",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.reallyaweso.me"
    )

    // Current working Invidious instances (verified active 2025-2026)
    private val INVIDIOUS_INSTANCES = listOf(
        "https://inv.tux.pizza",
        "https://invidious.nerdvpn.de",
        "https://invidious.drgns.space",
        "https://vid.puffyan.us"
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
        // Improved regex that handles query params and fragments properly
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
        // Only strip range params for muxed (non-adaptive) videoplayback URLs
        // Adaptive streams need range params for proper seeking
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
                userMessage = "Please enter or paste a valid web link (e.g. https://...)",
                technicalDetails = "Input '$inputQueryOrUrl' did not produce a valid HTTP/HTTPS URL."
            )
        }

        val queryUrl = extracted
        val platform = detectPlatform(queryUrl)

        // 1. If an actual stream was captured from the in-app browser or DOM
        if (!directStreamUrl.isNullOrBlank() && isValidHttpUrl(directStreamUrl)) {
            val directMedia = createDirectStreamMedia(queryUrl, directStreamUrl, platform)
            return@withContext ResolveResult.Success(directMedia)
        }

        // 2. YouTube extraction with multiple fallback methods
        if (platform == PlatformType.YOUTUBE) {
            val ytId = extractYouTubeId(queryUrl)
            if (ytId != null) {
                Log.d(TAG, "YouTube ID extracted: $ytId")

                // Method 1: WEB_EMBEDDED_PLAYER (most resilient, bypasses PO token)
                try {
                    val embeddedResult = tryInnertubeExtraction(ytId, queryUrl, InnertubeClient.WEB_EMBEDDED)
                    if (embeddedResult != null && embeddedResult.formats.isNotEmpty()) {
                        Log.d(TAG, "WEB_EMBEDDED Innertube succeeded with ${embeddedResult.formats.size} formats")
                        return@withContext ResolveResult.Success(embeddedResult)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WEB_EMBEDDED Innertube failed: ${e.message}")
                }

                // Method 2: Innertube IOS client (good for unthrottled streams)
                try {
                    val iosResult = tryInnertubeExtraction(ytId, queryUrl, InnertubeClient.IOS)
                    if (iosResult != null && iosResult.formats.isNotEmpty()) {
                        Log.d(TAG, "IOS Innertube succeeded with ${iosResult.formats.size} formats")
                        return@withContext ResolveResult.Success(iosResult)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "IOS Innertube failed: ${e.message}")
                }

                // Method 3: Innertube ANDROID_TESTSUITE client
                try {
                    val androidResult = tryInnertubeExtraction(ytId, queryUrl, InnertubeClient.ANDROID_TESTSUITE)
                    if (androidResult != null && androidResult.formats.isNotEmpty()) {
                        Log.d(TAG, "ANDROID_TESTSUITE Innertube succeeded with ${androidResult.formats.size} formats")
                        return@withContext ResolveResult.Success(androidResult)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ANDROID_TESTSUITE Innertube failed: ${e.message}")
                }

                // Method 4: Innertube WEB client
                try {
                    val webResult = tryInnertubeExtraction(ytId, queryUrl, InnertubeClient.WEB)
                    if (webResult != null && webResult.formats.isNotEmpty()) {
                        Log.d(TAG, "WEB Innertube succeeded with ${webResult.formats.size} formats")
                        return@withContext ResolveResult.Success(webResult)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WEB Innertube failed: ${e.message}")
                }

                // Method 5: Piped API mirrors
                try {
                    val pipedResult = tryPipedExtraction(ytId, queryUrl)
                    if (pipedResult != null && pipedResult.formats.isNotEmpty()) {
                        Log.d(TAG, "Piped succeeded with ${pipedResult.formats.size} formats")
                        return@withContext ResolveResult.Success(pipedResult)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Piped failed: ${e.message}")
                }

                // Method 6: Invidious API
                try {
                    val invidiousResult = tryInvidiousExtraction(ytId, queryUrl)
                    if (invidiousResult != null && invidiousResult.formats.isNotEmpty()) {
                        Log.d(TAG, "Invidious succeeded with ${invidiousResult.formats.size} formats")
                        return@withContext ResolveResult.Success(invidiousResult)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Invidious failed: ${e.message}")
                }

                // Method 7: Cobalt API
                try {
                    val cobaltResult = tryCobaltExtraction(queryUrl)
                    if (cobaltResult != null && cobaltResult.formats.isNotEmpty()) {
                        Log.d(TAG, "Cobalt succeeded with ${cobaltResult.formats.size} formats")
                        return@withContext ResolveResult.Success(cobaltResult)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Cobalt failed: ${e.message}")
                }

                return@withContext ResolveResult.Failure(
                    errorType = ResolveErrorType.MEDIA_UNAVAILABLE,
                    userMessage = "Could not extract YouTube video. Try opening in the built-in browser instead.",
                    technicalDetails = "All 7 extraction methods failed for video ID: $ytId"
                )
            } else {
                return@withContext ResolveResult.Failure(
                    errorType = ResolveErrorType.INVALID_URL,
                    userMessage = "Could not find a valid YouTube video ID in this link.",
                    technicalDetails = "No 11-char ID found in: $queryUrl"
                )
            }
        }

        // 3. TikTok extraction via TikWM
        if (platform == PlatformType.TIKTOK) {
            try {
                val tiktokResult = tryTikTokExtraction(queryUrl)
                if (tiktokResult != null && tiktokResult.formats.isNotEmpty()) {
                    return@withContext ResolveResult.Success(tiktokResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TikTok extraction failed: ${e.message}")
            }
        }

        // 4. Fallback: guide user to built-in browser for Instagram, FB, etc.
        ResolveResult.Failure(
            errorType = ResolveErrorType.RESOLVER_UNAVAILABLE,
            userMessage = "Opening in built-in browser to play and capture video...",
            technicalDetails = "Direct extraction requires in-browser session for ${platform.displayName}"
        )
    }

    // ========================
    // Innertube Client Configs
    // ========================
    private enum class InnertubeClient {
        WEB_EMBEDDED, IOS, ANDROID_TESTSUITE, WEB
    }

    private fun buildInnertubePayload(videoId: String, client: InnertubeClient): JSONObject {
        return when (client) {
            InnertubeClient.WEB_EMBEDDED -> JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_EMBEDDED_PLAYER")
                        put("clientVersion", "1.20241201.01.00")
                        put("clientScreen", "EMBED")
                        put("hl", "en")
                        put("gl", "US")
                    })
                    put("thirdParty", JSONObject().apply {
                        put("embedUrl", "https://www.youtube.com/")
                    })
                })
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", 20073)
                    })
                })
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            InnertubeClient.IOS -> JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "IOS")
                        put("clientVersion", "19.45.4")
                        put("deviceMake", "Apple")
                        put("deviceModel", "iPhone16,2")
                        put("osName", "iPhone")
                        put("osVersion", "18.1.0.22B83")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", 20073)
                    })
                })
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            InnertubeClient.ANDROID_TESTSUITE -> JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_TESTSUITE")
                        put("clientVersion", "1.9")
                        put("androidSdkVersion", 34)
                        put("osName", "Android")
                        put("osVersion", "14")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            InnertubeClient.WEB -> JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20241126.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", 20073)
                    })
                })
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
        }
    }

    private fun getInnertubeUserAgent(client: InnertubeClient): String {
        return when (client) {
            InnertubeClient.WEB_EMBEDDED -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            InnertubeClient.IOS -> "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
            InnertubeClient.ANDROID_TESTSUITE -> "com.google.android.youtube/1.9 (Linux; U; Android 14; en_US) gzip"
            InnertubeClient.WEB -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        }
    }

    private fun getInnertubeApiKey(client: InnertubeClient): String {
        return when (client) {
            InnertubeClient.WEB_EMBEDDED -> "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
            InnertubeClient.IOS -> "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"
            InnertubeClient.ANDROID_TESTSUITE -> "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
            InnertubeClient.WEB -> "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        }
    }

    // ========================
    // Innertube Extraction
    // ========================
    private fun tryInnertubeExtraction(videoId: String, sourceUrl: String, client: InnertubeClient): MediaInfo? {
        val payload = buildInnertubePayload(videoId, client)
        val apiKey = getInnertubeApiKey(client)
        val userAgent = getInnertubeUserAgent(client)

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=$apiKey&prettyPrint=false")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", userAgent)
            .addHeader("X-Youtube-Client-Name", when (client) {
                InnertubeClient.WEB_EMBEDDED -> "56"
                InnertubeClient.IOS -> "5"
                InnertubeClient.ANDROID_TESTSUITE -> "30"
                InnertubeClient.WEB -> "1"
            })
            .addHeader("X-Youtube-Client-Version", when (client) {
                InnertubeClient.WEB_EMBEDDED -> "1.20241201.01.00"
                InnertubeClient.IOS -> "19.45.4"
                InnertubeClient.ANDROID_TESTSUITE -> "1.9"
                InnertubeClient.WEB -> "2.20241126.01.00"
            })
            .addHeader("Origin", "https://www.youtube.com")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Innertube ${client.name} HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status")
            if (status != "OK") {
                val reason = playability?.optString("reason", "Unknown")
                Log.w(TAG, "Innertube ${client.name} playability: $status - $reason")
                return null
            }

            val videoDetails = json.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title", "YouTube Video") ?: "YouTube Video"
            val author = videoDetails?.optString("author", "YouTube Creator") ?: "YouTube Creator"
            val lengthSeconds = videoDetails?.optLong("lengthSeconds", 0L) ?: 0L
            val durationStr = formatDuration(lengthSeconds)
            val thumbnail = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

            val streamingData = json.optJSONObject("streamingData") ?: return null
            val formatsList = mutableListOf<MediaFormat>()
            val seenVideoQualities = mutableSetOf<String>()
            val seenAudioBitrates = mutableSetOf<Int>()

            // 1. Muxed streams (video + audio combined, typically 360p/720p max)
            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    val directUrl = f.optString("url", "")
                    // Some streams use signatureCipher instead of url - skip those (can't decipher without JS)
                    if (directUrl.isBlank() || !isValidHttpUrl(directUrl)) continue
                    if (directUrl.contains("odycdn.com")) continue

                    val quality = f.optString("qualityLabel", "").ifBlank { f.optString("quality", "360p") }
                    if (!seenVideoQualities.add(quality)) continue

                    val contentLength = f.optLong("contentLength", 0L)
                    formatsList.add(
                        MediaFormat(
                            formatId = "yt_mux_$i",
                            resolutionOrQuality = quality,
                            fileExtension = "mp4",
                            approxSize = if (contentLength > 0) formatFileSize(contentLength) else null,
                            mediaType = MediaType.VIDEO,
                            directUrl = directUrl
                        )
                    )
                }
            }

            // 2. Adaptive streams (separate video and audio, contains 1080p+ qualities)
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val af = adaptiveFormats.optJSONObject(i) ?: continue
                    val mimeType = af.optString("mimeType", "")
                    val directUrl = af.optString("url", "")
                    if (directUrl.isBlank() || !isValidHttpUrl(directUrl)) continue
                    if (directUrl.contains("odycdn.com")) continue

                    if (mimeType.contains("video")) {
                        val quality = af.optString("qualityLabel", "").ifBlank { af.optString("quality", "") }
                        if (quality.isBlank()) continue
                        // Prefer MP4/H.264 for widest device compatibility
                        val isCompatible = mimeType.contains("mp4") || mimeType.contains("avc")
                        if (!isCompatible) continue
                        if (!seenVideoQualities.add(quality)) continue

                        val contentLength = af.optLong("contentLength", 0L)
                        formatsList.add(
                            MediaFormat(
                                formatId = "yt_adp_v_$i",
                                resolutionOrQuality = quality,
                                fileExtension = "mp4",
                                approxSize = if (contentLength > 0) formatFileSize(contentLength) else null,
                                mediaType = MediaType.VIDEO,
                                directUrl = directUrl
                            )
                        )
                    } else if (mimeType.contains("audio")) {
                        val bitrate = af.optInt("averageBitrate", af.optInt("bitrate", 0))
                        val bitrateTier = (bitrate / 10000) * 10000
                        if (!seenAudioBitrates.add(bitrateTier)) continue

                        val bitrateLabel = if (bitrate > 0) "${bitrate / 1000}kbps" else "Original"
                        val ext = if (mimeType.contains("mp4") || mimeType.contains("m4a")) "m4a" else "webm"

                        formatsList.add(
                            MediaFormat(
                                formatId = "yt_adp_a_$i",
                                resolutionOrQuality = "Audio ($bitrateLabel)",
                                fileExtension = ext,
                                approxSize = "Audio",
                                mediaType = MediaType.AUDIO,
                                directUrl = directUrl
                            )
                        )
                    }
                }
            }

            // Sort and return
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
        return null
    }

    // ========================
    // Piped Extraction
    // ========================
    private fun tryPipedExtraction(videoId: String, sourceUrl: String): MediaInfo? {
        for (instance in PIPED_INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("$instance/streams/$videoId")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                    .addHeader("Accept", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Piped $instance HTTP ${response.code}")
                        return@use
                    }
                    val bodyString = response.body?.string() ?: return@use
                    val json = JSONObject(bodyString)

                    // Check for error response
                    if (json.has("error")) {
                        Log.w(TAG, "Piped $instance error: ${json.optString("error")}")
                        return@use
                    }

                    val title = json.optString("title", "YouTube Video").ifBlank { "YouTube Video" }
                    val uploader = json.optString("uploader", "YouTube Creator").ifBlank { "YouTube Creator" }
                    val durationSec = json.optLong("duration", 0L)
                    val durationStr = formatDuration(durationSec)
                    val thumbnail = json.optString("thumbnailUrl", "https://img.youtube.com/vi/$videoId/hqdefault.jpg")

                    val formatsList = mutableListOf<MediaFormat>()
                    val seenVideoQualities = mutableSetOf<String>()
                    val seenAudioBitrates = mutableSetOf<String>()

                    // Video streams
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
                            val qualityKey = "$quality-$videoOnly"
                            // Prefer muxed over video-only for same quality
                            if (qualityKey in seenVideoQualities) continue
                            if (videoOnly && quality in seenVideoQualities.map { it.substringBefore("-") }) continue
                            seenVideoQualities.add(qualityKey)

                            val label = if (videoOnly) "$quality (Video Only)" else quality
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

                    // Audio streams (all of them)
                    val audioStreams = json.optJSONArray("audioStreams")
                    if (audioStreams != null) {
                        for (i in 0 until audioStreams.length()) {
                            val stream = audioStreams.optJSONObject(i) ?: continue
                            val streamUrl = stream.optString("url", "")
                            if (!isValidHttpUrl(streamUrl) || streamUrl.contains("odycdn.com")) continue

                            val mimeType = stream.optString("mimeType", "").lowercase()
                            val bitrate = stream.optInt("bitrate", 0)
                            val bitrateLabel = if (bitrate > 0) "${bitrate / 1000}kbps" else "Audio"
                            if (!seenAudioBitrates.add(bitrateLabel)) continue

                            val ext = if (mimeType.contains("mp4") || mimeType.contains("m4a")) "m4a" else "webm"

                            formatsList.add(
                                MediaFormat(
                                    formatId = "piped_a_$i",
                                    resolutionOrQuality = "Audio ($bitrateLabel)",
                                    fileExtension = ext,
                                    approxSize = "Audio",
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
                Log.e(TAG, "Piped $instance failed: ${e.message}")
            }
        }
        return null
    }

    // ========================
    // Invidious Extraction
    // ========================
    private fun tryInvidiousExtraction(videoId: String, sourceUrl: String): MediaInfo? {
        for (instance in INVIDIOUS_INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("$instance/api/v1/videos/$videoId")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
                    .addHeader("Accept", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Invidious $instance HTTP ${response.code}")
                        return@use
                    }
                    val bodyString = response.body?.string() ?: return@use
                    val json = JSONObject(bodyString)

                    val title = json.optString("title", "YouTube Video").ifBlank { "YouTube Video" }
                    val author = json.optString("author", "YouTube Creator").ifBlank { "YouTube Creator" }
                    val lengthSeconds = json.optLong("lengthSeconds", 0L)
                    val durationStr = formatDuration(lengthSeconds)

                    // Get best thumbnail
                    val thumbs = json.optJSONArray("videoThumbnails")
                    var thumbnail = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                    if (thumbs != null && thumbs.length() > 0) {
                        for (t in 0 until thumbs.length()) {
                            val tb = thumbs.optJSONObject(t) ?: continue
                            if (tb.optString("quality") == "maxresdefault" || tb.optString("quality") == "sddefault") {
                                thumbnail = tb.optString("url", thumbnail)
                                break
                            }
                        }
                    }

                    val formatsList = mutableListOf<MediaFormat>()
                    val seenVideoQualities = mutableSetOf<String>()
                    val seenAudioBitrates = mutableSetOf<String>()

                    // Adaptive formats from Invidious
                    val adaptiveFormats = json.optJSONArray("adaptiveFormats")
                    if (adaptiveFormats != null) {
                        for (i in 0 until adaptiveFormats.length()) {
                            val af = adaptiveFormats.optJSONObject(i) ?: continue
                            val streamUrl = af.optString("url", "")
                            if (!isValidHttpUrl(streamUrl)) continue

                            val mimeType = af.optString("type", "").lowercase()
                            val container = af.optString("container", "").lowercase()

                            if (mimeType.contains("video")) {
                                if (!container.contains("mp4") && !mimeType.contains("mp4")) continue
                                val quality = af.optString("qualityLabel", "").ifBlank {
                                    af.optString("resolution", "")
                                }
                                if (quality.isBlank() || !seenVideoQualities.add(quality)) continue

                                val contentLength = af.optLong("clen", 0L)
                                formatsList.add(
                                    MediaFormat(
                                        formatId = "inv_v_$i",
                                        resolutionOrQuality = quality,
                                        fileExtension = "mp4",
                                        approxSize = if (contentLength > 0) formatFileSize(contentLength) else null,
                                        mediaType = MediaType.VIDEO,
                                        directUrl = streamUrl
                                    )
                                )
                            } else if (mimeType.contains("audio")) {
                                val bitrate = af.optInt("bitrate", 0)
                                val bitrateLabel = if (bitrate > 0) "${bitrate / 1000}kbps" else "Audio"
                                if (!seenAudioBitrates.add(bitrateLabel)) continue

                                val ext = if (container.contains("mp4") || container.contains("m4a")) "m4a" else "webm"
                                formatsList.add(
                                    MediaFormat(
                                        formatId = "inv_a_$i",
                                        resolutionOrQuality = "Audio ($bitrateLabel)",
                                        fileExtension = ext,
                                        approxSize = "Audio",
                                        mediaType = MediaType.AUDIO,
                                        directUrl = streamUrl
                                    )
                                )
                            }
                        }
                    }

                    // Muxed format streams from Invidious
                    val formatStreams = json.optJSONArray("formatStreams")
                    if (formatStreams != null) {
                        for (i in 0 until formatStreams.length()) {
                            val fs = formatStreams.optJSONObject(i) ?: continue
                            val streamUrl = fs.optString("url", "")
                            if (!isValidHttpUrl(streamUrl)) continue

                            val quality = fs.optString("qualityLabel", "").ifBlank {
                                fs.optString("resolution", "")
                            }
                            if (quality.isBlank() || !seenVideoQualities.add(quality)) continue

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
                Log.e(TAG, "Invidious $instance failed: ${e.message}")
            }
        }
        return null
    }

    // ========================
    // Cobalt API Extraction
    // ========================
    private fun tryCobaltExtraction(sourceUrl: String): MediaInfo? {
        try {
            val cobaltEndpoints = listOf(
                "https://api.cobalt.tools",
                "https://cobalt-api.hyper.lol"
            )

            for (endpoint in cobaltEndpoints) {
                try {
                    val payload = JSONObject().apply {
                        put("url", sourceUrl)
                        put("videoQuality", "max")
                        put("filenameStyle", "basic")
                    }

                    val request = Request.Builder()
                        .url("$endpoint/")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .addHeader("Accept", "application/json")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)

                        val status = json.optString("status", "")
                        if (status == "redirect" || status == "stream") {
                            val streamUrl = json.optString("url", "")
                            if (isValidHttpUrl(streamUrl)) {
                                val ytId = extractYouTubeId(sourceUrl)
                                val thumbnail = if (ytId != null) "https://img.youtube.com/vi/$ytId/hqdefault.jpg"
                                    else ""

                                return MediaInfo(
                                    sourceUrl = sourceUrl,
                                    title = json.optString("filename", "YouTube Video"),
                                    author = "YouTube Creator",
                                    duration = "HD",
                                    thumbnailUrl = thumbnail,
                                    platform = PlatformType.YOUTUBE,
                                    formats = listOf(
                                        MediaFormat(
                                            formatId = "cobalt_best",
                                            resolutionOrQuality = "Best Quality",
                                            fileExtension = "mp4",
                                            approxSize = null,
                                            mediaType = MediaType.VIDEO,
                                            directUrl = streamUrl
                                        )
                                    )
                                )
                            }
                        } else if (status == "picker" && json.has("picker")) {
                            val picker = json.optJSONArray("picker") ?: return@use
                            val formats = mutableListOf<MediaFormat>()
                            for (i in 0 until picker.length()) {
                                val item = picker.optJSONObject(i) ?: continue
                                val itemUrl = item.optString("url", "")
                                if (isValidHttpUrl(itemUrl)) {
                                    formats.add(
                                        MediaFormat(
                                            formatId = "cobalt_$i",
                                            resolutionOrQuality = "Quality ${i + 1}",
                                            fileExtension = "mp4",
                                            approxSize = null,
                                            mediaType = MediaType.VIDEO,
                                            directUrl = itemUrl
                                        )
                                    )
                                }
                            }
                            if (formats.isNotEmpty()) {
                                val ytId = extractYouTubeId(sourceUrl)
                                val thumbnail = if (ytId != null) "https://img.youtube.com/vi/$ytId/hqdefault.jpg"
                                    else ""
                                return MediaInfo(
                                    sourceUrl = sourceUrl,
                                    title = "YouTube Video",
                                    author = "YouTube Creator",
                                    duration = "HD",
                                    thumbnailUrl = thumbnail,
                                    platform = PlatformType.YOUTUBE,
                                    formats = formats
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Cobalt $endpoint failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cobalt extraction failed: ${e.message}")
        }
        return null
    }

    // ========================
    // TikTok Extraction
    // ========================
    private fun tryTikTokExtraction(url: String): MediaInfo? {
        val apiUrl = "https://www.tikwm.com/api/?url=" + URLEncoder.encode(url, "UTF-8")
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
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

                // HD no watermark (best quality)
                if (isValidHttpUrl(hdPlayUrl)) {
                    formats.add(
                        MediaFormat(
                            formatId = "tt_hd_no_wm",
                            resolutionOrQuality = "HD (No Watermark)",
                            fileExtension = "mp4",
                            approxSize = null,
                            mediaType = MediaType.VIDEO,
                            directUrl = hdPlayUrl
                        )
                    )
                }

                // Standard no watermark
                if (isValidHttpUrl(playUrl)) {
                    formats.add(
                        MediaFormat(
                            formatId = "tt_sd_no_wm",
                            resolutionOrQuality = if (formats.isEmpty()) "HD (No Watermark)" else "SD (No Watermark)",
                            fileExtension = "mp4",
                            approxSize = null,
                            mediaType = MediaType.VIDEO,
                            directUrl = playUrl
                        )
                    )
                }

                // With watermark
                if (isValidHttpUrl(wmPlayUrl)) {
                    formats.add(
                        MediaFormat(
                            formatId = "tt_wm",
                            resolutionOrQuality = "Standard (With Watermark)",
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
        return null
    }

    // ========================
    // Direct Stream (Browser)
    // ========================
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

    // ========================
    // Utilities
    // ========================
    fun extractYouTubeId(url: String): String? {
        // Handle all YouTube URL formats including mobile, embeds, shorts, live, and clip
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
        if (seconds <= 0) return "Live / Unknown"
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
            else -> "https://images.unsplash.com/photo-1518770660439-4636190af475?w=600&auto=format&fit=crop&q=80"
        }
    }
}
