package com.kb.ledger.data.remote

import com.kb.ledger.data.json.JsonSchemaValidator
import com.kb.ledger.data.json.LedgerJsonSerializer
import com.kb.ledger.data.json.ManifestJson
import com.kb.ledger.data.json.PersonJson
import com.kb.ledger.data.json.TransactionJson
import com.kb.ledger.util.AppResult
import com.kb.ledger.util.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Deterministic unit tests for [GoogleDriveRepository].
 *
 * Verifies:
 * - Existing vs. missing remote files (checkRemoteLedger, fetchRemoteLedger)
 * - Interrupted initialization detection and recovery
 * - JSON deserialization and schema validation gating
 * - Multi-file write-ahead ordering (data files committed before manifest)
 * - Rollback on initialization failure
 * - Overwrite protection (preventing silent cloud data loss)
 * - Deduplication of files with identical names
 * - Account email isolation
 */
class GoogleDriveRepositoryTest {

    private lateinit var fakeDriveService: FakeDriveApiService
    private lateinit var serializer: LedgerJsonSerializer
    private lateinit var repository: GoogleDriveRepository

    private val fixedClock = object : Clock {
        override fun nowMillis(): Long = 1729420800000L // 2024-10-20T10:40:00Z
        override fun nowIso8601(): String = "2024-10-20T10:40:00Z"
    }

    private val testEmail = "ledger.user@gmail.com"
    private val validPersonCloudId = "11111111-1111-4111-8111-111111111111"
    private val validTxCloudId = "22222222-2222-4222-8222-222222222222"

    @Before
    fun setUp() {
        fakeDriveService = FakeDriveApiService()
        serializer = LedgerJsonSerializer()
        repository = GoogleDriveRepository(
            driveApiService = fakeDriveService,
            serializer = serializer,
            validator = JsonSchemaValidator,
            clock = fixedClock,
            appVersion = "1.0-test"
        )
    }

    private fun createValidManifest(): ManifestJson = ManifestJson(
        schemaVersion = LedgerJsonSerializer.CURRENT_MANIFEST_VERSION,
        appVersion = "1.0",
        ledgerId = UUID.randomUUID().toString(),
        ownerId = testEmail,
        createdAt = "2024-10-20T10:00:00Z",
        lastSyncAt = "2024-10-20T10:00:00Z"
    )

    private fun createValidPerson(
        cloudId: String = validPersonCloudId,
        localId: Long? = 1L,
        name: String = "John Doe",
        isDeleted: Boolean = false
    ): PersonJson = PersonJson(
        cloudId = cloudId,
        localId = localId,
        name = name,
        phone = "9876543210",
        contactId = null,
        createdAt = "2024-10-20T10:00:00Z",
        updatedAt = "2024-10-20T10:00:00Z",
        updatedBy = testEmail,
        isDeleted = isDeleted
    )

    private fun createValidTransaction(
        id: String = validTxCloudId,
        personCloudId: String = validPersonCloudId,
        amountCents: Long = 50000L,
        type: String = "GIVE",
        isDeleted: Boolean = false
    ): TransactionJson = TransactionJson(
        id = id,
        personCloudId = personCloudId,
        amountCents = amountCents,
        type = type,
        note = "Test loan",
        imageFileId = null,
        createdAt = "2024-10-20T10:00:00Z",
        updatedAt = "2024-10-20T10:00:00Z",
        updatedBy = testEmail,
        isDeleted = isDeleted
    )

    // ── checkRemoteLedger ─────────────────────────────────────────────────────

    @Test
    fun checkRemoteLedger_emptyDrive_returnsNotFound() = runBlocking {
        val result = repository.checkRemoteLedger("token", testEmail)
        assertTrue(result is AppResult.Success)
        val status = (result as AppResult.Success).data
        assertTrue(status is RemoteLedgerCheckResult.NotFound)
    }

