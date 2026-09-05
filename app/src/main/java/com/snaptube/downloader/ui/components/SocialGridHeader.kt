package com.snaptube.downloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snaptube.downloader.ui.theme.FacebookBlue
import com.snaptube.downloader.ui.theme.InstagramPink
import com.snaptube.downloader.ui.theme.SnaptubeBlack
import com.snaptube.downloader.ui.theme.TikTokBlack
import com.snaptube.downloader.ui.theme.TikTokCyan
import com.snaptube.downloader.ui.theme.WhatsAppGreen
import com.snaptube.downloader.ui.theme.YouTubeRed

@Composable
fun SocialGridHeader(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(0.dp))
    ) {
        // Tilted 3D Social Grid positioned safely below top navigation
        Column(
            modifier = Modifier
                .graphicsLayer {
                    rotationZ = -12f
                    scaleX = 1.15f
                    scaleY = 1.15f
                    translationY = 20f
                }
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Row 1
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SocialTile(bg = FacebookBlue) {
                    Text("f", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
                SocialTile(bg = WhatsAppGreen) {
                    Icon(Icons.Default.Call, contentDescription = "WA", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                SocialTile(bg = TikTokBlack, border = TikTokCyan) {
                    Icon(Icons.Default.MusicNote, contentDescription = "TT", tint = TikTokCyan, modifier = Modifier.size(30.dp))
                }
                SocialTile(
                    bgBrush = Brush.linearGradient(listOf(Color(0xFF833AB4), InstagramPink, Color(0xFFFCAF45)))
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "IG", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Row 2
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SocialTile(bg = YouTubeRed) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "YT", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                SocialTile(
                    bgBrush = Brush.linearGradient(listOf(Color(0xFF833AB4), InstagramPink, Color(0xFFFCAF45)))
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "IG", tint = Color.White, modifier = Modifier.size(28.dp))
                }
                SocialTile(bg = WhatsAppGreen) {
                    Icon(Icons.Default.Call, contentDescription = "WA", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                SocialTile(bg = FacebookBlue) {
                    Text("f", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
            }

            // Row 3
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SocialTile(bg = TikTokBlack, border = TikTokCyan) {
                    Icon(Icons.Default.MusicNote, contentDescription = "TT", tint = TikTokCyan, modifier = Modifier.size(30.dp))
                }
                SocialTile(bg = FacebookBlue) {
                    Text("f", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
                SocialTile(bg = YouTubeRed) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "YT", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                SocialTile(bg = Color(0xFFE60023)) {
                    Icon(Icons.Default.Share, contentDescription = "Pin", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }

        // Two-way Fading Gradient Overlay: 100% black at top (protects nav buttons), soft glow in middle, pure black at bottom
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SnaptubeBlack,
                            SnaptubeBlack.copy(alpha = 0.90f),
                            SnaptubeBlack.copy(alpha = 0.40f),
                            SnaptubeBlack.copy(alpha = 0.20f),
                            SnaptubeBlack.copy(alpha = 0.65f),
                            SnaptubeBlack.copy(alpha = 0.95f),
                            SnaptubeBlack
                        )
                    )
                )
        )
    }
}

@Composable
private fun SocialTile(
    bg: Color = Color.DarkGray,
    bgBrush: Brush? = null,
    border: Color? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(RoundedCornerShape(18.dp))
            .then(
                if (bgBrush != null) Modifier.background(bgBrush)
                else Modifier.background(bg)
            )
            .alpha(0.85f),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
