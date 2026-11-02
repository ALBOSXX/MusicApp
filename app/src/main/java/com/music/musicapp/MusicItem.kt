package com.music.musicapp

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MusicItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateAdded: Long,
    val duration: Long = 0L,
    val sampleRate: Int,
    val bitrate: Int,
    val channels: Int
) {
    constructor() : this(
        id = 0L,
        uri = Uri.EMPTY,
        name = "",
        size = 0L,
        dateAdded = 0L,
        duration = 0L,
        sampleRate = 0,
        bitrate = 0,
        channels = 0
    )

//    fun getValidUri(context: Context): Uri? {
//
//        if (isUriValid(context, uri)) {
//            return uri
//        }
//
//
//        return findMediaByMetadata(context, name, size)
//    }
//
//    private fun isUriValid(context: Context, targetUri: Uri): Boolean {
//        return try {
//            context.contentResolver.openInputStream(targetUri)?.close()
//            true
//        } catch (e: Exception) {
//            false
//        }
//    }
//
//    private fun findMediaByMetadata(context: Context, displayName: String, fileSize: Long): Uri? {
//        val collection = if (video) {
//            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
//        } else {
//            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
//        }
//
//        val projection = arrayOf(MediaStore.MediaColumns._ID)
//        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
//        val selectionArgs = arrayOf(displayName, fileSize.toString())
//
//        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
//            if (cursor.moveToFirst()) {
//                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
//                val newId = cursor.getLong(idCol)
//                return ContentUris.withAppendedId(collection, newId)
//            }
//        }
//        return null
//    }


}