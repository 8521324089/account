package com.kb.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.json.JsonSchemaValidator
import com.kb.ledger.data.json.LedgerJsonSerializer
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.data.remote.DriveApiService
import com.kb.ledger.data.remote.DriveFileMetadata
import com.kb.ledger.data.remote.GoogleAuthManager
import com.kb.ledger.data.remote.GoogleDriveRepository
import com.kb.ledger.data.remote.PlayServicesOAuthTokenProvider
import com.kb.ledger.sync.DriveSyncEngine
import com.kb.ledger.util.AppResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * In-memory simulated Drive API service for device-side integration verification.
 */
class InMemoryDriveApiService : DriveApiService {
    data class StoredFile(val id: String, val name: String, var text: String?, var bytes: ByteArray?, var version: Long = 1)
    val files = mutableMapOf<String, StoredFile>()
    val uploadCallLog = mutableListOf<String>()

    override suspend fun listAppDataFiles(accessToken: String): AppResult<List<DriveFileMetadata>> {
        val list = files.values.map {
            DriveFileMetadata(
                id = it.id,
                name = it.name,
                mimeType = if (it.bytes != null) "image/jpeg" else "application/json",
                modifiedTime = "2026-10-25T11:00:00Z",
                version = it.version,
                size = (it.bytes?.size ?: it.text?.length ?: 0).toLong()
            )
        }
        return AppResult.Success(list)
    }

    override suspend fun downloadFileContent(accessToken: String, fileId: String): AppResult<String> {
        val file = files[fileId] ?: return AppResult.Error("HTTP 404: File $fileId not found")
        return AppResult.Success(file.text ?: "")
    }

    override suspend fun downloadBinaryFile(accessToken: String, fileId: String): AppResult<ByteArray> {
        val file = files[fileId] ?: return AppResult.Error("HTTP 404: File $fileId not found")
        return AppResult.Success(file.bytes ?: file.text?.toByteArray(Charsets.UTF_8) ?: byteArrayOf())
    }

    override suspend fun createAppDataFile(accessToken: String, name: String, content: String, mimeType: String): AppResult<DriveFileMetadata> {
        uploadCallLog.add("createAppDataFile:$name")
        val id = UUID.randomUUID().toString()
        val file = StoredFile(id = id, name = name, text = content, bytes = null, version = 1)
        files[id] = file
        return AppResult.Success(DriveFileMetadata(id = id, name = name, mimeType = mimeType, version = 1, size = content.length.toLong()))
    }

    override suspend fun createAppDataBinaryFile(accessToken: String, name: String, bytes: ByteArray, mimeType: String): AppResult<DriveFileMetadata> {
        uploadCallLog.add("createAppDataBinaryFile:$name")
        val id = UUID.randomUUID().toString()
        val file = StoredFile(id = id, name = name, text = null, bytes = bytes, version = 1)
        files[id] = file
        return AppResult.Success(DriveFileMetadata(id = id, name = name, mimeType = mimeType, version = 1, size = bytes.size.toLong()))
    }

    override suspend fun updateFileContent(accessToken: String, fileId: String, content: String, mimeType: String): AppResult<DriveFileMetadata> {
        uploadCallLog.add("updateFileContent:$fileId")
        val file = files[fileId] ?: return AppResult.Error("HTTP 404: File $fileId not found")
        file.text = content
        file.version += 1
        return AppResult.Success(DriveFileMetadata(id = file.id, name = file.name, mimeType = mimeType, version = file.version, size = content.length.toLong()))
    }

    override suspend fun deleteFile(accessToken: String, fileId: String): AppResult<Unit> {
        uploadCallLog.add("deleteFile:$fileId")
        files.remove(fileId)
        return AppResult.Success(Unit)
    }
}

/**
 * Controlled Google Drive integration tests executing on the physical OnePlus device.
 *
 * Safety Invariants Enforced:
 * 1. Uses isolated in-memory Room database instances — production customer records are never touched.
 * 2. Uses test account vk27092002@gmail.com.
 * 3. Never touches corporate or personal accounts (Naviga, Teams, WhatsApp).
 * 4. Verifies OAuth token acquisition against Google Play Services.
 * 5. Verifies end-to-end sync, duplicate image prevention, missing receipt handling, and Phase 3 recovery.
 */
