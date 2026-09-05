package com.snaptube.downloader.data.model

enum class MediaType {
    VIDEO,
    AUDIO
}

data class MediaFormat(
    val formatId: String,
    val resolutionOrQuality: String, // e.g. "1080p", "720p", "480p", "MP3 320k", "MP3 128k"
    val fileExtension: String,        // e.g. "mp4", "mp3", "m4a"
    val approxSize: String? = null,   // e.g. "45 MB", "4.2 MB"
    val mediaType: MediaType = MediaType.VIDEO,
    val directUrl: String? = null
)

data class MediaInfo(
    val sourceUrl: String,
    val title: String,
    val author: String,
    val duration: String,
    val thumbnailUrl: String,
    val platform: PlatformType,
    val formats: List<MediaFormat>
)

enum class PlatformType(val displayName: String, val iconColor: Long) {
    YOUTUBE("YouTube", 0xFFFF0000),
    INSTAGRAM("Instagram", 0xFFE1306C),
    TIKTOK("TikTok", 0xFF00F2FE),
    FACEBOOK("Facebook", 0xFF1877F2),
    TWITTER("X / Twitter", 0xFF1DA1F2),
    OTHER("Web", 0xFFFFCC00)
}
