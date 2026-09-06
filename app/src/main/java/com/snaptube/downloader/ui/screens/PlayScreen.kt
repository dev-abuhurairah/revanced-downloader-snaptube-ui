package com.snaptube.downloader.ui.screens

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.snaptube.downloader.core.download.DownloadHelper
import com.snaptube.downloader.data.model.DownloadItem
import com.snaptube.downloader.data.model.DownloadStatus
import com.snaptube.downloader.data.model.MediaType
import com.snaptube.downloader.ui.theme.SnaptubeBlack
import com.snaptube.downloader.ui.theme.SnaptubeCard
import com.snaptube.downloader.ui.theme.SnaptubeSearchBorder
import com.snaptube.downloader.ui.theme.SnaptubeTextPrimary
import com.snaptube.downloader.ui.theme.SnaptubeTextSecondary
import com.snaptube.downloader.ui.theme.SnaptubeYellow
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun PlayScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val downloads by DownloadHelper.downloadList.collectAsState()
    var activePlayingItem by remember { mutableStateOf<DownloadItem?>(null) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SnaptubeBlack)
            .padding(top = 16.dp)
    ) {
        Text(
            text = "My Library & Downloads",
            color = SnaptubeTextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        // Video Player Box when an item is selected
        activePlayingItem?.let { item ->
            LaunchedEffect(item.id, item.localFilePath) {
                try {
                    val path = item.localFilePath
                    val mediaItem = if (path != null) {
                        if (path.startsWith("content://")) {
                            MediaItem.fromUri(Uri.parse(path))
                        } else if (File(path).exists()) {
                            MediaItem.fromUri(Uri.fromFile(File(path)))
                        } else if (item.sourceUrl.startsWith("http://", ignoreCase = true) || item.sourceUrl.startsWith("https://", ignoreCase = true)) {
                            MediaItem.fromUri(Uri.parse(item.sourceUrl))
                        } else {
                            null
                        }
                    } else if (item.sourceUrl.startsWith("http://", ignoreCase = true) || item.sourceUrl.startsWith("https://", ignoreCase = true)) {
                        MediaItem.fromUri(Uri.parse(item.sourceUrl))
                    } else {
                        null
                    }
                    if (mediaItem != null) {
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                        exoPlayer.setMediaItem(mediaItem)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                        }
                    },
                    update = { /* Handled safely by LaunchedEffect */ }
                )

                IconButton(
                    onClick = {
                        exoPlayer.stop()
                        activePlayingItem = null
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close player", tint = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (downloads.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = SnaptubeTextSecondary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No downloaded media yet",
                        color = SnaptubeTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Search or paste links in the Download tab to save videos",
                        color = SnaptubeTextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(downloads, key = { it.id }) { item ->
                    DownloadCard(
                        item = item,
                        onPlayClick = {
                            activePlayingItem = item
                        },
                        onDeleteClick = {
                            DownloadHelper.removeDownload(item.id)
                        },
                        onRetryClick = {
                            DownloadHelper.retryDownload(context, item.id)
                        },
                        onShareClick = {
                            try {
                                val path = item.localFilePath
                                if (!path.isNullOrBlank()) {
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = if (item.format.mediaType == MediaType.AUDIO) "audio/*" else "video/*"
                                        val uri = if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Video"))
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Cannot share media file", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRetryClick: () -> Unit = {},
    onShareClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SnaptubeCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SnaptubeSearchBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = SnaptubeTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${item.format.resolutionOrQuality} • ${item.format.fileExtension.uppercase()}",
                        color = SnaptubeYellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (item.status == DownloadStatus.COMPLETED) {
                    IconButton(onClick = onShareClick) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = SnaptubeTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(onClick = onPlayClick) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(SnaptubeYellow),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = SnaptubeBlack,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = if (item.status == DownloadStatus.DOWNLOADING) Icons.Default.Close else Icons.Default.Delete,
                        contentDescription = if (item.status == DownloadStatus.DOWNLOADING) "Cancel" else "Delete",
                        tint = SnaptubeTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (item.status == DownloadStatus.DOWNLOADING) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = if (item.progress > 0) item.progress / 100f else 0f,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = SnaptubeYellow,
                    trackColor = SnaptubeBlack
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${item.progress}%" + if (item.totalBytes > 0) " (${item.downloadedBytes / (1024 * 1024)}/${item.totalBytes / (1024 * 1024)} MB)" else "",
                        color = SnaptubeTextSecondary,
                        fontSize = 11.sp
                    )
                    if (item.downloadSpeed.isNotBlank()) {
                        Text(
                            text = "⚡ ${item.downloadSpeed}",
                            color = SnaptubeYellow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else if (item.status == DownloadStatus.FAILED) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "❌ " + item.errorMessage.ifBlank { "Download failed" },
                        color = Color(0xFFFF5252),
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SnaptubeYellow)
                            .clickable { onRetryClick() }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "🔄 Retry",
                            color = SnaptubeBlack,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

