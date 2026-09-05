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

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun startDownload(context: Context, mediaInfo: MediaInfo, format: MediaFormat) {
        val downloadId = System.currentTimeMillis()

        val sanitizedTitle = mediaInfo.title
            .replace(Regex("[^a-zA-Z0-9.\\-_ ]"), "_")
            .trim()
            .take(50)
            .ifEmpty { "VidSnap_Media" }

        val fileName = "${sanitizedTitle}_${format.resolutionOrQuality.replace(" ", "_")}.${format.fileExtension}"

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
        showToast(context, "Starting download: $fileName")

        coroutineScope.launch {
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

            if (finalStreamUrl.isNullOrEmpty()) {
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

            val request = Request.Builder()
                .url(streamUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .addHeader("Referer", mediaInfo.sourceUrl)
                .addHeader("Accept", "*/*")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
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
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle(mediaInfo.title)
                setDescription("Downloading with VidSnap (${format.resolutionOrQuality})")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                addRequestHeader("Referer", mediaInfo.sourceUrl)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                try {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                } catch (_: Exception) {}
            }

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            manager?.enqueue(request)

            _downloadList.value = _downloadList.value.map {
                if (it.id == downloadId) {
                    it.copy(progress = 50, status = DownloadStatus.DOWNLOADING)
                } else it
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _downloadList.value = _downloadList.value.map {
                if (it.id == downloadId) it.copy(status = DownloadStatus.FAILED) else it
            }
            showToast(context, "Download failed: ${e.message}")
        }
    }

    private fun copyToPublicDownloads(sourceFile: File, fileName: String) {
        try {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidSnap")
            if (!publicDir.exists()) publicDir.mkdirs()
            val destFile = File(publicDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)
        } catch (_: Exception) {
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
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
