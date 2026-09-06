package com.snaptube.downloader.core.download

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

object DownloadHelper {

    private val _downloadList = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloadList: StateFlow<List<DownloadItem>> = _downloadList.asStateFlow()

    private var appContext: Context? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
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
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            _downloadList.value = items
        } catch (e: Exception) {
            e.printStackTrace()
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
            e.printStackTrace()
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
            showToast(context, "Cannot download: Direct stream URL is unavailable or invalid.")
            return
        }

        val downloadId = System.currentTimeMillis()
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
            status = DownloadStatus.DOWNLOADING
        )

        _downloadList.value = listOf(initialItem) + _downloadList.value
        persistDownloads()
        showToast(context, "Starting download: $fileName")

        coroutineScope.launch {
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
                    markDownloadFailed(downloadId)
                }
            } catch (c: CancellationException) {
                markDownloadFailed(downloadId)
                throw c
            } catch (t: Throwable) {
                t.printStackTrace()
                markDownloadFailed(downloadId)
                showToast(context, "Download failed: ${t.localizedMessage ?: "Network error"}")
            }
        }
    }

    private suspend fun executeStreamingDownload(
        context: Context,
        downloadId: Long,
        streamUrl: String,
        sourceReferer: String,
        fileName: String,
        isAudio: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val mimeType = if (isAudio) "audio/mpeg" else "video/mp4"
        val destination = StorageManager.createMediaDestination(context, fileName, mimeType, isAudio)
            ?: return@withContext false

        val reqBuilder = Request.Builder().url(streamUrl)
        reqBuilder.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")

        // Forward session cookies from WebView (essential for Instagram CDN and authenticated streams)
        val cookieManager = runCatching { android.webkit.CookieManager.getInstance() }.getOrNull()
        val streamCookies = runCatching { cookieManager?.getCookie(streamUrl) }.getOrNull()
        val refererCookies = runCatching { cookieManager?.getCookie(sourceReferer) }.getOrNull()
        val cookies = streamCookies ?: refererCookies
        if (!cookies.isNullOrBlank()) {
            reqBuilder.addHeader("Cookie", cookies)
        }

        val isInstagram = streamUrl.contains("cdninstagram.com") || streamUrl.contains("fbcdn.net") || sourceReferer.contains("instagram.com")
        val cleanReferer = if (isInstagram) "https://www.instagram.com/" else sanitizeHeaderValue(sourceReferer)
        if (cleanReferer != null) {
            reqBuilder.addHeader("Referer", cleanReferer)
        }
        reqBuilder.addHeader("Accept", "*/*")
        reqBuilder.addHeader("Accept-Encoding", "identity")
        val request = reqBuilder.build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                StorageManager.discardMediaDestination(context, destination)
                return@withContext false
            }

            val contentType = response.header("Content-Type")?.lowercase().orEmpty()
            if (contentType.contains("text/html") || contentType.contains("text/plain")) {
                StorageManager.discardMediaDestination(context, destination)
                showToast(context, "Link returned a web page instead of media stream.")
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

            val buffer = ByteArray(16384)
            var bytesRead: Int
            var totalRead = 0L
            var lastProgress = 0

            outputStream.use { out ->
                inputStream.use { input ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (contentLength > 0) {
                            val currentProgress = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 99)
                            if (currentProgress > lastProgress + 1) {
                                lastProgress = currentProgress
                                updateProgress(downloadId, currentProgress, totalRead, contentLength)
                            }
                        }
                    }
                    out.flush()
                }
            }

            // Minimum valid media verification (> 30 KB)
            if (totalRead < 30 * 1024) {
                StorageManager.discardMediaDestination(context, destination)
                showToast(context, "Download failed: Incomplete stream data received.")
                return@withContext false
            }

            val committed = StorageManager.commitMediaDestination(context, destination)
            if (!committed) {
                StorageManager.discardMediaDestination(context, destination)
                return@withContext false
            }

            // Update state with completed status and actual local identifier
            _downloadList.value = _downloadList.value.map {
                if (it.id == downloadId) {
                    it.copy(
                        progress = 100,
                        status = DownloadStatus.COMPLETED,
                        localFilePath = destination.identifier,
                        downloadedBytes = totalRead,
                        totalBytes = totalRead
                    )
                } else it
            }
            persistDownloads()

            showToast(context, "Saved successfully: $fileName")
            true
        } catch (e: Exception) {
            StorageManager.discardMediaDestination(context, destination)
            if (e is CancellationException) throw e
            e.printStackTrace()
            false
        }
    }

    private fun markDownloadFailed(downloadId: Long) {
        _downloadList.value = _downloadList.value.map {
            if (it.id == downloadId) it.copy(status = DownloadStatus.FAILED) else it
        }
        persistDownloads()
    }

    private fun updateProgress(downloadId: Long, progress: Int, downloaded: Long, total: Long) {
        _downloadList.value = _downloadList.value.map {
            if (it.id == downloadId) {
                it.copy(
                    progress = progress,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    status = DownloadStatus.DOWNLOADING
                )
            } else it
        }
    }

    fun removeDownload(id: Long) {
        val target = _downloadList.value.firstOrNull { it.id == id }
        val ctx = appContext
        if (target != null && ctx != null) {
            StorageManager.deleteMedia(ctx, target.localFilePath)
        }
        _downloadList.value = _downloadList.value.filter { it.id != id }
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
