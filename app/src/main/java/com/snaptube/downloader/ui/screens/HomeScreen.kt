package com.snaptube.downloader.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
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
import com.snaptube.downloader.ui.theme.FacebookBlue
import com.snaptube.downloader.ui.theme.InstagramPink
import com.snaptube.downloader.ui.theme.SnaptubeBlack
import com.snaptube.downloader.ui.theme.SnaptubeCard
import com.snaptube.downloader.ui.theme.SnaptubeSearchBorder
import com.snaptube.downloader.ui.theme.SnaptubeTextPrimary
import com.snaptube.downloader.ui.theme.SnaptubeTextSecondary
import com.snaptube.downloader.ui.theme.SnaptubeYellow
import com.snaptube.downloader.ui.theme.TikTokCyan
import com.snaptube.downloader.ui.theme.YouTubeRed
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    selectedTopTab: String,
    onTopTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var resolvedMedia by remember { mutableStateOf<MediaInfo?>(null) }

    fun processQuery(query: String) {
        if (query.isBlank()) {
            Toast.makeText(context, "Paste a link or enter search keywords", Toast.LENGTH_SHORT).show()
            return
        }
        isLoading = true
        coroutineScope.launch {
            val result = VideoExtractorEngine.resolveMedia(query)
            isLoading = false
            result.onSuccess { info ->
                resolvedMedia = info
            }.onFailure { err ->
                Toast.makeText(context, "Could not extract video: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SnaptubeBlack)
    ) {
        // 3D Social Grid Header fading into dark background
        SocialGridHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Tabs: "Search", "YouTube", "Music", "More"
            TopTabBar(
                selectedTab = selectedTopTab,
                onTabSelected = onTopTabSelected,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(55.dp))

            // Brand Typography: "Snaptube" (matching screenshot)
            Text(
                text = "Snaptube",
                color = SnaptubeYellow,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Search to download pill bar
            SearchDownloadBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { processQuery(searchQuery) },
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            if (isLoading) {
                Spacer(modifier = Modifier.height(32.dp))
                CircularProgressIndicator(
                    color = SnaptubeYellow,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Analyzing video stream...",
                    color = SnaptubeTextSecondary,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(42.dp))

            // Quick Paste Clipboard Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(SnaptubeCard)
                    .clickable {
                        val text = clipboardManager.getText()?.text
                        if (!text.isNullOrBlank()) {
                            searchQuery = text
                            processQuery(text)
                        } else {
                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
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

            Spacer(modifier = Modifier.height(28.dp))

            // Social Platform Shortcuts
            Text(
                text = "SUPPORTED SITES",
                color = SnaptubeTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PlatformIconBadge("YouTube", YouTubeRed) {
                    onTopTabSelected("YouTube")
                }
                PlatformIconBadge("Instagram", InstagramPink) {
                    searchQuery = "https://www.instagram.com/reel/"
                }
                PlatformIconBadge("TikTok", TikTokCyan) {
                    searchQuery = "https://www.tiktok.com/"
                }
                PlatformIconBadge("Facebook", FacebookBlue) {
                    searchQuery = "https://www.facebook.com/watch"
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }

        // Download Bottom Sheet with quality choices
        resolvedMedia?.let { media ->
            DownloadBottomSheet(
                mediaInfo = media,
                onFormatSelected = { format ->
                    DownloadHelper.startDownload(context, media, format)
                    resolvedMedia = null
                },
                onDismiss = { resolvedMedia = null }
            )
        }
    }
}

@Composable
private fun PlatformIconBadge(name: String, color: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            when (name) {
                "YouTube" -> Icon(Icons.Default.PlayArrow, contentDescription = name, tint = color, modifier = Modifier.size(26.dp))
                "Instagram" -> Icon(Icons.Default.CameraAlt, contentDescription = name, tint = color, modifier = Modifier.size(24.dp))
                "TikTok" -> Icon(Icons.Default.MusicNote, contentDescription = name, tint = color, modifier = Modifier.size(24.dp))
                "Facebook" -> Text("f", color = color, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = name, color = SnaptubeTextSecondary, fontSize = 11.sp)
    }
}
