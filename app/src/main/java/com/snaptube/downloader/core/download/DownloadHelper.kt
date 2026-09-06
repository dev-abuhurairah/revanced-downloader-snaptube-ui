package com.snaptube.downloader.core.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.snaptube.downloader.core.extractor.VideoExtractorEngine
import com.snaptube.downloader.core.storage.MediaDestination
import com.snaptube.downloader.core.storage.StorageManager
import com.snaptube.downloader.data.model.DownloadItem
import com.snaptube.downloader.data.model.DownloadStatus
import com.snaptube.downloader.data.model.MediaFormat
import com.snaptube.downloader.data.model.MediaInfo
import com.snaptube.downloader.data.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

object DownloadHelper {

    private const val TAG = "DownloadHelper"

    private val _downloadList = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloadList: StateFlow<List<DownloadItem>> = _downloadList.asStateFlow()

    private var appContext: Context? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val activeMediaInfoCache = ConcurrentHashMap<Long, MediaInfo>()
    private val activeJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val activeCalls = ConcurrentHashMap<Long, okhttp3.Call>()
    private val activeDestinations = ConcurrentHashMap<Long, MediaDestination>()

    // High throughput client with extended timeouts
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        loadFromPrefs(app)
    }

    private fun loadFromPrefs(context: Context) {
        try {
            val prefs = context.getSharedPreferences("vidsnap_downloads_prefs", Context.MODE_PRIVATE)
            val jsonString = prefs.getString("saved_downloads", null) ?: return
            val jsonArray = JSONArray(jsonString)
            val items = mutableListOf<DownloadItem>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val formatObj = obj.optJSONObject("format")
                val mediaFormat = if (formatObj != null) {
                    MediaFormat(
                        formatId = formatObj.optString("formatId", "default"),
                        resolutionOrQuality = formatObj.optString("resolutionOrQuality", "Original"),
                        fileExtension = formatObj.optString("fileExtension", "mp4"),
                        approxSize = formatObj.optString("approxSize").takeIf { it.isNotBlank() },
                        mediaType = runCatching {
                            MediaType.valueOf(formatObj.optString("mediaType", "VIDEO"))
                        }.getOrDefault(MediaType.VIDEO),
                        directUrl = formatObj.optString("directUrl").takeIf { it.isNotBlank() }
                    )
                } else {
                    MediaFormat("default", "Original", "mp4")
                }

                val statusStr = obj.optString("status", DownloadStatus.COMPLETED.name)
                var status = runCatching { DownloadStatus.valueOf(statusStr) }.getOrDefault(DownloadStatus.COMPLETED)
                val localPath = obj.optString("localFilePath").takeIf { it.isNotBlank() }

                if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PENDING) {
                    if (StorageManager.isMediaAvailable(context, localPath)) {
                        status = DownloadStatus.COMPLETED
                    } else {
                        status = DownloadStatus.FAILED
                    }
                }

                items.add(
                    DownloadItem(
                        id = obj.optLong("id", System.currentTimeMillis()),
                        title = obj.optString("title", "Saved Video"),
                        sourceUrl = obj.optString("sourceUrl", ""),
                        thumbnailUrl = obj.optString("thumbnailUrl", ""),
                        localFilePath = localPath,
                        format = mediaFormat,
                        progress = if (status == DownloadStatus.COMPLETED) 100 else obj.optInt("progress", 0),
                        totalBytes = obj.optLong("totalBytes", 0L),
                        downloadedBytes = obj.optLong("downloadedBytes", 0L),
                        status = status,
                        downloadSpeed = "",
                        errorMessage = obj.optString("errorMessage", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            _downloadList.value = items
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load downloads from prefs", e)
        }
    }

    private fun persistDownloads() {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences("vidsnap_downloads_prefs", Context.MODE_PRIVATE)
            val jsonArray = JSONArray()
            val snapshot = _downloadList.value.toList()
            snapshot.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("sourceUrl", item.sourceUrl)
                    put("thumbnailUrl", item.thumbnailUrl)
                    put("localFilePath", item.localFilePath ?: "")
                    put("progress", item.progress)
                    put("totalBytes", item.totalBytes)
                    put("downloadedBytes", item.downloadedBytes)
                    put("status", item.status.name)
                    put("errorMessage", item.errorMessage)
                    put("timestamp", item.timestamp)

                    val formatObj = JSONObject().apply {
                        put("formatId", item.format.formatId)
                        put("resolutionOrQuality", item.format.resolutionOrQuality)
                        put("fileExtension", item.format.fileExtension)
                        put("approxSize", item.format.approxSize ?: "")
                        put("mediaType", item.format.mediaType.name)
                        put("directUrl", item.format.directUrl ?: "")
                    }
                    put("format", formatObj)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString("saved_downloads", jsonArray.toString()).apply()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist downloads", e)
        }
    }

    private fun sanitizeForFilename(input: String): String {
        return input.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
    }

    private fun sanitizeHeaderValue(value: String): String? {
        val clean = value.filter { it.code in 32..126 }.trim()
        return if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            clean
        } else {
            null
        }
    }

    fun startDownload(context: Context, mediaInfo: MediaInfo, format: MediaFormat) {
        init(context)
        val downloadUrl = format.directUrl

        if (downloadUrl.isNullOrBlank() || !VideoExtractorEngine.isValidHttpUrl(downloadUrl)) {
            showToast(context, "Cannot download: Direct stream URL is unavailable.")
            return
        }

        val downloadId = System.currentTimeMillis()
        activeMediaInfoCache[downloadId] = mediaInfo

        val sanitizedTitle = sanitizeForFilename(mediaInfo.title)
            .take(45)
            .ifEmpty { "VidSnap_Video" }

        val sanitizedQuality = sanitizeForFilename(format.resolutionOrQuality)
            .take(20)
            .ifEmpty { "HD" }

        val sanitizedExt = sanitizeForFilename(format.fileExtension).ifEmpty { "mp4" }
        val fileName = "${sanitizedTitle}_${sanitizedQuality}.${sanitizedExt}"

        val initialItem = DownloadItem(
            id = downloadId,
            title = mediaInfo.title,
            sourceUrl = mediaInfo.sourceUrl,
            thumbnailUrl = mediaInfo.thumbnailUrl,
            localFilePath = null,
            format = format,
            progress = 0,
            status = DownloadStatus.DOWNLOADING,
            downloadSpeed = "Connecting..."
        )

        _downloadList.value = listOf(initialItem) + _downloadList.value
        persistDownloads()
        showToast(context, "Starting download: $fileName")

        DownloadService.start(context, downloadId)
        val job = coroutineScope.launch {
            try {
                val success = executeStreamingDownload(
                    context = context,
                    downloadId = downloadId,
                    streamUrl = downloadUrl,
                    sourceReferer = mediaInfo.sourceUrl,
                    fileName = fileName,
                    isAudio = format.mediaType == MediaType.AUDIO
                )

                if (!success) {
                    // Retry with alternate browser headers
                    Log.w(TAG, "Attempt 1 failed, retrying with browser user-agent...")
                    updateSpeed(downloadId, "Retrying...")
                    val retrySuccess = executeStreamingDownload(
                        context = context,
                        downloadId = downloadId,
                        streamUrl = downloadUrl,
                        sourceReferer = mediaInfo.sourceUrl,
                        fileName = fileName,
                        isAudio = format.mediaType == MediaType.AUDIO,
                        useAlternateHeaders = true
                    )
                    if (!retrySuccess) {
                        markDownloadFailed(downloadId, "Download failed - stream may have expired.")
                        showToast(context, "Download failed. Please re-fetch the video link.")
                    }
                }
            } catch (c: CancellationException) {
                markDownloadFailed(downloadId, "Cancelled")
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Download failed with exception", t)
                markDownloadFailed(downloadId, t.localizedMessage ?: "Network error")
                showToast(context, "Download failed: ${t.localizedMessage ?: "Network error"}")
            } finally {
                activeJobs.remove(downloadId)
            }
        }
        activeJobs[downloadId] = job
    }

    fun retryDownload(context: Context, downloadId: Long) {
        val item = _downloadList.value.firstOrNull { it.id == downloadId } ?: return
        val cachedInfo = activeMediaInfoCache[downloadId]
        val info = cachedInfo ?: MediaInfo(
            sourceUrl = item.sourceUrl,
            title = item.title,
            author = "Creator",
            duration = "Video",
            thumbnailUrl = item.thumbnailUrl,
            platform = VideoExtractorEngine.detectPlatform(item.sourceUrl),
            formats = listOf(item.format)
        )

        // Reset status
        _downloadList.value = _downloadList.value.map {
            if (it.id == downloadId) it.copy(status = DownloadStatus.DOWNLOADING, progress = 0, errorMessage = "", downloadSpeed = "Connecting...") else it
        }

        DownloadService.start(context, downloadId)
        val job = coroutineScope.launch {
            try {
                // Re-resolve if direct URL is missing or expired
                var directUrl = item.format.directUrl
                val isExpired = directUrl.isNullOrBlank() || VideoExtractorEngine.isYouTubeUrlExpired(directUrl)
                if (isExpired) {
                    updateSpeed(downloadId, "Refreshing link...")
                    val res = VideoExtractorEngine.resolveMedia(item.sourceUrl)
                    if (res is com.snaptube.downloader.core.model.ResolveResult.Success) {
                        val matching = res.mediaInfo.formats.firstOrNull {
                            it.mediaType == item.format.mediaType && it.resolutionOrQuality == item.format.resolutionOrQuality
                        } ?: res.mediaInfo.formats.firstOrNull { it.mediaType == item.format.mediaType }
                          ?: res.mediaInfo.formats.firstOrNull()

                        if (matching != null && !matching.directUrl.isNullOrBlank()) {
                            directUrl = matching.directUrl
                            _downloadList.value = _downloadList.value.map {
                                if (it.id == downloadId) it.copy(format = matching) else it
                            }
                        }
                    }
                }

                if (directUrl.isNullOrBlank()) {
                    markDownloadFailed(downloadId, "Could not refresh stream link.")
                    showToast(context, "Could not refresh stream link. Please copy link again.")
                    return@launch
                }

                val sanitizedTitle = sanitizeForFilename(item.title).take(45).ifEmpty { "VidSnap_Video" }
                val sanitizedQuality = sanitizeForFilename(item.format.resolutionOrQuality).take(20).ifEmpty { "HD" }
                val sanitizedExt = sanitizeForFilename(item.format.fileExtension).ifEmpty { "mp4" }
                val fileName = "${sanitizedTitle}_${sanitizedQuality}.${sanitizedExt}"

                val success = executeStreamingDownload(
                    context = context,
                    downloadId = downloadId,
                    streamUrl = directUrl,
                    sourceReferer = item.sourceUrl,
                    fileName = fileName,
                    isAudio = item.format.mediaType == MediaType.AUDIO
                )

                if (!success) {
                    markDownloadFailed(downloadId, "Retry failed.")
                    showToast(context, "Retry failed. Try opening in browser.")
                }
            } catch (c: CancellationException) {
                markDownloadFailed(downloadId, "Cancelled")
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Retry failed with exception", t)
                markDownloadFailed(downloadId, t.localizedMessage ?: "Network error")
            } finally {
                activeJobs.remove(downloadId)
            }
        }
        activeJobs[downloadId] = job
    }

    fun cancelDownload(downloadId: Long) {
        activeCalls.remove(downloadId)?.cancel()
        activeJobs.remove(downloadId)?.cancel()
        val ctx = appContext
        val dest = activeDestinations.remove(downloadId)
        if (ctx != null && dest != null) {
            StorageManager.discardMediaDestination(ctx, dest)
        }
        _downloadList.value = _downloadList.value.map {
            if (it.id == downloadId) it.copy(
                status = DownloadStatus.FAILED,
                errorMessage = "Cancelled by user",
                downloadSpeed = ""
            ) else it
        }
        persistDownloads()
    }

    private suspend fun executeStreamingDownload(
        context: Context,
        downloadId: Long,
        streamUrl: String,
        sourceReferer: String,
        fileName: String,
        isAudio: Boolean,
        useAlternateHeaders: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val mimeType = when {
            fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            fileName.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            fileName.endsWith(".webm", ignoreCase = true) -> if (isAudio) "audio/webm" else "video/webm"
            else -> if (isAudio) "audio/mpeg" else "video/mp4"
        }
        val destination = StorageManager.createMediaDestination(context, fileName, mimeType, isAudio)
            ?: return@withContext false

        activeDestinations[downloadId] = destination

        val reqBuilder = Request.Builder().url(streamUrl)

        // Headers customized to stream origin
        if (useAlternateHeaders) {
            reqBuilder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        } else if (streamUrl.contains("googlevideo.com")) {
            reqBuilder.addHeader("User-Agent", "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)")
            reqBuilder.addHeader("Range", "bytes=0-")
        } else {
            reqBuilder.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
        }

        // Attach cookies from in-app browser WebView session
        val cookieManager = runCatching { android.webkit.CookieManager.getInstance() }.getOrNull()
        val streamCookies = runCatching { cookieManager?.getCookie(streamUrl) }.getOrNull()
        val refererCookies = runCatching { cookieManager?.getCookie(sourceReferer) }.getOrNull()
        val cookies = streamCookies ?: refererCookies
        if (!cookies.isNullOrBlank()) {
            reqBuilder.addHeader("Cookie", cookies)
        }

        val isInstagram = streamUrl.contains("cdninstagram.com") || streamUrl.contains("fbcdn.net") || sourceReferer.contains("instagram.com")
        val isYouTube = streamUrl.contains("googlevideo.com") || streamUrl.contains("youtube.com")

        if (isInstagram) {
            reqBuilder.addHeader("Referer", "https://www.instagram.com/")
        } else if (isYouTube) {
            reqBuilder.addHeader("Referer", "https://www.youtube.com/")
            reqBuilder.addHeader("Origin", "https://www.youtube.com")
        } else {
            val cleanReferer = sanitizeHeaderValue(sourceReferer)
            if (cleanReferer != null) {
                reqBuilder.addHeader("Referer", cleanReferer)
            }
        }

        reqBuilder.addHeader("Accept", "*/*")
        reqBuilder.addHeader("Accept-Encoding", "identity;q=1, *;q=0")
        reqBuilder.addHeader("Connection", "keep-alive")

        val request = reqBuilder.build()
        val call = httpClient.newCall(request)
        activeCalls[downloadId] = call

        try {
            val response = call.execute()
            if (!response.isSuccessful && response.code != 206) {
                Log.w(TAG, "Download HTTP ${response.code} for $fileName")
                StorageManager.discardMediaDestination(context, destination)
                return@withContext false
            }

            val contentType = response.header("Content-Type")?.lowercase().orEmpty()
            if (contentType.contains("text/html") || contentType.contains("text/plain")) {
                Log.w(TAG, "Got HTML/text content instead of media for $fileName")
                StorageManager.discardMediaDestination(context, destination)
                return@withContext false
            }

            val body = response.body ?: run {
                StorageManager.discardMediaDestination(context, destination)
                return@withContext false
            }
            val contentLength = body.contentLength()

            val outputStream = StorageManager.openOutputStream(context, destination) ?: run {
                StorageManager.discardMediaDestination(context, destination)
                return@withContext false
            }
            val inputStream = body.byteStream()

            val buffer = ByteArray(65536) // 64KB buffer for high speed streaming
            var bytesRead: Int
            var totalRead = 0L
            var lastProgress = 0
            var lastSpeedTimestamp = System.currentTimeMillis()
            var bytesSinceLastSpeed = 0L

            outputStream.use { out ->
                inputStream.use { input ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        bytesSinceLastSpeed += bytesRead

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastSpeedTimestamp

                        // Update speed every 800ms
                        var speedText = ""
                        if (elapsed >= 800) {
                            val bytesPerSec = (bytesSinceLastSpeed * 1000) / elapsed
                            speedText = formatSpeed(bytesPerSec)
                            lastSpeedTimestamp = now
                            bytesSinceLastSpeed = 0L
                        }

                        if (contentLength > 0) {
                            val currentProgress = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 99)
                            if (currentProgress > lastProgress || speedText.isNotBlank()) {
                                lastProgress = currentProgress
                                updateProgress(downloadId, currentProgress, totalRead, contentLength, speedText)
                            }
                        } else {
                            val mbDownloaded = totalRead / (1024 * 1024)
                            if (mbDownloaded > lastProgress || speedText.isNotBlank()) {
                                lastProgress = mbDownloaded.toInt()
                                updateProgress(downloadId, -1, totalRead, 0L, speedText)
                            }
                        }
                    }
                    out.flush()
                }
            }

            // Verify minimum size (> 10 KB for audio, > 30 KB for video)
            val minSize = if (isAudio) 10 * 1024L else 30 * 1024L
            if (totalRead < minSize) {
                Log.w(TAG, "Downloaded file too small: $totalRead bytes")
                StorageManager.discardMediaDestination(context, destination)
                return@withContext false
            }

            val committed = StorageManager.commitMediaDestination(context, destination)
            if (!committed) {
                StorageManager.discardMediaDestination(context, destination)
                return@withContext false
            }

            // Mark completed
            _downloadList.value = _downloadList.value.map {
                if (it.id == downloadId) {
                    it.copy(
                        progress = 100,
                        status = DownloadStatus.COMPLETED,
                        localFilePath = destination.identifier,
                        downloadedBytes = totalRead,
                        totalBytes = totalRead,
                        downloadSpeed = "Completed",
                        errorMessage = ""
                    )
                } else it
            }
            persistDownloads()

            showToast(context, "✅ Downloaded to Gallery: $fileName")
            true
        } catch (e: Exception) {
            StorageManager.discardMediaDestination(context, destination)
            if (e is CancellationException) throw e
            Log.e(TAG, "Download streaming exception for $fileName", e)
            false
        } finally {
            activeCalls.remove(downloadId)
            activeDestinations.remove(downloadId)
        }
    }

    private fun markDownloadFailed(downloadId: Long, reason: String = "Download failed") {
        _downloadList.value = _downloadList.value.map {
            if (it.id == downloadId) it.copy(status = DownloadStatus.FAILED, errorMessage = reason, downloadSpeed = "") else it
        }
        persistDownloads()
    }

    private fun updateProgress(downloadId: Long, progress: Int, downloaded: Long, total: Long, speed: String) {
        _downloadList.value = _downloadList.value.map {
            if (it.id == downloadId) {
                it.copy(
                    progress = if (progress >= 0) progress else it.progress,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    downloadSpeed = speed.ifEmpty { it.downloadSpeed },
                    status = DownloadStatus.DOWNLOADING
                )
            } else it
        }
    }

    private fun updateSpeed(downloadId: Long, speed: String) {
        _downloadList.value = _downloadList.value.map {
            if (it.id == downloadId) it.copy(downloadSpeed = speed) else it
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
            bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    fun removeDownload(id: Long) {
        cancelDownload(id)
        val target = _downloadList.value.firstOrNull { it.id == id }
        val ctx = appContext
        if (target != null && ctx != null) {
            StorageManager.deleteMedia(ctx, target.localFilePath)
        }
        _downloadList.value = _downloadList.value.filter { it.id != id }
        activeMediaInfoCache.remove(id)
        persistDownloads()
    }

    private fun showToast(context: Context, message: String) {
        val targetCtx = appContext ?: context.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(targetCtx, message, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }
}

