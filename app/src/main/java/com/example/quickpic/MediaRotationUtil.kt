package com.example.quickpic

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Hasil rotasi per item. */
data class RotateResult(
    val uri: Uri,
    val success: Boolean,
    val errorMessage: String? = null,
)

/**
 * Rotasi permanen file gambar/video ke storage fisik sehingga saat dibuka di aplikasi
 * lain (Google Photos, WhatsApp, Gallery bawaan, PC, dll) hasil rotasi tetap permanen.
 *
 * Strategi:
 * 1. Gambar (JPEG, PNG, WebP): Rotasi fisik pixel data menggunakan Bitmap Matrix.
 * 2. Salin metadata EXIF berharga (Date, Camera info, GPS) ke file baru.
 * 3. Reset EXIF Orientation ke NORMAL (1) karena orientasi pixel sudah tegak lurus fisik.
 * 4. Tulis kembali ke storage melalui OutputStream MediaStore.
 * 5. Update database MediaStore (ORIENTATION = 0, SIZE, DATE_MODIFIED).
 * 6. Video: Update metadata ORIENTATION di MediaStore.
 * 7. Bersihkan semua layer cache (Memory, Coil, Disk Thumbnail).
 */
suspend fun Context.rotateMediaFilePermanently(
    uri: Uri,
    degrees: Int,
    thumbnailCache: ThumbnailCache? = null,
): RotateResult = withContext(Dispatchers.IO) {
    if (degrees == 0) return@withContext RotateResult(uri, true)

    val mimeType = contentResolver.getType(uri) ?: "image/jpeg"

    return@withContext when {
        mimeType.startsWith("image/") ->
            rotateImageFilePhysically(uri, degrees, mimeType, thumbnailCache)
        mimeType.startsWith("video/") ->
            rotateVideoMetadata(uri, degrees, thumbnailCache)
        else ->
            RotateResult(uri, false, "Format tidak didukung: $mimeType")
    }
}

// ---------------------------------------------------------------------------
// Rotasi Gambar Fisik (Pixel Data)
// ---------------------------------------------------------------------------