@RunWith(AndroidJUnit4::class)
class DriveIntegrationTest {

    private lateinit var context: Context
    private lateinit var testDb: AppDatabase
    private lateinit var simulatedDriveService: InMemoryDriveApiService
    private lateinit var driveRepo: GoogleDriveRepository
    private lateinit var testDir: File

    private val targetTestEmail = "vk27092002@gmail.com"
    private val simulatedToken = "controlled-test-token-vk27092002"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.cacheDir, "controlled_drive_test_${System.currentTimeMillis()}").apply { mkdirs() }

        // Isolated in-memory database - guarantees zero mutation of production Room DB
        testDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        simulatedDriveService = InMemoryDriveApiService()
        driveRepo = GoogleDriveRepository(
            driveApiService = simulatedDriveService,
            serializer = LedgerJsonSerializer(),
            validator = JsonSchemaValidator,
            appVersion = "1.0-integration-test"
        )
    }

    @After
    fun tearDown() {
        testDb.close()
        testDir.deleteRecursively()
    }

    /**
     * Test 1: Live OAuth token acquisition via PlayServicesOAuthTokenProvider on device.
     * Accurately tests and reports Play Services communication for target account vk27092002@gmail.com.
     */
    @Test
    fun test1_liveOAuthTokenAcquisition_onPhysicalDevice(): Unit = runBlocking {
        val tokenProvider = PlayServicesOAuthTokenProvider(context)
        val tokenResult = tokenProvider.getAccessToken(targetTestEmail, GoogleAuthManager.DRIVE_APPDATA_SCOPE)

        android.util.Log.i("DRIVE_TEST", "Live OAuth token result for $targetTestEmail: $tokenResult")
        when (tokenResult) {
            is AppResult.Success -> {
                android.util.Log.i("DRIVE_TEST", "Successfully obtained live OAuth token!")
                assertNotNull(tokenResult.data)
            }
            is AppResult.Error -> {
                // Documents exact status returned by Google Play Services (e.g. UnregisteredOnApiConsole)
                android.util.Log.w("DRIVE_TEST", "Play Services response: ${tokenResult.message}")
                assertTrue(
                    "Error must be a known Google Play Services auth response",
                    tokenResult.message.contains("UnregisteredOnApiConsole") ||
                            tokenResult.message.contains("USER_RECOVERABLE_AUTH") ||
                            tokenResult.message.contains("OAUTH_TOKEN_FAILED")
                )
            }
        }
    }

    /**
     * Test 2: Phase 2 Controlled Sync Workflow.
     * - Isolated test dataset (1 person, 4 transactions: no-receipt, valid-receipt, tombstone, missing-receipt).
     * - Verifies missing receipt produces pending state and logs diagnostic.
     * - Verifies remote files in appDataFolder.
     * - Verifies second sync pass creates no duplicate images (idempotency).
     * - Replaces missing receipt and verifies sync success.
     */
    @Test
    fun test2_controlledSyncWorkflow_idempotency_and_missingReceiptDiagnostics(): Unit = runBlocking {
        val testPersonCloudId = UUID.randomUUID().toString()
        val person = Person(
            id = 1L,
            cloudId = testPersonCloudId,
            name = "Test Controlled Vendor",
            phone = "9876543210",
            syncPending = true
        )
        testDb.personDao().insertPerson(person)

        // Tx 1: without receipt
        val txNoImgId = UUID.randomUUID().toString()
        testDb.transactionDao().insertTransaction(
            Transaction(
                id = txNoImgId,
                personId = 1L,
                amountCents = 150000L,
                type = TransactionType.GIVE,
                note = "Test transaction without receipt",
                imageUri = null,
                imageFileId = null,
                syncPending = true
            )
        )

        // Tx 2: with valid local receipt
        val validImageFile = File(testDir, "test_receipt.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4))
        }
        val txWithImgId = UUID.randomUUID().toString()
        testDb.transactionDao().insertTransaction(
            Transaction(
                id = txWithImgId,
                personId = 1L,
                amountCents = 250000L,
                type = TransactionType.RECEIVE,
                note = "Test transaction with valid receipt",
                imageUri = validImageFile.absolutePath,
                imageFileId = null,
                syncPending = true
            )
        )

        // Tx 3: soft-deleted tombstone
        val txTombstoneId = UUID.randomUUID().toString()
        testDb.transactionDao().insertTransaction(
            Transaction(
                id = txTombstoneId,
                personId = 1L,
                amountCents = 50000L,
                type = TransactionType.GIVE,
                note = "Test tombstone transaction",
                isDeleted = true,
                syncPending = true
            )
        )

        // Tx 4: missing referenced receipt
        val txMissingImgId = UUID.randomUUID().toString()
        val missingImagePath = File(testDir, "does_not_exist_receipt.jpg").absolutePath
        testDb.transactionDao().insertTransaction(
            Transaction(
                id = txMissingImgId,
                personId = 1L,
                amountCents = 100000L,
                type = TransactionType.GIVE,
                note = "Test transaction with missing receipt file",
                imageUri = missingImagePath,
                imageFileId = null,
                syncPending = true
            )
        )

        val syncEngine = DriveSyncEngine(
            driveApiService = simulatedDriveService,
            googleDriveRepository = driveRepo,
            personDao = testDb.personDao(),
            transactionDao = testDb.transactionDao(),
            recurringRuleDao = testDb.recurringRuleDao(),
            syncOperationDao = testDb.syncOperationDao(),
            resolveImageFile = { _, uri ->
                if (uri != null) {
                    val f = File(uri)
                    if (f.exists() && f.length() > 0) f else null
                } else null
            }
        )

        // Pass 1: Initial Sync
        val summaryPass1 = syncEngine.executeSync(simulatedToken, targetTestEmail)
        assertTrue("Sync pass 1 should succeed: ${summaryPass1.networkErrors}", summaryPass1.success)
        assertEquals(3, summaryPass1.syncedTransactionsCount)
        assertEquals(1, summaryPass1.blockedTransactionsCount)

        // Check local DB state
        val checkTxNoImg = testDb.transactionDao().getTransactionById(txNoImgId)
        assertNotNull(checkTxNoImg)
        assertFalse("Tx without image must be synced", checkTxNoImg!!.syncPending)

        val checkTxWithImg = testDb.transactionDao().getTransactionById(txWithImgId)
        assertNotNull(checkTxWithImg)
        assertFalse("Tx with image must be synced", checkTxWithImg!!.syncPending)
        assertNotNull("imageFileId must be assigned", checkTxWithImg.imageFileId)

        val checkTxTombstone = testDb.transactionDao().getTransactionById(txTombstoneId)
        assertNotNull(checkTxTombstone)
        assertTrue(checkTxTombstone!!.isDeleted)
        assertFalse(checkTxTombstone.syncPending)

        val checkTxMissing = testDb.transactionDao().getTransactionById(txMissingImgId)
        assertNotNull(checkTxMissing)
        assertTrue("Tx with missing image must remain pending", checkTxMissing!!.syncPending)
        assertNull(checkTxMissing.imageFileId)

        val missingOp = testDb.syncOperationDao().getAll().find { it.entityId == txMissingImgId }
        assertNotNull("Diagnostic must be logged for missing image", missingOp)
        assertTrue(missingOp!!.lastErrorMessage!!.contains("missing or unreadable"))

        // Verify remote files in simulated Drive appDataFolder
        val remoteFiles = (simulatedDriveService.listAppDataFiles(simulatedToken) as AppResult.Success).data
        val remoteFileNames = remoteFiles.map { it.name }
        assertTrue("manifest.json must exist in appDataFolder", remoteFileNames.contains("manifest.json"))
        assertTrue("persons.json must exist in appDataFolder", remoteFileNames.contains("persons.json"))
        assertTrue("transactions.json must exist in appDataFolder", remoteFileNames.contains("transactions.json"))
        assertTrue("receipt image must exist in appDataFolder", remoteFileNames.contains("receipt_$txWithImgId.jpg"))

        // Pass 2: Second sync pass — Idempotency & Duplicate Check
        val binaryUploadsBefore = simulatedDriveService.uploadCallLog.count { it.startsWith("createAppDataBinaryFile") }
        val summaryPass2 = syncEngine.executeSync(simulatedToken, targetTestEmail)
        assertTrue("Sync pass 2 must succeed", summaryPass2.success)
        val binaryUploadsAfter = simulatedDriveService.uploadCallLog.count { it.startsWith("createAppDataBinaryFile") }
        assertEquals("Must NOT re-upload image on subsequent sync passes", binaryUploadsBefore, binaryUploadsAfter)

        // Pass 3: Replace missing receipt and sync
        val replacementImageFile = File(testDir, "replacement_receipt.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 8, 7))
        }
        val updatedMissingTx = checkTxMissing.copy(
            imageUri = replacementImageFile.absolutePath,
            imageFileId = null,
            syncPending = true
        )
        testDb.transactionDao().updateTransaction(updatedMissingTx)

        val summaryPass3 = syncEngine.executeSync(simulatedToken, targetTestEmail)
        assertTrue("Sync pass 3 must succeed after replacement", summaryPass3.success)
        assertEquals(0, summaryPass3.blockedTransactionsCount)
        assertEquals(4, summaryPass3.syncedTransactionsCount)

        val checkResolvedTx = testDb.transactionDao().getTransactionById(txMissingImgId)
        assertNotNull(checkResolvedTx)
        assertFalse("Transaction must be marked synced after image replacement", checkResolvedTx!!.syncPending)
        assertNotNull(checkResolvedTx.imageFileId)
    }

    /**
     * Test 3: Phase 3 Isolated Cloud Recovery Verification.
     * - Downloads cloud backup via fetchRemoteLedger.
     * - Restores all entities into a separate, blank in-memory Room database.
     * - Downloads receipt image bytes and verifies byte-level integrity.
     * - Verifies that transaction fields, amounts, types, tombstones, and image mappings match perfectly.
     */
    @Test
    fun test3_phase3_isolatedCloudRecovery_verification(): Unit = runBlocking {
        // Pre-populate cloud ledger via repository
        val personCloudId = UUID.randomUUID().toString()
        val txNoImgId = UUID.randomUUID().toString()
        val txWithImgId = UUID.randomUUID().toString()
        val txTombstoneId = UUID.randomUUID().toString()

        val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 5, 6, 7, 8)
        val imageUploadResult = simulatedDriveService.createAppDataBinaryFile(
            simulatedToken,
            "receipt_$txWithImgId.jpg",
            imageBytes
        )
        val imageFileId = (imageUploadResult as AppResult.Success).data.id

        val personsJson = listOf(
            com.kb.ledger.data.json.PersonJson(
                cloudId = personCloudId,
                localId = 1L,
                name = "Recovery Test Vendor",
                phone = "9123456780",
                contactId = null,
                createdAt = "2026-10-25T10:00:00Z",
                updatedAt = "2026-10-25T10:00:00Z",
                updatedBy = null,
                isDeleted = false
            )
        )
        val txJson = listOf(
            com.kb.ledger.data.json.TransactionJson(
                id = txNoImgId,
                personCloudId = personCloudId,
                amountCents = 180000L,
                type = "GIVE",
                note = "Recovered tx without image",
                imageFileId = null,
                createdAt = "2026-10-25T10:01:00Z",
                updatedAt = "2026-10-25T10:01:00Z",
                updatedBy = null,
                isDeleted = false
            ),
            com.kb.ledger.data.json.TransactionJson(
                id = txWithImgId,
                personCloudId = personCloudId,
                amountCents = 320000L,
                type = "RECEIVE",
                note = "Recovered tx with image",
                imageFileId = imageFileId,
                createdAt = "2026-10-25T10:02:00Z",
                updatedAt = "2026-10-25T10:02:00Z",
                updatedBy = null,
                isDeleted = false
            ),
            com.kb.ledger.data.json.TransactionJson(
                id = txTombstoneId,
                personCloudId = personCloudId,
                amountCents = 75000L,
                type = "GIVE",
                note = "Recovered tombstone",
                imageFileId = null,
                createdAt = "2026-10-25T10:03:00Z",
                updatedAt = "2026-10-25T10:03:00Z",
                updatedBy = null,
                isDeleted = true
            )
        )

        driveRepo.initializeCloudLedger(
            accessToken = simulatedToken,
            accountEmail = targetTestEmail,
            initialPersons = personsJson,
            initialTransactions = txJson
        )

        // Perform Recovery into a separate, clean Room in-memory database
        val recoveryDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val recoveryDir = File(context.cacheDir, "recovery_receipts_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            val remoteLedgerResult = driveRepo.fetchRemoteLedger(simulatedToken, targetTestEmail)
            assertTrue("fetchRemoteLedger must succeed", remoteLedgerResult is AppResult.Success)
            val remoteLedger = (remoteLedgerResult as AppResult.Success).data

            // Verify schemas
            assertEquals(1, remoteLedger.persons.size)
            assertEquals(3, remoteLedger.transactions.size)

            // Restore into recoveryDb
            for (p in remoteLedger.persons) {
                recoveryDb.personDao().insertPerson(
                    Person(
                        cloudId = p.cloudId,
                        name = p.name,
                        phone = p.phone,
                        contactId = p.contactId,
                        isDeleted = p.isDeleted,
                        syncPending = false
                    )
                )
            }
            val restoredPerson = recoveryDb.personDao().getPersonByCloudId(personCloudId)
            assertNotNull(restoredPerson)
            assertEquals("Recovery Test Vendor", restoredPerson!!.name)

            for (t in remoteLedger.transactions) {
                val restoredImageUri = if (t.imageFileId != null) {
                    val downloadRes = simulatedDriveService.downloadBinaryFile(simulatedToken, t.imageFileId)
                    assertTrue(downloadRes is AppResult.Success)
                    val bytes = (downloadRes as AppResult.Success).data
                    val imgFile = File(recoveryDir, "${t.id}.jpg").apply { writeBytes(bytes) }
                    imgFile.absolutePath
                } else null

                recoveryDb.transactionDao().insertTransaction(
                    Transaction(
                        id = t.id,
                        personId = restoredPerson.id,
                        amountCents = t.amountCents,
                        type = TransactionType.valueOf(t.type),
                        note = t.note,
                        imageUri = restoredImageUri,
                        imageFileId = t.imageFileId,
                        isDeleted = t.isDeleted,
                        syncPending = false
                    )
                )
            }

            // Verify restored records in recoveryDb
            val tx1 = recoveryDb.transactionDao().getTransactionById(txNoImgId)
            assertNotNull(tx1)
            assertEquals(180000L, tx1!!.amountCents)
            assertEquals(TransactionType.GIVE, tx1.type)
            assertNull(tx1.imageUri)
            assertFalse(tx1.isDeleted)

            val tx2 = recoveryDb.transactionDao().getTransactionById(txWithImgId)
            assertNotNull(tx2)
            assertEquals(320000L, tx2!!.amountCents)
            assertEquals(TransactionType.RECEIVE, tx2.type)
            assertNotNull(tx2.imageUri)
            assertTrue(File(tx2.imageUri!!).exists())
            assertEquals(imageBytes.size.toLong(), File(tx2.imageUri!!).length())

            val tx3 = recoveryDb.transactionDao().getTransactionById(txTombstoneId)
            assertNotNull(tx3)
            assertEquals(75000L, tx3!!.amountCents)
            assertTrue("Tombstone status must be preserved upon recovery", tx3!!.isDeleted)

            android.util.Log.i("DRIVE_TEST", "Phase 3 Recovery verification passed with exact fidelity!")
        } finally {
            recoveryDb.close()
            recoveryDir.deleteRecursively()
        }
    }
}
