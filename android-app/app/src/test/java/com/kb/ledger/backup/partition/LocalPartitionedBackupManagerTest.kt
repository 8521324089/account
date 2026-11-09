package com.kb.ledger.backup.partition

import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.RecurrenceFrequency
import com.kb.ledger.data.model.RecurringRule
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.data.repository.FakePersonDao
import com.kb.ledger.data.repository.FakeRecurringRuleDao
import com.kb.ledger.data.repository.FakeTransactionDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests verifying date partitioning, bounded chunking, GZIP compression, atomic writes,
 * and reconciliation behavior for [LocalPartitionedBackupManager].
 */
class LocalPartitionedBackupManagerTest {

    private lateinit var tempDir: File
    private lateinit var backupDir: File
    private lateinit var backupManager: LocalPartitionedBackupManager

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("backup_test").toFile()
        backupDir = File(tempDir, "backup")
        backupManager = LocalPartitionedBackupManager(backupDir, targetChunkSize = 5) // Low chunk size for testing splitting
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── 1. Adding a transaction creates the correct date partition ───────────

    @Test
    fun addTransaction_createsCorrectDatePartition() = runBlocking {
        // 2026-10-25 00:00:00 UTC = 1792886400000L
        val createdAt = 1792886400000L
        val tx = Transaction(
            id = "tx-1",
            personId = 100L,
            amountCents = 50000L,
            type = TransactionType.GIVE,
            note = "Test entry",
            createdAt = createdAt
        )

        val ok = backupManager.recordTransactionMutation(tx)
        assertTrue(ok)

        val expectedPartitionDir = File(backupDir, "transactions/2026/10/25")
        assertTrue("Partition directory should exist", expectedPartitionDir.exists())

        val chunk1 = File(expectedPartitionDir, "chunk_0001.dat")
        assertTrue("chunk_0001.dat should exist", chunk1.exists())

        val manifest = backupManager.readManifest()
        assertNotNull(manifest)
        assertEquals(1, manifest?.totalTransactions)
        assertEquals(1, manifest?.activeTransactions)
        assertEquals(1, manifest?.transactionPartitionCount)
    }

    // ── 2. Editing business date does not change creation date partition ──────

    @Test
    fun editTransaction_preservesOriginalPartition() = runBlocking {
        val originalCreatedAt = 1792886400000L // 2026-10-25
        val tx = Transaction(
            id = "tx-edit",
            personId = 100L,
            amountCents = 10000L,
            type = TransactionType.GIVE,
            note = "Original",
            createdAt = originalCreatedAt
        )
        backupManager.recordTransactionMutation(tx)

        // User edits note and amount, but original createdAt is preserved
        val updatedTx = tx.copy(
            amountCents = 25000L,
            note = "Updated note",
            updatedAt = originalCreatedAt + 86400000L // updated next day
        )
        val updateOk = backupManager.recordTransactionMutation(updatedTx)
        assertTrue(updateOk)

        // Verify still in 2026/10/25 partition and not duplicated
        val partitionDir = File(backupDir, "transactions/2026/10/25")
        val chunk1 = File(partitionDir, "chunk_0001.dat")
        val envelope = CompressedFileHelper.readCompressed(chunk1, object : com.google.gson.reflect.TypeToken<BackupChunkEnvelope<Transaction>>() {})
        assertNotNull(envelope)
        assertEquals(1, envelope?.records?.size)
        assertEquals(25000L, envelope?.records?.get(0)?.amountCents)
        assertEquals("Updated note", envelope?.records?.get(0)?.note)

        // No new partition created for next day
        val otherPartition = File(backupDir, "transactions/2026/10/26")
        assertFalse("No partition for update date should exist", otherPartition.exists())
    }

    // ── 3. Deleting a transaction creates durable tombstone ──────────────────

    @Test
    fun deleteTransaction_createsTombstoneInChunk() = runBlocking {
        val createdAt = 1792886400000L // 2026-10-25
        val tx = Transaction(
            id = "tx-del",
            personId = 100L,
            amountCents = 10000L,
            type = TransactionType.RECEIVE,
            createdAt = createdAt
        )
        backupManager.recordTransactionMutation(tx)

        val delOk = backupManager.recordTransactionDeletion("tx-del", createdAt)
        assertTrue(delOk)

        val chunk1 = File(backupDir, "transactions/2026/10/25/chunk_0001.dat")
        val envelope = CompressedFileHelper.readCompressed(chunk1, object : com.google.gson.reflect.TypeToken<BackupChunkEnvelope<Transaction>>() {})
        assertNotNull(envelope)
        assertEquals(1, envelope?.records?.size)
        assertTrue("Deleted record must have isDeleted = true", envelope!!.records[0].isDeleted)

        val manifest = backupManager.readManifest()
        assertEquals(1, manifest?.deletedTransactions)
        assertEquals(0, manifest?.activeTransactions)
    }