private fun Context.rotateImageFilePhysically(
    uri: Uri,
    requestedDegrees: Int,
    mimeType: String,
    thumbnailCache: ThumbnailCache?,
): RotateResult {
    val tempFile = File(cacheDir, "rotate_temp_${System.currentTimeMillis()}.${if (mimeType.contains("png")) "png" else "jpg"}")
    try {
        // 1. Baca byte gambar asli
        val rawBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return RotateResult(uri, false, "Tidak dapat membaca file gambar.")

        // 2. Baca orientasi EXIF awal jika ada
        var existingExifDegrees = 0
        val exifAttributes = mutableMapOf<String, String>()
        val exifTagsToPreserve = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
        )

        try {
            rawBytes.inputStream().use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
                existingExifDegrees = exifOrientationToDegrees(orientation)

                for (tag in exifTagsToPreserve) {
                    val value = exif.getAttribute(tag)
                    if (!value.isNullOrBlank()) {
                        exifAttributes[tag] = value
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Decode Bitmap dengan resolusi penuh
        // Cek dimensi gambar terlebih dahulu
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)

        // Hitung sample size jika gambar luar biasa besar untuk mencegah OOM pada perangkat low-memory
        var sampleSize = 1
        val maxDimension = 8192 // Dukung hingga resolusi 8K
        while (boundsOpts.outWidth / sampleSize > maxDimension || boundsOpts.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sourceBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: return RotateResult(uri, false, "Gagal men-decode bitmap gambar.")

        // 4. Hitung total rotasi yang diperlukan:
        // Orientasi EXIF bawaan kamera + rotasi yang dipilih user
        val totalRotationDegrees = ((existingExifDegrees + requestedDegrees) % 360 + 360) % 360

        val matrix = Matrix().apply {
            postRotate(totalRotationDegrees.toFloat())
        }

        val rotatedBitmap = Bitmap.createBitmap(
            sourceBitmap,
            0,
            0,
            sourceBitmap.width,
            sourceBitmap.height,
            matrix,
            true,
        )
        if (rotatedBitmap !== sourceBitmap) {
            sourceBitmap.recycle()
        }

        // 5. Tulis ke file temporer lokal
        val isPng = mimeType.contains("png", ignoreCase = true)
        val isWebp = mimeType.contains("webp", ignoreCase = true)
        val format = when {
            isPng -> Bitmap.CompressFormat.PNG
            isWebp -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val quality = if (isPng) 100 else 96

        FileOutputStream(tempFile).use { out ->
            rotatedBitmap.compress(format, quality, out)
            out.flush()
        }
        rotatedBitmap.recycle()

        // 6. Tulis kembali metadata EXIF ke file temporer (khusus JPEG/WebP)
        if (!isPng) {
            try {
                val tempExif = ExifInterface(tempFile.absolutePath)
                for ((tag, value) in exifAttributes) {
                    tempExif.setAttribute(tag, value)
                }
                // Reset orientasi ke NORMAL karena pixelnya sudah diputar secara fisik
                tempExif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString(),
                )
                tempExif.saveAttributes()
            } catch (_: Exception) {}
        }

        // 7. Salin isi tempFile kembali ke ContentResolver OutputStream (MediaStore URI)
        val newBytes = tempFile.readBytes()
        val outStream = contentResolver.openOutputStream(uri, "rwt")
            ?: contentResolver.openOutputStream(uri, "wt")
            ?: contentResolver.openOutputStream(uri, "w")
            ?: return RotateResult(uri, false, "Tidak dapat membuka file penyimpanan untuk menulis. Pastikan izin telah diberikan.")

        outStream.use { out ->
            out.write(newBytes)
            out.flush()
        }

        // 8. Update database MediaStore
        val nowSeconds = System.currentTimeMillis() / 1000
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.ORIENTATION, 0) // Reset orientasi MediaStore ke 0
            put(MediaStore.MediaColumns.SIZE, newBytes.size.toLong())
            put(MediaStore.MediaColumns.DATE_MODIFIED, nowSeconds)
        }
        try {
            contentResolver.update(uri, values, null, null)
        } catch (_: Exception) {}

        try {
            contentResolver.notifyChange(uri, null)
        } catch (_: Exception) {}

        // Scan path file fisik jika memungkinkan
        try {
            getFilePathFromUri(uri)?.let { physicalPath ->
                MediaScannerConnection.scanFile(applicationContext, arrayOf(physicalPath), null, null)
            }
        } catch (_: Exception) {}

        // 9. Invalidate semua level cache (QuickPic Thumbnail Cache & Coil ImageLoader)
        thumbnailCache?.invalidate(uri)
        try {
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
        } catch (_: Exception) {}

        return RotateResult(uri, true)
    } catch (e: Throwable) {
        return RotateResult(uri, false, "${e.javaClass.simpleName}: ${e.message ?: "Error tidak diketahui"}")
    } finally {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}

// ---------------------------------------------------------------------------
// Rotasi Video Metadata
// ---------------------------------------------------------------------------

private fun Context.rotateVideoMetadata(
    uri: Uri,
    degrees: Int,
    thumbnailCache: ThumbnailCache?,
): RotateResult {
    return runCatching {
        val projection = arrayOf(MediaStore.Video.Media.ORIENTATION)
        val current = contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } ?: 0

        val newRotation = ((current + degrees) % 360 + 360) % 360

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.ORIENTATION, newRotation)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }
        val updated = contentResolver.update(uri, values, null, null)
        try {
            contentResolver.notifyChange(uri, null)
        } catch (_: Exception) {}

        thumbnailCache?.invalidate(uri)
        try {
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
        } catch (_: Exception) {}

        if (updated > 0) {
            RotateResult(uri, true)
        } else {
            RotateResult(uri, false, "Metadata orientasi video tidak dapat diupdate.")
        }
    }.getOrElse { e ->
        RotateResult(uri, false, "${e.javaClass.simpleName}: ${e.message ?: "Error tidak diketahui"}")
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun exifOrientationToDegrees(orientation: Int): Int = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> 90
    ExifInterface.ORIENTATION_ROTATE_180 -> 180
    ExifInterface.ORIENTATION_ROTATE_270 -> 270
    else -> 0
}

private fun Context.getFilePathFromUri(uri: Uri): String? {
    val projection = arrayOf(MediaStore.MediaColumns.DATA)
    return try {
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    } catch (_: Exception) {
        null
    }
}
