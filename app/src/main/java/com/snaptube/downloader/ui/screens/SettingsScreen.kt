package com.snaptube.downloader.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snaptube.downloader.ui.theme.SnaptubeBlack
import com.snaptube.downloader.ui.theme.SnaptubeCard
import com.snaptube.downloader.ui.theme.SnaptubeSearchBorder
import com.snaptube.downloader.ui.theme.SnaptubeTextPrimary
import com.snaptube.downloader.ui.theme.SnaptubeTextSecondary
import com.snaptube.downloader.ui.theme.SnaptubeYellow

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    var defaultQuality by remember { mutableStateOf("1080p FHD") }
    var wifiOnly by remember { mutableStateOf(false) }
    var autoResume by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SnaptubeBlack)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Settings",
            color = SnaptubeTextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SettingsSectionTitle("DOWNLOAD PREFERENCES")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SnaptubeCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SnaptubeSearchBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingsRow(
                            icon = Icons.Default.Folder,
                            title = "Download Location",
                            subtitle = "/Storage/emulated/0/Download/Snaptube"
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        SettingsRow(
                            icon = Icons.Default.HighQuality,
                            title = "Default Quality",
                            subtitle = defaultQuality,
                            onClick = {
                                defaultQuality = if (defaultQuality == "1080p FHD") "720p HD" else "1080p FHD"
                            }
                        )
                    }
                }
            }

            item {
                SettingsSectionTitle("NETWORK & ENGINE")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SnaptubeCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SnaptubeSearchBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Download over Wi-Fi only", color = SnaptubeTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text("Avoid using mobile data allowance", color = SnaptubeTextSecondary, fontSize = 12.sp)
                            }
                            Switch(
                                checked = wifiOnly,
                                onCheckedChange = { wifiOnly = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = SnaptubeBlack,
                                    checkedTrackColor = SnaptubeYellow
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-resume interrupted downloads", color = SnaptubeTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text("Resume downloading upon reconnection", color = SnaptubeTextSecondary, fontSize = 12.sp)
                            }
                            Switch(
                                checked = autoResume,
                                onCheckedChange = { autoResume = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = SnaptubeBlack,
                                    checkedTrackColor = SnaptubeYellow
                                )
                            )
                        }
                    }
                }
            }

            item {
                SettingsSectionTitle("ABOUT")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SnaptubeCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SnaptubeSearchBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingsRow(
                            icon = Icons.Default.Info,
                            title = "Snaptube Downloader",
                            subtitle = "Version 1.0.0 (Cloud Actions Build)"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        SettingsRow(
                            icon = Icons.Default.Speed,
                            title = "Supported Services",
                            subtitle = "Instagram, YouTube, TikTok, Facebook, Twitter, Pinterest"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        color = SnaptubeTextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp)
    )
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(SnaptubeYellow.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = SnaptubeYellow, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(text = title, color = SnaptubeTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = SnaptubeTextSecondary, fontSize = 12.sp)
        }
    }
}
