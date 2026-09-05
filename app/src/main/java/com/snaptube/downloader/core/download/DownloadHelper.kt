package com.snaptube.downloader.core.download

import android.app.DownloadManager
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.snaptube.downloader.core.extractor.VideoExtractorEngine
import com.snaptube.downloader.data.model.DownloadItem
import com.snaptube.downloader.data.model.DownloadStatus
import com.snaptube.downloader.data.model.MediaFormat
import com.snaptube.downloader.data.model.MediaInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

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
            val jsonArray = org.json.JSONArray(jsonString)
            val items = mutableListOf<DownloadItem>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val formatObj = obj.optJSONObject("format")
                val mediaFormat = if (formatObj != null) {
                    MediaFormat(
                        formatId = formatObj.optString("formatId", "default"),
                        resolutionOrQuality = formatObj.optString("resolutionOrQuality", "HD"),
                        fileExtension = formatObj.optString("fileExtension", "mp4"),
                        approxSize = formatObj.optString("approxSize").takeIf { it.isNotEmpty() },
                        mediaType = runCatching {
                            com.snaptube.downloader.data.model.MediaType.valueOf(formatObj.optString("mediaType", "VIDEO"))
                        }.getOrDefault(com.snaptube.downloader.data.model.MediaType.VIDEO),
                        directUrl = formatObj.optString("directUrl").takeIf { it.isNotEmpty() }
                    )
                } else {
                    MediaFormat("default", "HD", "mp4")
                }

                val statusStr = obj.optString("status", DownloadStatus.COMPLETED.name)
                var status = runCatching { DownloadStatus.valueOf(statusStr) }.getOrDefault(DownloadStatus.COMPLETED)
                val localPath = obj.optString("localFilePath").takeIf { it.isNotEmpty() }

                if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PENDING) {
                    if (localPath != null && File(localPath).exists() && File(localPath).length() > 50 * 1024) {
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
            val jsonArray = org.json.JSONArray()
            val snapshot = _downloadList.value.toList()
            snapshot.forEach { item ->
                val obj = org.json.JSONObject().apply {
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

                    val formatObj = org.json.JSONObject().apply {
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
        val downloadId = System.currentTimeMillis()

        val sanitizedTitle = sanitizeForFilename(mediaInfo.title)
            .take(45)
            .ifEmpty { "VidSnap_Video" }

        val sanitizedQuality = sanitizeForFilename(format.resolutionOrQuality)
            .take(20)
            .ifEmpty { "HD" }

        val sanitizedExt = sanitizeForFilename(format.fileExtension)
            .ifEmpty { "mp4" }

        val fileName = "${sanitizedTitle}_${sanitizedQuality}.${sanitizedExt}"

        // Initial task entry
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
                // Step 1: Ensure we have a valid, direct video stream URL
                var directUrl = format.directUrl
                if (directUrl.isNullOrEmpty() || directUrl == mediaInfo.sourceUrl) {
                    // Resolve stream on demand
                    val resolved = VideoExtractorEngine.resolveMedia(mediaInfo.sourceUrl)
                    resolved.onSuccess { info ->
                        val matching = info.formats.firstOrNull { it.formatId == format.formatId }
                            ?: info.formats.firstOrNull { it.directUrl != null }
                        if (matching?.directUrl != null) {
                            directUrl = matching.directUrl
                        }
                    }
                }

                val finalStreamUrl = directUrl

                if (finalStreamUrl.isNullOrEmpty() || (!finalStreamUrl.startsWith("http://", true) && !finalStreamUrl.startsWith("https://", true))) {
                    // If stream resolution is not direct, attempt system download manager
                    launchSystemDownloadManager(context, downloadId, mediaInfo, format, fileName)
                    return@launch
                }

                // Step 2: Download directly using OkHttp streaming for 100% reliability
                val success = downloadWithOkHttp(context, downloadId, finalStreamUrl, mediaInfo, fileName)
                if (!success) {
                    // Fallback to system DownloadManager
                    launchSystemDownloadManager(context, downloadId, mediaInfo, format, fileName)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                launchSystemDownloadManager(context, downloadId, mediaInfo, format, fileName)
            }
        }
    }

    private suspend fun downloadWithOkHttp(
        context: Context,
        downloadId: Long,
        streamUrl: String,
        mediaInfo: MediaInfo,
        fileName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Target storage directory (accessible without runtime permission dialogs)
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            if (!downloadDir.exists()) downloadDir.mkdirs()

            val targetFile = File(downloadDir, fileName)

            val reqBuilder = Request.Builder().url(streamUrl)
            reqBuilder.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            val cleanReferer = sanitizeHeaderValue(mediaInfo.sourceUrl)
            if (cleanReferer != null) {
                reqBuilder.addHeader("Referer", cleanReferer)
            }
            reqBuilder.addHeader("Accept", "*/*")
            val request = reqBuilder.build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext false
            }

            val contentType = response.header("Content-Type")?.lowercase().orEmpty()
            if (contentType.contains("text/html") || contentType.contains("text/plain")) {
                showToast(context, "Link returned a web page. Open in-app browser to capture real video!")
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            val contentLength = body.contentLength()

            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(targetFile)

            val buffer = ByteArray(8192)
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
                            if (currentProgress > lastProgress + 2) {
                                lastProgress = currentProgress
                                updateProgress(downloadId, currentProgress, totalRead, contentLength)
                            }
                        }
                    }
                    out.flush()
                }
            }

            // A valid video is never less than 50 KB
            if (totalRead < 50 * 1024) {
                targetFile.delete()
                _downloadList.value = _downloadList.value.map {
                    if (it.id == downloadId) it.copy(status = DownloadStatus.FAILED) else it
                }
                persistDownloads()
                showToast(context, "Download failed: Incomplete or empty video stream.")
                return@withContext false
            }

            // Successfully downloaded! Copy to public Downloads if possible
            copyToPublicDownloads(targetFile, fileName)

            // Notify Android MediaScanner so it appears in Gallery
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                null
            ) { _, _ -> }

            // Mark completed
            _downloadList.value = _downloadList.value.map {
                if (it.id == downloadId) {
                    it.copy(
                        progress = 100,
                        status = DownloadStatus.COMPLETED,
                        localFilePath = targetFile.absolutePath,
                        downloadedBytes = targetFile.length(),
                        totalBytes = targetFile.length()
                    )
                } else it
            }
            persistDownloads()

            showToast(context, "Downloaded successfully: $fileName")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun launchSystemDownloadManager(
        context: Context,
        downloadId: Long,
        mediaInfo: MediaInfo,
        format: MediaFormat,
        fileName: String
    ) {
        try {
            val downloadUrl = format.directUrl ?: mediaInfo.sourceUrl
            if (!downloadUrl.startsWith("http://", ignoreCase = true) && !downloadUrl.startsWith("https://", ignoreCase = true)) {
                throw IllegalArgumentException("Invalid download URL: $downloadUrl")
            }

            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle(mediaInfo.title.take(60))
                setDescription("Downloading with VidSnap (${format.resolutionOrQuality.take(30)})")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                val cleanReferer = sanitizeHeaderValue(mediaInfo.sourceUrl)
                if (cleanReferer != null) {
                    addRequestHeader("Referer", cleanReferer)
                }
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                try {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                } catch (_: Throwable) {}
            }

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: throw IllegalStateException("DownloadManager service not available")
            manager.enqueue(request)

            _downloadList.value = _downloadList.value.map {
                if (it.id == downloadId) {
                    it.copy(progress = 50, status = DownloadStatus.DOWNLOADING)
                } else it
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            _downloadList.value = _downloadList.value.map {
                if (it.id == downloadId) it.copy(status = DownloadStatus.FAILED) else it
            }
            persistDownloads()
            showToast(context, "Download failed: ${e.message}")
        }
    }

    private fun copyToPublicDownloads(sourceFile: File, fileName: String) {
        try {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidSnap")
            if (!publicDir.exists()) publicDir.mkdirs()
            val destFile = File(publicDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)
        } catch (_: Throwable) {
            // Silently fall back to app external files dir
        }
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
