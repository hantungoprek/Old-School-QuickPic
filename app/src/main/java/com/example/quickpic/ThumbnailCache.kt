package com.example.quickpic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.security.MessageDigest

/** Persistent thumbnail cache stored under the app cache directory.
 * Android removes this directory when the user chooses Settings > Apps >
 * QuickPic > Clear cache, while normal app restarts keep the thumbnails.
 */
class ThumbnailCache(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "quickpic_thumbnails").apply { mkdirs() }
    private val maxBytes = 256L * 1024L * 1024L

    fun fileFor(uri: Uri, version: String): File =
        File(directory, sha256("$uri|$version") + ".jpg")

    fun existing(uri: Uri, version: String): File? =
        fileFor(uri, version).takeIf { it.isFile && it.length() > 0L }

    fun save(uri: Uri, version: String, bitmap: Bitmap) {
        val target = fileFor(uri, version)
        runCatching {
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                out.flush()
            }
            trimIfNeeded()
        }
    }

    private fun trimIfNeeded() {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
