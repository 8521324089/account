package com.kb.ledger.sync

import com.kb.ledger.backup.partition.LocalPartitionedBackupManager
import com.kb.ledger.data.json.JsonSchemaValidator
import com.kb.ledger.data.json.LedgerJsonSerializer
import com.kb.ledger.data.json.ManifestJson
import com.kb.ledger.data.json.PersonJson
import com.kb.ledger.data.json.TransactionJson
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.data.remote.FakeDriveApiService
import com.kb.ledger.data.remote.GoogleDriveRepository
import com.kb.ledger.data.repository.FakePersonDao
import com.kb.ledger.data.repository.FakeRecurringRuleDao
import com.kb.ledger.data.repository.FakeSyncOperationDao
import com.kb.ledger.data.repository.FakeTransactionDao
import com.kb.ledger.util.AddressBookContactResolver
import com.kb.ledger.util.AppResult
import com.kb.ledger.util.Clock
import com.kb.ledger.util.ContactResolutionResult
import com.kb.ledger.util.ContactSearchHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Test double for [AddressBookContactResolver] simulating owner address book state.
 */
class FakeAddressBookContactResolver : AddressBookContactResolver {
    private val contacts = mutableMapOf<String, MutableSet<String>>()
    var shouldFailWithPermissionDenied = false
    var shouldThrowException = false

    fun addContact(phone: String, name: String) {
        val norm = ContactSearchHelper.normalizePhone(phone)
        contacts.getOrPut(norm) { mutableSetOf() }.add(name)
    }

    override suspend fun resolveContact(rawPhone: String): ContactResolutionResult {
        if (shouldFailWithPermissionDenied) {
            return ContactResolutionResult.PermissionDeniedOrError("READ_CONTACTS permission not granted")
        }
        if (shouldThrowException) {
            return ContactResolutionResult.PermissionDeniedOrError("Query failed: simulated exception")
        }
        val norm = ContactSearchHelper.normalizePhone(rawPhone)
        val names = contacts[norm] ?: return ContactResolutionResult.NoMatch
        return when {
            names.isEmpty() -> ContactResolutionResult.NoMatch
            names.size == 1 -> ContactResolutionResult.Match(names.first())
            else -> ContactResolutionResult.Ambiguous(names.toList().sorted())
        }
    }
}

/**
 * Comprehensive unit tests verifying Owner Address Book Name Resolution requirements:
 *
 * 1. Owner contact match updates the displayed name only.
 * 2. No owner contact match preserves the staff-supplied name.
 * 3. Contact permission denied or lookup failure preserves name and does not block sync.
 * 4. Existing owner person is reused without creating duplicates.
 * 5. Repeated synchronization is idempotent.
 * 6. Ambiguous matches are not silently resolved.
 * 7. Transaction foreign keys, balances, timestamps, IDs, and tombstones remain unchanged.
 * 8. Local backup reflects changed name and survives safe restore.
 */
class OwnerAddressBookResolutionTest {

    private lateinit var fakeTransactionDao: FakeTransactionDao
    private lateinit var fakePersonDao: FakePersonDao
    private lateinit var fakeRecurringRuleDao: FakeRecurringRuleDao
    private lateinit var fakeSyncOpDao: FakeSyncOperationDao
    private lateinit var fakeDriveService: FakeDriveApiService
    private lateinit var fakeResolver: FakeAddressBookContactResolver
    private lateinit var repository: GoogleDriveRepository
    private lateinit var serializer: LedgerJsonSerializer
    private lateinit var tempDir: File
    private lateinit var backupDir: File
    private lateinit var backupManager: LocalPartitionedBackupManager

    private val fixedClock = object : Clock {
        override fun nowMillis(): Long = 1729833600000L
        override fun nowIso8601(): String = "2024-10-25T05:20:00Z"
    }

    private val testToken = "test-token"
    private val testEmail = "owner@gmail.com"

