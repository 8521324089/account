package com.kb.ledger.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * Interface for image processing, decoding validation, downsampling, EXIF orientation correction,
 * and privacy sanitization (GPS metadata stripping).
 */
interface ReceiptImageProcessor {
    fun isDecodable(file: File): Boolean
    suspend fun processAndSanitizeFile(
        inputFile: File,
        outputFile: File,
        maxDimension: Int = 2048,
        quality: Int = 85
    ): Boolean

    suspend fun processAndSanitizeUri(
        contentResolver: ContentResolver,
        sourceUri: Uri,
        outputFile: File,
        maxDimension: Int = 2048,
        quality: Int = 85
    ): Boolean
}

/**
 * Production implementation of [ReceiptImageProcessor] using Android's native BitmapFactory,
 * Matrix, and ExifInterface.
 */
class AndroidReceiptImageProcessor(
    private val context: Context
) : ReceiptImageProcessor {

    companion object {
        fun isSupportedMimeType(mimeType: String?): Boolean {
            if (mimeType == null) return false
            val lower = mimeType.lowercase()
            return lower == "image/jpeg" || lower == "image/jpg" || lower == "image/png" || lower == "image/webp" || lower == "image/x-webp"
        }
    }

    override fun isDecodable(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0 && isSupportedMimeType(options.outMimeType)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun processAndSanitizeFile(
        inputFile: File,
        outputFile: File,
        maxDimension: Int,
        quality: Int
    ): Boolean = withContext(Dispatchers.IO) {
        if (!inputFile.exists() || inputFile.length() == 0L) return@withContext false

        val tempOut = File(outputFile.parentFile ?: inputFile.parentFile, "proc_tmp_${UUID.randomUUID()}.jpg")
        try {
            // 1. Inspect bounds & mime type
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(inputFile.absolutePath, boundsOptions)
            val srcWidth = boundsOptions.outWidth
            val srcHeight = boundsOptions.outHeight
            if (srcWidth <= 0 || srcHeight <= 0 || !isSupportedMimeType(boundsOptions.outMimeType)) {
                return@withContext false
            }

            // 2. Calculate inSampleSize to avoid unbounded memory loading
            var inSampleSize = 1
            val maxSrcDim = maxOf(srcWidth, srcHeight)
            while ((maxSrcDim / (inSampleSize * 2)) >= maxDimension) {
                inSampleSize *= 2
            }

            // 3. Decode sampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampledBitmap = BitmapFactory.decodeFile(inputFile.absolutePath, decodeOptions)
                ?: return@withContext false

            // 4. Read EXIF orientation before any modification
            val orientation = try {
                val exif = ExifInterface(inputFile.absolutePath)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            // 5. Apply EXIF rotation / flip matrix
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
            }

            // Further scale down if sampled bitmap still exceeds maxDimension
            val rotatedWidth = if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) sampledBitmap.height else sampledBitmap.width
            val rotatedHeight = if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) sampledBitmap.width else sampledBitmap.height
            val currentMaxDim = maxOf(rotatedWidth, rotatedHeight)
            if (currentMaxDim > maxDimension) {
                val scale = maxDimension.toFloat() / currentMaxDim.toFloat()
                matrix.postScale(scale, scale)
            }

            val finalBitmap = if (!matrix.isIdentity) {
                Bitmap.createBitmap(sampledBitmap, 0, 0, sampledBitmap.width, sampledBitmap.height, matrix, true).also {
                    if (it != sampledBitmap) sampledBitmap.recycle()
                }
            } else {
                sampledBitmap
            }

            // 6. Write cleanly to temporary file
            // Writing freshly compressed JPEG strips all EXIF metadata (GPS, camera serials, etc.)
            BufferedOutputStream(FileOutputStream(tempOut)).use { outStream ->
                val compressed = finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), outStream)
                outStream.flush()
                if (!compressed) {
                    finalBitmap.recycle()
                    tempOut.delete()
                    return@withContext false
                }
            }
            finalBitmap.recycle()

            // 7. Atomic move to outputFile
            if (outputFile.exists()) outputFile.delete()
            val moved = tempOut.renameTo(outputFile)
            if (!moved) {
                tempOut.copyTo(outputFile, overwrite = true)
                tempOut.delete()
            }

            outputFile.exists() && outputFile.length() > 0
        } catch (_: Exception) {
            tempOut.delete()
            false
        }
    }

    override suspend fun processAndSanitizeUri(
        contentResolver: ContentResolver,
        sourceUri: Uri,
        outputFile: File,
        maxDimension: Int,
        quality: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val rawTemp = File(outputFile.parentFile ?: context.cacheDir, "raw_uri_${UUID.randomUUID()}.tmp")
        try {
            contentResolver.openInputStream(sourceUri)?.use { input ->
                rawTemp.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext false

            if (!rawTemp.exists() || rawTemp.length() == 0L) {
                rawTemp.delete()
                return@withContext false
            }

            val success = processAndSanitizeFile(rawTemp, outputFile, maxDimension, quality)
            rawTemp.delete()
            success
        } catch (_: Exception) {
            rawTemp.delete()
            false
        }
    }
}
