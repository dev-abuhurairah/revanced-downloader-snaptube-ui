package com.snaptube.downloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snaptube.downloader.core.download.DownloadHelper
import com.snaptube.downloader.data.model.DownloadStatus
import com.snaptube.downloader.ui.screens.BrowserScreen
import com.snaptube.downloader.ui.screens.HomeScreen
import com.snaptube.downloader.ui.screens.PlayScreen
import com.snaptube.downloader.ui.screens.SettingsScreen
import com.snaptube.downloader.ui.theme.SnaptubeBlack
import com.snaptube.downloader.ui.theme.SnaptubeTheme
import com.snaptube.downloader.ui.theme.SnaptubeYellow

enum class BottomTab(val title: String) {
    DOWNLOAD("Download"),
    PLAY("Play"),
    SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {

    private var sharedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize persistent download storage
        DownloadHelper.init(this)

        // Handle shared URL from Instagram, YouTube, TikTok, etc.
        handleIncomingIntent(intent)

        setContent {
            SnaptubeTheme {
                SnaptubeMainApp(initialSharedUrl = sharedUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedUrl = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}

@Composable
fun SnaptubeMainApp(
    initialSharedUrl: String? = null
) {
    var currentBottomTab by remember { mutableStateOf(BottomTab.DOWNLOAD) }
    var currentTopTab by remember { mutableStateOf("Search") }
    var browserTargetUrl by remember { mutableStateOf<String?>(null) }

    val downloads by DownloadHelper.downloadList.collectAsState()
    val activeDownloadingCount = downloads.count { it.status == DownloadStatus.DOWNLOADING }
    val totalDownloadCount = downloads.size

    // Handle system back navigation gracefully
    BackHandler(enabled = currentBottomTab != BottomTab.DOWNLOAD || currentTopTab != "Search") {
        if (currentTopTab != "Search") {
            currentTopTab = "Search"
        } else if (currentBottomTab != BottomTab.DOWNLOAD) {
            currentBottomTab = BottomTab.DOWNLOAD
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(SnaptubeBlack),
        bottomBar = {
            // Modern Floating Pill Navigation Dock
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp),
                    color = Color(0xFF141418),
                    tonalElevation = 8.dp,
                    border = BorderStroke(1.dp, Color(0xFF25252E))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Download Tab
                        ModernNavBarItem(
                            selected = currentBottomTab == BottomTab.DOWNLOAD,
                            label = "Download",
                            iconSelected = Icons.Filled.FileDownload,
                            iconUnselected = Icons.Outlined.FileDownload,
                            badgeCount = 0,
                            onClick = {
                                currentBottomTab = BottomTab.DOWNLOAD
                                currentTopTab = "Search"
                            }
                        )

                        // 2. Play Tab (Downloads / Library)
                        ModernNavBarItem(
                            selected = currentBottomTab == BottomTab.PLAY,
                            label = "Play",
                            iconSelected = Icons.Filled.PlayCircle,
                            iconUnselected = Icons.Outlined.PlayCircle,
                            badgeCount = if (activeDownloadingCount > 0) activeDownloadingCount else totalDownloadCount,
                            isDownloadingBadge = activeDownloadingCount > 0,
                            onClick = {
                                currentBottomTab = BottomTab.PLAY
                            }
                        )

                        // 3. Settings Tab
                        ModernNavBarItem(
                            selected = currentBottomTab == BottomTab.SETTINGS,
                            label = "Settings",
                            iconSelected = Icons.Filled.Settings,
                            iconUnselected = Icons.Outlined.Settings,
                            badgeCount = 0,
                            onClick = {
                                currentBottomTab = BottomTab.SETTINGS
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(SnaptubeBlack)
        ) {
            when (currentBottomTab) {
                BottomTab.DOWNLOAD -> {
                    when (currentTopTab) {
                        "Search" -> HomeScreen(
                            selectedTopTab = currentTopTab,
                            onTopTabSelected = { currentTopTab = it },
                            onOpenBrowser = { url ->
                                browserTargetUrl = url
                                currentTopTab = "More"
                            },
                            initialSharedUrl = initialSharedUrl
                        )
                        "YouTube" -> BrowserScreen(
                            initialUrl = "https://m.youtube.com",
                            onBack = { currentTopTab = "Search" }
                        )
                        "Music" -> BrowserScreen(
                            initialUrl = "https://music.youtube.com",
                            onBack = { currentTopTab = "Search" }
                        )
                        "More" -> BrowserScreen(
                            initialUrl = browserTargetUrl ?: "https://www.instagram.com",
                            onBack = { currentTopTab = "Search" }
                        )
                    }
                }
                BottomTab.PLAY -> PlayScreen()
                BottomTab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun ModernNavBarItem(
    selected: Boolean,
    label: String,
    iconSelected: ImageVector,
    iconUnselected: ImageVector,
    badgeCount: Int = 0,
    isDownloadingBadge: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val animatedBgColor by animateColorAsState(
        targetValue = if (selected) SnaptubeYellow.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(durationMillis = 220),
        label = "pill_bg"
    )
    val animatedContentColor by animateColorAsState(
        targetValue = if (selected) SnaptubeYellow else Color(0xFF8E8E98),
        animationSpec = tween(durationMillis = 220),
        label = "pill_content"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(animatedBgColor)
            .then(
                if (selected) Modifier.background(
                    color = SnaptubeYellow.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(22.dp)
                ) else Modifier
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box {
                Icon(
                    imageVector = if (selected) iconSelected else iconUnselected,
                    contentDescription = label,
                    tint = animatedContentColor,
                    modifier = Modifier.size(22.dp)
                )

                if (badgeCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-4).dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (isDownloadingBadge) SnaptubeYellow else Color(0xFF33333E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                            color = if (isDownloadingBadge) SnaptubeBlack else Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (selected) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    color = animatedContentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
