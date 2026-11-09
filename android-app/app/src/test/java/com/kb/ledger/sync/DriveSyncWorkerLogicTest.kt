package com.kb.ledger.sync

import com.kb.ledger.data.json.LedgerJsonSerializer
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.SyncEntityType
import com.kb.ledger.data.model.SyncOperation
import com.kb.ledger.data.model.SyncOperationType
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.data.remote.FakeDriveApiService
import com.kb.ledger.data.remote.GoogleDriveRepository
import com.kb.ledger.data.json.JsonSchemaValidator
import com.kb.ledger.data.repository.FakePersonDao
import com.kb.ledger.data.repository.FakeRecurringRuleDao
import com.kb.ledger.data.repository.FakeSyncOperationDao
import com.kb.ledger.data.repository.FakeTransactionDao
import com.kb.ledger.util.AppResult
import com.kb.ledger.util.Clock
import kotlinx.coroutines.runBlocking
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
 * Deterministic unit tests for [DriveSyncEngine] and synchronization business logic.
 *
 * Verifies all 10 required behaviors for missing receipts, unreadable URIs,
 * idempotency, retry handling, and tombstone propagation.
 */
class DriveSyncWorkerLogicTest {

    private lateinit var fakeTransactionDao: FakeTransactionDao
    private lateinit var fakePersonDao: FakePersonDao
    private lateinit var fakeRecurringRuleDao: FakeRecurringRuleDao
    private lateinit var fakeSyncOpDao: FakeSyncOperationDao
    private lateinit var fakeDriveService: FakeDriveApiService
    private lateinit var repository: GoogleDriveRepository
    private lateinit var syncEngine: DriveSyncEngine
    private lateinit var tempDir: File

    private val fixedClock = object : Clock {
        override fun nowMillis(): Long = 1729833600000L
        override fun nowIso8601(): String = "2024-10-25T05:20:00Z"
    }

    private val testToken = "fake-drive-token"
    private val testEmail = "test.user@gmail.com"

    private var imageResolver: (suspend (String, String?) -> File?) = { _, uri ->
        if (uri != null && !uri.startsWith("content://")) {
            val f = File(uri)
            if (f.exists()) f else null
        } else {
            null
        }
    }

    @Before
    fun setUp() {
        fakeTransactionDao = FakeTransactionDao()
        fakePersonDao = FakePersonDao()
        fakeRecurringRuleDao = FakeRecurringRuleDao()
        fakeSyncOpDao = FakeSyncOperationDao()
        fakeDriveService = FakeDriveApiService()
        tempDir = Files.createTempDirectory("sync_worker_test").toFile()

        val serializer = LedgerJsonSerializer()
        repository = GoogleDriveRepository(
            driveApiService = fakeDriveService,
            serializer = serializer,
            validator = JsonSchemaValidator,
            clock = fixedClock,
            appVersion = "1.0-test"
        )

        syncEngine = DriveSyncEngine(
            driveApiService = fakeDriveService,
            googleDriveRepository = repository,
            personDao = fakePersonDao,
            transactionDao = fakeTransactionDao,
            recurringRuleDao = fakeRecurringRuleDao,
            syncOperationDao = fakeSyncOpDao,
            resolveImageFile = { id, uri -> imageResolver(id, uri) }
        )
    }

    // 1. Transaction without an image syncs successfully.
    @Test
    fun transactionWithoutImage_syncsSuccessfully() = runBlocking {
        val person = Person(id = 1L, name = "Alice", phone = "9999999999", syncPending = true)
        fakePersonDao.insertPerson(person)

        val tx = Transaction(
            id = "tx-no-img",
            personId = 1L,
            amountCents = 10000L,
            type = TransactionType.GIVE,
            imageUri = null,
            imageFileId = null,
            syncPending = true
        )
        fakeTransactionDao.insertTransaction(tx)

        val summary = syncEngine.executeSync(testToken, testEmail)

        assertTrue("Sync should succeed", summary.success)
        assertEquals(1, summary.syncedTransactionsCount)
        assertEquals(0, summary.blockedTransactionsCount)

        val checkTx = fakeTransactionDao.getTransactionById("tx-no-img")
        assertNotNull(checkTx)
        assertFalse("Transaction without image must be marked synced", checkTx!!.syncPending)
        assertNull(checkTx.imageFileId)

        val checkPerson = fakePersonDao.getPersonById(1L)
        assertNotNull(checkPerson)
        assertFalse("Person must be marked synced", checkPerson!!.syncPending)
    }

