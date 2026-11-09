package com.kb.ledger.backup.partition

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.reflect.TypeToken
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.RecurringRule
import com.kb.ledger.data.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed class BackupStatus {
    object Idle : BackupStatus()
    object Reconciling : BackupStatus()
    data class Current(val lastUpdated: Long, val totalTransactions: Int, val totalPartitions: Int) : BackupStatus()
    data class Error(val message: String) : BackupStatus()
}

/**
 * Result of a database restore operation from partitioned backup.
 */
data class RestoreResult(
    val restoredPersons: Int,
    val restoredTransactions: Int,
    val restoredRules: Int,
    val restoredImages: Int
)

/**
 * Benchmark metrics measuring partition latency, compression, and storage footprint.
 */
data class BenchmarkResult(
    val transactionCount: Int,
    val writeLatencyMs: Long,
    val readLatencyMs: Long,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val compressionRatio: Double,
    val chunkCount: Int
)

/**
 * High-performance, date-partitioned, compressed local backup engine.
 *
 * ## Principles
 * 1. Room is the authoritative source of truth.
 * 2. Backups are stored in app-private storage (`context.filesDir/backup/`).
 * 3. Transactions are partitioned by original `createdAt` timestamp (UTC timezone).
 * 4. Chunks are bounded (~1,000 transactions per chunk) to avoid large single-file rewrites.
 * 5. Files are versioned and GZIP-compressed (compact, not directly human-readable).
 * 6. Bill images are stored separately in `backup/images/` without Base64 clutter.
 * 7. Self-healing: startup reconciliation detects missing/stale files and rebuilds from Room.
 */