    // ── 4. Cross-day and month/year boundaries partition correctly ───────────

    @Test
    fun transactions_acrossMonthAndYearBoundaries_partitionCorrectly() = runBlocking {
        // 2025-12-31 23:59:00 UTC = 1767225540000L
        val tx2025 = Transaction(id = "tx-2025", personId = 1L, amountCents = 100L, type = TransactionType.GIVE, createdAt = 1767225540000L)
        // 2026-01-01 00:01:00 UTC = 1767225660000L
        val tx2026 = Transaction(id = "tx-2026", personId = 1L, amountCents = 200L, type = TransactionType.GIVE, createdAt = 1767225660000L)

        backupManager.recordTransactionMutation(tx2025)
        backupManager.recordTransactionMutation(tx2026)

        assertTrue(File(backupDir, "transactions/2025/12/31/chunk_0001.dat").exists())
        assertTrue(File(backupDir, "transactions/2026/01/01/chunk_0001.dat").exists())

        val manifest = backupManager.readManifest()
        assertEquals(2, manifest?.transactionPartitionCount)
        assertEquals(2, manifest?.totalTransactions)
    }

    // ── 5. High volume days split into bounded chunks ────────────────────────

    @Test
    fun highVolumeDay_splitsIntoBoundedChunks() = runBlocking {
        val createdAt = 1792886400000L // 2026-10-25
        // Manager targetChunkSize = 5, insert 12 transactions
        for (i in 1..12) {
            val tx = Transaction(
                id = "tx-vol-$i",
                personId = 1L,
                amountCents = i * 1000L,
                type = TransactionType.GIVE,
                createdAt = createdAt
            )
            backupManager.recordTransactionMutation(tx)
        }

        val partitionDir = File(backupDir, "transactions/2026/10/25")
        val chunks = partitionDir.listFiles { _, name -> name.startsWith("chunk_") && name.endsWith(".dat") }?.toList() ?: emptyList()
        assertTrue("Should have split across multiple chunks", chunks.size >= 3)

        // Verify all 12 transactions can be read back
        val allRead = backupManager.readAllTransactions()
        assertEquals(12, allRead.size)
    }

    // ── 6. Corrupt or invalid files fail safely ──────────────────────────────

    @Test
    fun corruptFile_returnsNullSafely() {
        val corruptFile = File(tempDir, "corrupt.dat")
        corruptFile.writeText("THIS IS NOT GZIP BYTES")

        val result = CompressedFileHelper.readCompressed(
            corruptFile,
            object : com.google.gson.reflect.TypeToken<BackupChunkEnvelope<Transaction>>() {}
        )
        assertNull("Corrupt file should return null without crashing", result)
    }

    // ── 7. Separate bill-image storage in backup ─────────────────────────────