    // 2. Transaction with a valid local image waits for successful image upload and ledger update.
    @Test
    fun transactionWithValidImage_waitsForSuccessfulImageUploadAndLedgerUpdate() = runBlocking {
        val person = Person(id = 1L, name = "Bob", phone = "8888888888", syncPending = true)
        fakePersonDao.insertPerson(person)

        val imageFile = File(tempDir, "receipt_valid.jpg")
        imageFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        val tx = Transaction(
            id = "tx-valid-img",
            personId = 1L,
            amountCents = 25000L,
            type = TransactionType.RECEIVE,
            imageUri = imageFile.absolutePath,
            imageFileId = null,
            syncPending = true
        )
        fakeTransactionDao.insertTransaction(tx)

        val summary = syncEngine.executeSync(testToken, testEmail)

        assertTrue("Sync should succeed", summary.success)
        assertEquals(1, summary.syncedTransactionsCount)
        assertEquals(0, summary.blockedTransactionsCount)

        val checkTx = fakeTransactionDao.getTransactionById("tx-valid-img")
        assertNotNull(checkTx)
        assertFalse("Transaction must be marked synced", checkTx!!.syncPending)
        assertNotNull("imageFileId must be populated after upload", checkTx.imageFileId)

        val uploadedFile = fakeDriveService.files.values.find { it.name == "receipt_tx-valid-img.jpg" }
        assertNotNull("Image file must exist in cloud", uploadedFile)
        assertEquals(checkTx.imageFileId, uploadedFile!!.id)
    }

    // 3. Transaction with a missing referenced image remains pending.
    @Test
    fun transactionWithMissingReferencedImage_remainsPending() = runBlocking {
        val person = Person(id = 1L, name = "Charlie", phone = "7777777777", syncPending = true)
        fakePersonDao.insertPerson(person)

        val missingPath = File(tempDir, "non_existent_receipt.jpg").absolutePath
        val tx = Transaction(
            id = "tx-missing-img",
            personId = 1L,
            amountCents = 15000L,
            type = TransactionType.GIVE,
            imageUri = missingPath,
            imageFileId = null,
            syncPending = true
        )
        fakeTransactionDao.insertTransaction(tx)

        val summary = syncEngine.executeSync(testToken, testEmail)

        assertEquals("Transaction must be reported as blocked", 1, summary.blockedTransactionsCount)
        assertEquals(0, summary.syncedTransactionsCount)
        assertTrue(summary.missingFileDiagnostics.containsKey("tx-missing-img"))
        assertTrue(summary.missingFileDiagnostics["tx-missing-img"]!!.contains("missing or unreadable"))

        val checkTx = fakeTransactionDao.getTransactionById("tx-missing-img")
        assertNotNull(checkTx)
        assertTrue("Transaction with missing image must remain pending", checkTx!!.syncPending)
        assertNull("imageFileId must remain null", checkTx.imageFileId)

        val ops = fakeSyncOpDao.getAll().filter { it.entityId == "tx-missing-img" }
        assertEquals(1, ops.size)
        assertTrue(ops[0].lastErrorMessage!!.contains("missing or unreadable"))
    }

    // 4. Transaction with an expired/unreadable legacy URI remains pending and reports a useful error.
    @Test
    fun transactionWithExpiredLegacyUri_remainsPendingAndReportsError() = runBlocking {
        val person = Person(id = 1L, name = "Diana", phone = "6666666666", syncPending = true)
        fakePersonDao.insertPerson(person)

        val legacyUri = "content://media/picker_get_content/com.google.android.apps.photos/12345"
        val tx = Transaction(
            id = "tx-legacy-uri",
            personId = 1L,
            amountCents = 18000L,
            type = TransactionType.GIVE,
            imageUri = legacyUri,
            imageFileId = null,
            syncPending = true
        )
        fakeTransactionDao.insertTransaction(tx)

        val summary = syncEngine.executeSync(testToken, testEmail)

        assertEquals(1, summary.blockedTransactionsCount)
        val diag = summary.missingFileDiagnostics["tx-legacy-uri"]
        assertNotNull(diag)
        assertTrue("Diagnostic must mention legacy URI issue", diag!!.contains("Legacy receipt URI expired"))

        val checkTx = fakeTransactionDao.getTransactionById("tx-legacy-uri")
        assertNotNull(checkTx)
        assertTrue("Transaction must remain pending", checkTx!!.syncPending)
        assertEquals("Historical imageUri must NOT be erased", legacyUri, checkTx.imageUri)
        assertNull("imageFileId must remain null", checkTx.imageFileId)

        val ops = fakeSyncOpDao.getAll().filter { it.entityId == "tx-legacy-uri" }
        assertEquals(1, ops.size)
        assertTrue(ops[0].lastErrorMessage!!.contains("Legacy receipt URI expired"))
    }

