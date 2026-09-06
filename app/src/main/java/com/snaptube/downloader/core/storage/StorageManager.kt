package com.snaptube.downloader.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

sealed interface MediaDestination {
    val identifier: String

    data class MediaStoreUri(val uri: Uri) : MediaDestination {
        override val identifier: String = uri.toString()
    }

    data class LocalFile(val file: File) : MediaDestination {
        override val identifier: String = file.absolutePath
    }
}

object StorageManager {

    fun createMediaDestination(
        context: Context,
        fileName: String,
        mimeType: String,
        isAudio: Boolean
    ): MediaDestination? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collectionUri = if (isAudio) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }

                val relativePath = if (isAudio) {
                    "${Environment.DIRECTORY_MUSIC}/VidSnap"
                } else {
                    "${Environment.DIRECTORY_MOVIES}/VidSnap"
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = context.contentResolver.insert(collectionUri, values) ?: return null
                MediaDestination.MediaStoreUri(uri)
            } else {
                val parentDir = context.getExternalFilesDir(if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)
                    ?: context.filesDir
                if (!parentDir.exists()) parentDir.mkdirs()
                val targetFile = File(parentDir, fileName)
                MediaDestination.LocalFile(targetFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun openOutputStream(context: Context, destination: MediaDestination): OutputStream? {
        return try {
            when (destination) {
                is MediaDestination.MediaStoreUri -> context.contentResolver.openOutputStream(destination.uri)
                is MediaDestination.LocalFile -> destination.file.outputStream()
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
                        context.contentResolver.update(destination.uri, values, null, null) > 0
                    } else {
                        true
                    }
                }
                is MediaDestination.LocalFile -> destination.file.exists() && destination.file.length() > 0
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
        } catch (_: Exception) {}
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
            e.printStackTrace()
            false
        }
    }
}
