package com.snaptube.downloader.core.model

import com.snaptube.downloader.data.model.MediaInfo

enum class ResolveErrorType {
    INVALID_URL,
    UNSUPPORTED_PLATFORM,
    RESOLVER_UNAVAILABLE,
    MEDIA_UNAVAILABLE,
    NETWORK_ERROR,
    PARSING_ERROR,
    UNKNOWN
}

sealed interface ResolveResult {
    data class Success(val mediaInfo: MediaInfo) : ResolveResult
    data class Failure(
        val errorType: ResolveErrorType,
        val userMessage: String,
        val technicalDetails: String? = null
    ) : ResolveResult
}