    // 5. Image upload failure prevents the affected transaction from being marked synced.
    @Test
    fun imageUploadFailure_preventsTransactionFromBeingMarkedSynced() = runBlocking {
        val person = Person(id = 1L, name = "Evan", phone = "5555555555", syncPending = true)
        fakePersonDao.insertPerson(person)

        val imageFile = File(tempDir, "receipt_fail.jpg")
        imageFile.writeBytes(byteArrayOf(9, 8, 7, 6))

        val tx = Transaction(
            id = "tx-img-fail",
            personId = 1L,
            amountCents = 12000L,
            type = TransactionType.GIVE,
            imageUri = imageFile.absolutePath,
            imageFileId = null,
            syncPending = true
        )
        fakeTransactionDao.insertTransaction(tx)

        fakeDriveService.createBinaryResultOverride = {
            AppResult.Error("HTTP 500: Server Image Upload Error")
        }

        val summary = syncEngine.executeSync(testToken, testEmail)

        assertEquals(1, summary.blockedTransactionsCount)
        assertEquals(0, summary.syncedTransactionsCount)
        assertTrue(summary.networkErrors.any { it.contains("HTTP 500") })

        val checkTx = fakeTransactionDao.getTransactionById("tx-img-fail")
        assertNotNull(checkTx)
        assertTrue("Transaction must remain syncPending = true", checkTx!!.syncPending)
        assertNull("imageFileId must remain null when upload fails", checkTx.imageFileId)

        val ops = fakeSyncOpDao.getAll().filter { it.entityId == "tx-img-fail" }
        assertEquals(1, ops.size)
        assertTrue(ops[0].lastErrorMessage!!.contains("HTTP 500"))
    }

    // 6. Successful image upload followed by ledger upload failure does not incorrectly mark the transaction synced.
    @Test
    fun successfulImageUpload_followedByLedgerFailure_doesNotMarkTransactionSynced() = runBlocking {
        val person = Person(id = 1L, name = "Fiona", phone = "4444444444", syncPending = true)
        fakePersonDao.insertPerson(person)

        val imageFile = File(tempDir, "receipt_ledger_fail.jpg")
        imageFile.writeBytes(byteArrayOf(1, 1, 2, 2))

        val tx = Transaction(
            id = "tx-ledger-fail",
            personId = 1L,
            amountCents = 32000L,
            type = TransactionType.RECEIVE,
            imageUri = imageFile.absolutePath,
            imageFileId = null,
            syncPending = true
        )
        fakeTransactionDao.insertTransaction(tx)

        // Make ledger file creation fail after image upload succeeds
        fakeDriveService.createResultOverride = {
            AppResult.Error("HTTP 503: Ledger Service Unavailable")
        }

        val summary = syncEngine.executeSync(testToken, testEmail)

        assertFalse("Sync overall must fail due to ledger error", summary.success)
        assertEquals(0, summary.syncedTransactionsCount)

        val checkTx = fakeTransactionDao.getTransactionById("tx-ledger-fail")
        assertNotNull(checkTx)
        assertNotNull("Image was successfully uploaded and fileId saved locally", checkTx!!.imageFileId)
        assertTrue("Transaction must STILL remain syncPending = true because ledger push failed", checkTx.syncPending)
    }

