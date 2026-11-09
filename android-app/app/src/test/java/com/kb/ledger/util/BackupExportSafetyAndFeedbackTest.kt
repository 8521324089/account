package com.kb.ledger.util

import android.net.TestUri
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.dao.PersonDao
import com.kb.ledger.data.dao.RecurringRuleDao
import com.kb.ledger.data.dao.SyncOperationDao
import com.kb.ledger.data.dao.TransactionDao
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.RecurringRule
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.data.repository.FakePersonDao
import com.kb.ledger.data.repository.FakeRecurringRuleDao
import com.kb.ledger.data.repository.FakeTransactionDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * Unit tests verifying SAF export failure safety (Task A) and UI feedback mapping (Task B).
 *
 * Verifies:
 * 1. Serialization failure before destination write.
 * 2. Invalid staged JSON rejected before destination write.
 * 3. Destination stream failure partway through writing.
 * 4. Temporary-file cleanup on success and failure.
 * 5. Large synthetic backup streaming behavior.
 * 6. Non-PII UI message mapping for success, warning, error, and picker cancellation.
 */
class BackupExportSafetyAndFeedbackTest {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var tempDir: File
    private lateinit var stagingDir: File
    private lateinit var filesDir: File

    private val dummyUri: Uri = TestUri("content://com.kb.ledger.provider/backup.json")

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("backup_export_safety_test").toFile()
        stagingDir = File(tempDir, "export_staging").also { it.mkdirs() }
        filesDir = File(tempDir, "files").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── Test Double Helpers ──────────────────────────────────────────────────

    private open class TestAppDatabase(
        val personDaoImpl: PersonDao = FakePersonDao(),
        val transactionDaoImpl: TransactionDao = FakeTransactionDao(),
        val recurringRuleDaoImpl: RecurringRuleDao = FakeRecurringRuleDao()
    ) : AppDatabase() {
        override fun personDao(): PersonDao = personDaoImpl
        override fun transactionDao(): TransactionDao = transactionDaoImpl
        override fun syncOperationDao(): SyncOperationDao = throw UnsupportedOperationException()
        override fun recurringRuleDao(): RecurringRuleDao = recurringRuleDaoImpl
        override fun clearAllTables() {}
        override fun createInvalidationTracker(): androidx.room.InvalidationTracker {
            return androidx.room.InvalidationTracker(this, "persons", "transactions", "recurring_rules")
        }
        override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper {
            throw UnsupportedOperationException()
        }
    }

    private class FailingPersonDao(
        private val failure: RuntimeException,
        delegate: PersonDao = FakePersonDao()
    ) : PersonDao by delegate {
        override suspend fun getAllPersonsIncludingDeleted(): List<Person> = throw failure
    }

    private class RecordingStreamProvider(
        private val outputStreamProvider: (Uri) -> OutputStream?
    ) : DestinationStreamProvider {
        var openCalled: Boolean = false
        var lastUri: Uri? = null

        override fun openOutputStream(uri: Uri): OutputStream? {
            openCalled = true
            lastUri = uri
            return outputStreamProvider(uri)
        }
    }

    private class RecordingSourceStreamProvider(
        private val inputStreamProvider: (Uri) -> InputStream?
    ) : SourceStreamProvider {
        var openCalled: Boolean = false
        var lastUri: Uri? = null

        override fun openInputStream(uri: Uri): InputStream? {
            openCalled = true
            lastUri = uri
            return inputStreamProvider(uri)
        }
    }

    // ── Task A: Export Failure Safety Tests ──────────────────────────────────

