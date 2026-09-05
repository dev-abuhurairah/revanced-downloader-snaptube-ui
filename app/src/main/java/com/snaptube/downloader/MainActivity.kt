package com.snaptube.downloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snaptube.downloader.ui.screens.BrowserScreen
import com.snaptube.downloader.ui.screens.HomeScreen
import com.snaptube.downloader.ui.screens.PlayScreen
import com.snaptube.downloader.ui.screens.SettingsScreen
import com.snaptube.downloader.ui.theme.SnaptubeBlack
import com.snaptube.downloader.ui.theme.SnaptubeCard
import com.snaptube.downloader.ui.theme.SnaptubeTextPrimary
import com.snaptube.downloader.ui.theme.SnaptubeTextSecondary
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

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(SnaptubeBlack),
        bottomBar = {
            NavigationBar(
                containerColor = SnaptubeBlack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .navigationBarsPadding(),
                tonalElevation = 0.dp
            ) {
                // 1. Download
                NavigationBarItem(
                    selected = currentBottomTab == BottomTab.DOWNLOAD,
                    onClick = {
                        currentBottomTab = BottomTab.DOWNLOAD
                        currentTopTab = "Search"
                    },
                    icon = {
                        Icon(
                            imageVector = if (currentBottomTab == BottomTab.DOWNLOAD) Icons.Filled.FileDownload else Icons.Outlined.FileDownload,
                            contentDescription = "Download",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "Download",
                            fontSize = 11.sp,
                            fontWeight = if (currentBottomTab == BottomTab.DOWNLOAD) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SnaptubeYellow,
                        selectedTextColor = SnaptubeYellow,
                        unselectedIconColor = SnaptubeTextSecondary,
                        unselectedTextColor = SnaptubeTextSecondary,
                        indicatorColor = Color.Transparent
                    )
                )

                // 2. Play
                NavigationBarItem(
                    selected = currentBottomTab == BottomTab.PLAY,
                    onClick = { currentBottomTab = BottomTab.PLAY },
                    icon = {
                        Icon(
                            imageVector = if (currentBottomTab == BottomTab.PLAY) Icons.Filled.PlayCircle else Icons.Outlined.PlayCircle,
                            contentDescription = "Play",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "Play",
                            fontSize = 11.sp,
                            fontWeight = if (currentBottomTab == BottomTab.PLAY) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SnaptubeYellow,
                        selectedTextColor = SnaptubeYellow,
                        unselectedIconColor = SnaptubeTextSecondary,
                        unselectedTextColor = SnaptubeTextSecondary,
                        indicatorColor = Color.Transparent
                    )
                )

                // 3. Settings
                NavigationBarItem(
                    selected = currentBottomTab == BottomTab.SETTINGS,
                    onClick = { currentBottomTab = BottomTab.SETTINGS },
                    icon = {
                        Icon(
                            imageVector = if (currentBottomTab == BottomTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "Settings",
                            fontSize = 11.sp,
                            fontWeight = if (currentBottomTab == BottomTab.SETTINGS) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SnaptubeYellow,
                        selectedTextColor = SnaptubeYellow,
                        unselectedIconColor = SnaptubeTextSecondary,
                        unselectedTextColor = SnaptubeTextSecondary,
                        indicatorColor = Color.Transparent
                    )
                )
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
                            }
                        )
                        "YouTube" -> BrowserScreen(
                            initialUrl = "https://m.youtube.com"
                        )
                        "Music" -> BrowserScreen(
                            initialUrl = "https://music.youtube.com"
                        )
                        "More" -> BrowserScreen(
                            initialUrl = browserTargetUrl ?: "https://www.instagram.com"
                        )
                    }
                }
                BottomTab.PLAY -> PlayScreen()
                BottomTab.SETTINGS -> SettingsScreen()
            }
        }
    }
}
