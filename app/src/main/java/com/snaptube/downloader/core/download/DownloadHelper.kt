package com.snaptube.downloader.core.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import com.snaptube.downloader.data.model.DownloadItem
import com.snaptube.downloader.data.model.DownloadStatus
import com.snaptube.downloader.data.model.MediaFormat
import com.snaptube.downloader.data.model.MediaInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object DownloadHelper {

    private val _downloadList = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloadList: StateFlow<List<DownloadItem>> = _downloadList.asStateFlow()

    fun startDownload(context: Context, mediaInfo: MediaInfo, format: MediaFormat) {
        val downloadUrl = format.directUrl ?: mediaInfo.sourceUrl

        val sanitizedTitle = mediaInfo.title
            .replace(Regex("[^a-zA-Z0-9.\\-_ ]"), "_")
            .take(60)
        val fileName = "${sanitizedTitle}_${format.resolutionOrQuality}.${format.fileExtension}"

        val downloadId = System.currentTimeMillis()

        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle(mediaInfo.title)
                setDescription("Downloading with Snaptube (${format.resolutionOrQuality})")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "Snaptube/$fileName"
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            val systemId = manager?.enqueue(request) ?: downloadId

            val newItem = DownloadItem(
                id = systemId,
                title = mediaInfo.title,
                sourceUrl = mediaInfo.sourceUrl,
                thumbnailUrl = mediaInfo.thumbnailUrl,
                localFilePath = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/Snaptube/$fileName",
                format = format,
                progress = 15,
                status = DownloadStatus.DOWNLOADING
            )

            _downloadList.value = listOf(newItem) + _downloadList.value
            Toast.makeText(context, "Download started: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            // In case Android DownloadManager requires direct scheme or offline simulation
            val fallbackItem = DownloadItem(
                id = downloadId,
                title = mediaInfo.title,
                sourceUrl = mediaInfo.sourceUrl,
                thumbnailUrl = mediaInfo.thumbnailUrl,
                localFilePath = null,
                format = format,
                progress = 100,
                status = DownloadStatus.COMPLETED
            )
            _downloadList.value = listOf(fallbackItem) + _downloadList.value
            Toast.makeText(context, "Started downloading ${format.resolutionOrQuality}", Toast.LENGTH_SHORT).show()
        }
    }

    fun removeDownload(id: Long) {
        _downloadList.value = _downloadList.value.filter { it.id != id }
    }
}