    @Test
    fun checkRemoteLedger_interruptedState_dataFilesExistWithoutManifest_returnsInterruptedInitialization() = runBlocking {
        // Only persons.json exists (interrupted before manifest commit)
        fakeDriveService.addFile(
            name = GoogleDriveRepository.PERSONS_FILE_NAME,
            content = serializer.serializePersons(listOf(createValidPerson()))
        )

        val result = repository.checkRemoteLedger("token", testEmail)
        assertTrue(result is AppResult.Success)
        val status = (result as AppResult.Success).data
        assertTrue(status is RemoteLedgerCheckResult.InterruptedInitialization)
        val interrupted = status as RemoteLedgerCheckResult.InterruptedInitialization
        assertEquals(1, interrupted.existingFiles.size)
        assertEquals(GoogleDriveRepository.PERSONS_FILE_NAME, interrupted.existingFiles[0].name)
    }

    @Test
    fun checkRemoteLedger_completeLedgerExists_returnsFound() = runBlocking {
        fakeDriveService.addFile(GoogleDriveRepository.MANIFEST_FILE_NAME, serializer.serializeManifest(createValidManifest()))
        fakeDriveService.addFile(GoogleDriveRepository.PERSONS_FILE_NAME, serializer.serializePersons(listOf(createValidPerson())))
        fakeDriveService.addFile(GoogleDriveRepository.TRANSACTIONS_FILE_NAME, serializer.serializeTransactions(listOf(createValidTransaction())))

        val result = repository.checkRemoteLedger("token", testEmail)
        assertTrue(result is AppResult.Success)
        val status = (result as AppResult.Success).data
        assertTrue(status is RemoteLedgerCheckResult.Found)
        val found = (status as RemoteLedgerCheckResult.Found).remoteLedger
        assertEquals(1, found.persons.size)
        assertEquals(1, found.transactions.size)
    }