    @Before
    fun setUp() {
        fakeTransactionDao = FakeTransactionDao()
        fakePersonDao = FakePersonDao()
        fakeTransactionDao.personDaoRef = fakePersonDao
        fakeRecurringRuleDao = FakeRecurringRuleDao()
        fakeSyncOpDao = FakeSyncOperationDao()
        fakeDriveService = FakeDriveApiService()
        fakeResolver = FakeAddressBookContactResolver()
        tempDir = Files.createTempDirectory("owner_resolution_test").toFile()
        backupDir = File(tempDir, "backup")
        backupManager = LocalPartitionedBackupManager(backupDir, targetChunkSize = 5)

        serializer = LedgerJsonSerializer()
        repository = GoogleDriveRepository(
            driveApiService = fakeDriveService,
            serializer = serializer,
            validator = JsonSchemaValidator,
            clock = fixedClock,
            appVersion = "1.0-test"
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createSyncEngine(): DriveSyncEngine {
        return DriveSyncEngine(
            driveApiService = fakeDriveService,
            googleDriveRepository = repository,
            personDao = fakePersonDao,
            transactionDao = fakeTransactionDao,
            recurringRuleDao = fakeRecurringRuleDao,
            syncOperationDao = fakeSyncOpDao,
            addressBookResolver = fakeResolver,
            localBackupManager = backupManager,
            resolveImageFile = { _, _ -> null }
        )
    }

    private suspend fun seedRemoteLedger(
        persons: List<PersonJson>,
        transactions: List<TransactionJson>
    ) {
        val manifest = ManifestJson(
            schemaVersion = 1,
            appVersion = "1.0",
            ledgerId = UUID.randomUUID().toString(),
            ownerId = testEmail,
            createdAt = "2024-10-25T00:00:00Z",
            lastSyncAt = "2024-10-25T00:00:00Z"
        )
        fakeDriveService.createAppDataFile(testToken, GoogleDriveRepository.MANIFEST_FILE_NAME, serializer.serializeManifest(manifest))
        fakeDriveService.createAppDataFile(testToken, GoogleDriveRepository.PERSONS_FILE_NAME, serializer.serializePersons(persons))
        fakeDriveService.createAppDataFile(testToken, GoogleDriveRepository.TRANSACTIONS_FILE_NAME, serializer.serializeTransactions(transactions))
    }

    // ── 1. Owner contact match changes the name only ─────────────────────────

    @Test
    fun ownerContactMatch_changesDisplayedNameOnly_preservingAllOtherFields() = runBlocking {
        // Staff created person in cloud:
        val staffCloudId = UUID.randomUUID().toString()
        val txId = UUID.randomUUID().toString()
        val remotePerson = PersonJson(
            cloudId = staffCloudId,
            localId = 101L,
            name = "Staff Entered Ramesh",
            phone = "09431733886", // 11-digit with leading trunk zero
            contactId = "staff-contact-99",
            createdAt = "2024-10-20T10:00:00Z",
            updatedAt = "2024-10-20T10:00:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        val remoteTx = TransactionJson(
            id = txId,
            personCloudId = staffCloudId,
            amountCents = 150000L,
            type = "RECEIVE",
            note = "Initial deposit from S1",
            imageFileId = null,
            createdAt = "2024-10-20T10:05:00Z",
            updatedAt = "2024-10-20T10:05:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        seedRemoteLedger(listOf(remotePerson), listOf(remoteTx))

        // Owner's phone address book has contact "Ramesh Bhai (VIP)" for "+91 94317 33886"
        fakeResolver.addContact("+91 94317 33886", "Ramesh Bhai (VIP)")

        val engine = createSyncEngine()
        val summary = engine.executeSync(testToken, testEmail)

        assertTrue("Sync must succeed. Errors: ${summary.networkErrors}", summary.success)
        assertEquals(1, summary.syncedPersonsCount)
        assertEquals(1, summary.syncedTransactionsCount)

        // Verify local person in owner's database
        val localPerson = fakePersonDao.getPersonByCloudId(staffCloudId)
        assertNotNull("Person must exist locally", localPerson)
        // 1. Name is updated to owner's contact name
        assertEquals("Ramesh Bhai (VIP)", localPerson!!.name)
        // 2. Phone is preserved exactly
        assertEquals("09431733886", localPerson.phone)
        // 3. Stable local ID is allocated (> 0)
        assertTrue(localPerson.id > 0)
        // 4. Tombstone status preserved
        assertFalse(localPerson.isDeleted)

        // Verify transaction foreign key links to localPerson.id
        val localTx = fakeTransactionDao.getTransactionById(txId)
        assertNotNull("Transaction must exist locally", localTx)
        assertEquals(localPerson.id, localTx!!.personId)
        assertEquals(150000L, localTx.amountCents)
        assertEquals(TransactionType.RECEIVE, localTx.type)

        // Verify cloud payload preserved staff-supplied name ("Staff Entered Ramesh")
        val cloudPersonsResult = repository.fetchRemoteLedger(testToken, testEmail)
        assertTrue(cloudPersonsResult is AppResult.Success)
        val cloudPersons = (cloudPersonsResult as AppResult.Success).data.persons
        assertEquals(1, cloudPersons.size)
        assertEquals("Staff Entered Ramesh", cloudPersons.first().name)
    }

    // ── 2. No owner contact match preserves the staff-supplied name ──────────

    @Test
    fun noOwnerContactMatch_preservesStaffSuppliedName() = runBlocking {
        val staffCloudId = UUID.randomUUID().toString()
        val remotePerson = PersonJson(
            cloudId = staffCloudId,
            localId = 102L,
            name = "Vendor Sunil (Staff Name)",
            phone = "9876543210",
            contactId = null,
            createdAt = "2024-10-21T08:00:00Z",
            updatedAt = "2024-10-21T08:00:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        seedRemoteLedger(listOf(remotePerson), emptyList())

        // Owner address book has NO matching contact for "9876543210"

        val engine = createSyncEngine()
        val summary = engine.executeSync(testToken, testEmail)

        assertTrue("Sync must succeed. Errors: ${summary.networkErrors}", summary.success)
        val localPerson = fakePersonDao.getPersonByCloudId(staffCloudId)
        assertNotNull(localPerson)
        // Staff-supplied name is preserved
        assertEquals("Vendor Sunil (Staff Name)", localPerson!!.name)
        assertEquals("9876543210", localPerson.phone)
    }

    // ── 2b. Owner resolved name is not unintentionally pushed back to cloud ──

    @Test
    fun ownerResolvedName_isNotPushedBackToCloud_preservingStaffNameForS1() = runBlocking {
        val staffCloudId = UUID.randomUUID().toString()
        val originalStaffName = "Staff Original Contact"
        val remotePerson = PersonJson(
            cloudId = staffCloudId,
            localId = 500L,
            name = originalStaffName,
            phone = "9876543210",
            contactId = null,
            createdAt = "2024-10-21T08:00:00Z",
            updatedAt = "2024-10-21T08:00:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        seedRemoteLedger(listOf(remotePerson), emptyList())

        // Owner's address book has a completely different name
        fakeResolver.addContact("9876543210", "Owner Private Nickname")

        val engine = createSyncEngine()
        val summary = engine.executeSync(testToken, testEmail)
        assertTrue(summary.success)

        // 1. Locally, owner sees their private nickname
        val localPerson = fakePersonDao.getPersonByCloudId(staffCloudId)
        assertEquals("Owner Private Nickname", localPerson?.name)

        // 2. In Google Drive cloud ledger, the staff name is preserved
        val cloudResult = repository.fetchRemoteLedger(testToken, testEmail)
        assertTrue(cloudResult is AppResult.Success)
        val cloudLedger = (cloudResult as AppResult.Success).data
        val cloudPerson = cloudLedger.persons.first { it.cloudId == staffCloudId }
        assertEquals("Cloud ledger must preserve staff name for S1", originalStaffName, cloudPerson.name)
    }

    // ── 3. Contact permission denied or lookup failure preserves name ─────────

    @Test
    fun contactPermissionDeniedOrFailure_preservesName_andDoesNotBlockSync() = runBlocking {
        val staffCloudId = UUID.randomUUID().toString()
        val txId = UUID.randomUUID().toString()
        val remotePerson = PersonJson(
            cloudId = staffCloudId,
            localId = 103L,
            name = "Contractor Deepak",
            phone = "9123456789",
            contactId = null,
            createdAt = "2024-10-22T08:00:00Z",
            updatedAt = "2024-10-22T08:00:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        val remoteTx = TransactionJson(
            id = txId,
            personCloudId = staffCloudId,
            amountCents = 80000L,
            type = "GIVE",
            note = "Hardware supplies",
            imageFileId = null,
            createdAt = "2024-10-22T08:10:00Z",
            updatedAt = "2024-10-22T08:10:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        seedRemoteLedger(listOf(remotePerson), listOf(remoteTx))

        // Owner denied READ_CONTACTS permission
        fakeResolver.shouldFailWithPermissionDenied = true

        val engine = createSyncEngine()
        val summary = engine.executeSync(testToken, testEmail)

        assertTrue("Sync must not be blocked when contact permission is denied", summary.success)
        val localPerson = fakePersonDao.getPersonByCloudId(staffCloudId)
        assertNotNull(localPerson)
        // Preserves staff-supplied name
        assertEquals("Contractor Deepak", localPerson!!.name)

        val localTx = fakeTransactionDao.getTransactionById(txId)
        assertNotNull("Transaction must sync normally", localTx)
        assertEquals(80000L, localTx!!.amountCents)
    }

    // ── 4. Existing owner person is reused without duplicate ─────────────────

    @Test
    fun existingOwnerPerson_isReused_withoutCreatingDuplicate() = runBlocking {
        // Owner already created a local customer offline before sync
        val localId = 55L
        val preExistingPerson = Person(
            id = localId,
            name = "Local Amit",
            phone = "+91 94317 33886", // Indian format
            cloudId = null, // No cloud identity established yet
            createdAt = 1729000000000L,
            updatedAt = 1729000000000L,
            syncPending = false
        )
        fakePersonDao.insertPerson(preExistingPerson)

        // Staff created a transaction for the same person in cloud
        val staffCloudId = UUID.randomUUID().toString()
        val txId = UUID.randomUUID().toString()
        val remotePerson = PersonJson(
            cloudId = staffCloudId,
            localId = 999L,
            name = "Staff Amit Kumar",
            phone = "9431733 886", // Normalized to "9431733886"
            contactId = null,
            createdAt = "2024-10-23T08:00:00Z",
            updatedAt = "2024-10-23T08:00:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        val remoteTx = TransactionJson(
            id = txId,
            personCloudId = staffCloudId,
            amountCents = 20000L,
            type = "RECEIVE",
            note = "Payment from Amit",
            imageFileId = null,
            createdAt = "2024-10-23T08:10:00Z",
            updatedAt = "2024-10-23T08:10:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        seedRemoteLedger(listOf(remotePerson), listOf(remoteTx))

        // Owner address book has contact "Amitabh (Owner Phonebook)"
        fakeResolver.addContact("09431733886", "Amitabh (Owner Phonebook)")

        val engine = createSyncEngine()
        val summary = engine.executeSync(testToken, testEmail)

        assertTrue("Sync must succeed. Errors: ${summary.networkErrors}", summary.success)

        // 1. Exactly 1 person in the database (no duplicate!)
        val allPersons = fakePersonDao.getAllPersonsList()
        assertEquals("Duplicate person must not be created", 1, allPersons.size)

        val reusedPerson = allPersons.first()
        // 2. Stable local ID is preserved
        assertEquals(localId, reusedPerson.id)
        // 3. Name updated to owner's contact name
        assertEquals("Amitabh (Owner Phonebook)", reusedPerson.name)
        // 4. Cloud ID attached
        assertEquals(staffCloudId, reusedPerson.cloudId)
        // 5. Phone preserved
        assertEquals("+91 94317 33886", reusedPerson.phone)

        // 6. Transaction linked to existing localId
        val localTx = fakeTransactionDao.getTransactionById(txId)
        assertNotNull(localTx)
        assertEquals(localId, localTx!!.personId)
    }

    // ── 5. Repeated synchronization is idempotent ────────────────────────────

    @Test
    fun repeatedSync_isCompletelyIdempotent() = runBlocking {
        val staffCloudId = UUID.randomUUID().toString()
        val txId = UUID.randomUUID().toString()
        val remotePerson = PersonJson(
            cloudId = staffCloudId,
            localId = 105L,
            name = "Staff Pooja",
            phone = "9876543210",
            contactId = null,
            createdAt = "2024-10-24T08:00:00Z",
            updatedAt = "2024-10-24T08:00:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        val remoteTx = TransactionJson(
            id = txId,
            personCloudId = staffCloudId,
            amountCents = 45000L,
            type = "RECEIVE",
            note = "Pass 1 tx",
            imageFileId = null,
            createdAt = "2024-10-24T08:05:00Z",
            updatedAt = "2024-10-24T08:05:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        seedRemoteLedger(listOf(remotePerson), listOf(remoteTx))
        fakeResolver.addContact("9876543210", "Pooja Sharma (Owner Contact)")

        val engine = createSyncEngine()

        // Pass 1
        val pass1 = engine.executeSync(testToken, testEmail)
        assertTrue("Pass 1 must succeed. Errors: ${pass1.networkErrors}", pass1.success)
        assertEquals(1, fakePersonDao.getAllPersonsList().size)
        assertEquals(1, fakeTransactionDao.getAllTransactions().size)

        // Pass 2 (Immediate retry)
        val pass2 = engine.executeSync(testToken, testEmail)
        assertTrue("Pass 2 must succeed. Errors: ${pass2.networkErrors}", pass2.success)
        assertEquals("Person count must remain 1 on retry", 1, fakePersonDao.getAllPersonsList().size)
        assertEquals("Transaction count must remain 1 on retry", 1, fakeTransactionDao.getAllTransactions().size)

        val finalPerson = fakePersonDao.getPersonByCloudId(staffCloudId)
        assertNotNull(finalPerson)
        assertEquals("Pooja Sharma (Owner Contact)", finalPerson!!.name)

        // Pass 3 (Subsequent sync pass)
        val pass3 = engine.executeSync(testToken, testEmail)
        assertTrue("Pass 3 must succeed. Errors: ${pass3.networkErrors}", pass3.success)
        assertEquals(1, fakePersonDao.getAllPersonsList().size)
        assertEquals(1, fakeTransactionDao.getAllTransactions().size)
    }

    // ── 6. Ambiguous matches are not silently resolved ───────────────────────

    @Test
    fun ambiguousAddressBookMatches_areNotSilentlyResolved() = runBlocking {
        val staffCloudId = UUID.randomUUID().toString()
        val remotePerson = PersonJson(
            cloudId = staffCloudId,
            localId = 106L,
            name = "Staff Raw Name",
            phone = "9876543210",
            contactId = null,
            createdAt = "2024-10-25T01:00:00Z",
            updatedAt = "2024-10-25T01:00:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        seedRemoteLedger(listOf(remotePerson), emptyList())

        // Owner address book has TWO different names for the same number
        fakeResolver.addContact("9876543210", "Rahul Office")
        fakeResolver.addContact("9876543210", "Rahul Personal")

        val engine = createSyncEngine()
        val summary = engine.executeSync(testToken, testEmail)

        assertTrue("Sync must succeed. Errors: ${summary.networkErrors}", summary.success)
        val localPerson = fakePersonDao.getPersonByCloudId(staffCloudId)
        assertNotNull(localPerson)
        // Must NOT guess or silently pick one; preserves staff-supplied name
        assertEquals("Staff Raw Name", localPerson!!.name)
    }

    // ── 7. Transactions, balances, timestamps, and tombstones unchanged ──────

    @Test
    fun transactionForeignKeys_balances_timestamps_and_tombstones_remainIntact() = runBlocking {
        val personId = 77L
        val originalCreatedAt = 1728000000000L
        val cloudPersonId = UUID.randomUUID().toString()
        val activeTxId = UUID.randomUUID().toString()
        val tombstoneTxId = UUID.randomUUID().toString()

        val localPerson = Person(
            id = personId,
            name = "Old Staff Name",
            phone = "9431733886",
            cloudId = cloudPersonId,
            createdAt = originalCreatedAt,
            updatedAt = originalCreatedAt,
            isDeleted = false,
            syncPending = false
        )
        fakePersonDao.insertPerson(localPerson)

        // Pre-existing transactions for this person:
        // 1. Active transaction: RECEIVE 100000L
        val txActive = Transaction(
            id = activeTxId,
            personId = personId,
            amountCents = 100000L,
            type = TransactionType.RECEIVE,
            note = "Active credit",
            createdAt = originalCreatedAt + 1000L,
            updatedAt = originalCreatedAt + 1000L,
            isDeleted = false,
            syncPending = false
        )
        // 2. Tombstone transaction: GIVE 30000L (soft-deleted)
        val txTombstone = Transaction(
            id = tombstoneTxId,
            personId = personId,
            amountCents = 30000L,
            type = TransactionType.GIVE,
            note = "Cancelled transaction",
            createdAt = originalCreatedAt + 2000L,
            updatedAt = originalCreatedAt + 2000L,
            isDeleted = true,
            syncPending = false
        )
        fakeTransactionDao.insertTransaction(txActive)
        fakeTransactionDao.insertTransaction(txTombstone)

        // Cloud ledger already has this person and active transaction
        val remotePerson = PersonJson(
            cloudId = cloudPersonId,
            localId = personId,
            name = "Staff Supplied Name",
            phone = "9431733886",
            contactId = null,
            createdAt = "2024-10-15T00:00:00Z",
            updatedAt = "2024-10-15T00:00:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        val remoteTx = TransactionJson(
            id = activeTxId,
            personCloudId = cloudPersonId,
            amountCents = 100000L,
            type = "RECEIVE",
            note = "Active credit",
            imageFileId = null,
            createdAt = "2024-10-15T00:10:00Z",
            updatedAt = "2024-10-15T00:10:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        seedRemoteLedger(listOf(remotePerson), listOf(remoteTx))

        // Owner address book resolves to "Verified Owner Name"
        fakeResolver.addContact("9431733886", "Verified Owner Name")

        val engine = createSyncEngine()
        val summary = engine.executeSync(testToken, testEmail)

        assertTrue("Sync must succeed. Errors: ${summary.networkErrors}", summary.success)

        val updatedPerson = fakePersonDao.getPersonById(personId)
        assertNotNull(updatedPerson)
        // Only displayed name updated
        assertEquals("Verified Owner Name", updatedPerson!!.name)
        // Creation date untouched
        assertEquals(originalCreatedAt, updatedPerson.createdAt)
        // Phone untouched
        assertEquals("9431733886", updatedPerson.phone)

        // Verify transactions: foreign keys, amounts, balances, tombstones untouched
        val activeTxAfter = fakeTransactionDao.getTransactionById(activeTxId)
        assertNotNull(activeTxAfter)
        assertEquals(personId, activeTxAfter!!.personId)
        assertEquals(100000L, activeTxAfter.amountCents)
        assertFalse(activeTxAfter.isDeleted)

        val tombstoneTxAfter = fakeTransactionDao.getTransactionById(tombstoneTxId)
        assertNotNull(tombstoneTxAfter)
        assertEquals(personId, tombstoneTxAfter!!.personId)
        assertEquals(30000L, tombstoneTxAfter.amountCents)
        assertTrue("Tombstone status must be preserved", tombstoneTxAfter.isDeleted)
    }

    // ── 8. Local backup reflects changed name and survives safe restore ──────

    @Test
    fun localBackup_reflectsChangedName_andSurvivesSafeRestore() = runBlocking {
        val staffCloudId = UUID.randomUUID().toString()
        val remotePerson = PersonJson(
            cloudId = staffCloudId,
            localId = 108L,
            name = "Staff Name 8",
            phone = "9876543210",
            contactId = null,
            createdAt = "2024-10-25T02:00:00Z",
            updatedAt = "2024-10-25T02:00:00Z",
            updatedBy = "s1",
            isDeleted = false
        )
        seedRemoteLedger(listOf(remotePerson), emptyList())

        fakeResolver.addContact("9876543210", "Owner VIP Client")

        val engine = createSyncEngine()
        val summary = engine.executeSync(testToken, testEmail)
        assertTrue("Sync must succeed. Errors: ${summary.networkErrors}", summary.success)

        // Check local database
        val localPerson = fakePersonDao.getPersonByCloudId(staffCloudId)
        assertNotNull(localPerson)
        assertEquals("Owner VIP Client", localPerson!!.name)

        // Check local partitioned backup: restore into a fresh clean FakePersonDao
        val freshPersonDao = FakePersonDao()
        val freshTxDao = FakeTransactionDao()
        val freshRuleDao = FakeRecurringRuleDao()

        val restoreResult = backupManager.restoreToDaos(
            personDao = freshPersonDao,
            transactionDao = freshTxDao,
            recurringRuleDao = freshRuleDao
        )

        assertEquals("1 person must be restored", 1, restoreResult.restoredPersons)
        val restoredPersons = freshPersonDao.getAllPersonsList()
        assertEquals(1, restoredPersons.size)
        assertEquals("Owner VIP Client", restoredPersons.first().name)
        assertEquals("9876543210", restoredPersons.first().phone)
    }
}
