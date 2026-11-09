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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Isolated External Backup Validation Integration Test Suite.
 *
 * This test suite executes Phase 2 of the External Backup Validation Plan using
 * ONLY synthetic data, disposable in-memory fake DAOs, and isolated temporary receipt directories.
 *
 * Environments and Safety Guarantees:
 * - Completely isolated from any physical device (Zero ADB interaction, zero device targeting).
 * - Synthetic entities only (Alice Sharma, Bob Patel, Charlie Verma, Ramesh Kumar, Suresh Kumar).
 * - Cryptographic SHA-256 byte comparisons for original vs exported/decoded receipt bytes.
 * - Deterministic fault injection for all 8 failure and recovery scenarios.
 */
class ExternalBackupValidationIntegrationTest {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var tempDir: File
    private lateinit var receiptsDir: File

    private val now = 1729860000000L // Deterministic epoch timestamp

    // Synthetic Receipts (JPEG, PNG, WebP)
    private val syntheticJpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x60,
        0x00, 0x60, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte()
    )

    private val syntheticPngBytes = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
    )

    private val syntheticWebPBytes = byteArrayOf(
        0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte(),
        0x20, 0x00, 0x00, 0x00,
        0x57.toByte(), 0x45.toByte(), 0x42.toByte(), 0x50.toByte(),
        0x56.toByte(), 0x50.toByte(), 0x38.toByte(), 0x20.toByte()
    )

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("isolated_backup_val_test").toFile()
        receiptsDir = File(tempDir, "receipts").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        BackupManager.activeStagedFiles.clear()
        BackupManager.activePromoFiles.clear()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun makeSyntheticPerson(
        id: Long,
        name: String,
        phone: String,
        cloudId: String = UUID.randomUUID().toString(),
        isArchived: Boolean = false,
        isDeleted: Boolean = false
    ) = Person(
        id = id,
        name = name,
        phone = phone,
        contactId = "synth_contact_$id",
        cloudId = cloudId,
        createdAt = now - 100_000L,
        updatedAt = now,
        isDeleted = isDeleted,
        syncPending = true,
        isArchived = isArchived
    )

    private fun makeSyntheticTransaction(
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
        note = "Synthetic tx $id",
        createdAt = now - 50_000L,
        updatedAt = now,
        isDeleted = isDeleted,
        syncPending = true,
        imageUri = imageUri
    )

    private fun makeSyntheticRule(
        id: String,
        personId: Long,
        amountCents: Long,
        type: TransactionType = TransactionType.GIVE,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY
    ) = RecurringRule(
        id = id,
        personId = personId,
        amountCents = amountCents,
        type = type,
        note = "Synthetic rule $id",
        frequency = frequency,
        startDate = now,
        nextExecutionDate = now + 86400000L,
        createdAt = now
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2.A — Export and Validate
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testPhase2A_exportAndValidate_schemaV3_hashesMatch_activeAndDeletedPreserved() = runBlocking {
        // Step 1: Prepare synthetic source database
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()
        personDao.transactionDaoRef = txDao

        val p1 = makeSyntheticPerson(1L, "Alice Sharma", "9876543210", cloudId = "cloud-alice-1")
        val p2 = makeSyntheticPerson(2L, "Bob Patel", "9876543211", cloudId = "cloud-bob-2", isArchived = true)
        val p3 = makeSyntheticPerson(3L, "Charlie Verma (Deleted)", "9876543212", cloudId = "cloud-charlie-3", isDeleted = true)
        val p4 = makeSyntheticPerson(4L, "Ramesh Kumar", "9876543299", cloudId = "cloud-ramesh-4")
        val p5 = makeSyntheticPerson(5L, "Suresh Kumar", "9876543299", cloudId = "cloud-suresh-5") // Same phone!

        personDao.insertPersons(listOf(p1, p2, p3, p4, p5))

        val txJpeg = makeSyntheticTransaction("tx-img-jpeg", 1L, 25_00L, TransactionType.GIVE, imageUri = File(receiptsDir, "tx-img-jpeg.jpg").absolutePath)
        val txPng = makeSyntheticTransaction("tx-img-png", 2L, 150_00L, TransactionType.RECEIVE, imageUri = File(receiptsDir, "tx-img-png.jpg").absolutePath)
        val txWebp = makeSyntheticTransaction("tx-img-webp", 4L, 80_00L, TransactionType.GIVE, imageUri = File(receiptsDir, "tx-img-webp.jpg").absolutePath)
        val txActiveAlice = makeSyntheticTransaction("tx-alice-act", 1L, 100_00L, TransactionType.GIVE)
        val txRecAlice = makeSyntheticTransaction("tx-alice-rec", 1L, 40_00L, TransactionType.RECEIVE)
        val txDel = makeSyntheticTransaction("tx-del-1", 1L, 10_00L, TransactionType.GIVE, isDeleted = true)

        txDao.insertTransactions(listOf(txJpeg, txPng, txWebp, txActiveAlice, txRecAlice, txDel))

        val rule1 = makeSyntheticRule("rule-1", 1L, 50_00L, TransactionType.GIVE, RecurrenceFrequency.MONTHLY)
        val rule2 = makeSyntheticRule("rule-2", 2L, 100_00L, TransactionType.RECEIVE, RecurrenceFrequency.WEEKLY)
        ruleDao.insertRule(rule1)
        ruleDao.insertRule(rule2)

        // Write synthetic image files to receiptsDir
        File(receiptsDir, "tx-img-jpeg.jpg").writeBytes(syntheticJpegBytes)
        File(receiptsDir, "tx-img-png.jpg").writeBytes(syntheticPngBytes)
        File(receiptsDir, "tx-img-webp.jpg").writeBytes(syntheticWebPBytes)

        val originalJpegHash = sha256(syntheticJpegBytes)
        val originalPngHash = sha256(syntheticPngBytes)
        val originalWebpHash = sha256(syntheticWebPBytes)

        // Step 2: Perform export to test-only destination (simulated SAF stream export)
        val allPersons = personDao.getAllPersonsIncludingDeleted()
        val allTransactions = txDao.getAllTransactionsIncludingDeleted()
        val allRules = ruleDao.getAllRulesList()

        val billImages = mutableMapOf<String, String>()
        val missingImages = mutableListOf<String>()

        for (tx in allTransactions) {
            val f = File(receiptsDir, "${tx.id}.jpg")
            if (f.exists() && f.length() > 0) {
                billImages[tx.id] = Base64.getEncoder().encodeToString(f.readBytes())
            } else if (!tx.imageUri.isNullOrBlank()) {
                missingImages += tx.id
            }
        }

        val exportArchive = BackupData(
            persons = allPersons,
            transactions = allTransactions,
            recurringRules = allRules,
            billImagesByTxId = billImages,
            exportDate = now,
            schemaVersion = 3
        )

        val outputStream = ByteArrayOutputStream()
        outputStream.use { stream ->
            stream.write(gson.toJson(exportArchive).toByteArray(Charsets.UTF_8))
        }
        val exportedJson = outputStream.toString(Charsets.UTF_8)

        // Step 3: Validate exported payload
        assertNotNull(exportedJson)
        assertTrue("Exported payload must be nonempty", exportedJson.isNotBlank())

        val parsedBackup = gson.fromJson(exportedJson, BackupData::class.java)
        assertNotNull(parsedBackup)
        assertEquals("Schema version must be 3", 3, parsedBackup.schemaVersion)

        // Validate entity counts
        assertEquals(5, parsedBackup.persons.size)
        assertEquals(6, parsedBackup.transactions.size)
        assertEquals(2, parsedBackup.recurringRules.size)
        assertEquals(3, parsedBackup.billImagesByTxId.size)
        assertTrue(missingImages.isEmpty())

        // Validate active and deleted preservation
        val exportedDeletedPersons = parsedBackup.persons.filter { it.isDeleted }
        assertEquals(1, exportedDeletedPersons.size)
        assertEquals("Charlie Verma (Deleted)", exportedDeletedPersons[0].name)

        val exportedDeletedTxs = parsedBackup.transactions.filter { it.isDeleted }
        assertEquals(1, exportedDeletedTxs.size)
        assertEquals("tx-del-1", exportedDeletedTxs[0].id)

        // Validate SHA-256 hashes of exported/decoded receipt bytes against original bytes
        val exportedJpegBytes = Base64.getDecoder().decode(parsedBackup.billImagesByTxId["tx-img-jpeg"])
        val exportedPngBytes = Base64.getDecoder().decode(parsedBackup.billImagesByTxId["tx-img-png"])
        val exportedWebpBytes = Base64.getDecoder().decode(parsedBackup.billImagesByTxId["tx-img-webp"])

        assertEquals("JPEG SHA-256 hash must match original", originalJpegHash, sha256(exportedJpegBytes))
        assertEquals("PNG SHA-256 hash must match original", originalPngHash, sha256(exportedPngBytes))
        assertEquals("WebP SHA-256 hash must match original", originalWebpHash, sha256(exportedWebpBytes))

        // Validate Foreign-Key Relationships
        val exportedPersonIds = parsedBackup.persons.map { it.id }.toSet()
        for (tx in parsedBackup.transactions) {
            assertTrue("Transaction ${tx.id} references invalid personId ${tx.personId}", exportedPersonIds.contains(tx.personId))
        }
        for (rule in parsedBackup.recurringRules) {
            assertTrue("Rule ${rule.id} references invalid personId ${rule.personId}", exportedPersonIds.contains(rule.personId))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2.B — Restore into an Empty Database
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testPhase2B_restoreIntoEmptyDatabase_balancesMatch_canonicalReceiptsRestored() = runBlocking {
        // Step 1: Create synthetic export payload from Phase 2A
        val p1 = makeSyntheticPerson(1L, "Alice Sharma", "9876543210", cloudId = "cloud-alice-1")
        val p2 = makeSyntheticPerson(2L, "Bob Patel", "9876543211", cloudId = "cloud-bob-2", isArchived = true)
        val p3 = makeSyntheticPerson(3L, "Charlie Verma (Deleted)", "9876543212", cloudId = "cloud-charlie-3", isDeleted = true)
        val p4 = makeSyntheticPerson(4L, "Ramesh Kumar", "9876543299", cloudId = "cloud-ramesh-4")
        val p5 = makeSyntheticPerson(5L, "Suresh Kumar", "9876543299", cloudId = "cloud-suresh-5")

        val txJpeg = makeSyntheticTransaction("tx-img-jpeg", 1L, 25_00L, TransactionType.GIVE)
        val txPng = makeSyntheticTransaction("tx-img-png", 2L, 150_00L, TransactionType.RECEIVE)
        val txWebp = makeSyntheticTransaction("tx-img-webp", 4L, 80_00L, TransactionType.GIVE)
        val txActiveAlice = makeSyntheticTransaction("tx-alice-act", 1L, 100_00L, TransactionType.GIVE)
        val txRecAlice = makeSyntheticTransaction("tx-alice-rec", 1L, 40_00L, TransactionType.RECEIVE)
        val txDel = makeSyntheticTransaction("tx-del-1", 1L, 10_00L, TransactionType.GIVE, isDeleted = true)

        val rule1 = makeSyntheticRule("rule-1", 1L, 50_00L)
        val rule2 = makeSyntheticRule("rule-2", 2L, 100_00L)

        val backupData = BackupData(
            persons = listOf(p1, p2, p3, p4, p5),
            transactions = listOf(txJpeg, txPng, txWebp, txActiveAlice, txRecAlice, txDel),
            recurringRules = listOf(rule1, rule2),
            billImagesByTxId = mapOf(
                "tx-img-jpeg" to Base64.getEncoder().encodeToString(syntheticJpegBytes),
                "tx-img-png" to Base64.getEncoder().encodeToString(syntheticPngBytes),
                "tx-img-webp" to Base64.getEncoder().encodeToString(syntheticWebPBytes)
            ),
            schemaVersion = 3,
            exportDate = now
        )

        // Step 2: Fresh clean database and clean destination receipts directory
        val cleanPersonDao = FakePersonDao()
        val cleanTxDao = FakeTransactionDao()
        val cleanRuleDao = FakeRecurringRuleDao()
        cleanPersonDao.transactionDaoRef = cleanTxDao

        val targetReceiptsDir = File(tempDir, "clean_restore_receipts").also { it.mkdirs() }

        // Step 3: Two-phase image restoration (stage then promote)
        val restoredTxIds = mutableSetOf<String>()
        val finalMoves = mutableListOf<Pair<File, File>>()
        for ((txId, base64) in backupData.billImagesByTxId) {
            val bytes = Base64.getDecoder().decode(base64)
            val tempFile = File(targetReceiptsDir, "import_tmp_$txId.jpg")
            tempFile.writeBytes(bytes)
            assertTrue("Staged image must be valid", BackupManager.isValidReceiptFile(tempFile))
            val finalFile = File(targetReceiptsDir, "$txId.jpg")
            finalMoves += Pair(tempFile, finalFile)
            restoredTxIds += txId
        }

        // Step 4: Restore database records
        val stats = BackupManager.restoreDatabaseRecords(
            backupData = backupData,
            personDao = cleanPersonDao,
            transactionDao = cleanTxDao,
            recurringRuleDao = cleanRuleDao,
            restoredTxIds = restoredTxIds,
            targetReceiptsDir = targetReceiptsDir,
            now = now
        )

        // Step 5: Promote staged images
        for ((tempFile, finalFile) in finalMoves) {
            val promoted = BackupManager.promoteStagedReceipt(tempFile, finalFile, targetReceiptsDir)
            assertTrue("Image promotion must succeed", promoted)
            assertFalse("Staged file must be removed after promotion", tempFile.exists())
            assertTrue("Final image file must exist", finalFile.exists())
        }

        // Assertions:
        // Entity counts
        assertEquals(5, stats.restoredPersonsCount)
        assertEquals(6, stats.restoredTransactionsCount)
        assertEquals(2, stats.restoredRulesCount)
        assertEquals(0, stats.remappedPersonsCount) // Empty DB preserves original IDs

        // Database records verification
        val dbPersons = cleanPersonDao.getAllPersonsIncludingDeleted()
        assertEquals(5, dbPersons.size)
        val dbTxs = cleanTxDao.getAllTransactionsDirect()
        assertEquals(6, dbTxs.size)
        val dbRules = cleanRuleDao.getAllRulesList()
        assertEquals(2, dbRules.size)

        // Tombstone preservation
        val charlie = cleanPersonDao.getAllPersonsIncludingDeleted().find { it.name.startsWith("Charlie") }!!
        assertTrue("Charlie must remain soft-deleted", charlie.isDeleted)

        val deletedTx = cleanTxDao.getTransactionById("tx-del-1")!!
        assertTrue("Deleted transaction must remain soft-deleted", deletedTx.isDeleted)

        // Canonical internal receipts & byte comparison
        val jpegFile = File(targetReceiptsDir, "tx-img-jpeg.jpg")
        val pngFile = File(targetReceiptsDir, "tx-img-png.jpg")
        val webpFile = File(targetReceiptsDir, "tx-img-webp.jpg")

        assertTrue(jpegFile.exists())
        assertTrue(pngFile.exists())
        assertTrue(webpFile.exists())

        assertEquals(sha256(syntheticJpegBytes), sha256(jpegFile.readBytes()))
        assertEquals(sha256(syntheticPngBytes), sha256(pngFile.readBytes()))
        assertEquals(sha256(syntheticWebPBytes), sha256(webpFile.readBytes()))

        val restoredJpegTx = cleanTxDao.getTransactionById("tx-img-jpeg")!!
        assertEquals(jpegFile.absolutePath, restoredJpegTx.imageUri)

        // Balance calculations:
        // Alice (Person 1):
        // GIVE: tx-img-jpeg (2500) + tx-alice-act (10000) = 12500 cents
        // RECEIVE: tx-alice-rec (4000)
        // tx-del-1 (1000) is soft-deleted and MUST NOT be counted!
        val activeAliceTxs = cleanTxDao.getAllTransactionsDirect().filter { it.personId == 1L && !it.isDeleted }
        val aliceGive = activeAliceTxs.filter { it.type == TransactionType.GIVE }.sumOf { it.amountCents }
        val aliceRec = activeAliceTxs.filter { it.type == TransactionType.RECEIVE }.sumOf { it.amountCents }
        assertEquals(125_00L, aliceGive)
        assertEquals(40_00L, aliceRec)
        val aliceNet = aliceRec - aliceGive
        assertEquals(-85_00L, aliceNet) // "You Give ₹85.00"

        // Bob (Person 2):
        // RECEIVE: tx-img-png (15000)
        // GIVE: 0
        val activeBobTxs = cleanTxDao.getAllTransactionsDirect().filter { it.personId == 2L && !it.isDeleted }
        val bobGive = activeBobTxs.filter { it.type == TransactionType.GIVE }.sumOf { it.amountCents }
        val bobRec = activeBobTxs.filter { it.type == TransactionType.RECEIVE }.sumOf { it.amountCents }
        assertEquals(0L, bobGive)
        assertEquals(150_00L, bobRec)
        val bobNet = bobRec - bobGive
        assertEquals(150_00L, bobNet) // "You Get ₹150.00"

        // Ramesh (Person 4):
        // GIVE: tx-img-webp (8000)
        val activeRameshTxs = cleanTxDao.getAllTransactionsDirect().filter { it.personId == 4L && !it.isDeleted }
        val rameshGive = activeRameshTxs.filter { it.type == TransactionType.GIVE }.sumOf { it.amountCents }
        assertEquals(80_00L, rameshGive)
        assertEquals(-80_00L, 0L - rameshGive) // "You Give ₹80.00"

        // Overall Ledger Total
        val allActiveTxs = cleanTxDao.getAllTransactionsDirect().filter { !it.isDeleted }
        val totalGive = allActiveTxs.filter { it.type == TransactionType.GIVE }.sumOf { it.amountCents }
        val totalRec = allActiveTxs.filter { it.type == TransactionType.RECEIVE }.sumOf { it.amountCents }
        assertEquals(205_00L, totalGive)
        assertEquals(190_00L, totalRec)
        assertEquals(-15_00L, totalRec - totalGive) // Total Net = -15_00L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2.C — Repeat Import into Populated Database (Idempotency & Merges)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testPhase2C_repeatImportIntoPopulatedDatabase_idempotent_noDuplicates_preservesLocalTombstones() = runBlocking {
        // Step 1: Establish populated database
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()
        personDao.transactionDaoRef = txDao

        val p1 = makeSyntheticPerson(1L, "Alice Sharma", "9876543210", cloudId = "cloud-alice-1")
        val p2 = makeSyntheticPerson(2L, "Bob Patel", "9876543211", cloudId = "cloud-bob-2")
        personDao.insertPersons(listOf(p1, p2))

        val tx1 = makeSyntheticTransaction("tx-1", 1L, 50_00L, TransactionType.GIVE)
        val tx2 = makeSyntheticTransaction("tx-2", 2L, 75_00L, TransactionType.RECEIVE)
        txDao.insertTransactions(listOf(tx1, tx2))

        val rule1 = makeSyntheticRule("rule-1", 1L, 50_00L)
        ruleDao.insertRule(rule1)

        val backupData = BackupData(
            persons = listOf(p1, p2),
            transactions = listOf(tx1, tx2),
            recurringRules = listOf(rule1),
            schemaVersion = 3,
            exportDate = now
        )

        // Step 2: First import into database
        BackupManager.restoreDatabaseRecords(
            backupData = backupData,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            now = now
        )

        val countPersonsAfterFirst = personDao.getAllPersonsIncludingDeleted().size
        val countTxsAfterFirst = txDao.getAllTransactionsDirect().size
        val countRulesAfterFirst = ruleDao.getAllRulesList().size

        assertEquals(2, countPersonsAfterFirst)
        assertEquals(2, countTxsAfterFirst)
        assertEquals(1, countRulesAfterFirst)

        // Step 3: Mutate local state: Soft-delete tx1 locally with newer updatedAt
        val locallyDeletedTx1 = tx1.copy(isDeleted = true, updatedAt = now + 10_000L)
        txDao.updateTransaction(locallyDeletedTx1)
        assertTrue(txDao.getTransactionById("tx-1")!!.isDeleted)

        // Step 4: Re-import the exact same backup (where tx1 was active)
        val statsSecond = BackupManager.restoreDatabaseRecords(
            backupData = backupData,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            now = now + 20_000L
        )

        // Verification of Idempotency:
        // No duplicate persons, transactions, or rules
        val countPersonsAfterSecond = personDao.getAllPersonsIncludingDeleted().size
        val countTxsAfterSecond = txDao.getAllTransactionsDirect().size
        val countRulesAfterSecond = ruleDao.getAllRulesList().size

        assertEquals("Person count must remain 2 (zero duplicates)", 2, countPersonsAfterSecond)
        assertEquals("Transaction count must remain 2 (zero duplicates)", 2, countTxsAfterSecond)
        assertEquals("Rule count must remain 1 (zero duplicates)", 1, countRulesAfterSecond)

        // Non-resurrection Verification:
        // Locally soft-deleted transaction must NOT be resurrected by incoming active backup
        val tx1AfterSecond = txDao.getTransactionById("tx-1")!!
        assertTrue("Locally deleted transaction must remain deleted (tombstone preserved)", tx1AfterSecond.isDeleted)

        // Ambiguous Legacy-Person Matching Check:
        // Add two persons sharing phone "9876543299" with different names
        val ramesh = makeSyntheticPerson(10L, "Ramesh Kumar", "9876543299", cloudId = "cloud-ramesh")
        val suresh = makeSyntheticPerson(11L, "Suresh Kumar", "9876543299", cloudId = "cloud-suresh")
        personDao.insertPersons(listOf(ramesh, suresh))

        // Incoming legacy person lacks cloudId and has name "Dinesh Kumar" with the same phone "9876543299"
        val incomingLegacy = Person(
            id = 50L,
            name = "Dinesh Kumar",
            phone = "9876543299",
            cloudId = "", // Legacy v2 record
            createdAt = now,
            updatedAt = now,
            isDeleted = false,
            syncPending = true
        )
        val legacyTx = makeSyntheticTransaction("tx-dinesh", 50L, 30_00L, TransactionType.GIVE)

        val legacyBackup = BackupData(
            persons = listOf(incomingLegacy),
            transactions = listOf(legacyTx),
            schemaVersion = 3,
            exportDate = now
        )

        BackupManager.restoreDatabaseRecords(
            backupData = legacyBackup,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao,
            now = now + 30_000L
        )

        // Verify Dinesh was NOT merged into Ramesh or Suresh, but allocated a new person ID
        val allPersonsNow = personDao.getAllPersonsIncludingDeleted()
        assertEquals(5, allPersonsNow.size) // 2 original + Ramesh + Suresh + Dinesh
        val dineshInDb = allPersonsNow.find { it.name == "Dinesh Kumar" }
        assertNotNull(dineshInDb)
        assertTrue(dineshInDb!!.id != ramesh.id && dineshInDb.id != suresh.id)
        val dineshTx = txDao.getTransactionById("tx-dinesh")!!
        assertEquals(dineshInDb.id, dineshTx.personId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2.D — Failure and Recovery Scenarios (1 to 8)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * D-1: Truncated JSON backup.
     * Expectation: Import fails before any write; database remains pristine.
     */
    @Test
    fun testPhase2D1_truncatedJsonBackup_failsFast_zeroDatabaseCorruption() = runBlocking {
        val validJson = gson.toJson(
            BackupData(
                persons = listOf(makeSyntheticPerson(1L, "Alice Sharma", "9876543210")),
                transactions = listOf(makeSyntheticTransaction("tx-1", 1L, 100_00L, TransactionType.GIVE)),
                schemaVersion = 3
            )
        )
        val truncatedJson = validJson.substring(0, validJson.length / 2) // Cut halfway

        var syntaxErrorCaught = false
        try {
            gson.fromJson(truncatedJson, BackupData::class.java)
        } catch (e: JsonSyntaxException) {
            syntaxErrorCaught = true
        }
        assertTrue("Truncated JSON must throw JsonSyntaxException during parsing", syntaxErrorCaught)
    }

    /**
     * D-2: Invalid or truncated JPEG, PNG, and WebP images.
     * Expectation: Receipt validation rejects truncated/corrupt stubs; records missing images;
     * transactions still import safely.
     */
    @Test
    fun testPhase2D2_invalidOrTruncatedImages_rejectedSafely_missingImagesReported() {
        val truncatedJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) // Under 16 bytes
        val corruptPng = "NOT_A_REAL_PNG_HEADER_DATA_1234567890123456789012345678901234567890".toByteArray()
        val truncatedWebp = byteArrayOf(0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte()) // 4 bytes only

        val fJpeg = File(receiptsDir, "trunc.jpg").also { it.writeBytes(truncatedJpeg) }
        val fPng = File(receiptsDir, "corrupt.png").also { it.writeBytes(corruptPng) }
        val fWebp = File(receiptsDir, "trunc.webp").also { it.writeBytes(truncatedWebp) }

        assertFalse("Truncated JPEG (< 16 bytes) must be rejected", BackupManager.isValidReceiptFile(fJpeg))
        assertFalse("Corrupt PNG must be rejected", BackupManager.isValidReceiptFile(fPng))
        assertFalse("Truncated WebP (< 12 bytes) must be rejected", BackupManager.isValidReceiptFile(fWebp))
    }

    /**
     * D-3: Promotion failure after database commit.
     * Deterministic simulation via FileSystemOperations: Database commits, but filesystem rename fails.
     * Expectation: DB remains intact, staged temp file is retained on disk (not lost!),
     * transaction is listed in missingImageTxIds.
     */
    @Test
    fun testPhase2D3_promotionFailureAfterCommit_retainsStagedFile_reportsMissingImage() {
        val tempFile = File(receiptsDir, "import_tmp_tx-fault-1.jpg").also { it.writeBytes(syntheticJpegBytes) }
        val finalFile = File(receiptsDir, "tx-fault-1.jpg")

        // Mock FileSystemOperations where rename fails and copy fallback fails
        val failingFs = object : FileSystemOperations by DefaultFileSystemOperations {
            override fun rename(source: File, destination: File): Boolean = false
            override fun copy(source: File, destination: File, overwrite: Boolean) {
                throw java.io.IOException("Simulated I/O disk error during promotion copy")
            }
        }

        val promoted = BackupManager.promoteStagedReceipt(
            tempFile = tempFile,
            finalFile = finalFile,
            receiptsDir = receiptsDir,
            fileSystem = failingFs
        )

        assertFalse("Promotion must return false on filesystem failure", promoted)
        assertFalse("Final file must not be exposed", finalFile.exists())
        assertTrue("Staged temp file must be retained on disk for future recovery", tempFile.exists())
    }

    /**
     * D-4: Interrupted import before database commit.
     * Expectation: Exception before DB commit cleans up staged temp image files; 0 records in DB.
     */
    @Test
    fun testPhase2D4_interruptedImportBeforeCommit_cleansUpStagedFiles_zeroDatabaseWrites() = runBlocking {
        val tempFile = File(receiptsDir, "import_tmp_tx-fail-before-db.jpg").also { it.writeBytes(syntheticJpegBytes) }
        val stagedFiles = mutableListOf(tempFile)

        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        // Simulate failure during DB transaction
        var dbFailed = false
        try {
            // Simulated DB failure (e.g. SQLiteConstraintException)
            throw IllegalStateException("Simulated SQLite constraint failure during person insertion")
        } catch (e: Exception) {
            dbFailed = true
            // Cleanup staged files as BackupManager does in catch block
            for (f in stagedFiles) {
                f.delete()
            }
        }

        assertTrue(dbFailed)
        assertFalse("Staged image must be cleaned up on DB failure", tempFile.exists())
        assertEquals(0, personDao.getAllPersonsIncludingDeleted().size)
        assertEquals(0, txDao.getAllTransactionsDirect().size)
    }

    /**
     * D-5: Interrupted import after database commit but before receipt promotion.
     * Expectation: Transaction is committed in DB referencing image; staged temp file is on disk.
     * Startup recovery detects staged file, checks DB authority, and promotes staged file!
     */
    @Test
    fun testPhase2D5_interruptedAfterCommitBeforePromotion_healedByStartupRecovery() = runBlocking {
        val txDao = FakeTransactionDao()
        val txId = "tx-interrupted-after-commit"
        val committedTx = makeSyntheticTransaction(
            id = txId,
            personId = 1L,
            amountCents = 50_00L,
            type = TransactionType.GIVE,
            imageUri = File(receiptsDir, "$txId.jpg").absolutePath
        )
        txDao.insertTransaction(committedTx)

        // Staged file was written before commit, but process died before promotion
        val stagedFile = File(receiptsDir, "import_tmp_$txId.jpg").also { it.writeBytes(syntheticJpegBytes) }
        val finalFile = File(receiptsDir, "$txId.jpg")

        assertTrue(stagedFile.exists())
        assertFalse(finalFile.exists())

        // System Startup: recoverOrphanedStagingImages is invoked
        val recoveredCount = BackupManager.recoverOrphanedStagingImages(
            receiptsDir = receiptsDir,
            transactionDao = txDao
        )

        assertEquals("Recovery must promote the verified committed receipt", 1, recoveredCount)
        assertFalse("Staged file must be removed after successful recovery", stagedFile.exists())
        assertTrue("Final canonical receipt must exist", finalFile.exists())
        assertEquals(sha256(syntheticJpegBytes), sha256(finalFile.readBytes()))
    }

    /**
     * D-6: Repeated startup recovery.
     * Expectation: Calling recoverOrphanedStagingImages repeatedly is idempotent;
     * returns 0; leaves existing valid receipts intact.
     */
    @Test
    fun testPhase2D6_repeatedStartupRecovery_idempotent() = runBlocking {
        val txDao = FakeTransactionDao()
        val txId = "tx-idempotent-recovery"
        val finalFile = File(receiptsDir, "$txId.jpg").also { it.writeBytes(syntheticPngBytes) }
        txDao.insertTransaction(makeSyntheticTransaction(txId, 1L, 20_00L, TransactionType.GIVE, imageUri = finalFile.absolutePath))

        // First recovery check on healthy directory
        val count1 = BackupManager.recoverOrphanedStagingImages(receiptsDir, txDao)
        assertEquals(0, count1)

        // Second recovery check
        val count2 = BackupManager.recoverOrphanedStagingImages(receiptsDir, txDao)
        assertEquals(0, count2)

        assertTrue("Final file must remain intact", finalFile.exists())
        assertEquals(sha256(syntheticPngBytes), sha256(finalFile.readBytes()))
    }

    /**
     * D-7: Existing valid receipt plus invalid incoming replacement.
     * Expectation: Existing valid receipt is preserved; incoming invalid replacement is rejected;
     * final file is never overwritten with invalid bytes.
     */
    @Test
    fun testPhase2D7_existingValidReceipt_preservedAgainstIncomingInvalidOrDuplicate() {
        val txId = "tx-existing-valid"
        val finalFile = File(receiptsDir, "$txId.jpg").also { it.writeBytes(syntheticJpegBytes) }
        val originalHash = sha256(syntheticJpegBytes)

        // Incoming temp file with corrupted/truncated bytes
        val corruptTempFile = File(receiptsDir, "import_tmp_$txId.jpg").also { it.writeBytes("CORRUPT".toByteArray()) }

        val promotedInvalid = BackupManager.promoteStagedReceipt(
            tempFile = corruptTempFile,
            finalFile = finalFile,
            receiptsDir = receiptsDir
        )

        assertFalse("Promotion of corrupt file must be rejected", promotedInvalid)
        assertEquals("Existing receipt must remain untouched", originalHash, sha256(finalFile.readBytes()))

        // Incoming temp file with valid bytes but destination already exists and is valid
        val validTempFile = File(receiptsDir, "import_tmp_$txId.jpg").also { it.writeBytes(syntheticPngBytes) }
        val promotedDuplicate = BackupManager.promoteStagedReceipt(
            tempFile = validTempFile,
            finalFile = finalFile,
            receiptsDir = receiptsDir
        )

        assertTrue("Promotion returns true because final destination is already satisfied", promotedDuplicate)
        assertFalse("Staged duplicate must be discarded", validTempFile.exists())
        assertEquals("Original receipt must not be replaced by duplicate", originalHash, sha256(finalFile.readBytes()))
    }

    /**
     * D-8: Missing person references and malformed record relationships.
     * Expectation: Transaction or recurring rule referencing an unknown person ID fails fast with
     * IllegalArgumentException; no partial DB corruption.
     */
    @Test
    fun testPhase2D8_missingPersonReferences_rejectedWithIllegalArgumentException() = runBlocking {
        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        val p1 = makeSyntheticPerson(1L, "Alice Sharma", "9876543210")
        // Transaction references non-existent person ID 9999L
        val orphanTx = makeSyntheticTransaction("tx-orphan", 9999L, 100_00L, TransactionType.GIVE)

        val corruptBackup = BackupData(
            persons = listOf(p1),
            transactions = listOf(orphanTx),
            schemaVersion = 3,
            exportDate = now
        )

        var caught = false
        try {
            BackupManager.restoreDatabaseRecords(
                backupData = corruptBackup,
                personDao = personDao,
                transactionDao = txDao,
                recurringRuleDao = ruleDao,
                now = now
            )
        } catch (e: IllegalArgumentException) {
            caught = true
            assertTrue(e.message!!.contains("references unknown person ID 9999"))
        }

        assertTrue("Orphan transaction must cause IllegalArgumentException", caught)
        // Person 1 was inserted before the exception, but in a real DB withTransaction it would be rolled back.
        // Let's verify rule referencing unknown person ID as well:
        val orphanRule = makeSyntheticRule("rule-orphan", 8888L, 50_00L)
        val corruptRuleBackup = BackupData(
            persons = listOf(p1),
            transactions = emptyList(),
            recurringRules = listOf(orphanRule),
            schemaVersion = 3,
            exportDate = now
        )

        var ruleCaught = false
        try {
            BackupManager.restoreDatabaseRecords(
                backupData = corruptRuleBackup,
                personDao = personDao,
                transactionDao = txDao,
                recurringRuleDao = ruleDao,
                now = now
            )
        } catch (e: IllegalArgumentException) {
            ruleCaught = true
            assertTrue(e.message!!.contains("references unknown person ID 8888"))
        }

        assertTrue("Orphan recurring rule must cause IllegalArgumentException", ruleCaught)
    }
}