    @Test
    fun serializationFailureBeforeDestinationWrite_doesNotOpenDestinationStream_andCleansUp() = runBlocking {
        // Arrange: personDao throws during query
        val failingPersonDao = FailingPersonDao(IllegalStateException("Database query error: SQLite disk I/O failure"))
        val db = TestAppDatabase(personDaoImpl = failingPersonDao)
        val recordingStream = RecordingStreamProvider { ByteArrayOutputStream() }

        val backupManager = BackupManager(
            database = db,
            destinationStreamProvider = recordingStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        // Act
        val result = backupManager.exportData(dummyUri)

        // Assert
        assertFalse("Export should fail", result.success)
        assertTrue("Error should mention DB failure", result.error?.contains("SQLite disk I/O failure") == true)
        assertFalse("Destination stream should NEVER be opened if query/serialization fails", recordingStream.openCalled)
        assertEquals("Staging directory should be empty after failure", 0, stagingDir.listFiles()?.size ?: 0)
    }

    @Test
    fun validateStagedBackupFile_rejectsCorruptMissingOrFutureSchemaJSON() {
        // 1. Non-existent file
        val missingFile = File(stagingDir, "non_existent.json")
        val errMissing = BackupManager.validateStagedBackupFile(missingFile)
        assertNotNull("Non-existent file should be rejected", errMissing)

        // 2. Empty file
        val emptyFile = File(stagingDir, "empty.json").apply { writeText("") }
        val errEmpty = BackupManager.validateStagedBackupFile(emptyFile)
        assertNotNull("Empty file should be rejected", errEmpty)

        // 3. Truncated / malformed JSON
        val malformedFile = File(stagingDir, "malformed.json").apply {
            writeText("{\"persons\": [{\"name\": \"Alice\"")
        }
        val errMalformed = BackupManager.validateStagedBackupFile(malformedFile)
        assertNotNull("Malformed JSON should be rejected", errMalformed)

        // 4. Missing required persons field
        val missingPersonsFile = File(stagingDir, "missing_persons.json").apply {
            writeText("{\"transactions\": [], \"schemaVersion\": 3}")
        }
        val errNoPersons = BackupManager.validateStagedBackupFile(missingPersonsFile)
        assertNotNull("Missing persons should be rejected", errNoPersons)
        assertTrue("Error should mention persons", errNoPersons!!.contains("persons"))

        // 5. Missing required transactions field
        val missingTxFile = File(stagingDir, "missing_tx.json").apply {
            writeText("{\"persons\": [], \"schemaVersion\": 3}")
        }
        val errNoTx = BackupManager.validateStagedBackupFile(missingTxFile)
        assertNotNull("Missing transactions should be rejected", errNoTx)
        assertTrue("Error should mention transactions", errNoTx!!.contains("transactions"))

        // 6. Future schema version
        val futureSchemaFile = File(stagingDir, "future_schema.json").apply {
            writeText("{\"persons\": [], \"transactions\": [], \"schemaVersion\": 999}")
        }
        val errFuture = BackupManager.validateStagedBackupFile(futureSchemaFile)
        assertNotNull("Future schema should be rejected", errFuture)
        assertTrue("Error should mention schema version", errFuture!!.contains("schema version"))

        // 7. Valid backup envelope
        val validFile = File(stagingDir, "valid.json").apply {
            writeText(gson.toJson(BackupData(persons = emptyList(), transactions = emptyList())))
        }
        val validResult = BackupManager.validateStagedBackupFile(validFile)
        assertNull("Valid backup file should return null (no error)", validResult)
    }

    @Test
    fun destinationStreamFailurePartwayThrough_catchesError_andCleansUpStagedFile() = runBlocking {
        // Arrange: Destination stream that writes 20 bytes then fails with IOException
        val failingStream = object : OutputStream() {
            var writtenCount = 0
            override fun write(b: Int) {
                writtenCount++
                if (writtenCount > 20) {
                    throw IOException("Destination disk full during write")
                }
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                for (i in off until off + len) {
                    write(b[i].toInt())
                }
            }
        }

        val db = TestAppDatabase()
        val recordingStream = RecordingStreamProvider { failingStream }

        val backupManager = BackupManager(
            database = db,
            destinationStreamProvider = recordingStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        // Act
        val result = backupManager.exportData(dummyUri)

        // Assert
        assertFalse("Export should report failure", result.success)
        assertTrue("Error should describe disk full", result.error?.contains("Destination disk full during write") == true)
        assertTrue("Destination stream was opened before failure", recordingStream.openCalled)
        assertEquals("Staging directory should have temporary files removed in finally", 0, stagingDir.listFiles()?.size ?: 0)
    }

    @Test
    fun temporaryFileCleanup_onSuccessAndFailure() = runBlocking {
        val db = TestAppDatabase()

        // 1. Successful run
        val successStream = RecordingStreamProvider { ByteArrayOutputStream() }
        val backupManager = BackupManager(
            database = db,
            destinationStreamProvider = successStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )
        val successResult = backupManager.exportData(dummyUri)
        assertTrue("Export should succeed", successResult.success)
        assertEquals("Staging dir should be empty on success", 0, stagingDir.listFiles()?.size ?: 0)

        // 2. Failure run: stream returns null (e.g. permission denied)
        val nullStream = RecordingStreamProvider { null }
        val backupManagerFailing = BackupManager(
            database = db,
            destinationStreamProvider = nullStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )
        val failResult = backupManagerFailing.exportData(dummyUri)
        assertFalse("Export should fail when stream cannot be opened", failResult.success)
        assertEquals("Staging dir should be empty on stream failure", 0, stagingDir.listFiles()?.size ?: 0)
    }

    @Test
    fun largeSyntheticBackupStreaming_correctlyStreamsMultipleChunksWithoutDataLoss() = runBlocking {
        // Arrange: 150 persons and 300 transactions (~100 KB payload, >12 chunks of 8 KB)
        val fakePersonDao = FakePersonDao()
        val fakeTxDao = FakeTransactionDao()

        val persons = (1..150).map { i ->
            Person(
                id = i.toLong(),
                name = "Customer Synthetic Record #$i",
                phone = "98765${i.toString().padStart(5, '0')}",
                cloudId = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis() - 10000L,
                updatedAt = System.currentTimeMillis()
            )
        }
        val transactions = (1..300).map { i ->
            Transaction(
                id = "synth-tx-$i-${UUID.randomUUID()}",
                personId = ((i % 150) + 1).toLong(),
                amountCents = (i * 1234L),
                type = if (i % 2 == 0) TransactionType.GIVE else TransactionType.RECEIVE,
                note = "Synthetic transaction entry #$i with extra padding text to verify multi-chunk SAF streaming integrity without in-memory buffering bottlenecks",
                createdAt = System.currentTimeMillis() - 5000L,
                updatedAt = System.currentTimeMillis()
            )
        }

        fakePersonDao.insertPersons(persons)
        transactions.forEach { fakeTxDao.insertTransaction(it) }

        val db = TestAppDatabase(personDaoImpl = fakePersonDao, transactionDaoImpl = fakeTxDao)
        val destinationCapture = ByteArrayOutputStream()
        val streamProvider = RecordingStreamProvider { destinationCapture }

        val backupManager = BackupManager(
            database = db,
            destinationStreamProvider = streamProvider,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        // Act
        val result = backupManager.exportData(dummyUri)

        // Assert
        assertTrue("Export must succeed for large dataset", result.success)
        assertTrue("Payload should exceed 8 KB buffer size", destinationCapture.size() > 8192)
        assertEquals("Staging files must be cleaned up", 0, stagingDir.listFiles()?.size ?: 0)

        // Verify deserialization of destination content
        val writtenJson = destinationCapture.toString(Charsets.UTF_8.name())
        val recoveredBackup: BackupData = gson.fromJson(writtenJson, BackupData::class.java)

        assertEquals("All 150 persons must be restored", 150, recoveredBackup.persons.size)
        assertEquals("All 300 transactions must be restored", 300, recoveredBackup.transactions.size)
        assertEquals("Current schema version 3 must be preserved", 3, recoveredBackup.schemaVersion)
    }

    // ── Task B: UI Feedback Mapping Tests ────────────────────────────────────

    @Test
    fun resolveImportMessage_successMapping() {
        val result = ImportResult(success = true, restoredImageCount = 5, missingImageTxIds = emptyList())
        val uiMsg = BackupFeedbackHelper.resolveImportMessage(result)

        assertTrue(uiMsg is BackupFeedbackHelper.ImportUiMessage.Success)
        assertEquals("Backup restored successfully.", (uiMsg as BackupFeedbackHelper.ImportUiMessage.Success).message)
    }

    @Test
    fun resolveImportMessage_warningMapping_singularAndPluralAttachments() {
        // Singular missing
        val singleResult = ImportResult(success = true, restoredImageCount = 4, missingImageTxIds = listOf("tx-1"))
        val singleMsg = BackupFeedbackHelper.resolveImportMessage(singleResult)
        assertTrue(singleMsg is BackupFeedbackHelper.ImportUiMessage.Warning)
        assertEquals(
            "Backup restored successfully, but 1 bill attachment could not be restored.",
            (singleMsg as BackupFeedbackHelper.ImportUiMessage.Warning).message
        )

        // Plural missing
        val pluralResult = ImportResult(success = true, restoredImageCount = 3, missingImageTxIds = listOf("tx-1", "tx-2", "tx-3"))
        val pluralMsg = BackupFeedbackHelper.resolveImportMessage(pluralResult)
        assertTrue(pluralMsg is BackupFeedbackHelper.ImportUiMessage.Warning)
        assertEquals(
            "Backup restored successfully, but 3 bill attachments could not be restored.",
            (pluralMsg as BackupFeedbackHelper.ImportUiMessage.Warning).message
        )
    }

    @Test
    fun resolveImportMessage_corruptOrMalformedErrorMapping() {
        val corruptResult = ImportResult(success = false, error = "Backup file is corrupt or unreadable: Unexpected end of input")
        val uiMsg = BackupFeedbackHelper.resolveImportMessage(corruptResult)
        assertTrue(uiMsg is BackupFeedbackHelper.ImportUiMessage.Error)
        assertEquals(
            "Backup file is corrupt or unreadable. Please choose a valid backup file.",
            (uiMsg as BackupFeedbackHelper.ImportUiMessage.Error).message
        )
    }

    @Test
    fun resolveImportMessage_incompleteRecordsErrorMapping() {
        val missingResult = ImportResult(success = false, error = "Incomplete backup file: 'persons' array is missing.")
        val uiMsg = BackupFeedbackHelper.resolveImportMessage(missingResult)
        assertTrue(uiMsg is BackupFeedbackHelper.ImportUiMessage.Error)
        assertEquals(
            "Backup file is incomplete or missing required records.",
            (uiMsg as BackupFeedbackHelper.ImportUiMessage.Error).message
        )
    }

    @Test
    fun resolveImportMessage_integrityErrorMapping() {
        val integrityResult = ImportResult(success = false, error = "Backup integrity error: transaction tx-1 references unknown person ID 999")
        val uiMsg = BackupFeedbackHelper.resolveImportMessage(integrityResult)
        assertTrue(uiMsg is BackupFeedbackHelper.ImportUiMessage.Error)
        assertEquals(
            "Backup data integrity check failed. No records were modified.",
            (uiMsg as BackupFeedbackHelper.ImportUiMessage.Error).message
        )
    }

    @Test
    fun resolveImportMessage_genericErrorMapping() {
        val genericResult = ImportResult(success = false, error = "Unexpected room exception")
        val uiMsg = BackupFeedbackHelper.resolveImportMessage(genericResult)
        assertTrue(uiMsg is BackupFeedbackHelper.ImportUiMessage.Error)
        assertEquals(
            "Backup restoration failed. No changes were made to your data.",
            (uiMsg as BackupFeedbackHelper.ImportUiMessage.Error).message
        )
    }

    @Test
    fun resolveImportMessage_pickerCancellation_returnsNoneWithoutError() {
        // Picker dismissed without selecting file
        val cancelMsg = BackupFeedbackHelper.resolveImportMessage(result = null, isCancelled = true)
        assertEquals(BackupFeedbackHelper.ImportUiMessage.None, cancelMsg)

        // Uninvoked / null result
        val nullMsg = BackupFeedbackHelper.resolveImportMessage(result = null, isCancelled = false)
        assertEquals(BackupFeedbackHelper.ImportUiMessage.None, nullMsg)
    }

    @Test
    fun resolveExportMessage_successWarningErrorCancellationMapping() {
        // Success
        val successMsg = BackupFeedbackHelper.resolveExportMessage(ExportResult(success = true))
        assertTrue(successMsg is BackupFeedbackHelper.ExportUiMessage.Success)
        assertEquals("Backup exported successfully.", (successMsg as BackupFeedbackHelper.ExportUiMessage.Success).message)

        // Warning (single missing image)
        val warnMsg1 = BackupFeedbackHelper.resolveExportMessage(ExportResult(success = true, missingImageTxIds = listOf("tx-1")))
        assertTrue(warnMsg1 is BackupFeedbackHelper.ExportUiMessage.Warning)
        assertEquals(
            "Backup exported successfully, but 1 bill attachment was missing from storage.",
            (warnMsg1 as BackupFeedbackHelper.ExportUiMessage.Warning).message
        )

        // Warning (plural missing images)
        val warnMsg2 = BackupFeedbackHelper.resolveExportMessage(ExportResult(success = true, missingImageTxIds = listOf("tx-1", "tx-2")))
        assertTrue(warnMsg2 is BackupFeedbackHelper.ExportUiMessage.Warning)
        assertEquals(
            "Backup exported successfully, but 2 bill attachments were missing from storage.",
            (warnMsg2 as BackupFeedbackHelper.ExportUiMessage.Warning).message
        )

        // Failure
        val errorMsg = BackupFeedbackHelper.resolveExportMessage(ExportResult(success = false, error = "Disk full"))
        assertTrue(errorMsg is BackupFeedbackHelper.ExportUiMessage.Error)
        assertEquals("Backup export could not be completed.", (errorMsg as BackupFeedbackHelper.ExportUiMessage.Error).message)

        // Cancellation
        val cancelMsg = BackupFeedbackHelper.resolveExportMessage(result = null, isCancelled = true)
        assertEquals(BackupFeedbackHelper.ExportUiMessage.None, cancelMsg)
    }

    @Test
    fun feedbackHelper_guaranteesZeroPIIInAllMessages() {
        val sensitiveErrors = listOf(
            "Failed processing transaction for customer Alice Sharma with balance $5,000",
            "Cannot open /data/user/0/com.kb.ledger/files/receipts/secret_doc.jpg",
            "OAuth Bearer token ya29.a0AfH6SM... expired during query",
            "Exception at com.kb.ledger.data.AppDatabase.query(AppDatabase.kt:42)"
        )

        for (sensitiveErr in sensitiveErrors) {
            val importMsg = BackupFeedbackHelper.resolveImportMessage(ImportResult(success = false, error = sensitiveErr))
            val exportMsg = BackupFeedbackHelper.resolveExportMessage(ExportResult(success = false, error = sensitiveErr))

            val importText = when (importMsg) {
                is BackupFeedbackHelper.ImportUiMessage.Error -> importMsg.message
                is BackupFeedbackHelper.ImportUiMessage.Warning -> importMsg.message
                is BackupFeedbackHelper.ImportUiMessage.Success -> importMsg.message
                BackupFeedbackHelper.ImportUiMessage.None -> ""
            }
            val exportText = when (exportMsg) {
                is BackupFeedbackHelper.ExportUiMessage.Error -> exportMsg.message
                is BackupFeedbackHelper.ExportUiMessage.Warning -> exportMsg.message
                is BackupFeedbackHelper.ExportUiMessage.Success -> exportMsg.message
                BackupFeedbackHelper.ExportUiMessage.None -> ""
            }

            // Verify no sensitive tokens leak
            assertFalse(importText.contains("Alice Sharma"))
            assertFalse(importText.contains("secret_doc.jpg"))
            assertFalse(importText.contains("ya29."))
            assertFalse(importText.contains("AppDatabase.kt"))

            assertFalse(exportText.contains("Alice Sharma"))
            assertFalse(exportText.contains("secret_doc.jpg"))
            assertFalse(exportText.contains("ya29."))
            assertFalse(exportText.contains("AppDatabase.kt"))
        }
    }

    // ── Task 1: Mutex Coordination & Concurrency Tests ───────────────────────

    private val validJpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x60,
        0x00, 0x60, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte()
    )

    @Test
    fun export_waitsWhileMutexHeld_andProceedsAfterUnlock() = runBlocking {
        val db = TestAppDatabase()
        val recordingStream = RecordingStreamProvider { ByteArrayOutputStream() }
        val backupManager = BackupManager(
            database = db,
            destinationStreamProvider = recordingStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        val mutexLocked = CompletableDeferred<Unit>()
        val unlockMutex = CompletableDeferred<Unit>()
        val exportStarted = CompletableDeferred<Unit>()
        var exportFinished = false

        // Simulate an in-flight operation (like restore or recovery) holding the mutex
        val holderJob = launch(Dispatchers.Default) {
            BackupManager.backupSyncMutex.withLock {
                mutexLocked.complete(Unit)
                unlockMutex.await()
            }
        }

        mutexLocked.await()
        assertTrue("Mutex should be held by holderJob", BackupManager.backupSyncMutex.isLocked)

        val exportJob = launch(Dispatchers.Default) {
            exportStarted.complete(Unit)
            val result = backupManager.exportData(dummyUri)
            assertTrue("Export should eventually succeed", result.success)
            exportFinished = true
        }

        exportStarted.await()
        // Small yield to let export attempt to acquire the mutex
        kotlinx.coroutines.yield()
        assertFalse("Export must wait while mutex is held", exportFinished)

        // Now release the mutex
        unlockMutex.complete(Unit)
        holderJob.join()
        exportJob.join()

        assertTrue("Export must finish after mutex is released", exportFinished)
        assertFalse("Mutex must be unlocked after all operations complete", BackupManager.backupSyncMutex.isLocked)
    }

    @Test
    fun export_exceptionReleasesMutex() = runBlocking {
        val failingPersonDao = FailingPersonDao(IllegalStateException("Simulated query failure"))
        val db = TestAppDatabase(personDaoImpl = failingPersonDao)
        val recordingStream = RecordingStreamProvider { ByteArrayOutputStream() }
        val backupManager = BackupManager(
            database = db,
            destinationStreamProvider = recordingStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        val result = backupManager.exportData(dummyUri)
        assertFalse("Export should fail due to query exception", result.success)
        assertFalse("Mutex must be unlocked even when export throws", BackupManager.backupSyncMutex.isLocked)

        // Verify another operation can acquire the mutex immediately
        val acquired = BackupManager.backupSyncMutex.tryLock()
        assertTrue("Mutex should be free for immediate acquisition", acquired)
        BackupManager.backupSyncMutex.unlock()
    }

    @Test
    fun export_cancellationPropagatesAndReleasesMutex() = runBlocking {
        val inExport = CompletableDeferred<Unit>()
        val slowPersonDao = object : PersonDao by FakePersonDao() {
            override suspend fun getAllPersonsIncludingDeleted(): List<Person> {
                inExport.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val db = TestAppDatabase(personDaoImpl = slowPersonDao)
        val recordingStream = RecordingStreamProvider { ByteArrayOutputStream() }
        val backupManager = BackupManager(
            database = db,
            destinationStreamProvider = recordingStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        var exportResult: ExportResult? = null
        var thrownException: Throwable? = null
        val exportJob = launch(Dispatchers.Default) {
            try {
                exportResult = backupManager.exportData(dummyUri)
            } catch (t: Throwable) {
                thrownException = t
                throw t
            }
        }

        inExport.await()
        assertTrue("Mutex must be locked while export is in progress", BackupManager.backupSyncMutex.isLocked)

        exportJob.cancelAndJoin()
        assertTrue("Export coroutine must be cancelled", exportJob.isCancelled)
        assertNull("exportData must rethrow CancellationException rather than returning an ExportResult", exportResult)
        assertTrue("CancellationException must propagate out of exportData", thrownException is CancellationException)
        assertFalse("Mutex must be released when export coroutine is cancelled", BackupManager.backupSyncMutex.isLocked)
    }

    @Test
    fun export_cancellationDuringImageRead_propagatesAndReleasesMutex() = runBlocking {
        val p = Person(id = 1L, name = "Cancel Person", phone = "9111111111")
        val tx = Transaction(
            id = "tx-cancel-img",
            personId = 1L,
            amountCents = 100_00L,
            type = TransactionType.GIVE,
            imageUri = "content://images/tx-cancel-img"
        )
        val fakePersonDao = FakePersonDao().apply { insertPerson(p) }
        val fakeTxDao = FakeTransactionDao().apply { insertTransaction(tx) }
        val db = TestAppDatabase(personDaoImpl = fakePersonDao, transactionDaoImpl = fakeTxDao)

        val receiptsDir = File(filesDir, "receipts").also { it.mkdirs() }
        val imgFile = File(receiptsDir, "tx-cancel-img.jpg")
        imgFile.writeBytes(validJpegBytes)

        val inImageRead = CompletableDeferred<Unit>()
        val cancellingFs = object : FileSystemOperations by DefaultFileSystemOperations {
            override fun exists(file: File): Boolean {
                if (file.name == "tx-cancel-img.jpg") {
                    inImageRead.complete(Unit)
                }
                return DefaultFileSystemOperations.exists(file)
            }
            override fun length(file: File): Long {
                if (file.name == "tx-cancel-img.jpg") {
                    // Simulate coroutine cancellation during image extraction
                    throw CancellationException("Simulated cancellation during image extraction")
                }
                return DefaultFileSystemOperations.length(file)
            }
        }

        val recordingStream = RecordingStreamProvider { ByteArrayOutputStream() }
        val backupManager = BackupManager(
            database = db,
            destinationStreamProvider = recordingStream,
            fileSystem = cancellingFs,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        var exportResult: ExportResult? = null
        var thrownException: Throwable? = null
        val exportJob = launch(Dispatchers.Default) {
            try {
                exportResult = backupManager.exportData(dummyUri)
            } catch (t: Throwable) {
                thrownException = t
                throw t
            }
        }

        inImageRead.await()
        exportJob.join()

        assertTrue("Export job must be cancelled", exportJob.isCancelled)
        assertNull("exportData must not catch cancellation and return an ExportResult", exportResult)
        assertTrue(
            "CancellationException must propagate rather than being swallowed as a missing-attachment warning",
            thrownException is CancellationException
        )
        assertFalse("Mutex must be released after cancellation during image read", BackupManager.backupSyncMutex.isLocked)
    }

    /**
     * Verifies that sequential invocations of [BackupManager.exportData] acquire and release
     * [BackupManager.backupSyncMutex] cleanly without leaving the mutex locked or deadlocking
     * subsequent calls.
     *
     * Note: This test verifies sequential acquisition; it does not perform nested lock acquisition
     * (Kotlin's Mutex is non-reentrant) nor does it claim to test concurrent interleaving.
     */
    @Test
    fun export_sequentialInvocationsAcquireAndReleaseMutexWithoutDeadlock() = runBlocking {
        val db = TestAppDatabase()
        val recordingStream = RecordingStreamProvider { ByteArrayOutputStream() }
        val backupManager = BackupManager(
            database = db,
            destinationStreamProvider = recordingStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        // Multiple sequential invocations must succeed without deadlocking on non-reentrant mutex
        val res1 = backupManager.exportData(dummyUri)
        assertTrue("First export should succeed", res1.success)
        assertFalse(BackupManager.backupSyncMutex.isLocked)

        val res2 = backupManager.exportData(dummyUri)
        assertTrue("Second export should succeed", res2.success)
        assertFalse(BackupManager.backupSyncMutex.isLocked)
    }

    @Test
    fun export_receiptReferencesAndPayloadsRemainConsistent() = runBlocking {
        val p1 = Person(id = 1L, name = "Alice Sharma", phone = "9876543210")
        val tx1 = Transaction(
            id = "tx-consistent-1",
            personId = 1L,
            amountCents = 1500_00L,
            type = TransactionType.GIVE,
            imageUri = "content://com.kb.ledger/receipts/tx-consistent-1.jpg"
        )
        val fakePersonDao = FakePersonDao().also { it.insertPerson(p1) }
        val fakeTxDao = FakeTransactionDao().also { it.insertTransaction(tx1) }
        val db = TestAppDatabase(personDaoImpl = fakePersonDao, transactionDaoImpl = fakeTxDao)

        // Write the corresponding receipt file to receiptsDir
        val receiptsDir = File(filesDir, "receipts").also { it.mkdirs() }
        val receiptFile = File(receiptsDir, "tx-consistent-1.jpg")
        receiptFile.writeBytes(validJpegBytes)

        val outputStream = ByteArrayOutputStream()
        val recordingStream = RecordingStreamProvider { outputStream }
        val backupManager = BackupManager(
            database = db,
            destinationStreamProvider = recordingStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        val result = backupManager.exportData(dummyUri)
        assertTrue("Export must succeed", result.success)
        assertTrue("Missing image list should be empty", result.missingImageTxIds.isEmpty())

        // Validate payload content
        val exportedJson = outputStream.toString(Charsets.UTF_8.name())
        val exportedBackup = gson.fromJson(exportedJson, BackupData::class.java)
        assertEquals(1, exportedBackup.transactions.size)
        assertEquals("tx-consistent-1", exportedBackup.transactions[0].id)
        assertNotNull(exportedBackup.billImagesByTxId)
        val encodedBytes = exportedBackup.billImagesByTxId!!["tx-consistent-1"]
        assertNotNull("Image payload must be present in backup envelope", encodedBytes)
        val decoded = Base64.getDecoder().decode(encodedBytes)
        assertArrayEquals("Payload bytes must match original receipt bytes exactly", validJpegBytes, decoded)
    }

    // ── Task 2: Missing Attachment Warning on Import Tests ───────────────────

    @Test
    fun import_attachmentMetadataWithNoPayload_reportedInMissingImageTxIds() = runBlocking {
        val fakePersonDao = FakePersonDao()
        val fakeTxDao = FakeTransactionDao()
        val db = TestAppDatabase(personDaoImpl = fakePersonDao, transactionDaoImpl = fakeTxDao)

        val p = Person(id = 1L, name = "Bob Builder", phone = "9111111111", cloudId = "cloud-bob")
        val tx = Transaction(
            id = "tx-no-payload",
            personId = 1L,
            amountCents = 500_00L,
            type = TransactionType.RECEIVE,
            imageUri = "content://media/external/images/media/42" // Nonblank metadata
        )

        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(tx),
            billImagesByTxId = emptyMap(), // Missing payload!
            schemaVersion = 3,
            exportDate = System.currentTimeMillis()
        )
        val jsonBytes = gson.toJson(backup).toByteArray(Charsets.UTF_8)
        val sourceStream = RecordingSourceStreamProvider { ByteArrayInputStream(jsonBytes) }

        val backupManager = BackupManager(
            database = db,
            sourceStreamProvider = sourceStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        val result = backupManager.importData(dummyUri)
        assertTrue("Import should succeed despite missing attachment payload: ${result.error}", result.success)
        assertEquals("Must report 1 missing image transaction", 1, result.missingImageTxIds.size)
        assertTrue("Missing image list must contain tx-no-payload", result.missingImageTxIds.contains("tx-no-payload"))

        // Verify attachment metadata was NOT silently cleared
        val storedTx = fakeTxDao.getTransactionById("tx-no-payload")
        assertNotNull(storedTx)
        assertEquals("content://media/external/images/media/42", storedTx?.imageUri)
    }

    @Test
    fun import_attachmentMetadataDriveIdOnly_reportedInMissingImageTxIds() = runBlocking {
        val fakePersonDao = FakePersonDao()
        val fakeTxDao = FakeTransactionDao()
        val db = TestAppDatabase(personDaoImpl = fakePersonDao, transactionDaoImpl = fakeTxDao)

        val p = Person(id = 1L, name = "Charlie", phone = "9222222222", cloudId = "cloud-charlie")
        val tx = Transaction(
            id = "tx-drive-only",
            personId = 1L,
            amountCents = 800_00L,
            type = TransactionType.GIVE,
            imageUri = null,
            imageFileId = "drive-file-id-99" // Drive metadata present without local imageUri
        )

        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(tx),
            billImagesByTxId = emptyMap(), // Missing payload
            schemaVersion = 3,
            exportDate = System.currentTimeMillis()
        )
        val jsonBytes = gson.toJson(backup).toByteArray(Charsets.UTF_8)
        val sourceStream = RecordingSourceStreamProvider { ByteArrayInputStream(jsonBytes) }

        val backupManager = BackupManager(
            database = db,
            sourceStreamProvider = sourceStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        val result = backupManager.importData(dummyUri)
        assertTrue(result.success)
        assertEquals(1, result.missingImageTxIds.size)
        assertTrue(result.missingImageTxIds.contains("tx-drive-only"))

        val storedTx = fakeTxDao.getTransactionById("tx-drive-only")
        assertNotNull(storedTx)
        assertEquals("drive-file-id-99", storedTx?.imageFileId)
    }

    @Test
    fun import_payloadPresentButCorrupt_reportedInMissingImageTxIds() = runBlocking {
        val fakePersonDao = FakePersonDao()
        val fakeTxDao = FakeTransactionDao()
        val db = TestAppDatabase(personDaoImpl = fakePersonDao, transactionDaoImpl = fakeTxDao)

        val p = Person(id = 1L, name = "David", phone = "9333333333", cloudId = "cloud-david")
        val tx = Transaction(
            id = "tx-corrupt-payload",
            personId = 1L,
            amountCents = 120_00L,
            type = TransactionType.GIVE,
            imageUri = "content://images/tx-corrupt"
        )

        val corruptBase64 = Base64.getEncoder().encodeToString("CORRUPT_NOT_AN_IMAGE_HEADER".toByteArray())
        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(tx),
            billImagesByTxId = mapOf("tx-corrupt-payload" to corruptBase64),
            schemaVersion = 3,
            exportDate = System.currentTimeMillis()
        )
        val jsonBytes = gson.toJson(backup).toByteArray(Charsets.UTF_8)
        val sourceStream = RecordingSourceStreamProvider { ByteArrayInputStream(jsonBytes) }

        val backupManager = BackupManager(
            database = db,
            sourceStreamProvider = sourceStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        val result = backupManager.importData(dummyUri)
        assertTrue("Import succeeds with warning", result.success)
        assertEquals("Corrupt payload should be reported as missing image", 1, result.missingImageTxIds.size)
        assertTrue(result.missingImageTxIds.contains("tx-corrupt-payload"))
    }

    @Test
    fun import_promotionFailure_reportedInMissingImageTxIds() = runBlocking {
        val fakePersonDao = FakePersonDao()
        val fakeTxDao = FakeTransactionDao()
        val db = TestAppDatabase(personDaoImpl = fakePersonDao, transactionDaoImpl = fakeTxDao)

        val p = Person(id = 1L, name = "Eva", phone = "9444444444", cloudId = "cloud-eva")
        val tx = Transaction(
            id = "tx-promo-fail",
            personId = 1L,
            amountCents = 300_00L,
            type = TransactionType.RECEIVE,
            imageUri = "content://images/tx-promo-fail"
        )

        val validBase64 = Base64.getEncoder().encodeToString(validJpegBytes)
        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(tx),
            billImagesByTxId = mapOf("tx-promo-fail" to validBase64),
            schemaVersion = 3,
            exportDate = System.currentTimeMillis()
        )
        val jsonBytes = gson.toJson(backup).toByteArray(Charsets.UTF_8)
        val sourceStream = RecordingSourceStreamProvider { ByteArrayInputStream(jsonBytes) }

        // Inject filesystem that fails promotion
        val failingFs = object : FileSystemOperations by DefaultFileSystemOperations {
            override fun rename(source: File, destination: File): Boolean = false
            override fun copy(source: File, destination: File, overwrite: Boolean) {
                throw IOException("Simulated filesystem I/O error during promotion")
            }
        }

        val backupManager = BackupManager(
            database = db,
            sourceStreamProvider = sourceStream,
            fileSystem = failingFs,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        val result = backupManager.importData(dummyUri)
        assertTrue("Import must succeed overall even if promotion fails", result.success)
        assertEquals("Promotion failure should be reported in missingImageTxIds", 1, result.missingImageTxIds.size)
        assertTrue(result.missingImageTxIds.contains("tx-promo-fail"))
        assertEquals("0 images successfully restored", 0, result.restoredImageCount)
    }

    @Test
    fun import_multipleFailureConditions_notDoubleCounted() = runBlocking {
        val fakePersonDao = FakePersonDao()
        val fakeTxDao = FakeTransactionDao()
        val db = TestAppDatabase(personDaoImpl = fakePersonDao, transactionDaoImpl = fakeTxDao)

        // Transaction has both imageUri and imageFileId, but lacks payload in billImagesByTxId
        val p = Person(id = 1L, name = "Frank", phone = "9555555555", cloudId = "cloud-frank")
        val tx = Transaction(
            id = "tx-multi-condition",
            personId = 1L,
            amountCents = 450_00L,
            type = TransactionType.GIVE,
            imageUri = "content://images/multi",
            imageFileId = "drive-multi"
        )

        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(tx),
            billImagesByTxId = emptyMap(),
            schemaVersion = 3,
            exportDate = System.currentTimeMillis()
        )
        val jsonBytes = gson.toJson(backup).toByteArray(Charsets.UTF_8)
        val sourceStream = RecordingSourceStreamProvider { ByteArrayInputStream(jsonBytes) }

        val backupManager = BackupManager(
            database = db,
            sourceStreamProvider = sourceStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        val result = backupManager.importData(dummyUri)
        assertTrue(result.success)
        assertEquals("Must not double count the transaction in missingImageTxIds", 1, result.missingImageTxIds.size)
        assertEquals(1, result.missingImageTxIds.count { it == "tx-multi-condition" })
    }

    @Test
    fun import_noAttachmentMetadataAndNoImages_cleanSuccess() = runBlocking {
        val fakePersonDao = FakePersonDao()
        val fakeTxDao = FakeTransactionDao()
        val db = TestAppDatabase(personDaoImpl = fakePersonDao, transactionDaoImpl = fakeTxDao)

        val p = Person(id = 1L, name = "Grace", phone = "9666666666", cloudId = "cloud-grace")
        val tx = Transaction(
            id = "tx-clean",
            personId = 1L,
            amountCents = 100_00L,
            type = TransactionType.RECEIVE,
            imageUri = null,
            imageFileId = null
        )

        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(tx),
            billImagesByTxId = emptyMap(),
            schemaVersion = 3,
            exportDate = System.currentTimeMillis()
        )
        val jsonBytes = gson.toJson(backup).toByteArray(Charsets.UTF_8)
        val sourceStream = RecordingSourceStreamProvider { ByteArrayInputStream(jsonBytes) }

        val backupManager = BackupManager(
            database = db,
            sourceStreamProvider = sourceStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        val result = backupManager.importData(dummyUri)
        assertTrue(result.success)
        assertEquals(0, result.restoredImageCount)
        assertTrue("No missing images should be reported when transaction has no attachment", result.missingImageTxIds.isEmpty())
    }

    @Test
    fun import_missingPayload_repeatImportRemainsIdempotent() = runBlocking {
        val fakePersonDao = FakePersonDao()
        val fakeTxDao = FakeTransactionDao()
        val db = TestAppDatabase(personDaoImpl = fakePersonDao, transactionDaoImpl = fakeTxDao)

        val p = Person(id = 1L, name = "Heidi", phone = "9777777777", cloudId = "cloud-heidi")
        val tx = Transaction(
            id = "tx-repeat-test",
            personId = 1L,
            amountCents = 250_00L,
            type = TransactionType.GIVE,
            imageUri = "content://images/heidi",
            isDeleted = false
        )

        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(tx),
            billImagesByTxId = emptyMap(),
            schemaVersion = 3,
            exportDate = 1729860000000L
        )
        val jsonBytes = gson.toJson(backup).toByteArray(Charsets.UTF_8)

        val backupManager = BackupManager(
            database = db,
            sourceStreamProvider = RecordingSourceStreamProvider { ByteArrayInputStream(jsonBytes) },
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        // First import
        val result1 = backupManager.importData(dummyUri)
        assertTrue(result1.success)
        assertEquals(listOf("tx-repeat-test"), result1.missingImageTxIds)
        assertEquals(1, fakeTxDao.getAllTransactionsIncludingDeleted().size)

        // Second import (repeated)
        val backupManager2 = BackupManager(
            database = db,
            sourceStreamProvider = RecordingSourceStreamProvider { ByteArrayInputStream(jsonBytes) },
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )
        val result2 = backupManager2.importData(dummyUri)
        assertTrue("Second import must also succeed", result2.success)
        assertEquals("Missing image report must be identical on second import", listOf("tx-repeat-test"), result2.missingImageTxIds)
        assertEquals("No duplicate transactions should be created", 1, fakeTxDao.getAllTransactionsIncludingDeleted().size)
        assertEquals("content://images/heidi", fakeTxDao.getTransactionById("tx-repeat-test")?.imageUri)
    }

    // ── Task 3: Versioned Export Filename Tests ──────────────────────────────

    @Test
    fun generateDefaultExportFilename_matchesExpectedFormatAndLocaleStability() {
        val testEpoch = 1729860000000L // 2024-10-25 deterministic timestamp

        val originalLocale = Locale.getDefault()
        try {
            // Verify format with default locale
            val defaultName = BackupManager.generateDefaultExportFilename(testEpoch)
            assertTrue("Must start with prefix ledger_backup_", defaultName.startsWith("ledger_backup_"))
            assertTrue("Must end with extension .json", defaultName.endsWith(".json"))
            assertTrue(
                "Must match ledger_backup_yyyyMMdd_HHmmss.json regex: $defaultName",
                defaultName.matches(Regex("""^ledger_backup_\d{8}_\d{6}\.json$"""))
            )

            // Test locale stability across non-English locales (e.g., German, Arabic, Hindi)
            val testLocales = listOf(Locale.US, Locale.GERMANY, Locale("ar"), Locale("hi", "IN"))
            for (loc in testLocales) {
                Locale.setDefault(loc)
                val locName = BackupManager.generateDefaultExportFilename(testEpoch)
                assertEquals(
                    "Filename must remain identical regardless of system locale",
                    defaultName,
                    locName
                )
                // Verify all digits are standard ASCII 0-9
                val datePart = locName.removePrefix("ledger_backup_").removeSuffix(".json")
                for (ch in datePart) {
                    if (ch != '_') {
                        assertTrue("Character '$ch' must be standard ASCII digit", ch in '0'..'9')
                    }
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    // ── Deleted Transactions & Missing Attachment Warning Tests ──────────────

    @Test
    fun import_missingPayload_ignoresSoftDeletedTransactions_andPreservesMetadata() = runBlocking {
        val fakePersonDao = FakePersonDao()
        val fakeTxDao = FakeTransactionDao()
        val db = TestAppDatabase(personDaoImpl = fakePersonDao, transactionDaoImpl = fakeTxDao)

        val p = Person(id = 1L, name = "Maya", phone = "9888888888", cloudId = "cloud-maya")
        val txActive = Transaction(
            id = "tx-active-1",
            personId = 1L,
            amountCents = 150_00L,
            type = TransactionType.GIVE,
            imageUri = "content://images/active.jpg",
            imageFileId = "drive-active-1",
            isDeleted = false
        )
        val txDeleted = Transaction(
            id = "tx-deleted-1",
            personId = 1L,
            amountCents = 200_00L,
            type = TransactionType.RECEIVE,
            imageUri = "content://images/deleted.jpg",
            imageFileId = "drive-deleted-1",
            isDeleted = true
        )

        // Neither transaction has payload in billImagesByTxId
        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(txActive, txDeleted),
            billImagesByTxId = emptyMap(),
            schemaVersion = 3,
            exportDate = System.currentTimeMillis()
        )
        val jsonBytes = gson.toJson(backup).toByteArray(Charsets.UTF_8)
        val sourceStream = RecordingSourceStreamProvider { ByteArrayInputStream(jsonBytes) }

        val backupManager = BackupManager(
            database = db,
            sourceStreamProvider = sourceStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        val result = backupManager.importData(dummyUri)

        // 1. Only active transaction should be reported in missing-attachment warning
        assertTrue("Import must succeed", result.success)
        assertEquals("Only active transaction should be reported in missingImageTxIds", listOf("tx-active-1"), result.missingImageTxIds)
        assertFalse("Soft-deleted transaction must not be reported in missingImageTxIds", result.missingImageTxIds.contains("tx-deleted-1"))

        // 2. Both transactions must be stored with intact metadata and correct tombstone status
        val restoredActive = fakeTxDao.getTransactionById("tx-active-1")
        assertNotNull("Active transaction must be present in database", restoredActive)
        assertFalse("Active transaction must not be marked deleted", restoredActive!!.isDeleted)
        assertEquals("content://images/active.jpg", restoredActive.imageUri)
        assertEquals("drive-active-1", restoredActive.imageFileId)

        val restoredDeleted = fakeTxDao.getTransactionById("tx-deleted-1")
        assertNotNull("Soft-deleted transaction must be present in database as tombstone", restoredDeleted)
        assertTrue("Soft-deleted transaction must preserve isDeleted == true", restoredDeleted!!.isDeleted)
        assertEquals("Tombstone imageUri metadata must be preserved intact", "content://images/deleted.jpg", restoredDeleted.imageUri)
        assertEquals("Tombstone imageFileId metadata must be preserved intact", "drive-deleted-1", restoredDeleted.imageFileId)
    }

    @Test
    fun export_missingPayload_ignoresSoftDeletedTransactions_andPreservesMetadata() = runBlocking {
        val fakePersonDao = FakePersonDao()
        val fakeTxDao = FakeTransactionDao()

        val p = Person(id = 1L, name = "Karan", phone = "9999911111", cloudId = "cloud-karan")
        val txActive = Transaction(
            id = "tx-active-exp",
            personId = 1L,
            amountCents = 300_00L,
            type = TransactionType.GIVE,
            imageUri = "content://images/active-exp.jpg",
            imageFileId = "drive-active-exp",
            isDeleted = false
        )
        val txDeleted = Transaction(
            id = "tx-deleted-exp",
            personId = 1L,
            amountCents = 400_00L,
            type = TransactionType.RECEIVE,
            imageUri = "content://images/deleted-exp.jpg",
            imageFileId = "drive-deleted-exp",
            isDeleted = true
        )

        fakePersonDao.insertPerson(p)
        fakeTxDao.insertTransaction(txActive)
        fakeTxDao.insertTransaction(txDeleted)

        val db = TestAppDatabase(personDaoImpl = fakePersonDao, transactionDaoImpl = fakeTxDao)
        val destinationCapture = ByteArrayOutputStream()
        val recordingStream = RecordingStreamProvider { destinationCapture }

        val backupManager = BackupManager(
            database = db,
            destinationStreamProvider = recordingStream,
            customCacheDir = tempDir,
            customFilesDir = filesDir
        )

        val result = backupManager.exportData(dummyUri)

        // 1. Only active transaction should be counted in missingImageTxIds warning list
        assertTrue("Export must succeed", result.success)
        assertEquals("Only active transaction should be reported in missingImageTxIds", listOf("tx-active-exp"), result.missingImageTxIds)
        assertFalse("Soft-deleted transaction must not be reported in missingImageTxIds", result.missingImageTxIds.contains("tx-deleted-exp"))

        // 2. Exported backup data must preserve both transactions and their metadata
        val exportedJson = destinationCapture.toString(Charsets.UTF_8.name())
        val exportedBackup: BackupData = gson.fromJson(exportedJson, BackupData::class.java)

        val exportedActive = exportedBackup.transactions.firstOrNull { it.id == "tx-active-exp" }
        assertNotNull("Active transaction must be present in exported backup", exportedActive)
        assertFalse("Active transaction must remain isDeleted == false", exportedActive!!.isDeleted)
        assertEquals("content://images/active-exp.jpg", exportedActive.imageUri)
        assertEquals("drive-active-exp", exportedActive.imageFileId)

        val exportedDeleted = exportedBackup.transactions.firstOrNull { it.id == "tx-deleted-exp" }
        assertNotNull("Soft-deleted transaction tombstone must be preserved in exported backup", exportedDeleted)
        assertTrue("Tombstone must preserve isDeleted == true", exportedDeleted!!.isDeleted)
        assertEquals("Tombstone imageUri metadata must not be cleared or rewritten", "content://images/deleted-exp.jpg", exportedDeleted.imageUri)
        assertEquals("Tombstone imageFileId metadata must not be cleared or rewritten", "drive-deleted-exp", exportedDeleted.imageFileId)
    }
}