class LocalPartitionedBackupManager(
    private val backupRootDir: File,
    val targetChunkSize: Int = DEFAULT_TARGET_CHUNK_SIZE
) {

    constructor(context: Context, targetChunkSize: Int = DEFAULT_TARGET_CHUNK_SIZE) : this(
        File(context.filesDir, "backup"),
        targetChunkSize
    )

    companion object {
        const val DEFAULT_TARGET_CHUNK_SIZE = 1000
        private val TX_CHUNK_TYPE = object : TypeToken<BackupChunkEnvelope<Transaction>>() {}
        private val PERSON_CHUNK_TYPE = object : TypeToken<BackupChunkEnvelope<Person>>() {}
        private val RULE_CHUNK_TYPE = object : TypeToken<BackupChunkEnvelope<RecurringRule>>() {}
        private val MANIFEST_TYPE = object : TypeToken<BackupManifest>() {}
    }

    private val mutex = Mutex()

    val manifestFile: File get() = File(backupRootDir, "manifest.dat")
    val personsDir: File get() = File(backupRootDir, "persons/chunks").also { it.mkdirs() }
    val rulesDir: File get() = File(backupRootDir, "recurring_rules/chunks").also { it.mkdirs() }
    val transactionsDir: File get() = File(backupRootDir, "transactions").also { it.mkdirs() }
    val imagesDir: File get() = File(backupRootDir, "images").also { it.mkdirs() }

    private val _status = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    init {
        backupRootDir.mkdirs()
        refreshStatusFromDisk()
    }

    private fun refreshStatusFromDisk() {
        val manifest = readManifest()
        if (manifest != null) {
            _status.value = BackupStatus.Current(
                lastUpdated = manifest.lastMutationAt,
                totalTransactions = manifest.totalTransactions,
                totalPartitions = manifest.transactionPartitionCount
            )
        }
    }

    fun readManifest(): BackupManifest? {
        return CompressedFileHelper.readCompressed(manifestFile, MANIFEST_TYPE)
    }

    // ── Single Mutation Handlers ─────────────────────────────────────────────

    /**
     * Adds or updates a single transaction in its date partition chunk.
     *
     * Guarantee: A transaction is strictly placed in the partition corresponding to its [Transaction.createdAt].
     * Updating an existing transaction's business note, amount, or other fields never moves it to another partition.
     */
    suspend fun recordTransactionMutation(tx: Transaction): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val partitionPath = PartitionDateUtils.toTransactionPartitionPath(tx.createdAt)
            val partitionDir = File(backupRootDir, partitionPath).also { it.mkdirs() }
            val partitionKey = PartitionDateUtils.toPartitionKey(tx.createdAt)

            // Find all existing chunk files in this partition
            val chunkFiles = (partitionDir.listFiles { _, name -> name.startsWith("chunk_") && name.endsWith(".dat") }
                ?: emptyArray()).sortedBy { it.name }

            var updated = false
            var isNewTx = false
            var wasDeleted = false
            var newChunkAdded = false

            // 1. Try to find if tx already exists in one of the chunks (update case)
            for (chunkFile in chunkFiles) {
                val envelope = CompressedFileHelper.readCompressed(chunkFile, TX_CHUNK_TYPE)
                if (envelope != null) {
                    val existingIdx = envelope.records.indexOfFirst { it.id == tx.id }
                    if (existingIdx >= 0) {
                        wasDeleted = envelope.records[existingIdx].isDeleted
                        val newRecords = envelope.records.toMutableList()
                        newRecords[existingIdx] = tx
                        val newEnvelope = envelope.copy(
                            records = newRecords,
                            recordCount = newRecords.size,
                            updatedAt = System.currentTimeMillis()
                        )
                        val writeOk = CompressedFileHelper.writeCompressedAtomic(chunkFile, newEnvelope)
                        if (!writeOk) {
                            _status.value = BackupStatus.Error("Failed to update transaction chunk")
                            return@withContext false
                        }
                        updated = true
                        break
                    }
                }
            }

            // 2. If not found in any existing chunk, append to the latest chunk (or create chunk_0001)
            if (!updated) {
                isNewTx = true
                val latestChunk = chunkFiles.lastOrNull()
                if (latestChunk != null) {
                    val envelope = CompressedFileHelper.readCompressed(latestChunk, TX_CHUNK_TYPE)
                    if (envelope != null && envelope.records.size < targetChunkSize) {
                        // Append to latest chunk
                        val newRecords = envelope.records + tx
                        val newEnvelope = envelope.copy(
                            records = newRecords,
                            recordCount = newRecords.size,
                            updatedAt = System.currentTimeMillis()
                        )
                        val writeOk = CompressedFileHelper.writeCompressedAtomic(latestChunk, newEnvelope)
                        if (!writeOk) {
                            _status.value = BackupStatus.Error("Failed to append to transaction chunk")
                            return@withContext false
                        }
                        updated = true
                    }
                }

                // If still not updated (no chunks yet, or latest chunk is full): create new chunk
                if (!updated) {
                    newChunkAdded = true
                    val nextIndex = chunkFiles.size + 1
                    val newChunkName = String.format("chunk_%04d.dat", nextIndex)
                    val newChunkFile = File(partitionDir, newChunkName)
                    val newEnvelope = BackupChunkEnvelope(
                        chunkType = BackupChunkEnvelope.TYPE_TRANSACTIONS,
                        partitionKey = partitionKey,
                        chunkIndex = nextIndex,
                        recordCount = 1,
                        updatedAt = System.currentTimeMillis(),
                        records = listOf(tx)
                    )
                    val writeOk = CompressedFileHelper.writeCompressedAtomic(newChunkFile, newEnvelope)
                    if (!writeOk) {
                        _status.value = BackupStatus.Error("Failed to create new transaction chunk")
                        return@withContext false
                    }
                }
            }

            // Mirror or remove bill image in backup/images/
            if (!tx.imageUri.isNullOrBlank()) {
                val candidateFile = File(tx.imageUri)
                val sourceFile = if (candidateFile.exists() && candidateFile.length() > 0) {
                    candidateFile
                } else {
                    val defaultReceipt = File(backupRootDir.parentFile, "receipts/${tx.id}.jpg")
                    if (defaultReceipt.exists() && defaultReceipt.length() > 0) defaultReceipt else null
                }
                if (sourceFile != null) {
                    copyBillImageInternal(tx.id, sourceFile)
                }
            } else if (tx.imageUri.isNullOrBlank() && !tx.isDeleted) {
                // User explicitly cleared the bill image from an active transaction
                val targetImg = File(imagesDir, "${tx.id}.jpg")
                if (targetImg.exists()) {
                    targetImg.delete()
                }
            }

            // Update manifest incrementally without full history read!
            updateManifestIncrementally(
                partitionKey = partitionKey,
                isNewTx = isNewTx,
                wasDeleted = wasDeleted,
                isDeleted = tx.isDeleted,
                newChunkAdded = newChunkAdded
            )
        }
    }

    /**
     * Soft-deletes a transaction in its date partition chunk.
     * Preserves a tombstone record with `isDeleted = true`.
     */
    suspend fun recordTransactionDeletion(txId: String, createdAt: Long): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val partitionPath = PartitionDateUtils.toTransactionPartitionPath(createdAt)
            val partitionDir = File(backupRootDir, partitionPath)
            if (!partitionDir.exists()) {
                _status.value = BackupStatus.Error("Partition not found for deletion")
                return@withContext false
            }

            val chunkFiles = (partitionDir.listFiles { _, name -> name.startsWith("chunk_") && name.endsWith(".dat") }
                ?: emptyArray()).sortedBy { it.name }

            var foundAndUpdated = false
            for (chunkFile in chunkFiles) {
                val envelope = CompressedFileHelper.readCompressed(chunkFile, TX_CHUNK_TYPE)
                if (envelope != null) {
                    val existingIdx = envelope.records.indexOfFirst { it.id == txId }
                    if (existingIdx >= 0) {
                        val existing = envelope.records[existingIdx]
                        val tombstone = existing.copy(
                            isDeleted = true,
                            updatedAt = System.currentTimeMillis(),
                            syncPending = true
                        )
                        val newRecords = envelope.records.toMutableList()
                        newRecords[existingIdx] = tombstone
                        val newEnvelope = envelope.copy(
                            records = newRecords,
                            recordCount = newRecords.size,
                            updatedAt = System.currentTimeMillis()
                        )
                        val writeOk = CompressedFileHelper.writeCompressedAtomic(chunkFile, newEnvelope)
                        if (!writeOk) {
                            _status.value = BackupStatus.Error("Failed to write tombstone chunk")
                            return@withContext false
                        }
                        foundAndUpdated = true
                        break
                    }
                }
            }

            if (foundAndUpdated) {
                // Note: Image is intentionally retained in backup/images/ for soft-deleted
                // transactions so it remains available for tombstone recovery and Drive sync.
                updateManifestIncrementally(
                    partitionKey = PartitionDateUtils.toPartitionKey(createdAt),
                    isNewTx = false,
                    wasDeleted = false,
                    isDeleted = true,
                    newChunkAdded = false
                )
            } else {
                _status.value = BackupStatus.Error("Transaction not found for deletion in backup")
            }
            foundAndUpdated
        }
    }

    /**
     * Writes all persons into bounded chunks under `persons/chunks/`.
     */
    suspend fun recordPersons(persons: List<Person>): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            personsDir.mkdirs()
            val chunks = persons.chunked(targetChunkSize)

            if (chunks.isEmpty()) {
                val envelope = BackupChunkEnvelope(
                    chunkType = BackupChunkEnvelope.TYPE_PERSONS,
                    partitionKey = "persons",
                    chunkIndex = 1,
                    recordCount = 0,
                    records = emptyList<Person>()
                )
                if (!CompressedFileHelper.writeCompressedAtomic(File(personsDir, "chunk_0001.dat"), envelope)) {
                    _status.value = BackupStatus.Error("Failed to write empty person chunk")
                    return@withContext false
                }
            } else {
                for ((idx, list) in chunks.withIndex()) {
                    val chunkFile = File(personsDir, String.format("chunk_%04d.dat", idx + 1))
                    val envelope = BackupChunkEnvelope(
                        chunkType = BackupChunkEnvelope.TYPE_PERSONS,
                        partitionKey = "persons",
                        chunkIndex = idx + 1,
                        recordCount = list.size,
                        records = list
                    )
                    if (!CompressedFileHelper.writeCompressedAtomic(chunkFile, envelope)) {
                        _status.value = BackupStatus.Error("Failed to write person chunk")
                        return@withContext false
                    }
                }
            }
            rebuildManifestFromDisk()
        }
    }

    /**
     * Writes all recurring rules into bounded chunks under `recurring_rules/chunks/`.
     */
    suspend fun recordRecurringRules(rules: List<RecurringRule>): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            rulesDir.mkdirs()
            val envelope = BackupChunkEnvelope(
                chunkType = BackupChunkEnvelope.TYPE_RECURRING_RULES,
                partitionKey = "recurring_rules",
                chunkIndex = 1,
                recordCount = rules.size,
                records = rules
            )
            val ok = CompressedFileHelper.writeCompressedAtomic(File(rulesDir, "chunk_0001.dat"), envelope)
            if (ok) {
                rebuildManifestFromDisk()
            } else {
                _status.value = BackupStatus.Error("Failed to write recurring rules chunk")
            }
            ok
        }
    }

    private fun copyBillImageInternal(txId: String, imageSourceFile: File): Boolean {
        if (!imageSourceFile.exists() || imageSourceFile.length() == 0L) return false
        imagesDir.mkdirs()
        val target = File(imagesDir, "$txId.jpg")
        val temp = File(imagesDir, "$txId.jpg.tmp_${System.nanoTime()}")
        return try {
            imageSourceFile.copyTo(temp, overwrite = true)
            if (target.exists()) target.delete()
            val moved = temp.renameTo(target)
            if (!moved) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.exists() && target.length() > 0
        } catch (e: Exception) {
            temp.delete()
            false
        }
    }

    /**
     * Backs up a bill image file into `backup/images/<txId>.jpg`.
     */
    suspend fun recordBillImage(txId: String, imageSourceFile: File): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            copyBillImageInternal(txId, imageSourceFile)
        }
    }

    /**
     * Cleans up orphaned .tmp files left over from interrupted file writes or system crashes.
     */
    fun cleanupStaleTempFiles() {
        try {
            backupRootDir.walkTopDown()
                .filter { it.isFile && (it.name.contains(".tmp") || it.name.endsWith(".tmp")) }
                .forEach { it.delete() }
        } catch (_: Exception) {
            // Best effort cleanup
        }
    }

    // ── Full Reconciliation & Recovery ───────────────────────────────────────

    /**
     * Rebuilds the complete date-partitioned backup directly from the authoritative Room database.
     * Guarantees complete crash recovery and repairs any missing or corrupt chunks.
     */
    suspend fun reconcileWithRoom(database: AppDatabase): Boolean = withContext(Dispatchers.IO) {
        val persons = database.personDao().getAllPersonsIncludingDeleted()
        val transactions = database.transactionDao().getAllTransactionsIncludingDeleted()
        val recurringRules = database.recurringRuleDao().getAllRulesList()
        reconcileWithData(persons, transactions, recurringRules)
    }

    private fun reconcileImagesInternal(transactions: List<Transaction>) {
        imagesDir.mkdirs()
        // Retain bill images for both active transactions and soft-deleted tombstones
        val allTxsWithImages = transactions.filter { !it.imageUri.isNullOrBlank() }
        val allTxIdsWithImages = allTxsWithImages.map { it.id }.toSet()

        for (tx in allTxsWithImages) {
            val candidate = File(tx.imageUri!!)
            val sourceFile = if (candidate.exists() && candidate.length() > 0) {
                candidate
            } else {
                val defaultReceipt = File(backupRootDir.parentFile, "receipts/${tx.id}.jpg")
                if (defaultReceipt.exists() && defaultReceipt.length() > 0) defaultReceipt else null
            }
            if (sourceFile != null) {
                val target = File(imagesDir, "${tx.id}.jpg")
                if (!target.exists() || target.length() != sourceFile.length()) {
                    copyBillImageInternal(tx.id, sourceFile)
                }
            }
        }

        // Only purge orphaned image files that do not correspond to ANY transaction in Room
        imagesDir.listFiles { _, name -> name.endsWith(".jpg") }?.forEach { imgFile ->
            val txId = imgFile.name.removeSuffix(".jpg")
            if (!allTxIdsWithImages.contains(txId)) {
                imgFile.delete()
            }
        }
    }

    /**
     * Rebuilds the backup from raw datasets. Ensures complete testability without Room mock overhead.
     */
    suspend fun reconcileWithData(
        persons: List<Person>,
        transactions: List<Transaction>,
        recurringRules: List<RecurringRule>
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            _status.value = BackupStatus.Reconciling
            cleanupStaleTempFiles()

            try {
                // 1. Write persons
                personsDir.mkdirs()
                val personChunks = persons.chunked(targetChunkSize)
                for ((idx, list) in personChunks.withIndex()) {
                    val chunkFile = File(personsDir, String.format("chunk_%04d.dat", idx + 1))
                    val envelope = BackupChunkEnvelope(
                        chunkType = BackupChunkEnvelope.TYPE_PERSONS,
                        partitionKey = "persons",
                        chunkIndex = idx + 1,
                        recordCount = list.size,
                        records = list
                    )
                    if (!CompressedFileHelper.writeCompressedAtomic(chunkFile, envelope)) {
                        _status.value = BackupStatus.Error("Failed to write person chunk ${idx + 1}")
                        return@withContext false
                    }
                }

                // 2. Write rules
                rulesDir.mkdirs()
                val ruleEnvelope = BackupChunkEnvelope(
                    chunkType = BackupChunkEnvelope.TYPE_RECURRING_RULES,
                    partitionKey = "recurring_rules",
                    chunkIndex = 1,
                    recordCount = recurringRules.size,
                    records = recurringRules
                )
                if (!CompressedFileHelper.writeCompressedAtomic(File(rulesDir, "chunk_0001.dat"), ruleEnvelope)) {
                    _status.value = BackupStatus.Error("Failed to write recurring rules chunk")
                    return@withContext false
                }

                // 3. Partition transactions by createdAt (UTC)
                val groupedByDay = transactions.groupBy { PartitionDateUtils.toPartitionKey(it.createdAt) }
                for ((partitionKey, txList) in groupedByDay) {
                    val partitionDir = File(backupRootDir, "transactions/$partitionKey").also { it.mkdirs() }
                    val txChunks = txList.chunked(targetChunkSize)
                    for ((idx, list) in txChunks.withIndex()) {
                        val chunkFile = File(partitionDir, String.format("chunk_%04d.dat", idx + 1))
                        val envelope = BackupChunkEnvelope(
                            chunkType = BackupChunkEnvelope.TYPE_TRANSACTIONS,
                            partitionKey = partitionKey,
                            chunkIndex = idx + 1,
                            recordCount = list.size,
                            records = list
                        )
                        if (!CompressedFileHelper.writeCompressedAtomic(chunkFile, envelope)) {
                            _status.value = BackupStatus.Error("Failed to write transaction chunk for $partitionKey")
                            return@withContext false
                        }
                    }
                }

                // 4. Reconcile bill images (mirror receipts, prune orphans, preserve tombstones)
                reconcileImagesInternal(transactions)

                // 5. Rebuild manifest from disk
                val success = rebuildManifestFromDisk()
                if (success) {
                    val m = readManifest()
                    _status.value = if (m != null) {
                        BackupStatus.Current(m.lastMutationAt, m.totalTransactions, m.transactionPartitionCount)
                    } else {
                        BackupStatus.Error("Failed to read manifest after reconciliation")
                    }
                } else {
                    _status.value = BackupStatus.Error("Failed to write manifest")
                }
                success
            } catch (e: Exception) {
                _status.value = BackupStatus.Error("Reconciliation failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Incrementally updates the manifest for a single transaction mutation without walking all historical partitions.
     */
    private fun updateManifestIncrementally(
        partitionKey: String,
        isNewTx: Boolean,
        wasDeleted: Boolean,
        isDeleted: Boolean,
        newChunkAdded: Boolean
    ): Boolean {
        val existing = readManifest()
        if (existing == null) {
            return rebuildManifestFromDisk()
        }

        val totalTx = if (isNewTx) existing.totalTransactions + 1 else existing.totalTransactions
        var activeTx = existing.activeTransactions
        var deletedTx = existing.deletedTransactions

        if (isNewTx) {
            if (isDeleted) deletedTx++ else activeTx++
        } else {
            if (wasDeleted && !isDeleted) {
                deletedTx--
                activeTx++
            } else if (!wasDeleted && isDeleted) {
                activeTx--
                deletedTx++
            }
        }

        val partitionMap = existing.transactionPartitions.toMutableMap()
        val currentChunks = partitionMap[partitionKey] ?: 0
        partitionMap[partitionKey] = if (newChunkAdded) currentChunks + 1 else currentChunks.coerceAtLeast(1)

        val imageFilesCount = imagesDir.listFiles { _, name -> name.endsWith(".jpg") }?.size ?: 0
        val now = System.currentTimeMillis()
        val newManifest = existing.copy(
            lastMutationAt = now,
            transactionPartitionCount = partitionMap.keys.size,
            totalTransactions = totalTx,
            activeTransactions = activeTx.coerceAtLeast(0),
            deletedTransactions = deletedTx.coerceAtLeast(0),
            totalImages = imageFilesCount,
            transactionPartitions = partitionMap
        )

        val writeOk = CompressedFileHelper.writeCompressedAtomic(manifestFile, newManifest)
        if (writeOk) {
            _status.value = BackupStatus.Current(now, totalTx, partitionMap.keys.size)
        } else {
            _status.value = BackupStatus.Error("Failed to write manifest file")
        }
        return writeOk
    }

    /**
     * Re-scans all partition files on disk and commits a fresh `manifest.dat`.
     */
    fun rebuildManifestFromDisk(): Boolean {
        try {
            var totalTx = 0
            var activeTx = 0
            var deletedTx = 0
            val partitionMap = mutableMapOf<String, Int>()

            if (transactionsDir.exists()) {
                transactionsDir.walkTopDown()
                    .filter { it.isFile && it.name.startsWith("chunk_") && it.name.endsWith(".dat") }
                    .forEach { chunkFile ->
                        val envelope = CompressedFileHelper.readCompressed(chunkFile, TX_CHUNK_TYPE)
                        if (envelope != null) {
                            val count = envelope.records.size
                            totalTx += count
                            activeTx += envelope.records.count { !it.isDeleted }
                            deletedTx += envelope.records.count { it.isDeleted }
                            partitionMap[envelope.partitionKey] = (partitionMap[envelope.partitionKey] ?: 0) + 1
                        }
                    }
            }

            var totalPersons = 0
            var personChunkCount = 0
            if (personsDir.exists()) {
                personsDir.listFiles { _, name -> name.startsWith("chunk_") && name.endsWith(".dat") }?.forEach { chunkFile ->
                    personChunkCount++
                    val envelope = CompressedFileHelper.readCompressed(chunkFile, PERSON_CHUNK_TYPE)
                    if (envelope != null) totalPersons += envelope.records.size
                }
            }

            var totalRules = 0
            if (rulesDir.exists()) {
                val ruleFile = File(rulesDir, "chunk_0001.dat")
                val envelope = CompressedFileHelper.readCompressed(ruleFile, RULE_CHUNK_TYPE)
                if (envelope != null) totalRules = envelope.records.size
            }

            val imageFilesCount = imagesDir.listFiles { _, name -> name.endsWith(".jpg") }?.size ?: 0

            val existingManifest = readManifest()
            val ledgerId = existingManifest?.ledgerId ?: UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            val newManifest = BackupManifest(
                schemaVersion = BackupManifest.CURRENT_SCHEMA_VERSION,
                ledgerId = ledgerId,
                lastReconciledAt = now,
                lastMutationAt = now,
                personChunkCount = personChunkCount.coerceAtLeast(1),
                totalPersons = totalPersons,
                ruleChunkCount = 1,
                totalRules = totalRules,
                transactionPartitionCount = partitionMap.keys.size,
                totalTransactions = totalTx,
                activeTransactions = activeTx,
                deletedTransactions = deletedTx,
                totalImages = imageFilesCount,
                transactionPartitions = partitionMap
            )

            val writeOk = CompressedFileHelper.writeCompressedAtomic(manifestFile, newManifest)
            if (writeOk) {
                _status.value = BackupStatus.Current(now, totalTx, partitionMap.keys.size)
            } else {
                _status.value = BackupStatus.Error("Failed to write manifest file")
            }
            return writeOk
        } catch (e: Exception) {
            _status.value = BackupStatus.Error("Failed to update manifest index: ${e.message}")
            return false
        }
    }

    /**
     * Reads all transactions across all date partitions.
     */
    suspend fun readAllTransactions(): List<Transaction> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Transaction>()
        if (transactionsDir.exists()) {
            transactionsDir.walkTopDown()
                .filter { it.isFile && it.name.startsWith("chunk_") && it.name.endsWith(".dat") }
                .forEach { chunkFile ->
                    val envelope = CompressedFileHelper.readCompressed(chunkFile, TX_CHUNK_TYPE)
                    if (envelope != null) {
                        list.addAll(envelope.records)
                    }
                }
        }
        list
    }

    /**
     * Reads all persons across all person chunks.
     */
    suspend fun readAllPersons(): List<Person> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Person>()
        if (personsDir.exists()) {
            personsDir.listFiles { _, name -> name.startsWith("chunk_") && name.endsWith(".dat") }
                ?.sortedBy { it.name }
                ?.forEach { chunkFile ->
                    val envelope = CompressedFileHelper.readCompressed(chunkFile, PERSON_CHUNK_TYPE)
                    if (envelope != null) {
                        list.addAll(envelope.records)
                    }
                }
        }
        list
    }

    /**
     * Reads all recurring rules.
     */
    suspend fun readAllRecurringRules(): List<RecurringRule> = withContext(Dispatchers.IO) {
        val list = mutableListOf<RecurringRule>()
        if (rulesDir.exists()) {
            rulesDir.listFiles { _, name -> name.startsWith("chunk_") && name.endsWith(".dat") }
                ?.sortedBy { it.name }
                ?.forEach { chunkFile ->
                    val envelope = CompressedFileHelper.readCompressed(chunkFile, RULE_CHUNK_TYPE)
                    if (envelope != null) {
                        list.addAll(envelope.records)
                    }
                }
        }
        list
    }

    /**
     * Restores all entities and receipt images from the partitioned backup into the supplied DAOs.
     * Can be invoked directly in unit tests or wrapped inside [restoreToDatabase] with transaction atomicity.
     */
    suspend fun restoreToDaos(
        personDao: com.kb.ledger.data.dao.PersonDao,
        transactionDao: com.kb.ledger.data.dao.TransactionDao,
        recurringRuleDao: com.kb.ledger.data.dao.RecurringRuleDao,
        targetReceiptsDir: File? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        val persons = readAllPersons()
        val transactions = readAllTransactions()
        val rules = readAllRecurringRules()

        for (person in persons) {
            personDao.insertPerson(person)
        }
        for (rule in rules) {
            recurringRuleDao.insertRule(rule)
        }
        for (tx in transactions) {
            transactionDao.insertTransaction(tx)
        }

        var restoredImagesCount = 0
        val destinationDir = targetReceiptsDir ?: File(backupRootDir.parentFile, "receipts")
        if (imagesDir.exists()) {
            destinationDir.mkdirs()
            imagesDir.listFiles { _, name -> name.endsWith(".jpg") }?.forEach { backupImg ->
                val destFile = File(destinationDir, backupImg.name)
                backupImg.copyTo(destFile, overwrite = true)
                restoredImagesCount++
            }
        }

        RestoreResult(
            restoredPersons = persons.size,
            restoredTransactions = transactions.size,
            restoredRules = rules.size,
            restoredImages = restoredImagesCount
        )
    }

    /**
     * Restores all entities and receipt images from the partitioned backup into Room.
     * Guarantees transactional atomicity: if an error occurs during restore, Room rolls back
     * the transaction completely, preventing a partially restored database.
     */
    suspend fun restoreToDatabase(
        database: AppDatabase,
        targetReceiptsDir: File? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.withTransaction {
                restoreToDaos(
                    personDao = database.personDao(),
                    transactionDao = database.transactionDao(),
                    recurringRuleDao = database.recurringRuleDao(),
                    targetReceiptsDir = targetReceiptsDir
                )
            }
        }
    }

    /**
     * Benchmarks partition performance for a given number of synthetic transactions.
     */
    suspend fun benchmark(transactionCount: Int): BenchmarkResult = withContext(Dispatchers.IO) {
        val testTxs = (1..transactionCount).map { i ->
            Transaction(
                id = "bench-$i",
                personId = 1L,
                amountCents = i * 100L,
                type = com.kb.ledger.data.model.TransactionType.GIVE,
                note = "Benchmark entry $i",
                createdAt = 1729833600000L // 2026-10-25T00:00:00Z
            )
        }

        val uncompressedBytes = CompressedFileHelper.gson.toJson(testTxs).toByteArray().size.toLong()

        val startWrite = System.currentTimeMillis()
        val envelope = BackupChunkEnvelope(
            chunkType = BackupChunkEnvelope.TYPE_TRANSACTIONS,
            partitionKey = "2026/10/25",
            chunkIndex = 1,
            recordCount = testTxs.size,
            records = testTxs
        )
        val testFile = File(backupRootDir, "benchmark_chunk.dat")
        CompressedFileHelper.writeCompressedAtomic(testFile, envelope)
        val writeLatency = System.currentTimeMillis() - startWrite

        val compressedBytes = testFile.length()

        val startRead = System.currentTimeMillis()
        val readEnvelope = CompressedFileHelper.readCompressed(testFile, TX_CHUNK_TYPE)
        val readLatency = System.currentTimeMillis() - startRead
        testFile.delete()

        val ratio = if (uncompressedBytes > 0) compressedBytes.toDouble() / uncompressedBytes else 1.0

        BenchmarkResult(
            transactionCount = transactionCount,
            writeLatencyMs = writeLatency,
            readLatencyMs = readLatency,
            uncompressedBytes = uncompressedBytes,
            compressedBytes = compressedBytes,
            compressionRatio = ratio,
            chunkCount = (transactionCount + targetChunkSize - 1) / targetChunkSize
        )
    }
}
