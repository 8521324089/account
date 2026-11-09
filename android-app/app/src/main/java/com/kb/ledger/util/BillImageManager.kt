package com.kb.ledger.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Manages bill/receipt images for ledger transactions.
 *
 * ## Problem
 * Android `content://` URIs granted during image picker sessions are transient.
 * They expire across device reboots (unless `takePersistableUriPermission` was called),
 * and they are meaningless on any other device. Storing a raw `content://` string in the
 * database creates a time-bomb: the image disappears silently after a reboot, a gallery
 * app update, or a device transfer.
 *
 * ## Solution
 * All bill images are strictly managed inside app-private internal storage
 * (`context.filesDir/receipts/<txId>.jpg`).
 *
 * - When taking a photo inside Ledger: Captured directly into private internal receipts storage
 *   via FileProvider. Never saved to Gallery, DCIM, Pictures, or shared external storage.
 * - When picking from Gallery: Bytes are read from the content URI and copied into private internal
 *   receipts storage. The original Gallery file is left 100% untouched.
 * - When viewing/editing/deleting: Only the private internal file is accessed or deleted.
 *   The phone's Gallery is never modified, moved, or deleted.
 *
 * ## Guarantees
 * 1. The original gallery/content:// bytes are never mutated, moved, or deleted.
 * 2. Taking a photo captures directly into `context.filesDir/receipts/` without touching Gallery.
 * 3. The internal copy is written to a staging path first and verified (non-empty bytes).
 * 4. Staging files are committed atomically to their canonical `<txId>.jpg` filename on save.
 * 5. Discarded entries clean up their staging files immediately.
 */
