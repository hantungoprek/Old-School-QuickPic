package com.example.quickpic.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface DataRepository {
    val data: Flow<MediaLibrary>
}

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateAddedSeconds: Long,
    val durationMillis: Long,
    val sizeBytes: Long,
    val relativePath: String,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

data class MediaFolder(
    val path: String,
    val displayName: String,
    val thumbnail: Uri,
    val photoCount: Int,
    val videoCount: Int,
) {
    val totalCount: Int get() = photoCount + videoCount
}

data class MediaLibrary(
    val folders: List<MediaFolder>,
    val media: List<MediaItem>,
)

class DefaultDataRepository(context: Context) : DataRepository {
    private val contentResolver = context.applicationContext.contentResolver

    override val data: Flow<MediaLibrary> = flow {
        emit(contentResolver.loadMediaLibrary())
    }
}

private fun ContentResolver.loadMediaLibrary(): MediaLibrary {
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.DATE_ADDED,
        MediaStore.Files.FileColumns.DURATION,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.RELATIVE_PATH,
    )

    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
    val selectionArgs = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
    )
    val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

    val media = query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
        val mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
        val sizeColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
        val relativePathColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)

        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val mimeType = cursor.getString(mimeTypeColumn).orEmpty()
                val mediaType = cursor.getInt(mediaTypeColumn)
                val relativePath = if (relativePathColumn >= 0) {
                    cursor.getString(relativePathColumn).orEmpty()
                } else {
                    ""
                }

                // Ignore any unexpected MediaStore row that slipped through the selection.
                if (mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE &&
                    mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                ) continue

                add(
                    MediaItem(
                        id = id,
                        // Querying Files is useful for one combined image/video
                        // library, but the URI handed to the rest of the app must
                        // remain in its real media collection.  In particular,
                        // opening a Video URI is more reliable than opening the
                        // read-only Files aggregation URI on scoped storage.
                        uri = ContentUris.withAppendedId(
                            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                            } else {
                                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                            },
                            id,
                        ),
                        displayName = cursor.getString(nameColumn) ?: "Untitled media",
                        mimeType = mimeType,
                        dateAddedSeconds = cursor.getLong(dateAddedColumn),
                        durationMillis = if (cursor.isNull(durationColumn)) 0L else cursor.getLong(durationColumn),
                        sizeBytes = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else 0L,
                        relativePath = relativePath.ifBlank { "Pictures/" },
                    )
                )
            }
        }
    } ?: emptyList()

    val folders = media
        .groupBy { it.relativePath }
        .map { (path, items) ->
            val folderName = path
                .trimEnd('/')
                .substringAfterLast('/')
                .ifBlank { "Internal storage" }

            MediaFolder(
                path = path,
                displayName = folderName,
                thumbnail = items.first().uri,
                photoCount = items.count { !it.isVideo },
                videoCount = items.count { it.isVideo },
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

    return MediaLibrary(folders = folders, media = media)
}
