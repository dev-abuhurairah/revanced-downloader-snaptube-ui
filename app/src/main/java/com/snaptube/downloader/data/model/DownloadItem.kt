package com.snaptube.downloader.data.model

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    PAUSED
}

data class DownloadItem(
    val id: Long,
    val title: String,
    val sourceUrl: String,
    val thumbnailUrl: String,
    val localFilePath: String?,
    val format: MediaFormat,
    val progress: Int = 0, // 0 to 100
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    val timestamp: Long = System.currentTimeMillis()
)
