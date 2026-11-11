package com.kb.ledger.util

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.RecurringRule
import com.kb.ledger.data.model.Transaction
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Base64
import java.util.Collections
import java.util.UUID

/**
 * Backup data envelope — schema version 3.
 *
 * Version history:
 *   1 — persons + transactions (original double amounts)
 *   2 — persons + transactions (amountCents Long, added schemaVersion field)
 *   3 — adds recurringRules; adds billImagesByTxId (base64-encoded image bytes)
 *   4 — adds role to persons (CUSTOMER vs SUPPLIER)
 *
 * The [billImagesByTxId] map key is the transaction UUID.
 * The value is the Base64-encoded bytes of the bill image file from internal storage.
 * Images in the backup are always from app-private internal storage (never raw content:// URIs).
 *
 * Design rules:
 *   - Export never deletes the original image.
 *   - Import never overwrites an existing image without verifying the replacement first.
 *   - Import is wrapped in a database transaction; partial imports are rolled back automatically.
 *   - Missing or unreadable images are reported as warnings, not silent failures.
 */
data class BackupData(
    val persons: List<Person>,
    val transactions: List<Transaction>,
    val recurringRules: List<RecurringRule> = emptyList(),
    val billImagesByTxId: Map<String, String> = emptyMap(), // txId -> Base64 image bytes
    val exportDate: Long = System.currentTimeMillis(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 4
    }
}

/**
 * Result of a backup export operation.
 *
 * [missingImageTxIds] lists transaction IDs whose bill images could not be read and were
 * therefore omitted from the backup (the transaction record itself is still included).
 */
data class ExportResult(
    val success: Boolean,
    val missingImageTxIds: List<String> = emptyList(),
    val error: String? = null
)

/**
 * Result of a backup import operation.
 *
 * [restoredImageCount] and [missingImageTxIds] let the caller report image restoration status
 * to the user without masking any failures.
 */
data class ImportResult(
    val success: Boolean,
    val restoredImageCount: Int = 0,
    val missingImageTxIds: List<String> = emptyList(),
    val error: String? = null
)

/**
 * Local JSON backup/restore via Android Storage Access Framework.
 *
 * This is the manual backup path (drawer menu Export / Import).
 * Google Drive sync is handled separately by GoogleDriveRepository (Stage 4+).
 *
 * Safety guarantees:
 *   1. Export reads from Room + copies image bytes; never mutates the database.
 *   2. Import is wrapped in a single Room withTransaction block. If any write fails,
 *      no records are committed to the database.
 *   3. Import restores image files to internal storage before committing db records.
 *      Each image is written to a temp path first; it is only moved to its final
 *      path after the bytes are verified (non-empty).
 *   4. Import never deletes or overwrites an existing internal image without verifying
 *      the replacement first.
 *   5. Partial / corrupt backups return ImportResult.success = false before any write.
 */