    @Test
    fun checkRemoteLedger_blankEmail_returnsAccountRequiredError() = runBlocking {
        val result = repository.checkRemoteLedger("token", "   ")
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("ACCOUNT_REQUIRED"))
    }

    @Test
    fun checkRemoteLedger_driveApiError_propagatesError() = runBlocking {
        fakeDriveService.listFilesResult = AppResult.Error("AUTH_TOKEN_EXPIRED (HTTP 401): Expired")

        val result = repository.checkRemoteLedger("expired-token", testEmail)
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("AUTH_TOKEN_EXPIRED"))
    }

    // ── fetchRemoteLedger ─────────────────────────────────────────────────────

    @Test
    fun fetchRemoteLedger_success_validLedger() = runBlocking {
        val manifest = createValidManifest()
        val person = createValidPerson()
        val tx = createValidTransaction()

        fakeDriveService.addFile(GoogleDriveRepository.MANIFEST_FILE_NAME, serializer.serializeManifest(manifest), fileId = "m-id")
        fakeDriveService.addFile(GoogleDriveRepository.PERSONS_FILE_NAME, serializer.serializePersons(listOf(person)), fileId = "p-id")
        fakeDriveService.addFile(GoogleDriveRepository.TRANSACTIONS_FILE_NAME, serializer.serializeTransactions(listOf(tx)), fileId = "t-id")

        val result = repository.fetchRemoteLedger("token", testEmail)
        assertTrue(result is AppResult.Success)
        val ledger = (result as AppResult.Success).data
        assertEquals("m-id", ledger.manifestFileId)
        assertEquals("p-id", ledger.personsFileId)
        assertEquals("t-id", ledger.transactionsFileId)
        assertEquals(1, ledger.persons.size)
        assertEquals(1, ledger.transactions.size)
        assertEquals(validPersonCloudId, ledger.persons[0].cloudId)
    }

    @Test
    fun fetchRemoteLedger_missingManifest_returnsLedgerNotFoundError() = runBlocking {
        fakeDriveService.addFile(GoogleDriveRepository.PERSONS_FILE_NAME, "[]")
        fakeDriveService.addFile(GoogleDriveRepository.TRANSACTIONS_FILE_NAME, "[]")

        val result = repository.fetchRemoteLedger("token", testEmail)
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("LEDGER_NOT_FOUND"))
    }

    @Test
    fun fetchRemoteLedger_missingPersonsFile_returnsIncompleteLedgerError() = runBlocking {
        fakeDriveService.addFile(GoogleDriveRepository.MANIFEST_FILE_NAME, serializer.serializeManifest(createValidManifest()))
        fakeDriveService.addFile(GoogleDriveRepository.TRANSACTIONS_FILE_NAME, "[]")

        val result = repository.fetchRemoteLedger("token", testEmail)
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("INCOMPLETE_LEDGER"))
    }

    @Test
    fun fetchRemoteLedger_malformedPersonsJson_rejectedBySerializer() = runBlocking {
        fakeDriveService.addFile(GoogleDriveRepository.MANIFEST_FILE_NAME, serializer.serializeManifest(createValidManifest()))
        fakeDriveService.addFile(GoogleDriveRepository.PERSONS_FILE_NAME, "{ NOT_VALID_JSON")
        fakeDriveService.addFile(GoogleDriveRepository.TRANSACTIONS_FILE_NAME, "[]")

        val result = repository.fetchRemoteLedger("token", testEmail)
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("Failed to parse"))
    }

    @Test
    fun fetchRemoteLedger_unsupportedSchemaVersion_rejected() = runBlocking {
        val futureManifest = """
            {
                "schemaVersion": 99,
                "appVersion": "99.0",
                "ledgerId": "test",
                "ownerId": "$testEmail",
                "createdAt": "2024-10-20T10:00:00Z",
                "lastSyncAt": "2024-10-20T10:00:00Z"
            }
        """.trimIndent()
        fakeDriveService.addFile(GoogleDriveRepository.MANIFEST_FILE_NAME, futureManifest)
        fakeDriveService.addFile(GoogleDriveRepository.PERSONS_FILE_NAME, serializer.serializePersons(emptyList()))
        fakeDriveService.addFile(GoogleDriveRepository.TRANSACTIONS_FILE_NAME, serializer.serializeTransactions(emptyList()))

        val result = repository.fetchRemoteLedger("token", testEmail)
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("newer than supported"))
    }

    @Test
    fun fetchRemoteLedger_referentialIntegrityViolation_rejectedByValidator() = runBlocking {
        fakeDriveService.addFile(GoogleDriveRepository.MANIFEST_FILE_NAME, serializer.serializeManifest(createValidManifest()))
        // Persons list does not have the person referenced by transaction
        fakeDriveService.addFile(GoogleDriveRepository.PERSONS_FILE_NAME, serializer.serializePersons(emptyList()))
        fakeDriveService.addFile(GoogleDriveRepository.TRANSACTIONS_FILE_NAME, serializer.serializeTransactions(listOf(createValidTransaction())))

        val result = repository.fetchRemoteLedger("token", testEmail)
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("has no matching person record"))
    }

    // ── initializeCloudLedger ─────────────────────────────────────────────────

    @Test
    fun initializeCloudLedger_creationOrder_dataFilesBeforeManifest() = runBlocking {
        val person = createValidPerson()
        val tx = createValidTransaction()

        val result = repository.initializeCloudLedger(
            accessToken = "token",
            accountEmail = testEmail,
            initialPersons = listOf(person),
            initialTransactions = listOf(tx)
        )

        assertTrue(result is AppResult.Success)
        val ledger = (result as AppResult.Success).data
        assertEquals(testEmail, ledger.manifest.ownerId)

        // Verify creation order in callLog: persons -> transactions -> manifest
        val createCalls = fakeDriveService.callLog.filter { it.startsWith("createAppDataFile:") }
        assertEquals(3, createCalls.size)
        assertEquals("createAppDataFile:persons.json", createCalls[0])
        assertEquals("createAppDataFile:transactions.json", createCalls[1])
        assertEquals("createAppDataFile:manifest.json", createCalls[2])
    }

    @Test
    fun initializeCloudLedger_transactionsFailure_rollsBackPersonsFile() = runBlocking {
        fakeDriveService.createResultOverride = { name ->
            if (name == GoogleDriveRepository.TRANSACTIONS_FILE_NAME) {
                AppResult.Error("DRIVE_SERVER_ERROR (HTTP 500): Quota Exceeded")
            } else {
                null
            }
        }

        val result = repository.initializeCloudLedger("token", testEmail)
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("Quota Exceeded"))

        // Rollback verification: persons.json should have been deleted
        assertTrue(fakeDriveService.callLog.any { it.startsWith("deleteFile:") })
        assertEquals(0, fakeDriveService.files.size)
    }

    @Test
    fun initializeCloudLedger_manifestFailure_rollsBackAllDataFiles() = runBlocking {
        fakeDriveService.createResultOverride = { name ->
            if (name == GoogleDriveRepository.MANIFEST_FILE_NAME) {
                AppResult.Error("DRIVE_SERVER_ERROR (HTTP 503): Unavailable")
            } else {
                null
            }
        }

        val result = repository.initializeCloudLedger("token", testEmail)
        assertTrue(result is AppResult.Error)

        // Rollback verification: both persons and transactions should be deleted
        val deleteCalls = fakeDriveService.callLog.filter { it.startsWith("deleteFile:") }
        assertEquals(2, deleteCalls.size)
        assertEquals(0, fakeDriveService.files.size)
    }

    // ── updateCloudLedger ─────────────────────────────────────────────────────

    @Test
    fun updateCloudLedger_success_updatesDataAndManifest() = runBlocking {
        val manifest = createValidManifest()
        val person = createValidPerson()
        val tx = createValidTransaction()

        val manifestFile = fakeDriveService.addFile(GoogleDriveRepository.MANIFEST_FILE_NAME, serializer.serializeManifest(manifest))
        val personsFile = fakeDriveService.addFile(GoogleDriveRepository.PERSONS_FILE_NAME, serializer.serializePersons(listOf(person)))
        val txFile = fakeDriveService.addFile(GoogleDriveRepository.TRANSACTIONS_FILE_NAME, serializer.serializeTransactions(listOf(tx)))

        val existingRemote = RemoteLedger(
            manifest = manifest,
            manifestFileId = manifestFile.id,
            persons = listOf(person),
            personsFileId = personsFile.id,
            transactions = listOf(tx),
            transactionsFileId = txFile.id
        )

        val updatedPerson = person.copy(name = "Updated John")
        val result = repository.updateCloudLedger(
            accessToken = "token",
            accountEmail = testEmail,
            existingLedger = existingRemote,
            updatedPersons = listOf(updatedPerson),
            updatedTransactions = listOf(tx)
        )

        assertTrue(result is AppResult.Success)
        val updatedLedger = (result as AppResult.Success).data
        assertEquals("Updated John", updatedLedger.persons[0].name)
        assertEquals("2024-10-20T10:40:00Z", updatedLedger.manifest.lastSyncAt)

        // Verify update calls occurred for all 3 files
        val updateCalls = fakeDriveService.callLog.filter { it.startsWith("updateFileContent:") }
        assertEquals(3, updateCalls.size)
    }

    @Test
    fun updateCloudLedger_overwriteProtection_refusesToOverwriteActiveRemoteWithEmptyLocal() = runBlocking {
        val manifest = createValidManifest()
        val activePerson = createValidPerson(isDeleted = false)
        val activeTx = createValidTransaction(isDeleted = false)

        val existingRemote = RemoteLedger(
            manifest = manifest,
            manifestFileId = "m-id",
            persons = listOf(activePerson),
            personsFileId = "p-id",
            transactions = listOf(activeTx),
            transactionsFileId = "t-id"
        )

        // Attempting to push empty local records to non-empty cloud ledger
        val result = repository.updateCloudLedger(
            accessToken = "token",
            accountEmail = testEmail,
            existingLedger = existingRemote,
            updatedPersons = emptyList(),
            updatedTransactions = emptyList()
        )

        assertTrue(result is AppResult.Error)
        val error = result as AppResult.Error
        assertTrue(error.message.contains("OVERWRITE_PROTECTION"))
        assertTrue(error.message.contains("Refusing to overwrite"))

        // Ensure zero update calls were made
        assertFalse(fakeDriveService.callLog.any { it.startsWith("updateFileContent:") })
    }

    @Test
    fun updateCloudLedger_overwriteProtection_allowsUpdateIfRemoteOnlyHasTombstones() = runBlocking {
        val manifest = createValidManifest()
        val deletedPerson = createValidPerson(isDeleted = true)
        val deletedTx = createValidTransaction(isDeleted = true)

        val mFile = fakeDriveService.addFile(GoogleDriveRepository.MANIFEST_FILE_NAME, serializer.serializeManifest(manifest))
        val pFile = fakeDriveService.addFile(GoogleDriveRepository.PERSONS_FILE_NAME, serializer.serializePersons(listOf(deletedPerson)))
        val tFile = fakeDriveService.addFile(GoogleDriveRepository.TRANSACTIONS_FILE_NAME, serializer.serializeTransactions(listOf(deletedTx)))

        val existingRemote = RemoteLedger(
            manifest = manifest,
            manifestFileId = mFile.id,
            persons = listOf(deletedPerson),
            personsFileId = pFile.id,
            transactions = listOf(deletedTx),
            transactionsFileId = tFile.id
        )

        // Empty local is allowed because remote has no active records
        val result = repository.updateCloudLedger(
            accessToken = "token",
            accountEmail = testEmail,
            existingLedger = existingRemote,
            updatedPersons = emptyList(),
            updatedTransactions = emptyList()
        )

        assertTrue(result is AppResult.Success)
    }

    @Test
    fun updateCloudLedger_validationGate_rejectsInvalidTransactionsBeforeDriveCall() = runBlocking {
        val manifest = createValidManifest()
        val person = createValidPerson()
        val existingRemote = RemoteLedger(
            manifest = manifest,
            manifestFileId = "m-id",
            persons = listOf(person),
            personsFileId = "p-id",
            transactions = emptyList(),
            transactionsFileId = "t-id"
        )

        // Invalid transaction: amountCents <= 0
        val invalidTx = createValidTransaction().copy(amountCents = -500L)

        val result = repository.updateCloudLedger(
            accessToken = "token",
            accountEmail = testEmail,
            existingLedger = existingRemote,
            updatedPersons = listOf(person),
            updatedTransactions = listOf(invalidTx)
        )

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("amountCents must be > 0"))
        assertFalse(fakeDriveService.callLog.any { it.startsWith("updateFileContent:") })
    }

    // ── cleanupDuplicateFiles ─────────────────────────────────────────────────

    @Test
    fun cleanupDuplicateFiles_deletesOlderDuplicates_keepsNewest() = runBlocking {
        // Two manifest files with different versions
        fakeDriveService.addFile(GoogleDriveRepository.MANIFEST_FILE_NAME, "{}", fileId = "old-manifest", version = 1)
        fakeDriveService.addFile(GoogleDriveRepository.MANIFEST_FILE_NAME, "{}", fileId = "new-manifest", version = 2)

        val result = repository.cleanupDuplicateFiles("token")
        assertTrue(result is AppResult.Success)
        assertEquals(1, (result as AppResult.Success).data)

        // Only "new-manifest" should remain
        assertEquals(1, fakeDriveService.files.size)
        assertTrue(fakeDriveService.files.containsKey("new-manifest"))
        assertFalse(fakeDriveService.files.containsKey("old-manifest"))
    }

    @Test
    fun cleanupDuplicateFiles_noDuplicates_deletesNothing() = runBlocking {
        fakeDriveService.addFile(GoogleDriveRepository.MANIFEST_FILE_NAME, "{}", fileId = "m1")
        fakeDriveService.addFile(GoogleDriveRepository.PERSONS_FILE_NAME, "[]", fileId = "p1")

        val result = repository.cleanupDuplicateFiles("token")
        assertTrue(result is AppResult.Success)
        assertEquals(0, (result as AppResult.Success).data)
        assertEquals(2, fakeDriveService.files.size)
    }
}
