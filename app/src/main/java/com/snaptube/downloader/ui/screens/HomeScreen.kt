package com.snaptube.downloader.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snaptube.downloader.core.download.DownloadHelper
import com.snaptube.downloader.core.extractor.VideoExtractorEngine
import com.snaptube.downloader.data.model.MediaInfo
import com.snaptube.downloader.ui.components.DownloadBottomSheet
import com.snaptube.downloader.ui.components.SearchDownloadBar
import com.snaptube.downloader.ui.components.SocialGridHeader
import com.snaptube.downloader.ui.components.TopTabBar
import com.snaptube.downloader.ui.theme.SnaptubeBlack
import com.snaptube.downloader.ui.theme.SnaptubeCard
import com.snaptube.downloader.ui.theme.SnaptubeTextSecondary
import com.snaptube.downloader.ui.theme.SnaptubeYellow
import kotlinx.coroutines.launch

import androidx.compose.runtime.LaunchedEffect

@Composable
fun HomeScreen(
    selectedTopTab: String,
    onTopTabSelected: (String) -> Unit,
    onOpenBrowser: (String) -> Unit = {},
    initialSharedUrl: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var loadingStatus by remember { mutableStateOf("Analyzing video stream...") }
    var resolvedMedia by remember { mutableStateOf<MediaInfo?>(null) }

    fun processQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            Toast.makeText(context, "Paste a link or enter search keywords", Toast.LENGTH_SHORT).show()
            return
        }

        val extractedUrl = VideoExtractorEngine.extractUrlFromText(trimmed)
        if (!VideoExtractorEngine.isValidHttpUrl(extractedUrl)) {
            val encoded = runCatching { java.net.URLEncoder.encode(trimmed, "UTF-8") }.getOrDefault("video")
            Toast.makeText(context, "Searching for: $trimmed", Toast.LENGTH_SHORT).show()
            onOpenBrowser("https://m.youtube.com/results?search_query=$encoded")
            return
        }

        val platformLabel = when {
            extractedUrl.contains("tiktok.com", ignoreCase = true) -> "TikTok"
            extractedUrl.contains("youtube.com", ignoreCase = true) || extractedUrl.contains("youtu.be", ignoreCase = true) -> "YouTube"
            extractedUrl.contains("instagram.com", ignoreCase = true) -> "Instagram"
            extractedUrl.contains("twitter.com", ignoreCase = true) || extractedUrl.contains("x.com", ignoreCase = true) -> "Twitter / X"
            extractedUrl.contains("facebook.com", ignoreCase = true) || extractedUrl.contains("fb.watch", ignoreCase = true) -> "Facebook"
            else -> "Media"
        }
        loadingStatus = "Resolving $platformLabel streams..."
        isLoading = true
        coroutineScope.launch {
            try {
                when (val result = VideoExtractorEngine.resolveMedia(extractedUrl)) {
                    is com.snaptube.downloader.core.model.ResolveResult.Success -> {
                        resolvedMedia = result.mediaInfo
                    }
                    is com.snaptube.downloader.core.model.ResolveResult.Failure -> {
                        Toast.makeText(context, result.userMessage, Toast.LENGTH_LONG).show()
                        // Open in browser for both RESOLVER_UNAVAILABLE and MEDIA_UNAVAILABLE
                        if (result.errorType == com.snaptube.downloader.core.model.ResolveErrorType.RESOLVER_UNAVAILABLE ||
                            result.errorType == com.snaptube.downloader.core.model.ResolveErrorType.MEDIA_UNAVAILABLE) {
                            onOpenBrowser(extractedUrl)
                        }
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                val msg = t.localizedMessage ?: t.message ?: "Unknown error"
                Toast.makeText(context, "Extraction error: $msg", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
            }
        }
    }

    // Auto-process incoming shared URL if passed
    LaunchedEffect(initialSharedUrl) {
        if (!initialSharedUrl.isNullOrBlank()) {
            val cleanUrl = VideoExtractorEngine.extractUrlFromText(initialSharedUrl)
            searchQuery = cleanUrl
            processQuery(cleanUrl)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SnaptubeBlack)
    ) {
        // 3D Social Grid Header positioned safely below top navigation tabs
        SocialGridHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 95.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Tabs: "Search", "YouTube", "Music", "More"
            TopTabBar(
                selectedTab = selectedTopTab,
                onTabSelected = onTopTabSelected,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Brand Typography: "VidSnap"
            Text(
                text = "VidSnap",
                color = SnaptubeYellow,
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(44.dp))

            // Search to download pill bar
            SearchDownloadBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { processQuery(searchQuery) },
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            if (isLoading) {
                Spacer(modifier = Modifier.height(28.dp))
                CircularProgressIndicator(
                    color = SnaptubeYellow,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = loadingStatus,
                    color = SnaptubeTextSecondary,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Quick Paste Clipboard Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(SnaptubeCard)
                    .clickable {
                        try {
                            val rawText = clipboardManager.getText()?.text?.toString().orEmpty()
                            val clean = VideoExtractorEngine.extractUrlFromText(rawText)
                            if (clean.isNotBlank()) {
                                searchQuery = clean
                                processQuery(clean)
                            } else {
                                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                            }
                        } catch (t: Throwable) {
                            t.printStackTrace()
                            Toast.makeText(context, "Could not read clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "📋 Paste Link from Clipboard",
                    color = SnaptubeYellow,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Developer Attribution Footer
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(SnaptubeCard.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚡ Built with ❤️ by ",
                    color = SnaptubeTextSecondary,
                    fontSize = 12.sp
                )
                Text(
                    text = "dev-abuhurairah",
                    color = SnaptubeYellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(70.dp))
        }

        // Download Bottom Sheet with quality choices
        resolvedMedia?.let { media ->
            DownloadBottomSheet(
                mediaInfo = media,
                onFormatSelected = { format ->
                    try {
                        DownloadHelper.startDownload(context, media, format)
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        Toast.makeText(context, "Download error: ${t.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        resolvedMedia = null
                    }
                },
                onDismiss = { resolvedMedia = null }
            )
        }
    }
}