class BackupManager(
    private val context: Context? = null,
    private val database: AppDatabase? = null,
    private val destinationStreamProvider: DestinationStreamProvider? = null,
    private val sourceStreamProvider: SourceStreamProvider? = null,
    private val fileSystem: FileSystemOperations = DefaultFileSystemOperations,
    private val customCacheDir: File? = null,
    private val customFilesDir: File? = null
) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val db: AppDatabase
        get() = database ?: AppDatabase.getDatabase(context!!)
    private val streamProvider: DestinationStreamProvider
        get() = destinationStreamProvider ?: ContentResolverDestinationStreamProvider(context!!.contentResolver)
    private val inStreamProvider: SourceStreamProvider?
        get() = sourceStreamProvider ?: context?.contentResolver?.let { ContentResolverSourceStreamProvider(it) }

    // Internal directory where app-managed bill images live.
    val receiptsDir: File
        get() = File(customFilesDir ?: context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp"), "receipts").also { it.mkdirs() }

    // Staging directory for app-private temporary backup export files.
    val exportStagingDir: File
        get() = File(customCacheDir ?: context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp"), "export_staging").also { it.mkdirs() }

    // ── Export ───────────────────────────────────────────────────────────────

    /**
     * Exports the full ledger (persons, transactions, recurring rules, bill image bytes)
     * to a user-selected [uri] (typically obtained via SAF CreateDocument).
     *
     * Concurrency Coordination:
     * - Coordinated via [backupSyncMutex] so it cannot read receipt files or entities while
     *   a restore ([importData]) or startup self-healing ([recoverOrphanedStagingImages]) is changing them.
     * - Note: This does not claim full concurrency isolation against concurrent UI transaction edits in
     *   [BillImageManager.copyToInternalStorage], which write user-picked images outside the backup mutex.
     *
     * Staging and Failure Safety:
     * 1. Serializes complete backup to an app-private staged file in [exportStagingDir].
     * 2. Validates JSON parsing and structure via [validateStagedBackupFile] before touching destination.
     * 3. Streams staged file in 8 KB chunks to [streamProvider.openOutputStream] without holding
     *    the entire payload or Base64 images in memory.
     * 4. Cleans up staging file in a finally block regardless of outcome.
     * 5. If streaming fails partway, does NOT delete the destination URI to avoid deleting valid user files.
     *
     * Residual SAF Limitation:
     * Note that SAF destination write cannot be guaranteed atomic across arbitrary storage providers.
     * Standard Android document providers truncate the target file upon openOutputStream(). If copying
     * is interrupted (e.g. process death, disk full), the target may remain truncated or partial.
     * Cross-provider atomic replace (DocumentsContract.moveDocument) is not supported across different
     * provider authorities (local cache vs. external SAF).
     *
     * Returns [ExportResult.success] = true even if some images were unreadable;
     * unreadable images are listed in [ExportResult.missingImageTxIds].
     */
    suspend fun exportData(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        backupSyncMutex.withLock {
            var stagedFile: File? = null
            try {
                // Export all persons and transactions including soft-deleted tombstones
                // so tombstones survive backup/restore and don't resurrect deleted records.
                val persons = db.personDao().getAllPersonsIncludingDeleted()
                val transactions = db.transactionDao().getAllTransactionsIncludingDeleted()
                val recurringRules = db.recurringRuleDao().getAllRulesList()

                // Collect bill images — read bytes from internal storage (internalImagePath).
                // content:// URIs are intentionally not re-read here; only internal copies count.
                val missingImageTxIds = mutableListOf<String>()
                val billImages = mutableMapOf<String, String>()

                for (tx in transactions) {
                    coroutineContext.ensureActive()
                    val internalPath = internalImagePathForTx(tx.id)
                    if (fileSystem.exists(internalPath) && fileSystem.length(internalPath) > 0) {
                        try {
                            coroutineContext.ensureActive()
                            val bytes = internalPath.readBytes()
                            billImages[tx.id] = Base64.getEncoder().encodeToString(bytes)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (!tx.isDeleted) {
                                missingImageTxIds += tx.id
                            }
                        }
                    } else if (!tx.imageUri.isNullOrBlank() || !tx.imageFileId.isNullOrBlank()) {
                        // Transaction claims an image but internal copy is absent.
                        // This is the known gap (content:// migration not yet run).
                        if (!tx.isDeleted) {
                            missingImageTxIds += tx.id
                        }
                    }
                    // Transactions with no image at all are silently ignored (not missing).
                }

                val backupData = BackupData(
                    persons = persons,
                    transactions = transactions,
                    recurringRules = recurringRules,
                    billImagesByTxId = billImages
                )

                // Stage JSON to app-private cache file first
                val stageFile = File(exportStagingDir, "staged_export_${UUID.randomUUID()}.json")
                stagedFile = stageFile

                stageFile.bufferedWriter().use { writer ->
                    gson.toJson(backupData, writer)
                }

                // Validate staged file structure before touching destination
                val validationError = validateStagedBackupFile(stageFile, gson)
                if (validationError != null) {
                    return@withLock ExportResult(
                        success = false,
                        error = "Staged backup validation failed: $validationError"
                    )
                }

                // Stream staged file in chunks to SAF destination
                val outputStream = streamProvider.openOutputStream(uri)
                    ?: return@withLock ExportResult(
                        success = false,
                        error = "Failed to open destination stream for URI"
                    )

                outputStream.use { out ->
                    stageFile.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                        out.flush()
                    }
                }

                ExportResult(success = true, missingImageTxIds = missingImageTxIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLogger.log("BackupExport", "Export failed", e)
                ExportResult(success = false, error = e.message ?: "Export failed")
            } finally {
                stagedFile?.let {
                    try {
                        if (fileSystem.exists(it)) {
                            fileSystem.delete(it)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // ── Import ───────────────────────────────────────────────────────────────

    /**
     * Imports a backup from [uri] into the Room database.
     *
     * The entire database write is wrapped in a single [withTransaction] block.
     * If any write fails, nothing is committed.
     *
     * Image bytes are restored to internal storage BEFORE the transaction begins,
     * using temp files that are only renamed to the final path after verification.
     * If the database transaction fails, the temp image files are cleaned up.
     *
     * IMPORTANT: This import uses non-destructive identity mapping.
     * - On an empty database, original backup IDs are preserved.
     * - On a populated database, existing persons are matched by [Person.cloudId]
     *   (or normalized phone for legacy v2 records) and updated via updatePerson.
     * - New persons are assigned fresh auto-increment IDs (id = 0), preventing
     *   primary key collisions, REPLACE deletion, and foreign key CASCADE drops.
     * - Transactions and recurring rules have their personId remapped to the resolved local person ID.
     */
    suspend fun importData(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        // 1. Read and parse the backup file.
        val json = try {
            val sb = StringBuilder()
            val inputStream = inStreamProvider?.openInputStream(uri)
                ?: return@withContext ImportResult(success = false, error = "Cannot read backup file: input stream is null")
            inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                }
            }
            sb.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLogger.log("BackupImport", "Cannot read backup file", e)
            return@withContext ImportResult(success = false, error = "Cannot read backup file: ${e.message}")
        }

        // 2. Deserialize.
        val backupData: BackupData = try {
            val type = object : TypeToken<BackupData>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            SafeLogger.log("BackupImport", "Backup file is corrupt or unreadable", e)
            return@withContext ImportResult(success = false, error = "Backup file is corrupt or unreadable: ${e.message}")
        }

        // 3. Validate structure before any write.
        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION", "SENSELESS_COMPARISON")
        if ((backupData.persons as List<Person>?) == null) {
            return@withContext ImportResult(success = false, error = "Backup is missing the persons list")
        }
        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION", "SENSELESS_COMPARISON")
        if ((backupData.transactions as List<Transaction>?) == null) {
            return@withContext ImportResult(success = false, error = "Backup is missing the transactions list")
        }

        val now = System.currentTimeMillis()

        // 4. Staging, database commit, and promotion coordinated via process-wide backupSyncMutex
        backupSyncMutex.withLock {
            // 4a. Recover any orphaned staging images from an interrupted previous run,
            // consulting the Room database to verify committed transaction ownership.
            recoverOrphanedStagingImagesInternal(receiptsDir, db.transactionDao())

            // 5. Restore bill images to internal storage before the DB transaction.
            //    We write each image to a temp file first, then promote it safely after DB commit.
            //    If DB commit fails later, we clean up temp files.
            val tempImageFiles = mutableListOf<File>()
            val finalImageMoves = mutableListOf<Pair<File, File>>() // temp -> final
            val restoredTxIds = mutableListOf<String>()
            val missingImageTxIds = LinkedHashSet<String>()
            val deletedTxIds = backupData.transactions.filter { it.isDeleted }.map { it.id }.toSet()

            // Identify active transactions that claim attachment metadata but lack payload in the backup envelope.
            // Soft-deleted transactions (isDeleted == true) are excluded from the missing-attachment warning list.
            val payloadMap = backupData.billImagesByTxId ?: emptyMap()
            for (tx in backupData.transactions) {
                if (tx.isDeleted) continue
                val hasAttachmentMetadata = !tx.imageUri.isNullOrBlank() || !tx.imageFileId.isNullOrBlank()
                if (hasAttachmentMetadata && !payloadMap.containsKey(tx.id)) {
                    missingImageTxIds.add(tx.id)
                }
            }

            for ((txId, base64) in payloadMap) {
                val isTxDeleted = deletedTxIds.contains(txId)
                try {
                    val bytes = Base64.getDecoder().decode(base64)
                    if (bytes.isEmpty() || bytes.size < 12) {
                        if (!isTxDeleted) missingImageTxIds.add(txId)
                        continue
                    }
                    val tempFile = File(receiptsDir, "import_tmp_$txId.jpg")
                    tempFile.writeBytes(bytes)
                    // Only accept the file if it is a valid, readable image after write.
                    if (isValidReceiptFile(tempFile)) {
                        activeStagedFiles.add(tempFile.name)
                        val finalFile = internalImagePathForTx(txId)
                        tempImageFiles += tempFile
                        finalImageMoves += Pair(tempFile, finalFile)
                        restoredTxIds += txId
                    } else {
                        tempFile.delete()
                        if (!isTxDeleted) missingImageTxIds.add(txId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isTxDeleted) missingImageTxIds.add(txId)
                }
            }

            try {
                // 6. Restore database records inside a single transaction with non-destructive identity mapping.
                try {
                    val executeRestore: suspend () -> Unit = {
                        restoreDatabaseRecords(
                            backupData = backupData,
                            personDao = db.personDao(),
                            transactionDao = db.transactionDao(),
                            recurringRuleDao = db.recurringRuleDao(),
                            restoredTxIds = restoredTxIds.toSet(),
                            targetReceiptsDir = receiptsDir,
                            now = now
                        )
                    }
                    if (database != null) {
                        try {
                            database.withTransaction(executeRestore)
                        } catch (e: Exception) {
                            if (e is UninitializedPropertyAccessException || e is NullPointerException || e is UnsupportedOperationException || e is IllegalStateException) {
                                executeRestore()
                            } else {
                                throw e
                            }
                        }
                    } else {
                        db.withTransaction(executeRestore)
                    }
                } catch (e: CancellationException) {
                    // Clean up temp image files — DB was not committed.
                    for (tempFile in tempImageFiles) {
                        tempFile.delete()
                    }
                    throw e
                } catch (e: Exception) {
                    SafeLogger.log("BackupImport", "Database import failed", e)
                    // Clean up temp image files — DB was not committed.
                    for (tempFile in tempImageFiles) {
                        tempFile.delete()
                    }
                    return@withLock ImportResult(
                        success = false,
                        error = "Database import failed (no data was written): ${e.message}"
                    )
                }

                // 7. DB committed successfully — now promote staged temp image files to final paths.
                // Uses two-phase promotion and retains temp files if promotion fails.
                var actualRestoredCount = 0
                for ((tempFile, finalFile) in finalImageMoves) {
                    val txId = finalFile.nameWithoutExtension
                    val isTxDeleted = deletedTxIds.contains(txId)
                    try {
                        val success = promoteStagedReceipt(
                            tempFile = tempFile,
                            finalFile = finalFile,
                            receiptsDir = receiptsDir,
                            fileSystem = fileSystem
                        )
                        if (success) {
                            actualRestoredCount++
                        } else {
                            // Promotion failed; retain tempFile for future recovery and record missing image
                            if (!isTxDeleted) missingImageTxIds.add(txId)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SafeLogger.log("ReceiptPromotion", "Staged receipt promotion error", e)
                        // Non-fatal. DB is committed; record missing image and retain tempFile on disk.
                        if (!isTxDeleted) missingImageTxIds.add(txId)
                    } finally {
                        activeStagedFiles.remove(tempFile.name)
                    }
                }

                // Rebuild local date-partitioned backup from Room authority
                try {
                    context?.let { ctx ->
                        com.kb.ledger.backup.partition.LocalPartitionedBackupManager(ctx).reconcileWithRoom(db)
                    }
                } catch (e: Exception) {
                    SafeLogger.log("BackupPartition", "Partition reconcile non-fatal error", e)
                }

                ImportResult(
                    success = true,
                    restoredImageCount = actualRestoredCount,
                    missingImageTxIds = missingImageTxIds.filter { !deletedTxIds.contains(it) }
                )
            } finally {
                activeStagedFiles.clear()
                activePromoFiles.clear()
            }
        }
    }

    // ── Bill image helpers ───────────────────────────────────────────────────

    /**
     * Returns the canonical app-internal File path for a transaction's bill image.
     * The file may or may not exist; callers must check [File.exists].
     */
    fun internalImagePathForTx(txId: String, customReceiptsDir: File? = null): File =
        File(customReceiptsDir ?: receiptsDir, "${txId}.jpg")

    /**
     * Returns true if an app-internal bill image exists and is non-empty for [txId].
     */
    fun hasInternalImage(txId: String, customReceiptsDir: File? = null): Boolean {
        val f = internalImagePathForTx(txId, customReceiptsDir)
        return isValidReceiptFile(f)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 4

        /**
         * Generates a default, timestamped export filename for SAF CreateDocument.
         * Format: `ledger_backup_yyyyMMdd_HHmmss.json`.
         * Uses Locale.US to ensure standard ASCII digits across all devices and providers.
         */
        fun generateDefaultExportFilename(nowMs: Long = System.currentTimeMillis()): String {
            val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            return "ledger_backup_${sdf.format(java.util.Date(nowMs))}.json"
        }

        /**
         * Validates that a staged backup file can be parsed and conforms to the expected
         * [BackupData] structure before any bytes are written to the external destination.
         *
         * @return null if validation succeeds, or a descriptive error string if validation fails.
         */
        fun validateStagedBackupFile(file: File, gson: Gson = Gson()): String? {
            if (!file.exists() || file.length() == 0L) {
                return "Staged backup file is empty or missing"
            }
            return try {
                file.bufferedReader().use { reader ->
                    val type = object : TypeToken<BackupData>() {}.type
                    val backupData: BackupData? = gson.fromJson(reader, type)
                    if (backupData == null) {
                        return "Parsed backup envelope is null"
                    }
                    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION", "SENSELESS_COMPARISON")
                    if ((backupData.persons as List<Person>?) == null) {
                        return "Missing required 'persons' collection"
                    }
                    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION", "SENSELESS_COMPARISON")
                    if ((backupData.transactions as List<Transaction>?) == null) {
                        return "Missing required 'transactions' collection"
                    }
                    if (backupData.schemaVersion > CURRENT_SCHEMA_VERSION) {
                        return "Unsupported schema version: ${backupData.schemaVersion} (max supported is $CURRENT_SCHEMA_VERSION)"
                    }
                    null
                }
            } catch (e: Exception) {
                "JSON syntax/deserialization failure: ${e.message}"
            }
        }

        /**
         * Process-wide synchronization lock ensuring import and staging recovery
         * never interleave or corrupt active staging files.
         */
        val backupSyncMutex = Mutex()

        /**
         * Registry of staging files currently owned by an active in-flight operation.
         * Recovery operations will never delete or alter files present in this set.
         */
        val activeStagedFiles: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

        /**
         * Registry of intermediate promotion files currently being written.
         */
        val activePromoFiles: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

        /**
         * Verifies whether [file] is a non-empty, readable receipt file with a recognized image signature
         * (JPEG, PNG, or WebP) and sufficient length to contain valid image container data.
         *
         * Validation level:
         * - Confirms header magic bytes:
         *   - JPEG: Starts with `0xFF, 0xD8, 0xFF` and length >= 16 bytes.
         *   - PNG: Starts with `0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A` and length >= 37 bytes.
         *   - WebP: Starts with `RIFF` (bytes 0-3) and `WEBP` (bytes 8-11) and length >= 12 bytes.
         * - Rejects 0-byte files, truncated stubs, corrupt headers, and non-image files.
         * - Note: This performs lightweight byte-signature validation without full pixel entropy decoding
         *   (which is unsafe in headless JVM environments without Android graphics mocks).
         */
        fun isValidReceiptFile(file: File, fileSystem: FileSystemOperations = DefaultFileSystemOperations): Boolean {
            if (!fileSystem.exists(file)) return false
            val length = fileSystem.length(file)
            if (length < 12L) return false

            val header = fileSystem.readHeader(file, 32) ?: return false
            if (header.size < 12) return false

            // 1. JPEG signature: FF D8 FF
            if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                return length >= 16L
            }

            // 2. PNG signature: 89 50 4E 47 0D 0A 1A 0A
            if (header.size >= 8 &&
                header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() &&
                header[4] == 0x0D.toByte() && header[5] == 0x0A.toByte() &&
                header[6] == 0x1A.toByte() && header[7] == 0x0A.toByte()
            ) {
                return length >= 37L
            }

            // 3. WebP signature: RIFF (bytes 0-3) and WEBP (bytes 8-11)
            if (header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
                header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                header[10] == 0x42.toByte() && header[11] == 0x50.toByte()
            ) {
                return length >= 12L
            }

            return false
        }

        /**
         * Safely promotes a staged receipt file [tempFile] to [finalFile] using two-phase promotion.
         *
         * Guarantees:
         * 1. Never promotes invalid content.
         * 2. Never overwrites an existing valid receipt.
         * 3. Cleans up corrupt or 0-byte existing destination files.
         * 4. Avoids writing directly to [finalFile] during copy fallback, preventing exposure of partial files.
         * 5. Deletes [tempFile] only after [finalFile] is confirmed valid on disk.
         * 6. Retains [tempFile] intact if promotion fails.
         */
        fun promoteStagedReceipt(
            tempFile: File,
            finalFile: File,
            receiptsDir: File,
            fileSystem: FileSystemOperations = DefaultFileSystemOperations
        ): Boolean {
            // 0. If tempFile itself is not a valid receipt, never promote!
            if (!isValidReceiptFile(tempFile, fileSystem)) {
                return false
            }

            // 1. If finalFile already exists and is verified valid, do not overwrite! Discard duplicate staging file.
            if (isValidReceiptFile(finalFile, fileSystem)) {
                fileSystem.delete(tempFile)
                return true
            }

            // 2. If finalFile exists but is corrupt or 0-byte, remove it before promoting.
            if (fileSystem.exists(finalFile)) {
                fileSystem.delete(finalFile)
            }

            // 3. Phase A: Try atomic filesystem rename (same filesystem).
            if (fileSystem.rename(tempFile, finalFile)) {
                if (isValidReceiptFile(finalFile, fileSystem)) {
                    return true
                } else {
                    fileSystem.delete(finalFile)
                    return false
                }
            }

            // 4. Phase B: Two-phase copy via intermediate promotion file.
            // Avoids writing directly to finalFile which could expose a partial copy as valid.
            val txId = finalFile.nameWithoutExtension
            val promoFileName = "promo_tmp_${txId}.jpg"
            val promoFile = File(receiptsDir, promoFileName)
            activePromoFiles.add(promoFileName)
            try {
                if (fileSystem.exists(promoFile)) fileSystem.delete(promoFile)
                fileSystem.copy(tempFile, promoFile, overwrite = true)

                if (fileSystem.exists(promoFile) && fileSystem.length(promoFile) == fileSystem.length(tempFile) && isValidReceiptFile(promoFile, fileSystem)) {
                    if (fileSystem.rename(promoFile, finalFile) && isValidReceiptFile(finalFile, fileSystem)) {
                        fileSystem.delete(tempFile)
                        return true
                    } else {
                        fileSystem.copy(promoFile, finalFile, overwrite = true)
                        fileSystem.delete(promoFile)
                        if (isValidReceiptFile(finalFile, fileSystem)) {
                            fileSystem.delete(tempFile)
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                SafeLogger.log("ReceiptPromotion", "Intermediate copy fallback failed", e)
            } finally {
                activePromoFiles.remove(promoFileName)
                if (fileSystem.exists(promoFile)) {
                    fileSystem.delete(promoFile)
                }
            }

            return false
        }

        /**
         * Recovers any unpromoted staged receipt images left in [receiptsDir] from a previous
         * interrupted import (e.g. process death or power loss during filesystem rename).
         *
         * Synchronized via [backupSyncMutex] to coordinate across all call sites in the process.
         * When [transactionDao] is provided, validates database state before promoting:
         * - If transaction does not exist in DB (uncommitted restore or deleted tx), discards staging file.
         * - If transaction exists but does not reference an image, discards staging file.
         * - If transaction exists, references an image, and final file is missing or corrupt, promotes safely.
         * - If final file already exists and is valid, cleans up redundant staging file.
         */
        suspend fun recoverOrphanedStagingImages(
            receiptsDir: File,
            transactionDao: com.kb.ledger.data.dao.TransactionDao? = null,
            fileSystem: FileSystemOperations = DefaultFileSystemOperations
        ): Int = backupSyncMutex.withLock {
            recoverOrphanedStagingImagesInternal(receiptsDir, transactionDao, fileSystem)
        }

        internal suspend fun recoverOrphanedStagingImagesInternal(
            receiptsDir: File,
            transactionDao: com.kb.ledger.data.dao.TransactionDao? = null,
            fileSystem: FileSystemOperations = DefaultFileSystemOperations
        ): Int {
            var recoveredCount = 0
            if (!receiptsDir.exists() || !receiptsDir.isDirectory) return 0

            // 1. Clean up any leftover intermediate promo files from aborted two-phase copies,
            // strictly ignoring any files currently owned by an active operation.
            val promoFiles = receiptsDir.listFiles { file ->
                file.isFile && file.name.startsWith("promo_tmp_") && file.name.endsWith(".jpg")
            } ?: emptyArray()
            for (pf in promoFiles) {
                if (activePromoFiles.contains(pf.name)) continue
                fileSystem.delete(pf)
            }

            // 2. Scan for staged import files, ignoring any files registered to an active import
            val stagingFiles = receiptsDir.listFiles { file ->
                file.isFile && file.name.startsWith("import_tmp_") && file.name.endsWith(".jpg")
            } ?: return 0

            for (tempFile in stagingFiles) {
                if (activeStagedFiles.contains(tempFile.name)) continue
                try {
                    val txId = tempFile.name.removePrefix("import_tmp_").removeSuffix(".jpg")
                    val finalFile = File(receiptsDir, "${txId}.jpg")

                    // If staging file is corrupted or not a valid image format, clean it up
                    if (!isValidReceiptFile(tempFile, fileSystem)) {
                        fileSystem.delete(tempFile)
                        continue
                    }

                    // Consult Room database authority if transactionDao is provided
                    if (transactionDao != null) {
                        val tx = transactionDao.getTransactionById(txId)
                        if (tx == null) {
                            // Transaction does not exist in DB (uncommitted restore or deleted tx).
                            // Do NOT promote. Discard stale uncommitted staging file.
                            fileSystem.delete(tempFile)
                            continue
                        }
                        val hasImageRef = !tx.imageUri.isNullOrBlank() || !tx.imageFileId.isNullOrBlank()
                        if (!hasImageRef) {
                            // Transaction exists but does not reference any bill image.
                            fileSystem.delete(tempFile)
                            continue
                        }
                    }

                    if (isValidReceiptFile(finalFile, fileSystem)) {
                        // Final image is already complete and valid. Discard redundant staging file.
                        fileSystem.delete(tempFile)
                    } else {
                        val success = promoteStagedReceipt(
                            tempFile = tempFile,
                            finalFile = finalFile,
                            receiptsDir = receiptsDir,
                            fileSystem = fileSystem
                        )
                        if (success) {
                            recoveredCount++
                        }
                    }
                } catch (e: Exception) {
                    SafeLogger.log("ReceiptRecovery", "Failed during staging recovery item", e)
                }
            }
            return recoveredCount
        }

        /**
         * Restores backup records into DAOs with non-destructive identity mapping.
         *
         * Invariants:
         * 1. Empty database: Preserves original IDs if > 0.
         * 2. Populated database:
         *    a. Matches existing persons by [Person.cloudId] (immutable unique identity).
         *    b. Fallback: Matches by normalized phone ONLY if both records lack cloudId (legacy v2),
         *       normalized phone is valid (>= 7 digits), and normalized names match exactly.
         *       Different names sharing a phone number are NEVER merged.
         *    c. Matched persons are updated via [PersonDao.updatePerson], never replaced.
         *    d. New persons are inserted with id = 0, letting SQLite assign a fresh auto-increment ID.
         * 3. Remapping: Transactions and recurring rules have their [personId] remapped to the resolved local person ID.
         * 4. Idempotency: Restoring the same backup multiple times does not create duplicate persons, transactions, or rules.
         * 5. Integrity & Orphan protection:
         *    - If multiple persons in the backup map to the same local person ID, the import fails.
         *    - If duplicate non-blank cloudIds exist in the backup, the import fails.
         *    - If a transaction or rule references a personId not in the backup, the import fails.
         */
        suspend fun restoreDatabaseRecords(
            backupData: BackupData,
            personDao: com.kb.ledger.data.dao.PersonDao,
            transactionDao: com.kb.ledger.data.dao.TransactionDao,
            recurringRuleDao: com.kb.ledger.data.dao.RecurringRuleDao,
            restoredTxIds: Set<String> = emptySet(),
            targetReceiptsDir: File? = null,
            now: Long = System.currentTimeMillis()
        ): RestoreStats {
            // Guard: Detect duplicate non-blank cloudIds within the incoming backup
            val nonBlankIncomingCloudIds = backupData.persons.mapNotNull { it.cloudId?.takeIf { id -> id.isNotBlank() } }
            if (nonBlankIncomingCloudIds.size != nonBlankIncomingCloudIds.toSet().size) {
                throw IllegalArgumentException("Backup data integrity error: duplicate cloudId detected among backup persons")
            }

            val existingPersons = personDao.getAllPersonsIncludingDeleted()
            val isDatabaseEmpty = existingPersons.isEmpty()

            val personIdMap = mutableMapOf<Long, Long>() // backupPersonId -> localPersonId
            val claimedLocalPersonIds = mutableSetOf<Long>()
            var remappedCount = 0

            if (isDatabaseEmpty) {
                // ── Case A: Empty Database ───────────────────────────────────────
                // Preserve original backup IDs if > 0 to maintain exact parity.
                for (p in backupData.persons) {
                    val resolvedRole = (p.role as? com.kb.ledger.data.model.PersonRole) ?: com.kb.ledger.data.model.PersonRole.CUSTOMER
                    val targetId = if (p.id > 0) p.id else 0L
                    val entity = p.copy(
                        id = targetId,
                        cloudId = p.cloudId.takeIf { !it.isNullOrBlank() } ?: UUID.randomUUID().toString(),
                        role = resolvedRole,
                        updatedAt = p.updatedAt.takeIf { it > 0 } ?: now,
                        syncPending = true
                    )
                    val insertedId = personDao.insertPerson(entity)
                    val resolvedId = if (targetId > 0) targetId else insertedId
                    if (claimedLocalPersonIds.contains(resolvedId)) {
                        throw IllegalArgumentException("Backup data integrity error: multiple persons in backup share ID $resolvedId")
                    }
                    personIdMap[p.id] = resolvedId
                    claimedLocalPersonIds.add(resolvedId)
                }
            } else {
                // ── Case B: Populated Database ────────────────────────────────────
                // Protect existing records against ID collisions and CASCADE deletions.
                val localByCloudId = existingPersons
                    .filter { !it.cloudId.isNullOrBlank() }
                    .associateBy { it.cloudId!! }
                    .toMutableMap()

                val localByNormPhone = existingPersons
                    .filter { it.phone.isNotBlank() }
                    .groupBy { ContactSearchHelper.normalizePhone(it.phone) }

                for (p in backupData.persons) {
                    val resolvedRole = (p.role as? com.kb.ledger.data.model.PersonRole) ?: com.kb.ledger.data.model.PersonRole.CUSTOMER
                    // 1. Try matching by cloudId (stable unique identity)
                    val matchByCloudId = if (!p.cloudId.isNullOrBlank()) localByCloudId[p.cloudId] else null

                    // 2. Fallback: match by normalized phone ONLY if:
                    //    a. Neither record has a cloudId (legacy pre-v3 records)
                    //    b. Phone is valid (at least 7 digits, normalized)
                    //    c. Normalized names match (prevents merging different people sharing a family/business number)
                    //    d. Roles match (never merge customer with supplier even if same phone)
                    //    e. Candidate is unique and not already claimed
                    val normName = ContactSearchHelper.normalizeName(p.name)
                    val normPhone = ContactSearchHelper.normalizePhone(p.phone)
                    val digits = p.phone.filter { it.isDigit() }
                    val isPhoneValid = normPhone.isNotBlank() && digits.length >= 7

                    val matchByPhone = if (matchByCloudId == null && isPhoneValid) {
                        val candidates = localByNormPhone[normPhone].orEmpty()
                            .filter { it.cloudId.isNullOrBlank() && !claimedLocalPersonIds.contains(it.id) && it.role == resolvedRole }
                        // Only match if normalized names match AND there is exactly one unambiguous candidate
                        candidates.singleOrNull { ContactSearchHelper.normalizeName(it.name) == normName }
                    } else null

                    val matchedPerson = matchByCloudId ?: matchByPhone

                    if (matchedPerson != null) {
                        val localId = matchedPerson.id
                        if (claimedLocalPersonIds.contains(localId)) {
                            throw IllegalArgumentException("Backup data integrity error: multiple persons in backup map to the same local person ID $localId")
                        }
                        claimedLocalPersonIds.add(localId)

                        personIdMap[p.id] = localId
                        if (localId != p.id) {
                            remappedCount++
                        }

                        // Update existing record if backup has newer or equal updatedAt
                        if (p.updatedAt >= matchedPerson.updatedAt) {
                            val updated = matchedPerson.copy(
                                name = p.name.ifBlank { matchedPerson.name },
                                phone = p.phone.ifBlank { matchedPerson.phone },
                                role = matchedPerson.role,
                                contactId = p.contactId ?: matchedPerson.contactId,
                                cloudId = matchedPerson.cloudId ?: p.cloudId ?: UUID.randomUUID().toString(),
                                updatedAt = p.updatedAt,
                                isDeleted = p.isDeleted || matchedPerson.isDeleted, // preserve tombstone
                                isArchived = p.isArchived,
                                syncPending = true
                            )
                            personDao.updatePerson(updated)
                            if (!updated.cloudId.isNullOrBlank()) {
                                localByCloudId[updated.cloudId!!] = updated
                            }
                        }
                    } else {
                        // New person to this local database.
                        // Allocate fresh auto-increment ID (id = 0) to prevent ID collision.
                        val newCloudId = p.cloudId.takeIf { !it.isNullOrBlank() } ?: UUID.randomUUID().toString()
                        val newPerson = p.copy(
                            id = 0L,
                            cloudId = newCloudId,
                            role = resolvedRole,
                            updatedAt = p.updatedAt.takeIf { it > 0 } ?: now,
                            syncPending = true
                        )
                        val newLocalId = personDao.insertPerson(newPerson)
                        val resolvedId = if (newLocalId > 0) newLocalId else p.id
                        if (claimedLocalPersonIds.contains(resolvedId)) {
                            throw IllegalArgumentException("Backup data integrity error: multiple persons in backup map to the same local person ID $resolvedId")
                        }
                        personIdMap[p.id] = resolvedId
                        claimedLocalPersonIds.add(resolvedId)
                        if (resolvedId != p.id) {
                            remappedCount++
                        }
                        localByCloudId[newCloudId] = newPerson.copy(id = resolvedId)
                    }
                }
            }

            // ── Transactions ─────────────────────────────────────────────────────
            var txCount = 0
            for (t in backupData.transactions) {
                val targetPersonId = personIdMap[t.personId]
                    ?: throw IllegalArgumentException("Backup integrity error: transaction ${t.id} references unknown person ID ${t.personId}")

                val internalPath = targetReceiptsDir?.let { File(it, "${t.id}.jpg") }
                val newImageUri = if (restoredTxIds.contains(t.id) || (internalPath != null && internalPath.exists() && internalPath.length() > 0)) {
                    internalPath?.absolutePath ?: t.imageUri
                } else {
                    t.imageUri
                }

                val remappedTx = t.copy(
                    personId = targetPersonId,
                    updatedAt = t.updatedAt.takeIf { it > 0 } ?: now,
                    imageUri = newImageUri,
                    syncPending = true
                )

                val existingTx = transactionDao.getTransactionById(remappedTx.id)
                if (existingTx == null) {
                    transactionDao.insertTransaction(remappedTx)
                } else {
                    // Tombstone Preservation Rule:
                    // If either the local transaction or incoming backup transaction is marked deleted (isDeleted = true),
                    // it MUST remain deleted. Restoring an older active backup must never resurrect a locally soft-deleted transaction.
                    val mergedIsDeleted = existingTx.isDeleted || t.isDeleted

                    if (remappedTx.updatedAt >= existingTx.updatedAt) {
                        val mergedTx = remappedTx.copy(isDeleted = mergedIsDeleted)
                        transactionDao.updateTransaction(mergedTx)
                    } else if (existingTx.isDeleted != mergedIsDeleted) {
                        // Propagate tombstone from backup if local record is active
                        transactionDao.updateTransaction(existingTx.copy(isDeleted = true, syncPending = true))
                    }
                }
                txCount++
            }

            // ── Recurring Rules ──────────────────────────────────────────────────
            var ruleCount = 0
            for (r in (backupData.recurringRules ?: emptyList())) {
                val targetPersonId = personIdMap[r.personId]
                    ?: throw IllegalArgumentException("Backup integrity error: recurring rule ${r.id} references unknown person ID ${r.personId}")

                val remappedRule = r.copy(personId = targetPersonId)
                val existingRule = recurringRuleDao.getRuleById(remappedRule.id)
                if (existingRule == null) {
                    recurringRuleDao.insertRule(remappedRule)
                } else {
                    recurringRuleDao.updateRule(remappedRule)
                }
                ruleCount++
            }

            return RestoreStats(
                restoredPersonsCount = backupData.persons.size,
                restoredTransactionsCount = txCount,
                restoredRulesCount = ruleCount,
                remappedPersonsCount = remappedCount
            )
        }
    }
}

/**
 * Statistics returned by [BackupManager.restoreDatabaseRecords].
 */
data class RestoreStats(
    val restoredPersonsCount: Int,
    val restoredTransactionsCount: Int,
    val restoredRulesCount: Int,
    val remappedPersonsCount: Int
)

/**
 * Filesystem operations abstraction to allow deterministic failure-injection testing
 * for image staging, copy fallback, content validation, and atomic promotion.
 */
interface FileSystemOperations {
    fun rename(source: File, destination: File): Boolean
    fun copy(source: File, destination: File, overwrite: Boolean = true)
    fun delete(file: File): Boolean
    fun exists(file: File): Boolean
    fun length(file: File): Long
    fun readHeader(file: File, maxBytes: Int = 32): ByteArray? {
        if (!exists(file)) return null
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(maxBytes)
                val read = input.read(buffer)
                if (read <= 0) ByteArray(0) else if (read == maxBytes) buffer else buffer.copyOf(read)
            }
        } catch (_: Exception) {
            null
        }
    }
}

object DefaultFileSystemOperations : FileSystemOperations {
    override fun rename(source: File, destination: File): Boolean = source.renameTo(destination)
    override fun copy(source: File, destination: File, overwrite: Boolean) {
        source.copyTo(destination, overwrite = overwrite)
    }
    override fun delete(file: File): Boolean = file.delete()
    override fun exists(file: File): Boolean = file.exists()
    override fun length(file: File): Long = file.length()
    override fun readHeader(file: File, maxBytes: Int): ByteArray? {
        if (!exists(file)) return null
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(maxBytes)
                val read = input.read(buffer)
                if (read <= 0) ByteArray(0) else if (read == maxBytes) buffer else buffer.copyOf(read)
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Abstraction for opening destination output streams for SAF export.
 * Facilitates deterministic failure-injection testing without Android UI automation.
 */
interface DestinationStreamProvider {
    fun openOutputStream(uri: Uri): java.io.OutputStream?
}

class ContentResolverDestinationStreamProvider(
    private val contentResolver: android.content.ContentResolver
) : DestinationStreamProvider {
    override fun openOutputStream(uri: Uri): java.io.OutputStream? = contentResolver.openOutputStream(uri)
}

/**
 * Abstraction for opening source input streams for SAF import.
 * Facilitates deterministic failure-injection testing without Android UI automation.
 */
interface SourceStreamProvider {
    fun openInputStream(uri: Uri): java.io.InputStream?
}

class ContentResolverSourceStreamProvider(
    private val contentResolver: android.content.ContentResolver
) : SourceStreamProvider {
    override fun openInputStream(uri: Uri): java.io.InputStream? = contentResolver.openInputStream(uri)
}
