package com.snaptube.downloader.core.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

sealed interface MediaDestination {
    val identifier: String

    data class MediaStoreUri(val uri: Uri, val displayName: String, val isAudio: Boolean) : MediaDestination {
        override val identifier: String = uri.toString()
    }

    data class LocalFile(val file: File, val isAudio: Boolean) : MediaDestination {
        override val identifier: String = file.absolutePath
    }
}

object StorageManager {

    private const val TAG = "StorageManager"

    fun createMediaDestination(
        context: Context,
        fileName: String,
        mimeType: String,
        isAudio: Boolean
    ): MediaDestination? {
        // Attempt 1: MediaStore (Android 10+ scoped storage primary)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val collectionUri = if (isAudio) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }

                val subFolder = if (isAudio) "Music/VidSnap" else "Movies/VidSnap"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, subFolder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = context.contentResolver.insert(collectionUri, values)
                if (uri != null) {
                    Log.d(TAG, "Created MediaStore destination: $uri")
                    return MediaDestination.MediaStoreUri(uri, fileName, isAudio)
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore primary insert failed, attempting MediaStore Downloads fallback...", e)
            }

            // Attempt 2: MediaStore.Downloads fallback for Android 10+
            try {
                val downloadsCollection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VidSnap")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(downloadsCollection, values)
                if (uri != null) {
                    Log.d(TAG, "Created MediaStore Downloads destination: $uri")
                    return MediaDestination.MediaStoreUri(uri, fileName, isAudio)
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore Downloads insert failed, attempting public file system fallback...", e)
            }
        }

        // Attempt 3: Public Shared Directory (Downloads or Movies/Music)
        try {
            val baseDir = if (isAudio) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }
            val vidSnapDir = File(baseDir, "VidSnap")
            if (!vidSnapDir.exists()) vidSnapDir.mkdirs()
            val targetFile = File(vidSnapDir, fileName)
            Log.d(TAG, "Created public file destination: ${targetFile.absolutePath}")
            return MediaDestination.LocalFile(targetFile, isAudio)
        } catch (e: Exception) {
            Log.w(TAG, "Public directory creation failed, attempting app-specific external storage...", e)
        }

        // Attempt 4: App-Specific External Storage (guaranteed always writable without permissions)
        return try {
            val type = if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            val appDir = context.getExternalFilesDir(type) ?: context.filesDir
            if (!appDir.exists()) appDir.mkdirs()
            val targetFile = File(appDir, fileName)
            Log.d(TAG, "Created app external file destination: ${targetFile.absolutePath}")
            MediaDestination.LocalFile(targetFile, isAudio)
        } catch (e: Exception) {
            Log.e(TAG, "All storage destinations failed", e)
            null
        }
    }

    fun openOutputStream(context: Context, destination: MediaDestination, append: Boolean = false): OutputStream? {
        return try {
            when (destination) {
                is MediaDestination.MediaStoreUri -> {
                    val mode = if (append) "wa" else "w"
                    context.contentResolver.openOutputStream(destination.uri, mode)
                }
                is MediaDestination.LocalFile -> {
                    val parent = destination.file.parentFile
                    if (parent != null && !parent.exists()) parent.mkdirs()
                    FileOutputStream(destination.file, append)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open OutputStream for ${destination.identifier}", e)
            null
        }
    }

    fun commitMediaDestination(context: Context, destination: MediaDestination): Boolean {
        return try {
            when (destination) {
                is MediaDestination.MediaStoreUri -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        context.contentResolver.update(destination.uri, values, null, null)
                    }
                    // Trigger scan
                    notifyMediaScan(context, destination.uri.toString())
                    true
                }
                is MediaDestination.LocalFile -> {
                    val exists = destination.file.exists() && destination.file.length() > 0
                    if (exists) {
                        notifyMediaScan(context, destination.file.absolutePath)
                    }
                    exists
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to commit destination: ${destination.identifier}", e)
            true
        }
    }

    fun discardMediaDestination(context: Context, destination: MediaDestination) {
        try {
            when (destination) {
                is MediaDestination.MediaStoreUri -> {
                    context.contentResolver.delete(destination.uri, null, null)
                }
                is MediaDestination.LocalFile -> {
                    if (destination.file.exists()) {
                        destination.file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error discarding destination: ${destination.identifier}", e)
        }
    }

    fun notifyMediaScan(context: Context, pathOrUri: String) {
        try {
            if (pathOrUri.startsWith("content://")) {
                val uri = Uri.parse(pathOrUri)
                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
            } else {
                val file = File(pathOrUri)
                if (file.exists()) {
                    MediaScannerConnection.scanFile(
                        context.applicationContext,
                        arrayOf(file.absolutePath),
                        null
                    ) { scannedPath, uri ->
                        Log.d(TAG, "Scanned $scannedPath to Gallery: $uri")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Media scan notification error", e)
        }
    }

    fun isMediaAvailable(context: Context, localPath: String?): Boolean {
        if (localPath.isNullOrBlank()) return false
        return try {
            if (localPath.startsWith("content://")) {
                val uri = Uri.parse(localPath)
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    pfd.statSize > 0
                } ?: false
            } else {
                val file = File(localPath)
                file.exists() && file.length() > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    fun deleteMedia(context: Context, localPath: String?): Boolean {
        if (localPath.isNullOrBlank()) return false
        return try {
            if (localPath.startsWith("content://")) {
                val uri = Uri.parse(localPath)
                context.contentResolver.delete(uri, null, null) > 0
            } else {
                val file = File(localPath)
                if (file.exists()) file.delete() else false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete media: $localPath", e)
            false
        }
    }
}