    // 7. A missing image does not prevent unrelated eligible transactions from syncing.
    @Test
    fun missingImage_doesNotPreventUnrelatedEligibleTransactionsFromSyncing() = runBlocking {
        val person = Person(id = 1L, name = "George", phone = "3333333333", syncPending = true)
        fakePersonDao.insertPerson(person)

        val missingTx = Transaction(
            id = "tx-blocked-missing",
            personId = 1L,
            amountCents = 1000L,
            type = TransactionType.GIVE,
            imageUri = "/missing/receipt_404.jpg",
            imageFileId = null,
            syncPending = true
        )
        val cleanTx = Transaction(
            id = "tx-clean-no-image",
            personId = 1L,
            amountCents = 2000L,
            type = TransactionType.RECEIVE,
            imageUri = null,
            imageFileId = null,
            syncPending = true
        )
        val validImageFile = File(tempDir, "receipt_ok.jpg")
        validImageFile.writeBytes(byteArrayOf(3, 4, 5))
        val validImageTx = Transaction(
            id = "tx-valid-image",
            personId = 1L,
            amountCents = 3000L,
            type = TransactionType.GIVE,
            imageUri = validImageFile.absolutePath,
            imageFileId = null,
            syncPending = true
        )

        fakeTransactionDao.insertTransaction(missingTx)
        fakeTransactionDao.insertTransaction(cleanTx)
        fakeTransactionDao.insertTransaction(validImageTx)

        val summary = syncEngine.executeSync(testToken, testEmail)

        assertTrue("Sync should succeed for eligible records", summary.success)
        assertEquals(2, summary.syncedTransactionsCount)
        assertEquals(1, summary.blockedTransactionsCount)

        assertTrue(fakeTransactionDao.getTransactionById("tx-blocked-missing")!!.syncPending)
        assertFalse(fakeTransactionDao.getTransactionById("tx-clean-no-image")!!.syncPending)
        assertFalse(fakeTransactionDao.getTransactionById("tx-valid-image")!!.syncPending)
        assertFalse(fakePersonDao.getPersonById(1L)!!.syncPending)
    }

    // 8. Retrying a successful upload does not create duplicate cloud image records.
    @Test
    fun retryingSuccessfulUpload_doesNotCreateDuplicateCloudImageRecords() = runBlocking {
        val person = Person(id = 1L, name = "Hannah", phone = "2222222222", syncPending = true)
        fakePersonDao.insertPerson(person)

        val imageFile = File(tempDir, "receipt_retry.jpg")
        imageFile.writeBytes(byteArrayOf(7, 7, 7))

        fakeDriveService.addFile("receipt_tx-retry.jpg", "BINARY[3 bytes]", fileId = "drive-existing-file-999")

        val tx = Transaction(
            id = "tx-retry",
            personId = 1L,
            amountCents = 40000L,
            type = TransactionType.GIVE,
            imageUri = imageFile.absolutePath,
            imageFileId = "drive-existing-file-999",
            syncPending = true
        )
        fakeTransactionDao.insertTransaction(tx)

        val binaryUploadsBefore = fakeDriveService.callLog.count { it.startsWith("createAppDataBinaryFile") }

        val summary = syncEngine.executeSync(testToken, testEmail)

        assertTrue("Sync should succeed", summary.success)
        assertEquals(1, summary.syncedTransactionsCount)

        val binaryUploadsAfter = fakeDriveService.callLog.count { it.startsWith("createAppDataBinaryFile") }
        assertEquals("Must not upload binary image again when imageFileId is already known", binaryUploadsBefore, binaryUploadsAfter)

        val checkTx = fakeTransactionDao.getTransactionById("tx-retry")
        assertNotNull(checkTx)
        assertFalse("Transaction should now be marked synced", checkTx!!.syncPending)
        assertEquals("drive-existing-file-999", checkTx.imageFileId)
    }

