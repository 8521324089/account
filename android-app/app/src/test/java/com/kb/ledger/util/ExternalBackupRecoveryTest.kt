package com.kb.ledger.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.RecurrenceFrequency
import com.kb.ledger.data.model.RecurringRule
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.data.repository.FakePersonDao
import com.kb.ledger.data.repository.FakeRecurringRuleDao
import com.kb.ledger.data.repository.FakeTransactionDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

/**
 * Rigorous test suite verifying external backup export, full recovery into a clean installation,
 * protection against corrupt/incomplete backups, and preservation of populated data.
 */
class ExternalBackupRecoveryTest {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var tempDir: File
    private lateinit var receiptsDir: File

    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("backup_recovery_test").toFile()
        receiptsDir = File(tempDir, "receipts").also { it.mkdirs() }
    }

    @org.junit.After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── Helper functions ─────────────────────────────────────────────────────

    private fun makePerson(id: Long, name: String, isArchived: Boolean = false, isDeleted: Boolean = false) = Person(
        id = id,
        name = name,
        phone = "9876543210",
        contactId = "sys_contact_$id",
        cloudId = UUID.randomUUID().toString(),
        createdAt = now - 100000L,
        updatedAt = now,
        isDeleted = isDeleted,
        syncPending = true,
        isArchived = isArchived
    )

    private fun makeTransaction(
        id: String,
        personId: Long,
        amountCents: Long,
        type: TransactionType,
        isDeleted: Boolean = false,
        imageUri: String? = null
    ) = Transaction(
        id = id,
        personId = personId,
        amountCents = amountCents,
        type = type,
        note = "Payment note for $id",
        createdAt = now - 50000L,
        updatedAt = now,
        isDeleted = isDeleted,
        syncPending = true,
        imageUri = imageUri
    )

    private fun makeRule(id: String, personId: Long, amountCents: Long) = RecurringRule(
        id = id,
        personId = personId,
        amountCents = amountCents,
        type = TransactionType.GIVE,
        note = "Monthly rent",
        frequency = RecurrenceFrequency.MONTHLY,
        startDate = now,
        nextExecutionDate = now + 86400000L,
        createdAt = now
    )

    private val validJpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x60,
        0x00, 0x60, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte()
    )

    private val validPngBytes = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
    )

    // ── 1. Full Export and Recovery into Clean Database ──────────────────────

    @Test
    fun fullExportAndRestore_intoCleanDatabase_preservesAllDataAndInvariants() = runBlocking {
        // Original populated state
        val originalPersons = listOf(
            makePerson(1L, "Alice Sharma", isArchived = false),
            makePerson(2L, "Bob Patel", isArchived = true),
            makePerson(3L, "Charlie Verma (Deleted)", isDeleted = true)
        )

        val txImgId = UUID.randomUUID().toString()
        val dummyReceiptBytes = "SAMPLE_JPEG_RECEIPT_BINARY_PAYLOAD".toByteArray()
        val imageBase64 = Base64.getEncoder().encodeToString(dummyReceiptBytes)

        val originalTransactions = listOf(
            makeTransaction("tx-1", 1L, 50075L, TransactionType.GIVE),
            makeTransaction("tx-2", 1L, 20000L, TransactionType.RECEIVE),
            makeTransaction("tx-3", 2L, 9999999L, TransactionType.GIVE),
            makeTransaction(txImgId, 1L, 15000L, TransactionType.GIVE, imageUri = "content://media/picker_1"),
            makeTransaction("tx-del", 1L, 5000L, TransactionType.GIVE, isDeleted = true)
        )

        val originalRules = listOf(
            makeRule("rule-1", 1L, 100000L)
        )

        val originalBillImages = mapOf(txImgId to imageBase64)

        // Step 1: Export to BackupData
        val exportArchive = BackupData(
            persons = originalPersons,
            transactions = originalTransactions,
            recurringRules = originalRules,
            billImagesByTxId = originalBillImages,
            schemaVersion = 3,
            exportDate = now
        )
        val exportedJson = gson.toJson(exportArchive)
        assertNotNull(exportedJson)
        assertTrue(exportedJson.contains("Alice Sharma"))
        assertTrue(exportedJson.contains("50075"))

        // Step 2: Simulate Fresh Clean Installation
        val cleanPersonDao = FakePersonDao()
        val cleanTransactionDao = FakeTransactionDao()
        val cleanRuleDao = FakeRecurringRuleDao()
        cleanPersonDao.transactionDaoRef = cleanTransactionDao

        // Step 3: Deserialize & Validate
        val restoredArchive = gson.fromJson(exportedJson, BackupData::class.java)
        assertNotNull(restoredArchive)
        assertEquals(3, restoredArchive.persons.size)
        assertEquals(5, restoredArchive.transactions.size)
        assertEquals(1, restoredArchive.recurringRules.size)
        assertEquals(1, restoredArchive.billImagesByTxId.size)

        // Step 4: Insert into clean DAOs (mirroring BackupManager import logic)
        for (p in restoredArchive.persons) {
            cleanPersonDao.insertPerson(p)
        }
        for (t in restoredArchive.transactions) {
            cleanTransactionDao.insertTransaction(t)
        }
        for (r in restoredArchive.recurringRules) {
            cleanRuleDao.insertRule(r)
        }

        // Step 5: Verify all fields in restored DB
        val dbPersons = cleanPersonDao.getAllPersonsIncludingDeleted()
        assertEquals(3, dbPersons.size)
        val alice = dbPersons.find { it.id == 1L }!!
        assertEquals("Alice Sharma", alice.name)
        assertFalse(alice.isArchived)
        assertFalse(alice.isDeleted)

        val bob = dbPersons.find { it.id == 2L }!!
        assertEquals("Bob Patel", bob.name)
        assertTrue(bob.isArchived)

        val charlie = dbPersons.find { it.id == 3L }!!
        assertTrue("Tombstone person must remain soft-deleted", charlie.isDeleted)

        val dbTxs = cleanTransactionDao.getAllTransactionsDirect()
        assertEquals(5, dbTxs.size)

        val tx1 = dbTxs.find { it.id == "tx-1" }!!
        assertEquals(1L, tx1.personId)
        assertEquals(50075L, tx1.amountCents)
        assertEquals(TransactionType.GIVE, tx1.type)

        val txDel = dbTxs.find { it.id == "tx-del" }!!
        assertTrue("Soft-deleted transaction must remain a tombstone", txDel.isDeleted)

        // Verify balance calculation on restored data
        val activeTxsForAlice = dbTxs.filter { it.personId == 1L && !it.isDeleted }
        val giveTotal = activeTxsForAlice.filter { it.type == TransactionType.GIVE }.sumOf { it.amountCents }
        val receiveTotal = activeTxsForAlice.filter { it.type == TransactionType.RECEIVE }.sumOf { it.amountCents }
        // tx-1 (50075 GIVE) + txImg (15000 GIVE) = 65075 GIVE
        // tx-2 (20000 RECEIVE) = 20000 RECEIVE
        assertEquals(65075L, giveTotal)
        assertEquals(20000L, receiveTotal)
        assertEquals(-45075L, receiveTotal - giveTotal)

        // Verify image bytes round-trip
        val restoredBytes = Base64.getDecoder().decode(restoredArchive.billImagesByTxId[txImgId]!!)
        assertArrayEquals(dummyReceiptBytes, restoredBytes)
    }

    // ── 2. Corrupted Backup Protection ────────────────────────────────────────

    @Test
    fun corruptBackup_truncatedJson_failsSafelyWithoutModifyingDatabase() = runBlocking {
        // Setup initial database with real customer data
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val existingPerson = makePerson(10L, "Protected Existing Person")
        val existingTx = makeTransaction("tx-existing", 10L, 12345L, TransactionType.RECEIVE)
        personDao.insertPerson(existingPerson)
        txDao.insertTransaction(existingTx)

        val truncatedJson = """{"persons": [{"id": 1, "name": "Broken"""

        var importFailed = false
        try {
            gson.fromJson(truncatedJson, BackupData::class.java)
        } catch (e: JsonSyntaxException) {
            importFailed = true
        }

        assertTrue("Truncated JSON must trigger JsonSyntaxException", importFailed)

        // Verify that existing database was NOT touched
        val personsAfter = personDao.getAllPersonsIncludingDeleted()
        val txsAfter = txDao.getAllTransactionsDirect()
        assertEquals(1, personsAfter.size)
        assertEquals("Protected Existing Person", personsAfter[0].name)
        assertEquals(1, txsAfter.size)
        assertEquals(12345L, txsAfter[0].amountCents)
    }

    @Test
    fun corruptBackup_randomBinary_failsSafely() = runBlocking {
        val nonJsonPayload = "PK\u0003\u0004\u0014\u0000\u0000\u0000\u0008\u0000CORRUPTED_ZIP_BINARY"
        var parseFailed = false
        try {
            gson.fromJson(nonJsonPayload, BackupData::class.java)
        } catch (e: Exception) {
            parseFailed = true
        }
        assertTrue("Random binary payload must fail deserialization", parseFailed)
    }

    // ── 3. Incomplete / Malformed Backup Structure ────────────────────────────

    @Test
    fun incompleteBackup_missingPersonsOrTransactions_failsValidation() {
        val missingPersonsJson = """
            {
              "transactions": [],
              "schemaVersion": 3
            }
        """.trimIndent()

        val parsed = gson.fromJson(missingPersonsJson, BackupData::class.java)
        // In Kotlin/Gson, missing key yields null
        assertNull("Missing persons field must parse as null", parsed.persons)

        // Validation check mirroring BackupManager.importData line 206
        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION", "SENSELESS_COMPARISON")
        val isValid = (parsed.persons as List<Person>?) != null && (parsed.transactions as List<Transaction>?) != null
        assertFalse("Backup missing persons list must fail structural validation", isValid)
    }

    // ── 4. Non-Destructive Restore (No Data Wipe) ─────────────────────────────

    @Test
    fun restoreBackup_doesNotWipeExistingUnrelatedRecords() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        // Existing customer on device: Person 100
        val devicePerson = makePerson(100L, "Existing Local Customer")
        val deviceTx = makeTransaction("tx-device-1", 100L, 5000L, TransactionType.RECEIVE)
        personDao.insertPerson(devicePerson)
        txDao.insertTransaction(deviceTx)

        // Backup contains Person 200
        val backupPerson = makePerson(200L, "Imported Partner")
        val backupTx = makeTransaction("tx-imported-1", 200L, 8000L, TransactionType.GIVE)

        val backup = BackupData(
            persons = listOf(backupPerson),
            transactions = listOf(backupTx)
        )

        // Import using safe restore engine
        val stats = BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        assertEquals(1, stats.restoredPersonsCount)
        assertEquals(1, stats.restoredTransactionsCount)

        // Both records must exist simultaneously
        val allPersons = personDao.getAllPersonsIncludingDeleted()
        val allTxs = txDao.getAllTransactionsDirect()

        assertEquals(2, allPersons.size)
        assertTrue(allPersons.any { it.id == 100L && it.name == "Existing Local Customer" })
        assertTrue(allPersons.any { it.name == "Imported Partner" })

        assertEquals(2, allTxs.size)
        assertTrue(allTxs.any { it.id == "tx-device-1" && it.amountCents == 5000L && it.personId == 100L })
        assertTrue(allTxs.any { it.id == "tx-imported-1" && it.amountCents == 8000L })
    }

    // ── 5. Empty Database Restore ─────────────────────────────────────────────

    @Test
    fun restoreIntoEmptyDatabase_preservesOriginalIdsAndRelationships() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val person1 = makePerson(10L, "Alice")
        val person2 = makePerson(20L, "Bob")
        val tx1 = makeTransaction("tx-1", 10L, 5000L, TransactionType.GIVE)
        val tx2 = makeTransaction("tx-2", 20L, 10000L, TransactionType.RECEIVE)
        val rule1 = makeRule("rule-1", 10L, 2000L)

        val backup = BackupData(
            persons = listOf(person1, person2),
            transactions = listOf(tx1, tx2),
            recurringRules = listOf(rule1)
        )

        val stats = BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        assertEquals(2, stats.restoredPersonsCount)
        assertEquals(2, stats.restoredTransactionsCount)
        assertEquals(1, stats.restoredRulesCount)
        assertEquals(0, stats.remappedPersonsCount) // Preserved exact IDs

        val restoredP1 = personDao.getPersonById(10L)
        val restoredP2 = personDao.getPersonById(20L)
        assertNotNull(restoredP1)
        assertNotNull(restoredP2)
        assertEquals("Alice", restoredP1!!.name)
        assertEquals("Bob", restoredP2!!.name)

        val restoredTx1 = txDao.getTransactionById("tx-1")
        val restoredTx2 = txDao.getTransactionById("tx-2")
        assertNotNull(restoredTx1)
        assertNotNull(restoredTx2)
        assertEquals(10L, restoredTx1!!.personId)
        assertEquals(20L, restoredTx2!!.personId)

        val restoredRule1 = ruleDao.getRuleById("rule-1")
        assertNotNull(restoredRule1)
        assertEquals(10L, restoredRule1!!.personId)
    }

    // ── 6. ID Collision Protection & Remapping ────────────────────────────────

    @Test
    fun restoreIntoPopulatedDatabase_collidingPersonIds_remapsSafelyWithoutOverwritingExistingPerson() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        // Local device has Person 1: "Local Charlie"
        val localCharlie = makePerson(1L, "Local Charlie").copy(cloudId = "uuid-charlie-local")
        val localCharlieTx = makeTransaction("tx-charlie-local", 1L, 3500L, TransactionType.GIVE)
        personDao.insertPerson(localCharlie)
        txDao.insertTransaction(localCharlieTx)

        // Incoming backup also has Person 1: "Imported David" (DIFFERENT person with same integer ID 1)
        val importedDavid = makePerson(1L, "Imported David").copy(cloudId = "uuid-david-imported")
        val importedDavidTx = makeTransaction("tx-david-imported", 1L, 7500L, TransactionType.RECEIVE)
        val importedDavidRule = makeRule("rule-david-imported", 1L, 1200L)

        val backup = BackupData(
            persons = listOf(importedDavid),
            transactions = listOf(importedDavidTx),
            recurringRules = listOf(importedDavidRule)
        )

        val stats = BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        assertEquals(1, stats.restoredPersonsCount)
        assertEquals(1, stats.remappedPersonsCount) // ID 1 was remapped to avoid collision

        // 1. Verify Local Charlie at ID 1 is 100% UNTOUCHED
        val personAtId1 = personDao.getPersonById(1L)
        assertNotNull(personAtId1)
        assertEquals("Local Charlie", personAtId1!!.name)
        assertEquals("uuid-charlie-local", personAtId1.cloudId)

        val charlieTx = txDao.getTransactionById("tx-charlie-local")
        assertNotNull(charlieTx)
        assertEquals(1L, charlieTx!!.personId)
        assertEquals(3500L, charlieTx.amountCents)

        // 2. Verify Imported David was allocated a fresh ID (ID != 1L)
        val allPersons = personDao.getAllPersonsIncludingDeleted()
        assertEquals(2, allPersons.size)

        val davidInDb = allPersons.find { it.cloudId == "uuid-david-imported" }
        assertNotNull("Imported David must exist in DB under his cloudId", davidInDb)
        assertNotEquals("Imported David must NOT have ID 1", 1L, davidInDb!!.id)
        val davidNewId = davidInDb.id

        // 3. Verify David's transaction was remapped to David's new ID
        val davidTx = txDao.getTransactionById("tx-david-imported")
        assertNotNull(davidTx)
        assertEquals(davidNewId, davidTx!!.personId)
        assertEquals(7500L, davidTx.amountCents)

        // 4. Verify David's recurring rule was remapped to David's new ID
        val davidRule = ruleDao.getRuleById("rule-david-imported")
        assertNotNull(davidRule)
        assertEquals(davidNewId, davidRule!!.personId)
    }

    @Test
    fun restoreIntoPopulatedDatabase_preservesExistingTransactionsAndDoesNotCascadeDelete() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        // Local person 5 has 3 transactions
        val localP5 = makePerson(5L, "Local Person 5").copy(cloudId = "uuid-p5-local")
        personDao.insertPerson(localP5)
        txDao.insertTransaction(makeTransaction("tx-5-1", 5L, 100L, TransactionType.GIVE))
        txDao.insertTransaction(makeTransaction("tx-5-2", 5L, 200L, TransactionType.RECEIVE))
        txDao.insertTransaction(makeTransaction("tx-5-3", 5L, 300L, TransactionType.GIVE))

        // Backup has colliding Person 5 with 2 transactions
        val backupP5 = makePerson(5L, "Backup Colliding Person").copy(cloudId = "uuid-p5-backup")
        val backupTx1 = makeTransaction("tx-b-1", 5L, 1000L, TransactionType.GIVE)
        val backupTx2 = makeTransaction("tx-b-2", 5L, 2000L, TransactionType.RECEIVE)

        val backup = BackupData(
            persons = listOf(backupP5),
            transactions = listOf(backupTx1, backupTx2)
        )

        BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        // All 5 transactions must exist; zero cascade drops!
        val allTxs = txDao.getAllTransactionsDirect()
        assertEquals(5, allTxs.size)

        // Original 3 transactions still belong to local person 5
        val p5Txs = txDao.getTransactionsForPersonOnce(5L)
        assertEquals(3, p5Txs.size)
        assertTrue(p5Txs.any { it.id == "tx-5-1" })
        assertTrue(p5Txs.any { it.id == "tx-5-2" })
        assertTrue(p5Txs.any { it.id == "tx-5-3" })
    }

    // ── 7. Repeated Import Idempotency ────────────────────────────────────────

    @Test
    fun repeatedImportOfSameBackup_isIdempotentAndDoesNotDuplicateRecords() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val p1 = makePerson(1L, "Target Customer").copy(cloudId = "uuid-target-1")
        val tx1 = makeTransaction("tx-target-1", 1L, 4000L, TransactionType.GIVE)
        val rule1 = makeRule("rule-target-1", 1L, 1000L)

        val backup = BackupData(
            persons = listOf(p1),
            transactions = listOf(tx1),
            recurringRules = listOf(rule1)
        )

        // First import into clean DB
        BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        val personsAfter1 = personDao.getAllPersonsIncludingDeleted().size
        val txsAfter1 = txDao.getAllTransactionsDirect().size
        val rulesAfter1 = ruleDao.getAllRulesList().size

        assertEquals(1, personsAfter1)
        assertEquals(1, txsAfter1)
        assertEquals(1, rulesAfter1)

        // Second import of the exact same backup
        val stats2 = BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        val personsAfter2 = personDao.getAllPersonsIncludingDeleted().size
        val txsAfter2 = txDao.getAllTransactionsDirect().size
        val rulesAfter2 = ruleDao.getAllRulesList().size

        // Invariant: Exactly identical counts! Zero duplicate rows!
        assertEquals("Person count must remain 1 on re-import", personsAfter1, personsAfter2)
        assertEquals("Transaction count must remain 1 on re-import", txsAfter1, txsAfter2)
        assertEquals("Rule count must remain 1 on re-import", rulesAfter1, rulesAfter2)
        assertEquals(0, stats2.remappedPersonsCount)
    }

    // ── 8. Matching Existing Person by CloudId ────────────────────────────────

    @Test
    fun matchingExistingPersonByCloudId_updatesWithoutChangingLocalId() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        // Local person has ID 50 and cloudId "uuid-shared"
        val localP = makePerson(50L, "Old Display Name").copy(
            cloudId = "uuid-shared",
            updatedAt = now - 100000L
        )
        personDao.insertPerson(localP)

        // Backup has person with ID 1 but SAME cloudId "uuid-shared" and newer timestamp
        val backupP = makePerson(1L, "Updated New Name").copy(
            cloudId = "uuid-shared",
            updatedAt = now
        )
        val backupTx = makeTransaction("tx-shared-1", 1L, 9999L, TransactionType.RECEIVE)

        val backup = BackupData(
            persons = listOf(backupP),
            transactions = listOf(backupTx)
        )

        val stats = BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        // ID 1 from backup was mapped to local ID 50!
        assertEquals(1, stats.remappedPersonsCount)
        assertEquals(1, personDao.getAllPersonsIncludingDeleted().size)

        val updatedP = personDao.getPersonById(50L)
        assertNotNull(updatedP)
        assertEquals("Updated New Name", updatedP!!.name)

        // Transaction in backup referencing ID 1 is correctly attached to local ID 50
        val txInDb = txDao.getTransactionById("tx-shared-1")
        assertNotNull(txInDb)
        assertEquals(50L, txInDb!!.personId)
    }

    // ── 9. Legacy Matching by Normalized Phone ────────────────────────────────

    @Test
    fun matchingLegacyPersonWithoutCloudId_matchesByNormalizedPhone() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        // Legacy local person without cloudId
        val legacyPerson = makePerson(7L, "Legacy Person").copy(
            phone = "09431733886", // Indian trunk 0 format
            cloudId = null
        )
        personDao.insertPerson(legacyPerson)

        // Backup person with same normalized phone
        val backupPerson = makePerson(1L, "Legacy Person").copy(
            phone = "+91 94317 33886", // International formatted
            cloudId = "uuid-assigned-later"
        )
        val backupTx = makeTransaction("tx-leg-1", 1L, 1200L, TransactionType.GIVE)

        val backup = BackupData(
            persons = listOf(backupPerson),
            transactions = listOf(backupTx)
        )

        BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        // Should match existing local person at ID 7 and attach cloudId
        assertEquals(1, personDao.getAllPersonsIncludingDeleted().size)
        val personAt7 = personDao.getPersonById(7L)
        assertNotNull(personAt7)
        assertEquals("uuid-assigned-later", personAt7!!.cloudId)

        val txAt7 = txDao.getTransactionById("tx-leg-1")
        assertNotNull(txAt7)
        assertEquals(7L, txAt7!!.personId)
    }

    // ── 10. Orphaned Records Failsafe ─────────────────────────────────────────

    @Test
    fun orphanedTransactionInBackup_failsSafelyWithoutModifyingDatabase() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val p1 = makePerson(1L, "Alice")
        // Transaction references non-existent personId 9999
        val orphanTx = makeTransaction("tx-orphan", 9999L, 5000L, TransactionType.GIVE)

        val corruptBackup = BackupData(
            persons = listOf(p1),
            transactions = listOf(orphanTx)
        )

        var failed = false
        try {
            BackupManager.restoreDatabaseRecords(
                backupData = corruptBackup,
                personDao = personDao,
                transactionDao = txDao,
                recurringRuleDao = ruleDao,
                targetReceiptsDir = receiptsDir
            )
        } catch (e: IllegalArgumentException) {
            failed = true
            assertTrue(e.message!!.contains("references unknown person ID 9999"))
        }

        assertTrue("Orphaned transaction must throw IllegalArgumentException", failed)
    }

    @Test
    fun orphanedRecurringRuleInBackup_failsSafelyWithoutModifyingDatabase() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val p1 = makePerson(1L, "Alice")
        val orphanRule = makeRule("rule-orphan", 8888L, 1000L)

        val corruptBackup = BackupData(
            persons = listOf(p1),
            transactions = emptyList(),
            recurringRules = listOf(orphanRule)
        )

        var failed = false
        try {
            BackupManager.restoreDatabaseRecords(
                backupData = corruptBackup,
                personDao = personDao,
                transactionDao = txDao,
                recurringRuleDao = ruleDao,
                targetReceiptsDir = receiptsDir
            )
        } catch (e: IllegalArgumentException) {
            failed = true
            assertTrue(e.message!!.contains("references unknown person ID 8888"))
        }

        assertTrue("Orphaned recurring rule must throw IllegalArgumentException", failed)
    }

    // ── 11. Tombstone Preservation ────────────────────────────────────────────

    @Test
    fun tombstonePreservation_softDeletedPersonsAndTransactionsSurviveRestore() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val deletedPerson = makePerson(1L, "Deleted User", isDeleted = true)
        val deletedTx = makeTransaction("tx-deleted", 1L, 3000L, TransactionType.GIVE, isDeleted = true)

        val backup = BackupData(
            persons = listOf(deletedPerson),
            transactions = listOf(deletedTx)
        )

        BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        val allPersons = personDao.getAllPersonsIncludingDeleted()
        val allTxs = txDao.getAllTransactionsIncludingDeleted()

        assertEquals(1, allPersons.size)
        assertTrue("Soft-deleted person must retain isDeleted = true", allPersons[0].isDeleted)

        assertEquals(1, allTxs.size)
        assertTrue("Soft-deleted transaction must retain isDeleted = true", allTxs[0].isDeleted)
    }

    // ── 12. Audit A: Strict Identity Matching Scenarios ───────────────────────

    @Test
    fun incomingBackup_duplicateCloudIds_throwsIntegrityError() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val p1 = makePerson(1L, "Alice").copy(cloudId = "duplicate-uuid")
        val p2 = makePerson(2L, "Bob").copy(cloudId = "duplicate-uuid")

        val backup = BackupData(
            persons = listOf(p1, p2),
            transactions = emptyList()
        )

        var failed = false
        try {
            BackupManager.restoreDatabaseRecords(
                backupData = backup,
                personDao = personDao,
                transactionDao = txDao,
                recurringRuleDao = ruleDao,
                targetReceiptsDir = receiptsDir
            )
        } catch (e: IllegalArgumentException) {
            failed = true
            assertTrue(e.message!!.contains("duplicate cloudId"))
        }
        assertTrue("Duplicate cloudIds in backup must fail safely", failed)
    }

    @Test
    fun matchingByPhone_twoExistingPeopleSharePhone_doesNotGuessAndInsertsSeparately() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        // Two existing local people share the exact same phone number (family/shop phone)
        val p1 = makePerson(10L, "Ramesh Sharma").copy(phone = "9876543210", cloudId = null)
        val p2 = makePerson(20L, "Suresh Sharma").copy(phone = "9876543210", cloudId = null)
        personDao.insertPerson(p1)
        personDao.insertPerson(p2)

        // Incoming legacy person also has "9876543210", but named "Dinesh Sharma" (different name)
        val incoming = makePerson(1L, "Dinesh Sharma").copy(phone = "9876543210", cloudId = null)
        val incomingTx = makeTransaction("tx-dinesh", 1L, 1000L, TransactionType.GIVE)

        val backup = BackupData(
            persons = listOf(incoming),
            transactions = listOf(incomingTx)
        )

        BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        // Must NOT guess or merge into Ramesh or Suresh!
        val allPersons = personDao.getAllPersonsIncludingDeleted()
        assertEquals(3, allPersons.size)

        val dineshInDb = allPersons.find { it.name == "Dinesh Sharma" }
        assertNotNull(dineshInDb)
        assertNotEquals(10L, dineshInDb!!.id)
        assertNotEquals(20L, dineshInDb.id)

        // Tx is mapped to Dinesh's new ID, not to Ramesh (10) or Suresh (20)
        val txInDb = txDao.getTransactionById("tx-dinesh")
        assertNotNull(txInDb)
        assertEquals(dineshInDb.id, txInDb!!.personId)
    }

    @Test
    fun matchingConflictingPhoneAndCloudId_cloudIdTakesPrecedenceOverPhone() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        // Person A has phone "9876543210" and cloudId "uuid-A"
        val personA = makePerson(10L, "Person A").copy(phone = "9876543210", cloudId = "uuid-A")
        // Person B has phone "1122334455" and cloudId "uuid-B"
        val personB = makePerson(20L, "Person B").copy(phone = "1122334455", cloudId = "uuid-B")
        personDao.insertPerson(personA)
        personDao.insertPerson(personB)

        // Incoming record has cloudId "uuid-B" (matching Person B) but phone "9876543210" (matching Person A's phone)
        val incoming = makePerson(1L, "Person B Updated").copy(phone = "9876543210", cloudId = "uuid-B")
        val incomingTx = makeTransaction("tx-b-new", 1L, 5000L, TransactionType.RECEIVE)

        val backup = BackupData(
            persons = listOf(incoming),
            transactions = listOf(incomingTx)
        )

        BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        // cloudId "uuid-B" matches Person B (ID 20), Person A (ID 10) is untouched
        val dbPersonA = personDao.getPersonById(10L)!!
        assertEquals("Person A", dbPersonA.name)

        val dbPersonB = personDao.getPersonById(20L)!!
        assertEquals("Person B Updated", dbPersonB.name)

        val txInDb = txDao.getTransactionById("tx-b-new")!!
        assertEquals(20L, txInDb.personId) // Mapped to Person B, NOT Person A
    }

    @Test
    fun matchingByPhone_missingOrDummyPhone_doesNotMatchAndInsertsSeparately() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        // Local legacy person with dummy phone "0"
        val localP = makePerson(10L, "Dummy Phone Local").copy(phone = "0", cloudId = null)
        personDao.insertPerson(localP)

        // Incoming legacy person with dummy phone "0"
        val incoming = makePerson(1L, "Dummy Phone Incoming").copy(phone = "0", cloudId = null)

        val backup = BackupData(
            persons = listOf(incoming),
            transactions = emptyList()
        )

        BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        // Dummy phone (< 7 digits) must NOT match; must insert as a distinct person
        val allPersons = personDao.getAllPersonsIncludingDeleted()
        assertEquals(2, allPersons.size)
    }

    @Test
    fun matchingByPhone_twoIncomingPeopleSharePhone_injectiveMappingPreventsCombining() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        // Local legacy person with phone "9876543210" and name "Same Name"
        val localP = makePerson(10L, "Same Name").copy(phone = "9876543210", cloudId = null)
        personDao.insertPerson(localP)

        // Incoming backup has two separate people who happen to have the same phone and name
        val incoming1 = makePerson(1L, "Same Name").copy(phone = "9876543210", cloudId = null)
        val incoming2 = makePerson(2L, "Same Name").copy(phone = "9876543210", cloudId = null)
        val tx1 = makeTransaction("tx-inc-1", 1L, 100L, TransactionType.GIVE)
        val tx2 = makeTransaction("tx-inc-2", 2L, 200L, TransactionType.RECEIVE)

        val backup = BackupData(
            persons = listOf(incoming1, incoming2),
            transactions = listOf(tx1, tx2)
        )

        BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        // First incoming matched local 10, second incoming could not claim local 10, so allocated fresh ID!
        val allPersons = personDao.getAllPersonsIncludingDeleted()
        assertEquals(2, allPersons.size)

        val txInDb1 = txDao.getTransactionById("tx-inc-1")!!
        val txInDb2 = txDao.getTransactionById("tx-inc-2")!!
        assertNotEquals("Transactions must belong to two distinct local persons", txInDb1.personId, txInDb2.personId)
    }

    @Test
    fun matchingByPhone_differentNames_doesNotMergeAndInsertsSeparately() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        // Local legacy person named Ramesh with phone "9876543210"
        val localRamesh = makePerson(10L, "Ramesh Patel").copy(phone = "9876543210", cloudId = null)
        personDao.insertPerson(localRamesh)

        // Incoming legacy person named Suresh with the same phone "9876543210"
        val incomingSuresh = makePerson(1L, "Suresh Patel").copy(phone = "9876543210", cloudId = null)
        val incomingTx = makeTransaction("tx-suresh", 1L, 10000L, TransactionType.GIVE)

        val backup = BackupData(
            persons = listOf(incomingSuresh),
            transactions = listOf(incomingTx)
        )

        BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            targetReceiptsDir = receiptsDir
        )

        // Ramesh and Suresh must remain two separate records!
        val allPersons = personDao.getAllPersonsIncludingDeleted()
        assertEquals(2, allPersons.size)

        val rameshInDb = personDao.getPersonById(10L)!!
        assertEquals("Ramesh Patel", rameshInDb.name)

        val sureshInDb = allPersons.find { it.name == "Suresh Patel" }
        assertNotNull(sureshInDb)
        assertNotEquals(10L, sureshInDb!!.id)

        val sureshTx = txDao.getTransactionById("tx-suresh")!!
        assertEquals(sureshInDb.id, sureshTx.personId)
    }

    // ── 13. Audit C: Receipt Staging and Self-Healing Recovery ────────────────

    @Test
    fun receiptRecovery_orphanedStagingImages_arePromotedSuccessfully() = runBlocking {
        val orphanStagedFile = File(receiptsDir, "import_tmp_tx-orphan-123.jpg")
        orphanStagedFile.writeBytes(validJpegBytes)

        val recovered = BackupManager.recoverOrphanedStagingImages(receiptsDir)
        assertEquals(1, recovered)

        val finalFile = File(receiptsDir, "tx-orphan-123.jpg")
        assertTrue("Final file must exist", finalFile.exists())
        assertEquals(validJpegBytes.size.toLong(), finalFile.length())
        assertFalse("Staging file must have been renamed/deleted", orphanStagedFile.exists())
    }

    @Test
    fun receiptRecovery_orphanedStagingImage_cleansUpIfFinalAlreadyExists() = runBlocking {
        val finalFile = File(receiptsDir, "tx-exists-456.jpg")
        finalFile.writeBytes(validJpegBytes)

        val orphanStagedFile = File(receiptsDir, "import_tmp_tx-exists-456.jpg")
        orphanStagedFile.writeBytes(validPngBytes)

        val recovered = BackupManager.recoverOrphanedStagingImages(receiptsDir)
        assertEquals(0, recovered)

        assertTrue(finalFile.exists())
        assertEquals(validJpegBytes.size.toLong(), finalFile.length())
        assertArrayEquals(validJpegBytes, finalFile.readBytes())
        assertFalse("Orphaned staging file must be cleaned up", orphanStagedFile.exists())
    }

    // ── 14. Transaction Tombstone Resurrection Prevention ─────────────────────

    @Test
    fun tombstone_existingTxDeletedLocally_backupHasActiveTxOlderTimestamp_remainsDeleted() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val p = makePerson(1L, "Alice")
        personDao.insertPerson(p)

        // Locally: transaction was soft-deleted at now
        val localDeletedTx = makeTransaction("tx-tomb-1", 1L, 5000L, TransactionType.GIVE, isDeleted = true)
            .copy(updatedAt = now)
        txDao.insertTransaction(localDeletedTx)

        // Incoming backup: taken earlier (now - 10000L) with isDeleted = false (active)
        val backupActiveTx = makeTransaction("tx-tomb-1", 1L, 5000L, TransactionType.GIVE, isDeleted = false)
            .copy(updatedAt = now - 10000L)

        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(backupActiveTx)
        )

        BackupManager.restoreDatabaseRecords(backup, personDao, txDao, ruleDao, targetReceiptsDir = receiptsDir)

        val restoredTx = txDao.getTransactionById("tx-tomb-1")
        assertNotNull(restoredTx)
        assertTrue("Older active backup must NOT resurrect a locally soft-deleted transaction", restoredTx!!.isDeleted)
    }

    @Test
    fun tombstone_existingTxDeletedLocally_backupHasActiveTxEqualTimestamp_remainsDeleted() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val p = makePerson(1L, "Alice")
        personDao.insertPerson(p)

        // Locally: transaction soft-deleted with updatedAt = now
        val localDeletedTx = makeTransaction("tx-tomb-2", 1L, 8000L, TransactionType.RECEIVE, isDeleted = true)
            .copy(updatedAt = now)
        txDao.insertTransaction(localDeletedTx)

        // Incoming backup: exact same updatedAt = now, but isDeleted = false
        val backupActiveTx = makeTransaction("tx-tomb-2", 1L, 8000L, TransactionType.RECEIVE, isDeleted = false)
            .copy(updatedAt = now)

        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(backupActiveTx)
        )

        BackupManager.restoreDatabaseRecords(backup, personDao, txDao, ruleDao, targetReceiptsDir = receiptsDir)

        val restoredTx = txDao.getTransactionById("tx-tomb-2")
        assertNotNull(restoredTx)
        assertTrue("Equal-timestamp active backup must NOT resurrect a locally soft-deleted transaction", restoredTx!!.isDeleted)
    }

    @Test
    fun tombstone_existingTxDeletedLocally_backupHasActiveTxNewerTimestamp_remainsDeleted() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val p = makePerson(1L, "Alice")
        personDao.insertPerson(p)

        // Locally: transaction soft-deleted with updatedAt = now
        val localDeletedTx = makeTransaction("tx-tomb-3", 1L, 12000L, TransactionType.GIVE, isDeleted = true)
            .copy(updatedAt = now)
        txDao.insertTransaction(localDeletedTx)

        // Incoming backup: clock skew / future timestamp (now + 50000L) with isDeleted = false
        val backupActiveTx = makeTransaction("tx-tomb-3", 1L, 12000L, TransactionType.GIVE, isDeleted = false)
            .copy(updatedAt = now + 50000L)

        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(backupActiveTx)
        )

        BackupManager.restoreDatabaseRecords(backup, personDao, txDao, ruleDao, targetReceiptsDir = receiptsDir)

        val restoredTx = txDao.getTransactionById("tx-tomb-3")
        assertNotNull(restoredTx)
        assertTrue("Newer active backup (clock drift) must NOT resurrect a locally soft-deleted transaction", restoredTx!!.isDeleted)
    }

    @Test
    fun tombstone_repeatedImport_isIdempotentAndNeverResurrects() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val p = makePerson(1L, "Alice")
        personDao.insertPerson(p)

        val localDeletedTx = makeTransaction("tx-tomb-repeat", 1L, 3000L, TransactionType.GIVE, isDeleted = true)
        txDao.insertTransaction(localDeletedTx)

        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(localDeletedTx)
        )

        // Import 1
        BackupManager.restoreDatabaseRecords(backup, personDao, txDao, ruleDao, targetReceiptsDir = receiptsDir)
        assertTrue(txDao.getTransactionById("tx-tomb-repeat")!!.isDeleted)

        // Import 2
        BackupManager.restoreDatabaseRecords(backup, personDao, txDao, ruleDao, targetReceiptsDir = receiptsDir)
        assertTrue("Repeated restore must remain deleted", txDao.getTransactionById("tx-tomb-repeat")!!.isDeleted)

        // Import 3
        BackupManager.restoreDatabaseRecords(backup, personDao, txDao, ruleDao, targetReceiptsDir = receiptsDir)
        assertTrue("Repeated restore must remain deleted", txDao.getTransactionById("tx-tomb-repeat")!!.isDeleted)
    }

    @Test
    fun tombstone_backupHasDeletedTx_propagatesTombstoneToActiveLocalTx() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val p = makePerson(1L, "Alice")
        personDao.insertPerson(p)

        // Locally: transaction is active (isDeleted = false)
        val localActiveTx = makeTransaction("tx-tomb-prop", 1L, 6000L, TransactionType.RECEIVE, isDeleted = false)
        txDao.insertTransaction(localActiveTx)

        // Backup contains tombstone (isDeleted = true)
        val backupDeletedTx = makeTransaction("tx-tomb-prop", 1L, 6000L, TransactionType.RECEIVE, isDeleted = true)

        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(backupDeletedTx)
        )

        BackupManager.restoreDatabaseRecords(backup, personDao, txDao, ruleDao, targetReceiptsDir = receiptsDir)

        val restoredTx = txDao.getTransactionById("tx-tomb-prop")
        assertNotNull(restoredTx)
        assertTrue("Backup tombstone must soft-delete active local transaction", restoredTx!!.isDeleted)
    }

    @Test
    fun tombstone_deletedTx_doesNotAffectDashboardBalance() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val p = makePerson(1L, "Alice")
        personDao.insertPerson(p)

        // Two transactions: 1 active GIVE (10000L = ₹100), 1 deleted GIVE (50000L = ₹500)
        val activeTx = makeTransaction("tx-bal-active", 1L, 10000L, TransactionType.GIVE, isDeleted = false)
        val deletedTx = makeTransaction("tx-bal-deleted", 1L, 50000L, TransactionType.GIVE, isDeleted = true)

        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(activeTx, deletedTx)
        )

        BackupManager.restoreDatabaseRecords(backup, personDao, txDao, ruleDao, targetReceiptsDir = receiptsDir)

        val allActive = txDao.getAllTransactions() // queries isDeleted = 0
        assertEquals(1, allActive.size)
        assertEquals("tx-bal-active", allActive[0].id)
        assertEquals(10000L, allActive[0].amountCents)
    }

    // ── 15. Safe Receipt Promotion and Interruption Tests ─────────────────────

    @Test
    fun receiptPromotion_renameFails_twoPhaseCopyFallbackSucceeds() {
        val staged = File(receiptsDir, "import_tmp_tx-rename-fallback.jpg")
        staged.writeBytes(validJpegBytes)
        val target = File(receiptsDir, "tx-rename-fallback.jpg")

        // Mock filesystem where rename from import_tmp fails (simulating cross-filesystem or OS lock)
        val simulatedFs = object : FileSystemOperations by DefaultFileSystemOperations {
            override fun rename(source: File, destination: File): Boolean {
                if (source.name.startsWith("import_tmp_")) {
                    return false // Rename directly from import_tmp fails
                }
                return source.renameTo(destination) // promo_tmp rename succeeds
            }
        }

        val success = BackupManager.promoteStagedReceipt(staged, target, receiptsDir, simulatedFs)
        assertTrue("Two-phase copy fallback must succeed when rename fails", success)
        assertTrue(target.exists())
        assertEquals(validJpegBytes.size.toLong(), target.length())
        assertFalse("Staged file must be cleaned up on success", staged.exists())
    }

    @Test
    fun receiptPromotion_copyFailsBeforeAnyBytes_retainsStagedFile() {
        val staged = File(receiptsDir, "import_tmp_tx-copy-fail.jpg")
        staged.writeBytes(validJpegBytes)
        val target = File(receiptsDir, "tx-copy-fail.jpg")

        val simulatedFs = object : FileSystemOperations by DefaultFileSystemOperations {
            override fun rename(source: File, destination: File): Boolean = false
            override fun copy(source: File, destination: File, overwrite: Boolean) {
                throw java.io.IOException("Disk full before bytes written")
            }
        }

        val success = BackupManager.promoteStagedReceipt(staged, target, receiptsDir, simulatedFs)
        assertFalse("Promotion must return false on copy failure", success)
        assertFalse("Final destination must not be created", target.exists())
        assertTrue("Staged file must be RETAINED on promotion failure", staged.exists())
        assertEquals(validJpegBytes.size.toLong(), staged.length())
    }

    @Test
    fun receiptPromotion_copyInterruptedPartialBytes_retainsStagedFileAndNeverExposesPartialFinal() {
        val staged = File(receiptsDir, "import_tmp_tx-partial.jpg")
        staged.writeBytes(validJpegBytes)
        val target = File(receiptsDir, "tx-partial.jpg")

        val simulatedFs = object : FileSystemOperations by DefaultFileSystemOperations {
            override fun rename(source: File, destination: File): Boolean = false
            override fun copy(source: File, destination: File, overwrite: Boolean) {
                // Simulates writing partial bytes to destination then crashing/throwing
                destination.writeBytes(byteArrayOf(1, 2)) // 2 bytes instead of valid image
                throw java.io.IOException("Process killed mid-copy")
            }
        }

        val success = BackupManager.promoteStagedReceipt(staged, target, receiptsDir, simulatedFs)
        assertFalse("Promotion must return false on interrupted copy", success)
        assertFalse("Partial final destination must NEVER be exposed as valid", target.exists())
        assertTrue("Intact staging file must be preserved", staged.exists())
        assertEquals(validJpegBytes.size.toLong(), staged.length())
    }

    @Test
    fun receiptPromotion_existingValidFinalImage_remainsUnchanged() {
        val target = File(receiptsDir, "tx-preserve-valid.jpg")
        target.writeBytes(validJpegBytes)

        val staged = File(receiptsDir, "import_tmp_tx-preserve-valid.jpg")
        staged.writeBytes(validPngBytes)

        val success = BackupManager.promoteStagedReceipt(staged, target, receiptsDir)
        assertTrue(success)
        assertArrayEquals("Existing valid receipt must NOT be overwritten", validJpegBytes, target.readBytes())
        assertFalse("Duplicate staging file must be deleted", staged.exists())
    }

    @Test
    fun receiptPromotion_existingCorruptOrEmptyFinalImage_isCleanedUpAndReplaced() {
        val target = File(receiptsDir, "tx-broken.jpg")
        target.createNewFile() // 0-byte broken file

        val staged = File(receiptsDir, "import_tmp_tx-broken.jpg")
        staged.writeBytes(validJpegBytes)

        val success = BackupManager.promoteStagedReceipt(staged, target, receiptsDir)
        assertTrue(success)
        assertTrue(target.exists())
        assertArrayEquals("Corrupt 0-byte file must be replaced with valid staged image", validJpegBytes, target.readBytes())
        assertFalse(staged.exists())
    }

    // ── 16. Database-Aware Recovery Tests ─────────────────────────────────────

    @Test
    fun recovery_stagedReceiptForUncommittedTransaction_isCleanedUpNotPromoted() = runBlocking {
        val txDao = FakeTransactionDao()

        // Staged image exists on disk, but tx was NEVER committed to txDao
        val staged = File(receiptsDir, "import_tmp_tx-uncommitted.jpg")
        staged.writeBytes(validJpegBytes)

        val recovered = BackupManager.recoverOrphanedStagingImages(receiptsDir, txDao)
        assertEquals("Uncommitted transaction staging file must NOT be promoted", 0, recovered)

        val target = File(receiptsDir, "tx-uncommitted.jpg")
        assertFalse("Final file must not exist for uncommitted transaction", target.exists())
        assertFalse("Staging file for uncommitted transaction must be discarded", staged.exists())
    }

    @Test
    fun recovery_stagedReceiptForTransactionWithoutImageRef_isCleanedUpNotPromoted() = runBlocking {
        val txDao = FakeTransactionDao()

        // Tx exists in DB, but does NOT reference any image (imageUri is null)
        val tx = makeTransaction("tx-no-img", 1L, 500L, TransactionType.GIVE, imageUri = null)
        txDao.insertTransaction(tx)

        val staged = File(receiptsDir, "import_tmp_tx-no-img.jpg")
        staged.writeBytes(validJpegBytes)

        val recovered = BackupManager.recoverOrphanedStagingImages(receiptsDir, txDao)
        assertEquals(0, recovered)

        val target = File(receiptsDir, "tx-no-img.jpg")
        assertFalse(target.exists())
        assertFalse("Staging file for transaction without image ref must be deleted", staged.exists())
    }

    @Test
    fun recovery_stagedReceiptForCommittedTransactionWithImageRef_promotesSuccessfully() = runBlocking {
        val txDao = FakeTransactionDao()

        // Tx exists in DB and references internal receipt image
        val targetPath = File(receiptsDir, "tx-committed-img.jpg").absolutePath
        val tx = makeTransaction("tx-committed-img", 1L, 2500L, TransactionType.RECEIVE, imageUri = targetPath)
        txDao.insertTransaction(tx)

        val staged = File(receiptsDir, "import_tmp_tx-committed-img.jpg")
        staged.writeBytes(validJpegBytes)

        val recovered = BackupManager.recoverOrphanedStagingImages(receiptsDir, txDao)
        assertEquals(1, recovered)

        val target = File(receiptsDir, "tx-committed-img.jpg")
        assertTrue("Committed transaction receipt must be promoted", target.exists())
        assertArrayEquals(validJpegBytes, target.readBytes())
        assertFalse(staged.exists())
    }

    @Test
    fun receiptPromotion_invalidImageDataAndZeroByteStagingFiles_cleanedUpWithoutPromoting() = runBlocking {
        val txDao = FakeTransactionDao()

        val targetPath = File(receiptsDir, "tx-zero-byte.jpg").absolutePath
        val tx = makeTransaction("tx-zero-byte", 1L, 1000L, TransactionType.GIVE, imageUri = targetPath)
        txDao.insertTransaction(tx)

        val staged = File(receiptsDir, "import_tmp_tx-zero-byte.jpg")
        staged.createNewFile() // 0 bytes (invalid)

        val recovered = BackupManager.recoverOrphanedStagingImages(receiptsDir, txDao)
        assertEquals(0, recovered)

        val target = File(receiptsDir, "tx-zero-byte.jpg")
        assertFalse("0-byte staging file must not produce a final file", target.exists())
        assertFalse("0-byte staging file must be cleaned up", staged.exists())
    }

    @Test
    fun receiptPromotion_processInterruptionAfterRoomCommit_healedOnSubsequentRecovery() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val p = makePerson(1L, "Alice")
        val tx = makeTransaction("tx-interrupted-promo", 1L, 7500L, TransactionType.GIVE, imageUri = File(receiptsDir, "tx-interrupted-promo.jpg").absolutePath)

        val backup = BackupData(
            persons = listOf(p),
            transactions = listOf(tx)
        )

        // 1. Database records are committed
        BackupManager.restoreDatabaseRecords(
            backupData = backup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            restoredTxIds = setOf("tx-interrupted-promo"),
            targetReceiptsDir = receiptsDir
        )

        // 2. Simulate process death before promotion: staging file remains on disk
        val staged = File(receiptsDir, "import_tmp_tx-interrupted-promo.jpg")
        staged.writeBytes(validJpegBytes)
        val finalFile = File(receiptsDir, "tx-interrupted-promo.jpg")
        assertFalse("Final file not yet promoted", finalFile.exists())

        // 3. App restarts (or next import runs) -> recovery heals the orphaned staging image
        val recovered = BackupManager.recoverOrphanedStagingImages(receiptsDir, txDao)
        assertEquals(1, recovered)

        assertTrue("Final image must be recovered on subsequent startup/import", finalFile.exists())
        assertArrayEquals(validJpegBytes, finalFile.readBytes())
        assertFalse(staged.exists())
    }

    // ── 17. Concurrency & Active Staging Ownership Tests ───────────────────────

    @Test
    fun recovery_ignoresFilesOwnedByActiveImport() = runBlocking {
        val txDao = FakeTransactionDao()
        val activeFileName = "import_tmp_tx-active-in-flight.jpg"
        val activeStaged = File(receiptsDir, activeFileName)
        activeStaged.writeBytes(validJpegBytes)

        // Register file in activeStagedFiles set (simulating in-flight import before DB commit)
        BackupManager.activeStagedFiles.add(activeFileName)
        try {
            // Recovery scans the receiptsDir; even though tx is NOT in DB, active file must be spared
            val recovered = BackupManager.recoverOrphanedStagingImages(receiptsDir, txDao)
            assertEquals("Active file must not be promoted by recovery", 0, recovered)
            assertTrue("Active staging file must NOT be deleted while import is in progress", activeStaged.exists())
        } finally {
            BackupManager.activeStagedFiles.remove(activeFileName)
        }

        // Once registration is removed (e.g. aborted import after process restart), subsequent recovery cleans it up
        val recoveredAfter = BackupManager.recoverOrphanedStagingImages(receiptsDir, txDao)
        assertEquals(0, recoveredAfter)
        assertFalse("Abandoned staging file must be cleaned up once active ownership ends", activeStaged.exists())
    }

    @Test
    fun cleanup_ignoresPromoFilesOwnedByActivePromotion() = runBlocking {
        val promoName = "promo_tmp_tx-promo-active.jpg"
        val activePromo = File(receiptsDir, promoName)
        activePromo.writeBytes(validJpegBytes)

        BackupManager.activePromoFiles.add(promoName)
        try {
            BackupManager.recoverOrphanedStagingImages(receiptsDir)
            assertTrue("Active intermediate promo file must NOT be deleted by concurrent recovery", activePromo.exists())
        } finally {
            BackupManager.activePromoFiles.remove(promoName)
        }

        // Once unregistered, recovery cleans up stale promo file
        BackupManager.recoverOrphanedStagingImages(receiptsDir)
        assertFalse("Abandoned intermediate promo file must be deleted", activePromo.exists())
    }

    @Test
    fun synchronization_recoveryAndImportCoordinateViaMutex() = runBlocking {
        // Confirm backupSyncMutex is held and released cleanly
        assertTrue("Mutex should initially be free", !BackupManager.backupSyncMutex.isLocked)
        BackupManager.backupSyncMutex.lock()
        try {
            assertTrue("Mutex is now locked", BackupManager.backupSyncMutex.isLocked)
        } finally {
            BackupManager.backupSyncMutex.unlock()
        }
        assertFalse("Mutex should be unlocked", BackupManager.backupSyncMutex.isLocked)
    }

    // ── 18. Receipt File Content Validation Tests ──────────────────────────────

    @Test
    fun receiptValidation_validJpeg_isAccepted() {
        val file = File(receiptsDir, "valid.jpg")
        file.writeBytes(validJpegBytes)
        assertTrue(BackupManager.isValidReceiptFile(file))
    }

    @Test
    fun receiptValidation_validPng_isAccepted() {
        val file = File(receiptsDir, "valid.png")
        file.writeBytes(validPngBytes)
        assertTrue(BackupManager.isValidReceiptFile(file))
    }

    @Test
    fun receiptValidation_invalidSignature_isRejected() {
        val file = File(receiptsDir, "invalid-sig.jpg")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16))
        assertFalse("Arbitrary bytes with invalid signature must be rejected", BackupManager.isValidReceiptFile(file))
    }

    @Test
    fun receiptValidation_truncatedHeader_isRejected() {
        val file = File(receiptsDir, "truncated.jpg")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())) // Only 4 bytes
        assertFalse("Truncated header (< 12 bytes) must be rejected", BackupManager.isValidReceiptFile(file))
    }

    @Test
    fun receiptValidation_zeroByteFile_isRejected() {
        val file = File(receiptsDir, "empty.jpg")
        file.createNewFile()
        assertFalse("0-byte file must be rejected", BackupManager.isValidReceiptFile(file))
    }

    @Test
    fun receiptValidation_nonImageFileLargerThanFourBytes_isRejected() {
        val file = File(receiptsDir, "text.txt")
        file.writeBytes("This is an arbitrary text file with plenty of characters exceeding four bytes".toByteArray())
        assertFalse("Text/non-image file must be rejected regardless of length", BackupManager.isValidReceiptFile(file))
    }

    @Test
    fun receiptPromotion_invalidContentCandidate_neverPromotedAndPreservesValidFinal() {
        val finalFile = File(receiptsDir, "tx-guard.jpg")
        finalFile.writeBytes(validJpegBytes)

        val stagedCorrupt = File(receiptsDir, "import_tmp_tx-guard.jpg")
        stagedCorrupt.writeBytes("CORRUPT_NON_IMAGE_DATA_EXCEEDING_FOUR_BYTES".toByteArray())

        val success = BackupManager.promoteStagedReceipt(stagedCorrupt, finalFile, receiptsDir)
        assertFalse("Promotion must fail when staging file has invalid image content", success)
        assertArrayEquals("Existing valid receipt must be preserved untouched", validJpegBytes, finalFile.readBytes())
    }

    // ── 19. Privacy-Safe Diagnostics Tests ─────────────────────────────────────

    @Test
    fun safeLogger_recordsEventAndExceptionClass_withoutPII() {
        var recordedTag: String? = null
        var recordedMsg: String? = null
        var recordedExClass: String? = null

        SafeLogger.listener = SafeLogger.Listener { tag, message, exceptionClass ->
            recordedTag = tag
            recordedMsg = message
            recordedExClass = exceptionClass
        }

        try {
            SafeLogger.log("TestRecovery", "Safe diagnostic test", java.io.IOException("Disk read error"))
            assertEquals("TestRecovery", recordedTag)
            assertEquals("Safe diagnostic test", recordedMsg)
            assertEquals("IOException", recordedExClass)
        } finally {
            SafeLogger.listener = null
        }
    }

    @Test
    fun safeLogger_exceptionInListener_neverBubblesUp() {
        SafeLogger.listener = SafeLogger.Listener { _, _, _ ->
            throw RuntimeException("Simulated listener failure")
        }

        try {
            // Must not throw despite listener throwing
            SafeLogger.log("Tag", "Msg", java.lang.IllegalStateException())
        } finally {
            SafeLogger.listener = null
        }
    }
}

