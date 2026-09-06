package com.snaptube.downloader.core.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.snaptube.downloader.MainActivity
import com.snaptube.downloader.R
import com.snaptube.downloader.data.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "vidsnap_download_channel"
        const val CHANNEL_NAME = "VidSnap Downloads"
        const val NOTIFICATION_ID = 9901

        const val ACTION_START_DOWNLOAD = "com.snaptube.downloader.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.snaptube.downloader.action.CANCEL_DOWNLOAD"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"

        fun start(context: Context, downloadId: Long? = null) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                downloadId?.let { putExtra(EXTRA_DOWNLOAD_ID, it) }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithInitialNotification()
        observeDownloads()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (intent.action == ACTION_CANCEL_DOWNLOAD) {
                val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) {
                    DownloadHelper.cancelDownload(id)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time download progress and status for VidSnap"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithInitialNotification() {
        val notification = buildNotification("VidSnap Downloader", "Preparing download...", 0, indeterminate = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeDownloads() {
        observeJob?.cancel()
        observeJob = serviceScope.launch {
            DownloadHelper.downloadList.collect { list ->
                val activeItems = list.filter { it.status == DownloadStatus.DOWNLOADING }
                if (activeItems.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val firstActive = activeItems.first()
                    val title = firstActive.title
                    val progress = firstActive.progress
                    val totalMb = firstActive.totalBytes / (1024 * 1024)
                    val downloadedMb = firstActive.downloadedBytes / (1024 * 1024)

                    val text = buildString {
                        if (progress > 0) append("$progress%")
                        if (totalMb > 0) append(" ($downloadedMb / $totalMb MB)")
                        if (firstActive.downloadSpeed.isNotBlank()) append(" • ${firstActive.downloadSpeed}")
                    }.ifEmpty { "Downloading..." }

                    val notif = buildNotification(
                        title = title,
                        text = text,
                        progress = progress,
                        indeterminate = progress <= 0,
                        downloadId = firstActive.id
                    )

                    try {
                        val manager = NotificationManagerCompat.from(this@DownloadService)
                        manager.notify(NOTIFICATION_ID, notif)
                    } catch (_: SecurityException) {}
                }
            }
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean = false,
        downloadId: Long? = null
    ): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (indeterminate) {
            builder.setProgress(100, 0, true)
        } else {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        }

        if (downloadId != null) {
            val cancelIntent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            val cancelPendingIntent = PendingIntent.getService(
                this,
                downloadId.toInt(),
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
        }

        return builder.build()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        super.onDestroy()
    }
}