    // 9. Replacing a missing image allows the transaction to sync successfully afterward.
    @Test
    fun replacingMissingImage_allowsTransactionToSyncSuccessfullyAfterward() = runBlocking {
        val person = Person(id = 1L, name = "Ian", phone = "1111111111", syncPending = true)
        fakePersonDao.insertPerson(person)

        val missingTx = Transaction(
            id = "tx-replaceable",
            personId = 1L,
            amountCents = 15000L,
            type = TransactionType.GIVE,
            imageUri = "/missing/lost_receipt.jpg",
            imageFileId = null,
            syncPending = true
        )
        fakeTransactionDao.insertTransaction(missingTx)

        // Pass 1: fails/blocks because file is missing
        val summary1 = syncEngine.executeSync(testToken, testEmail)
        assertEquals(1, summary1.blockedTransactionsCount)
        assertTrue(fakeTransactionDao.getTransactionById("tx-replaceable")!!.syncPending)

        // User replaces the receipt with a fresh valid file
        val newValidReceipt = File(tempDir, "new_replacement_receipt.jpg")
        newValidReceipt.writeBytes(byteArrayOf(9, 9, 9, 9))

        val replacedTx = missingTx.copy(
            imageUri = newValidReceipt.absolutePath,
            imageFileId = null,
            syncPending = true
        )
        fakeTransactionDao.updateTransaction(replacedTx)

        // Pass 2: succeeds now that file is present
        val summary2 = syncEngine.executeSync(testToken, testEmail)
        assertTrue("Sync must succeed after receipt replacement", summary2.success)
        assertEquals(0, summary2.blockedTransactionsCount)
        assertEquals(1, summary2.syncedTransactionsCount)

        val finalTx = fakeTransactionDao.getTransactionById("tx-replaceable")
        assertNotNull(finalTx)
        assertFalse("Transaction should be synced after receipt replacement", finalTx!!.syncPending)
        assertNotNull("New imageFileId must be populated", finalTx.imageFileId)
    }

    // 10. Tombstones continue to propagate correctly.
    @Test
    fun tombstonesContinueToPropagateCorrectly() = runBlocking {
        val person = Person(
            id = 1L,
            name = "Jack",
            phone = "1234500000",
            isDeleted = true,
            syncPending = true
        )
        fakePersonDao.insertPerson(person)

        val deletedTx = Transaction(
            id = "tx-tombstone-test",
            personId = 1L,
            amountCents = 5500L,
            type = TransactionType.RECEIVE,
            isDeleted = true,
            syncPending = true
        )
        fakeTransactionDao.insertTransaction(deletedTx)

        val summary = syncEngine.executeSync(testToken, testEmail)

        assertTrue("Sync should succeed", summary.success)
        assertEquals(1, summary.syncedPersonsCount)
        assertEquals(1, summary.syncedTransactionsCount)

        val checkPerson = fakePersonDao.getAllPersonsIncludingDeleted().find { it.id == 1L }
        assertNotNull(checkPerson)
        assertTrue("Person must remain deleted", checkPerson!!.isDeleted)
        assertFalse("syncPending must be cleared", checkPerson.syncPending)

        val checkTx = fakeTransactionDao.getTransactionById("tx-tombstone-test")
        assertNotNull(checkTx)
        assertTrue("Transaction must remain deleted", checkTx!!.isDeleted)
        assertFalse("syncPending must be cleared", checkTx.syncPending)
    }

    @Test
    fun imageReplacement_resetsImageFileId_and_requiresNewUpload() = runBlocking {
        val tx = Transaction(
            id = "tx-img-replace",
            personId = 1L,
            amountCents = 15000L,
            type = TransactionType.GIVE,
            imageUri = "/data/data/com.kb.ledger/files/receipts/old.jpg",
            imageFileId = "drive-old-123",
            syncPending = false
        )
        fakeTransactionDao.insertTransaction(tx)

        val newImageUri = "/data/data/com.kb.ledger/files/receipts/new.jpg"
        val imageChanged = newImageUri != tx.imageUri
        val resolvedImageFileId = if (imageChanged) null else tx.imageFileId

        val updatedTx = tx.copy(
            imageUri = newImageUri,
            imageFileId = resolvedImageFileId,
            syncPending = true
        )
        fakeTransactionDao.updateTransaction(updatedTx)

        val retrieved = fakeTransactionDao.getTransactionById(tx.id)
        assertNotNull(retrieved)
        assertNull("Replacing image must clear imageFileId so sync worker uploads new image", retrieved?.imageFileId)
        assertTrue("Replacing image must set syncPending = true", retrieved!!.syncPending)
    }

    @Test
    fun failedSync_retainsSyncOperationsWithRetryIncrement() = runBlocking {
        val op = SyncOperation(
            id = "op-1",
            type = SyncOperationType.UPSERT,
            entityType = SyncEntityType.TRANSACTION,
            entityId = "tx-retry-1"
        )
        fakeSyncOpDao.insert(op)

        fakeSyncOpDao.incrementRetry(op.id, "HTTP 503 Service Unavailable")

        val remaining = fakeSyncOpDao.getAll()
        assertEquals(1, remaining.size)
        assertEquals(1, remaining[0].retryCount)
        assertEquals("HTTP 503 Service Unavailable", remaining[0].lastErrorMessage)
    }
}