class BillImageManager(
    private val context: Context,
    val imageProcessor: ReceiptImageProcessor = AndroidReceiptImageProcessor(context)
) {

    val receiptsDir: File
        get() = File(context.filesDir, "receipts").also { it.mkdirs() }

    // ── Camera capture (directly into private storage) ────────────────────────

    /**
     * Creates a new temporary staging file in app-private receipts directory for camera capture.
     * The camera application will write directly into this file via FileProvider.
     * No image is written to Gallery, DCIM, or external shared storage.
     */
    fun createCameraStagingFile(): File {
        return File(receiptsDir, "camera_stage_${UUID.randomUUID()}.jpg")
    }

    /**
     * Returns a FileProvider content URI for [file] in receipts storage.
     * This URI can be passed to [androidx.activity.result.contract.ActivityResultContracts.TakePicture].
     */
    fun getCameraUri(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Validates, downsamples, corrects EXIF orientation, and strips sensitive metadata
     * from a freshly captured camera staging file.
     *
     * Returns the sanitized [File] on success, or null if the capture was empty, corrupt, or invalid.
     */
    suspend fun processCameraCapture(capturedFile: File): File? = withContext(Dispatchers.IO) {
        if (!capturedFile.exists() || capturedFile.length() == 0L) {
            capturedFile.delete()
            return@withContext null
        }

        val sanitizedFile = File(receiptsDir, "camera_sanitized_${UUID.randomUUID()}.jpg")
        val success = imageProcessor.processAndSanitizeFile(capturedFile, sanitizedFile)
        capturedFile.delete()

        if (success && sanitizedFile.exists() && sanitizedFile.length() > 0) {
            sanitizedFile
        } else {
            sanitizedFile.delete()
            null
        }
    }

    // ── Gallery picking (copy-into-private, leaving original untouched) ──────

    /**
     * Reads the image bytes from [sourceUri] (Gallery/Picker content:// URI), validates decodability,
     * downsamples, corrects orientation, strips GPS metadata, and writes a private copy
     * to a staging file in `context.filesDir/receipts/`.
     *
     * The source image is completely untouched. Only read stream is opened.
     * Returns the staged File on success, or null if reading or validation failed.
     */
    suspend fun copyUriToStagingFile(sourceUri: Uri): File? = withContext(Dispatchers.IO) {
        val stagedFile = File(receiptsDir, "gallery_stage_${UUID.randomUUID()}.jpg")
        val success = imageProcessor.processAndSanitizeUri(context.contentResolver, sourceUri, stagedFile)
        if (success && stagedFile.exists() && stagedFile.length() > 0) {
            stagedFile
        } else {
            stagedFile.delete()
            null
        }
    }

    /**
     * Checks if [file] exists, is non-empty, and decodable.
     */
    fun isImageValid(file: File): Boolean {
        return imageProcessor.isDecodable(file)
    }

    // ── Commit & Cleanup staging ──────────────────────────────────────────────

    /**
     * Commits a staged image (from camera capture or gallery copy) to the canonical
     * transaction bill file: `context.filesDir/receipts/<txId>.jpg`.
     *
     * Returns the canonical absolute path on success, or null if failed.
     */
    suspend fun commitStagedImage(stagedPath: String, txId: String): String? = withContext(Dispatchers.IO) {
        val stagedFile = File(stagedPath)
        val finalFile = internalFileForTx(txId)

        if (!stagedFile.exists() || stagedFile.length() == 0L) {
            return@withContext if (finalFile.exists() && finalFile.length() > 0) finalFile.absolutePath else null
        }

        // If stagedFile is already the final file, nothing to do.
        if (stagedFile.canonicalPath == finalFile.canonicalPath) {
            return@withContext finalFile.absolutePath
        }

        try {
            if (finalFile.exists()) finalFile.delete()
            val moved = stagedFile.renameTo(finalFile)
            if (!moved) {
                stagedFile.copyTo(finalFile, overwrite = true)
                stagedFile.delete()
            }
            if (finalFile.exists() && finalFile.length() > 0) {
                finalFile.absolutePath
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Deletes a temporary staging file if user cancelled or discarded transaction entry.
     * Only deletes files matching `camera_stage_`, `gallery_stage_`, or `pick_tmp_`.
     */
    fun deleteStagingFile(path: String?): Boolean {
        if (path == null) return false
        val file = File(path)
        if (file.parentFile?.canonicalPath == receiptsDir.canonicalPath &&
            (file.name.startsWith("camera_stage_") || file.name.startsWith("camera_sanitized_") || file.name.startsWith("gallery_stage_") || file.name.startsWith("pick_tmp_"))
        ) {
            return file.delete()
        }
        return false
    }

    /**
     * Cleans up orphaned staging files older than 1 hour.
     */
    fun cleanStaleStagingFiles() {
        try {
            val cutoff = System.currentTimeMillis() - 3_600_000L
            receiptsDir.listFiles()?.forEach { file ->
                if ((file.name.startsWith("camera_stage_") || file.name.startsWith("camera_sanitized_") || file.name.startsWith("gallery_stage_") || file.name.startsWith("pick_tmp_")) &&
                    file.lastModified() < cutoff
                ) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
            // Best effort
        }
    }


    // ── Copy on pick ─────────────────────────────────────────────────────────

    /**
     * Copies the image at [sourceUri] (typically a picker `content://` URI) into
     * app-private internal storage for [txId].
     *
     * Returns the absolute path of the internal copy on success, or null if the copy
     * failed (caller should fall back to storing [sourceUri] string and report the issue).
     *
     * The operation is idempotent: if an internal copy already exists and [overwriteExisting]
     * is false (the default), the existing copy's path is returned without re-reading the source.
     */
    suspend fun copyToInternalStorage(
        sourceUri: Uri,
        txId: String,
        overwriteExisting: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val finalFile = internalFileForTx(txId)

        // Honour existing copy unless caller explicitly wants to overwrite.
        if (!overwriteExisting && finalFile.exists() && finalFile.length() > 0) {
            return@withContext finalFile.absolutePath
        }

        val tempFile = File(receiptsDir, "pick_tmp_${txId}.jpg")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null // Cannot open source

            // Verify the copy is non-empty.
            if (!tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                return@withContext null
            }

            // Atomic rename to final path.
            if (finalFile.exists()) finalFile.delete()
            val moved = tempFile.renameTo(finalFile)
            if (!moved) {
                // renameTo can fail across mount points on some devices; fall back to copy.
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            if (finalFile.exists() && finalFile.length() > 0) {
                finalFile.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            tempFile.delete()
            null
        }
    }

    // ── Migration helper ─────────────────────────────────────────────────────

    /**
     * Attempts to migrate a legacy `content://` URI stored in a transaction into
     * app-private internal storage.
     *
     * This is a best-effort, idempotent operation:
     * - If the URI is already an internal path (starts with filesDir), returns it unchanged.
     * - If the URI is a content:// scheme, attempts to copy and returns the new internal path.
     * - If the copy fails (e.g., URI permission expired), returns the original URI string
     *   and logs the failure — it does NOT throw and does NOT discard the original reference.
     *
     * [txId] is the UUID of the transaction (used as the internal filename).
     * [existingUri] is the current value of `Transaction.imageUri`.
     */
    suspend fun migrateContentUriToInternal(
        txId: String,
        existingUri: String
    ): String = withContext(Dispatchers.IO) {
        val internalPrefix = context.filesDir.absolutePath

        // Already an internal path — no migration needed.
        if (existingUri.startsWith(internalPrefix) || existingUri.startsWith("/")) {
            val existingFile = File(existingUri)
            if (existingFile.exists() && existingFile.length() > 0) {
                return@withContext existingUri
            }
            // Internal path but file is missing — fall through and report.
        }

        // If it is not a content URI, we cannot migrate it.
        if (!existingUri.startsWith("content://")) {
            return@withContext existingUri
        }

        val uri = try { Uri.parse(existingUri) } catch (_: Exception) { return@withContext existingUri }

        val internalPath = copyToInternalStorage(uri, txId, overwriteExisting = false)
        return@withContext internalPath ?: existingUri // keep original on failure
    }

    // ── Query helpers ────────────────────────────────────────────────────────

    /**
     * Returns the canonical internal file for [txId].
     * The file may or may not exist; callers must check [File.exists].
     */
    fun internalFileForTx(txId: String): File =
        File(receiptsDir, "${txId}.jpg")

    /**
     * Returns true if a valid (non-empty) internal bill image exists for [txId].
     */
    fun hasInternalImage(txId: String): Boolean {
        val f = internalFileForTx(txId)
        return f.exists() && f.length() > 0
    }

    /**
     * Deletes the internal bill image for [txId] if it exists.
     * Call this only when the transaction itself is being hard-deleted and you are
     * certain no backup references the file.
     *
     * NOTE: Do NOT call this for soft-deleted transactions; the image may be needed
     * for backup/restore or tombstone synchronization.
     */
    fun deleteInternalImage(txId: String): Boolean {
        val f = internalFileForTx(txId)
        return if (f.exists()) f.delete() else false
    }

    // ── Content URI persistence ──────────────────────────────────────────────

    /**
     * Attempts to take a persistable URI permission grant for [uri].
     * This extends the life of a content:// grant across reboots for the app's process
     * and is a defence-in-depth measure complementing the internal copy.
     *
     * Safe to call even if the URI does not support persistable grants (the exception
     * is silently swallowed).
     */
    fun tryTakePersistablePermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Not all URIs support persistable grants; ignore.
        }
    }
}