    @Test
    fun recordBillImage_storesFileInImagesDirectory() = runBlocking {
        val dummyImageFile = File(tempDir, "test_receipt.jpg")
        dummyImageFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())) // JPEG header

        val ok = backupManager.recordBillImage("tx-with-img", dummyImageFile)
        assertTrue(ok)

        val backupImage = File(backupDir, "images/tx-with-img.jpg")
        assertTrue("Backup image file should exist in backup/images/", backupImage.exists())
        assertEquals(4L, backupImage.length())
    }

    // ── 8. Benchmark performance test ────────────────────────────────────────

    @Test
    fun benchmark_measuresLatencyAndCompressionRatio() = runBlocking {
        val result = backupManager.benchmark(100)
        assertEquals(100, result.transactionCount)
        assertTrue("Write latency should be measured (>= 0)", result.writeLatencyMs >= 0)
        assertTrue("Uncompressed bytes should be > compressed bytes", result.compressedBytes < result.uncompressedBytes)
        assertTrue("Compression ratio should be < 1.0", result.compressionRatio < 1.0)
    }

    // ── 9. Failed write does not falsely mark backup current ─────────────────

    @Test
    fun failedWrite_doesNotCorruptManifest() = runBlocking {
        // Create an unwriteable file path
        val invalidFile = File("/proc/non_existent_path_cannot_write/chunk.dat")
        val envelope = BackupChunkEnvelope(
            chunkType = BackupChunkEnvelope.TYPE_TRANSACTIONS,
            partitionKey = "2026/10/25",
            recordCount = 0,
            records = emptyList<Transaction>()
        )
        val writeOk = CompressedFileHelper.writeCompressedAtomic(invalidFile, envelope)
        assertFalse("Atomic write to invalid directory must return false", writeOk)
        assertFalse(invalidFile.exists())
    }

    // ── 10. Persons and recurring rules chunking ─────────────────────────────

    @Test
    fun recordPersonsAndRules_writesChunksAndUpdatesManifest() = runBlocking {
        val persons = (1..7).map { i ->
            Person(id = i.toLong(), name = "Person $i", phone = "987654321$i")
        }
        val rules = listOf(
            RecurringRule(
                id = "rule-1",
                personId = 1L,
                amountCents = 10000L,
                type = TransactionType.GIVE,
                frequency = com.kb.ledger.data.model.RecurrenceFrequency.MONTHLY,
                startDate = 1792886400000L,
                nextExecutionDate = 1792886400000L
            )
        )

        val pOk = backupManager.recordPersons(persons)
        val rOk = backupManager.recordRecurringRules(rules)
        assertTrue(pOk)
        assertTrue(rOk)

        val manifest = backupManager.readManifest()
        assertNotNull(manifest)
        assertEquals(7, manifest?.totalPersons)
        assertEquals(2, manifest?.personChunkCount) // targetChunkSize is 5, so 7 items = 2 chunks
        assertEquals(1, manifest?.totalRules)
    }

    // ── 11. Crash recovery: startup reconciliation repairs missing/stale files ─

    @Test
    fun crashRecovery_startupReconciliationRepairsMissingOrStalePartitions() = runBlocking {
        // Clear existing backup directory to simulate unwritten state after Room mutations
        backupDir.deleteRecursively()
        assertFalse(backupDir.exists())

        val persons = listOf(
            Person(id = 1L, name = "Alice", phone = "1111111111"),
            Person(id = 2L, name = "Bob", phone = "2222222222", isDeleted = true) // Tombstone
        )
        val transactions = listOf(
            Transaction(id = "tx-active", personId = 1L, amountCents = 15000L, type = TransactionType.GIVE, createdAt = 1792886400000L), // 2026-10-25
            Transaction(id = "tx-tombstone", personId = 2L, amountCents = 5000L, type = TransactionType.RECEIVE, isDeleted = true, createdAt = 1792972800000L) // 2026-10-26
        )
        val rules = listOf(
            RecurringRule(
                id = "rule-1",
                personId = 1L,
                amountCents = 2000L,
                type = TransactionType.GIVE,
                frequency = com.kb.ledger.data.model.RecurrenceFrequency.DAILY,
                startDate = 1792886400000L,
                nextExecutionDate = 1792972800000L
            )
        )

        // Reconcile with simulated Room database state
        val ok = backupManager.reconcileWithData(persons, transactions, rules)
        assertTrue("Reconciliation must succeed", ok)

        // Verify status is Current
        val status = backupManager.status.value
        assertTrue("Status must be Current after reconciliation", status is BackupStatus.Current)
        val currentStatus = status as BackupStatus.Current
        assertEquals(2, currentStatus.totalTransactions)
        assertEquals(2, currentStatus.totalPartitions)

        // Verify manifest contains tombstones
        val manifest = backupManager.readManifest()
        assertNotNull(manifest)
        assertEquals(2, manifest?.totalTransactions)
        assertEquals(1, manifest?.activeTransactions)
        assertEquals(1, manifest?.deletedTransactions)
        assertEquals(2, manifest?.transactionPartitionCount)
        assertEquals(2, manifest?.totalPersons)
        assertEquals(1, manifest?.totalRules)

        // Verify partitions exist on disk
        assertTrue(File(backupDir, "transactions/2026/10/25/chunk_0001.dat").exists())
        assertTrue(File(backupDir, "transactions/2026/10/26/chunk_0001.dat").exists())
    }

    // ── 12. Write failure sets status to Error and never reports Current ───────

    @Test
    fun writeFailure_setsStatusToError_neverReportsCurrent() = runBlocking {
        // Attempt to create a manager inside a read-only root or invalid file
        val unwriteableDir = File("/proc/forbidden_backup_dir")
        val brokenManager = LocalPartitionedBackupManager(unwriteableDir)

        val tx = Transaction(id = "tx-fail", personId = 1L, amountCents = 100L, type = TransactionType.GIVE, createdAt = 1792886400000L)
        val ok = brokenManager.recordTransactionMutation(tx)
        assertFalse("Write to forbidden directory must fail", ok)

        val status = brokenManager.status.value
        assertTrue("Status must be Error, not Current", status is BackupStatus.Error)
    }

    // ── 13. Chunk capacity boundary exact split ──────────────────────────────

    @Test
    fun chunkCapacityBoundary_exactSplitAtCapacity() = runBlocking {
        val createdAt = 1792886400000L // 2026-10-25
        val partitionDir = File(backupDir, "transactions/2026/10/25")

        // Add 5 transactions (capacity is 5)
        for (i in 1..5) {
            backupManager.recordTransactionMutation(
                Transaction(id = "tx-bound-$i", personId = 1L, amountCents = i * 100L, type = TransactionType.GIVE, createdAt = createdAt)
            )
        }

        var chunks = partitionDir.listFiles { _, name -> name.startsWith("chunk_") && name.endsWith(".dat") }?.toList() ?: emptyList()
        assertEquals("At exact capacity (5), exactly 1 chunk should exist", 1, chunks.size)

        // Add 6th transaction -> must split and create chunk_0002.dat
        backupManager.recordTransactionMutation(
            Transaction(id = "tx-bound-6", personId = 1L, amountCents = 600L, type = TransactionType.GIVE, createdAt = createdAt)
        )

        chunks = partitionDir.listFiles { _, name -> name.startsWith("chunk_") && name.endsWith(".dat") }?.toList() ?: emptyList()
        assertEquals("At capacity + 1 (6), exactly 2 chunks should exist", 2, chunks.size)

        // Verify chunk 1 has 5 records, chunk 2 has 1 record
        val chunk1Envelope = CompressedFileHelper.readCompressed(File(partitionDir, "chunk_0001.dat"), object : com.google.gson.reflect.TypeToken<BackupChunkEnvelope<Transaction>>() {})
        val chunk2Envelope = CompressedFileHelper.readCompressed(File(partitionDir, "chunk_0002.dat"), object : com.google.gson.reflect.TypeToken<BackupChunkEnvelope<Transaction>>() {})
        assertEquals(5, chunk1Envelope?.records?.size)
        assertEquals(1, chunk2Envelope?.records?.size)
        assertEquals("tx-bound-6", chunk2Envelope?.records?.get(0)?.id)
    }

    // ── 14. Updating one transaction does not rewrite unrelated partitions ─────

    @Test
    fun updatingOneTransaction_doesNotRewriteUnrelatedDatePartitions() = runBlocking {
        val day1Millis = 1792800000000L // 2026-10-24
        val day2Millis = 1792886400000L // 2026-10-25

        // Insert transaction on Day 1
        val txDay1 = Transaction(id = "tx-day1", personId = 1L, amountCents = 1000L, type = TransactionType.GIVE, createdAt = day1Millis)
        backupManager.recordTransactionMutation(txDay1)

        val day1ChunkFile = File(backupDir, "transactions/2026/10/24/chunk_0001.dat")
        assertTrue(day1ChunkFile.exists())
        val day1LastModified = day1ChunkFile.lastModified()
        val day1Length = day1ChunkFile.length()

        // Wait brief pause to ensure lastModified timestamp resolution
        Thread.sleep(20)

        // Insert and update transactions on Day 2
        val txDay2 = Transaction(id = "tx-day2", personId = 1L, amountCents = 2000L, type = TransactionType.GIVE, createdAt = day2Millis)
        backupManager.recordTransactionMutation(txDay2)
        backupManager.recordTransactionMutation(txDay2.copy(amountCents = 9999L, note = "Updated Day 2"))

        // Day 1 chunk file must be 100% untouched
        assertEquals("Day 1 chunk must not be rewritten", day1LastModified, day1ChunkFile.lastModified())
        assertEquals("Day 1 chunk size must remain identical", day1Length, day1ChunkFile.length())
    }

    // ── 15. Orphaned temp files are cleaned up ────────────────────────────────

    @Test
    fun orphanedTempFiles_cleanedUpDuringReconciliation() = runBlocking {
        backupDir.mkdirs()
        val staleTemp = File(backupDir, "transactions.tmp_${System.nanoTime()}")
        staleTemp.writeText("Incomplete write from crashed process")
        assertTrue(staleTemp.exists())

        backupManager.cleanupStaleTempFiles()
        assertFalse("Stale .tmp file should be purged", staleTemp.exists())
    }

    // ── 16. Timezone invariance: midnight, month-end, year-end, leap day ───────

    @Test
    fun partitionDateUtils_timezoneInvariance_midnight_monthend_yearend_dst() {
        val originalTz = java.util.TimeZone.getDefault()
        try {
            // Test in UTC
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))

            // Midnight UTC
            assertEquals("2026/12/31", PartitionDateUtils.toPartitionKey(1798761599999L)) // 2026-12-31T23:59:59.999Z
            assertEquals("2027/01/01", PartitionDateUtils.toPartitionKey(1798761600000L)) // 2027-01-01T00:00:00.000Z

            // Leap day (2028-02-29 12:00:00 UTC) = 1835438400000L
            assertEquals("2028/02/29", PartitionDateUtils.toPartitionKey(1835438400000L))
            // Next day (2028-03-01 12:00:00 UTC) = 1835524800000L
            assertEquals("2028/03/01", PartitionDateUtils.toPartitionKey(1835524800000L))

            // Switch device default timezone to America/New_York (EDT/EST)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            assertEquals("2026/12/31", PartitionDateUtils.toPartitionKey(1798761599999L))
            assertEquals("2027/01/01", PartitionDateUtils.toPartitionKey(1798761600000L))

            // Switch device default timezone to Asia/Kolkata (IST)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
            assertEquals("2026/12/31", PartitionDateUtils.toPartitionKey(1798761599999L))
            assertEquals("2027/01/01", PartitionDateUtils.toPartitionKey(1798761600000L))
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    // ── 17. Repeatable benchmark reporting statistical ranges ─────────────────

    @Test
    fun repeatableBenchmark_realisticRecords_reportsStatisticalRanges() = runBlocking {
        val iterations = 5
        val writeLatencies = mutableListOf<Long>()
        val readLatencies = mutableListOf<Long>()

        for (i in 1..iterations) {
            val bench = backupManager.benchmark(500)
            writeLatencies.add(bench.writeLatencyMs)
            readLatencies.add(bench.readLatencyMs)
            assertTrue(bench.compressionRatio < 0.35) // Greater than 65% space savings
        }

        val avgWrite = writeLatencies.average()
        val avgRead = readLatencies.average()
        println("Repeatable Benchmark (500 records x $iterations runs):")
        println("  Write Latency: min=${writeLatencies.minOrNull()}ms, max=${writeLatencies.maxOrNull()}ms, avg=${avgWrite}ms")
        println("  Read Latency:  min=${readLatencies.minOrNull()}ms, max=${readLatencies.maxOrNull()}ms, avg=${avgRead}ms")

        assertTrue("Average write latency for 500 records should be under 500ms", avgWrite < 500.0)
        assertTrue("Average read latency for 500 records should be under 200ms", avgRead < 200.0)
    }

    // ── 18. Image backup mirroring & incremental manifest count ────────────────

    @Test
    fun transactionWithImage_mirrorsToBackupImages_andUpdatesTotalImages() = runBlocking {
        val receiptsDir = File(tempDir, "receipts").also { it.mkdirs() }
        val sampleImage = File(receiptsDir, "tx-img-1.jpg")
        sampleImage.writeBytes(byteArrayOf(10, 20, 30, 40, 50))

        val tx = Transaction(
            id = "tx-img-1",
            personId = 1L,
            amountCents = 1500L,
            type = TransactionType.GIVE,
            imageUri = sampleImage.absolutePath,
            createdAt = 1792886400000L // 2026-10-25
        )

        val ok = backupManager.recordTransactionMutation(tx)
        assertTrue(ok)

        val backedUpImage = File(backupDir, "images/tx-img-1.jpg")
        assertTrue("Image should be mirrored to backup/images", backedUpImage.exists())
        assertEquals(5L, backedUpImage.length())

        val manifest = backupManager.readManifest()
        assertNotNull(manifest)
        assertEquals(1, manifest?.totalImages)

        // Now update transaction to remove image
        val txWithoutImage = tx.copy(imageUri = null)
        val updateOk = backupManager.recordTransactionMutation(txWithoutImage)
        assertTrue(updateOk)

        assertFalse("Backed up image should be deleted when removed from tx", backedUpImage.exists())
        val manifestAfterRemoval = backupManager.readManifest()
        assertEquals(0, manifestAfterRemoval?.totalImages)
    }

    // ── 19. Transaction soft-deletion retains image for tombstone recovery & sync ──

    @Test
    fun transactionSoftDeletion_retainsBackupImage_forRecoveryAndSync() = runBlocking {
        val receiptsDir = File(tempDir, "receipts").also { it.mkdirs() }
        val sampleImage = File(receiptsDir, "tx-del-img.jpg")
        sampleImage.writeBytes(byteArrayOf(1, 2, 3))

        val tx = Transaction(
            id = "tx-del-img",
            personId = 1L,
            amountCents = 2000L,
            type = TransactionType.GIVE,
            imageUri = sampleImage.absolutePath,
            createdAt = 1792886400000L
        )
        backupManager.recordTransactionMutation(tx)
        assertEquals(1, backupManager.readManifest()?.totalImages)

        val backedUpImage = File(backupDir, "images/tx-del-img.jpg")
        assertTrue(backedUpImage.exists())

        // Soft-delete transaction (tombstone)
        val deleted = backupManager.recordTransactionDeletion("tx-del-img", tx.createdAt)
        assertTrue(deleted)

        // The image must be retained for tombstone recovery and cloud sync
        assertTrue("Backup image must be retained for soft-deleted transaction", backedUpImage.exists())
        val manifest = backupManager.readManifest()
        assertEquals(1, manifest?.totalImages)
        assertEquals(1, manifest?.deletedTransactions)
        assertEquals(0, manifest?.activeTransactions)

        // However, if the user explicitly clears the image from an active transaction:
        val activeTx = Transaction(
            id = "tx-active-clear",
            personId = 1L,
            amountCents = 3000L,
            type = TransactionType.GIVE,
            imageUri = sampleImage.absolutePath,
            createdAt = 1792886400000L
        )
        backupManager.recordTransactionMutation(activeTx)
        val clearImg = File(backupDir, "images/tx-active-clear.jpg")
        assertTrue(clearImg.exists())

        // Clear image
        backupManager.recordTransactionMutation(activeTx.copy(imageUri = null))
        assertFalse("Explicitly cleared image must be deleted", clearImg.exists())
    }

    // ── 20. Bounded chunk reading & tombstone preservation on restore ─────────

    @Test
    fun readAll_preservesAllChunksAndTombstones() = runBlocking {
        // Create 12 persons with targetChunkSize = 5 (splits across 3 chunks)
        val persons = (1..12).map { i ->
            Person(
                id = i.toLong(),
                name = "Person $i",
                phone = "555-000$i",
                createdAt = 1000L + i,
                isDeleted = (i == 5) // Person 5 is a tombstone
            )
        }
        val personOk = backupManager.recordPersons(persons)
        assertTrue(personOk)

        val restoredPersons = backupManager.readAllPersons()
        assertEquals(12, restoredPersons.size)
        val p5 = restoredPersons.find { it.id == 5L }
        assertNotNull(p5)
        assertTrue("Tombstone person must remain deleted", p5!!.isDeleted)

        // Create 7 transactions with targetChunkSize = 5 (splits across 2 chunks in same day)
        val dayMillis = 1792886400000L
        val txs = (1..7).map { i ->
            Transaction(
                id = "tx-batch-$i",
                personId = 1L,
                amountCents = i * 100L,
                type = TransactionType.GIVE,
                createdAt = dayMillis,
                isDeleted = (i == 3) // Tx 3 is a tombstone
            )
        }
        for (t in txs) {
            backupManager.recordTransactionMutation(t)
        }

        val restoredTxs = backupManager.readAllTransactions()
        assertEquals(7, restoredTxs.size)
        val t3 = restoredTxs.find { it.id == "tx-batch-3" }
        assertNotNull(t3)
        assertTrue("Tombstone transaction must retain isDeleted = true", t3!!.isDeleted)

        val manifest = backupManager.readManifest()
        assertEquals(12, manifest?.totalPersons)
        assertEquals(3, manifest?.personChunkCount)
        assertEquals(7, manifest?.totalTransactions)
        assertEquals(6, manifest?.activeTransactions)
        assertEquals(1, manifest?.deletedTransactions)
    }

    // ── 21. Fresh-database recovery test (isolated JVM environment) ───────────

    @Test
    fun freshDatabaseRecovery_restoresAllFieldsTombstonesAndImages_withoutDuplication() = runBlocking {
        val receiptsDir = File(tempDir, "receipts").also { it.mkdirs() }
        val receipt1 = File(receiptsDir, "tx-img-p1.jpg").also { it.writeBytes(byteArrayOf(55, 66, 77)) }

        // Seed backup with:
        // 1. Multiple persons (active and tombstone)
        val p1 = Person(id = 10L, name = "Alice", phone = "111", createdAt = 1000L, isDeleted = false)
        val p2 = Person(id = 20L, name = "Bob Tombstone", phone = "222", createdAt = 2000L, isDeleted = true)
        backupManager.recordPersons(listOf(p1, p2))

        // 2. Recurring rules
        val rule1 = RecurringRule(
            id = "rule-1",
            personId = 10L,
            amountCents = 5000L,
            type = TransactionType.GIVE,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = 1000L,
            nextExecutionDate = 5000L
        )
        backupManager.recordRecurringRules(listOf(rule1))

        // 3. Transactions across multiple date partitions
        val txDay1 = Transaction(id = "tx-p1-1", personId = 10L, amountCents = 1500L, type = TransactionType.GIVE, note = "Day 1 tx", createdAt = 1782647958014L) // 2026/06/28
        val txDay2 = Transaction(id = "tx-p1-2", personId = 10L, amountCents = 2500L, type = TransactionType.RECEIVE, note = "Day 2 with receipt", imageUri = receipt1.absolutePath, createdAt = 1782875150821L) // 2026/07/01
        val txDay3Deleted = Transaction(id = "tx-p1-3-del", personId = 20L, amountCents = 3500L, type = TransactionType.GIVE, note = "Tombstone", isDeleted = true, createdAt = 1792888535509L) // 2026/10/25

        backupManager.recordTransactionMutation(txDay1)
        backupManager.recordTransactionMutation(txDay2)
        backupManager.recordTransactionMutation(txDay3Deleted)

        val manifestBefore = backupManager.readManifest()
        assertNotNull(manifestBefore)
        assertEquals(2, manifestBefore?.totalPersons)
        assertEquals(1, manifestBefore?.totalRules)
        assertEquals(3, manifestBefore?.totalTransactions)
        assertEquals(2, manifestBefore?.activeTransactions)
        assertEquals(1, manifestBefore?.deletedTransactions)
        assertEquals(1, manifestBefore?.totalImages)

        // Restore into fresh isolated DAOs and clean receipts folder
        val freshPersonDao = FakePersonDao()
        val freshTxDao = FakeTransactionDao()
        val freshRuleDao = FakeRecurringRuleDao()
        val restoredReceiptsDir = File(tempDir, "restored_receipts")

        val result = backupManager.restoreToDaos(freshPersonDao, freshTxDao, freshRuleDao, restoredReceiptsDir)
        assertEquals(2, result.restoredPersons)
        assertEquals(3, result.restoredTransactions)
        assertEquals(1, result.restoredRules)
        assertEquals(1, result.restoredImages)

        // Verify Persons:
        val restoredP1 = freshPersonDao.getPersonById(10L)
        assertNotNull(restoredP1)
        assertEquals("Alice", restoredP1?.name)
        assertFalse(restoredP1!!.isDeleted)

        // Tombstone must NOT be restored as active:
        assertNull("Tombstone person must not be returned by getPersonById (active query)", freshPersonDao.getPersonById(20L))
        val allPersons = freshPersonDao.getAllPersonsIncludingDeleted()
        assertEquals(2, allPersons.size)
        val restoredP2 = allPersons.find { it.id == 20L }
        assertNotNull(restoredP2)
        assertTrue("Tombstone person must remain isDeleted = true", restoredP2!!.isDeleted)

        // Verify Recurring Rules:
        val allRules = freshRuleDao.getAllRulesList()
        assertEquals(1, allRules.size)
        assertEquals("rule-1", allRules[0].id)
        assertEquals(5000L, allRules[0].amountCents)

        // Verify Transactions:
        val allTxs = freshTxDao.getAllTransactionsIncludingDeleted()
        assertEquals(3, allTxs.size)

        val rTx1 = allTxs.find { it.id == "tx-p1-1" }!!
        assertEquals(1782647958014L, rTx1.createdAt)
        assertEquals(1500L, rTx1.amountCents)
        assertEquals(TransactionType.GIVE, rTx1.type)
        assertEquals("Day 1 tx", rTx1.note)
        assertFalse(rTx1.isDeleted)

        val rTx2 = allTxs.find { it.id == "tx-p1-2" }!!
        assertEquals(1782875150821L, rTx2.createdAt)
        assertEquals(TransactionType.RECEIVE, rTx2.type)
        assertEquals(receipt1.absolutePath, rTx2.imageUri)
        assertFalse(rTx2.isDeleted)

        // Tombstone transaction:
        val rTx3 = allTxs.find { it.id == "tx-p1-3-del" }!!
        assertEquals(1792888535509L, rTx3.createdAt)
        assertTrue("Tombstone transaction must retain isDeleted = true", rTx3.isDeleted)

        // Verify restored receipt file:
        val restoredReceiptFile = File(restoredReceiptsDir, "tx-p1-2.jpg")
        assertTrue("Restored receipt file must exist", restoredReceiptFile.exists())
        assertEquals(3L, restoredReceiptFile.length())

        // Test idempotency: Running restore a second time must NOT duplicate any records
        val repeatResult = backupManager.restoreToDaos(freshPersonDao, freshTxDao, freshRuleDao, restoredReceiptsDir)
        assertEquals(2, repeatResult.restoredPersons)
        assertEquals(3, repeatResult.restoredTransactions)
        assertEquals(2, freshPersonDao.getAllPersonsIncludingDeleted().size)
        assertEquals(3, freshTxDao.getAllTransactionsIncludingDeleted().size)
    }

    // ── 22. Mutation, image replacement, and orphan pruning tests ──────────────

    @Test
    fun mutationsAndReconciliation_imageReplacements_andOrphanPruning() = runBlocking {
        val receiptsDir = File(tempDir, "receipts").also { it.mkdirs() }
        val receipt1 = File(receiptsDir, "tx-mut-1.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val receipt2 = File(receiptsDir, "tx-mut-1-v2.jpg").also { it.writeBytes(byteArrayOf(9, 8, 7, 6)) }

        val createdAt = 1792886400000L
        val tx = Transaction(
            id = "tx-mut-1",
            personId = 1L,
            amountCents = 1000L,
            type = TransactionType.GIVE,
            note = "Original note",
            imageUri = receipt1.absolutePath,
            createdAt = createdAt
        )
        backupManager.recordTransactionMutation(tx)
        val backupImg = File(backupDir, "images/tx-mut-1.jpg")
        assertTrue(backupImg.exists())
        assertEquals(3L, backupImg.length())

        // Replace image
        val replacedTx = tx.copy(imageUri = receipt2.absolutePath, note = "Updated note with new receipt")
        backupManager.recordTransactionMutation(replacedTx)
        assertEquals(4L, backupImg.length()) // Updated to new receipt bytes
        assertEquals(1, backupManager.readManifest()?.totalImages)

        // Verify date partition was NOT altered by note/image replacement
        val partitionDir = File(backupDir, "transactions/2026/10/25")
        assertTrue("Transaction must remain in original 2026/10/25 partition", partitionDir.exists())

        // Orphaned backup image simulation:
        val orphanImg = File(backupDir, "images/hard-deleted-tx.jpg").also { it.writeBytes(byteArrayOf(0)) }
        assertTrue(orphanImg.exists())
        backupManager.rebuildManifestFromDisk()
        assertEquals(2, backupManager.readManifest()?.totalImages)

        // Reconciliation with Room transactions:
        // Room only contains `tx-mut-1`. `hard-deleted-tx` does not exist in Room.
        val fakeDb = listOf(replacedTx)
        backupManager.reconcileWithData(emptyList(), fakeDb, emptyList())

        // Orphan image must be pruned from imagesDir, and manifest updated to 1
        assertFalse("Orphaned image for hard-deleted transaction must be pruned", orphanImg.exists())
        assertEquals(1, backupManager.readManifest()?.totalImages)
    }
}


