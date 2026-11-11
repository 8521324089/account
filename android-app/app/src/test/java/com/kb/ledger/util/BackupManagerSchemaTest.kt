package com.kb.ledger.util

import java.util.Base64
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.RecurringRule
import com.kb.ledger.data.model.RecurrenceFrequency
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for [BackupData] schema version 3 serialization / deserialization.
 *
 * These tests exercise the backup data model and serialization logic directly,
 * without requiring Android context or Room (both of which are integration concerns).
 *
 * Integration tests that actually exercise BackupManager.exportData() / importData()
 * with a real Room database should be placed in the androidTest source set.
 */
class BackupManagerSchemaTest {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val now = System.currentTimeMillis()

    private fun makePerson(name: String) = Person(
        id = 0L,
        name = name,
        phone = "9999900001",
        cloudId = UUID.randomUUID().toString(),
        createdAt = now,
        updatedAt = now,
        isDeleted = false,
        syncPending = true
    )

    private fun makeTransaction(personId: Long, amountCents: Long, type: TransactionType) = Transaction(
        id = UUID.randomUUID().toString(),
        personId = personId,
        amountCents = amountCents,
        type = type,
        note = "test note",
        createdAt = now,
        updatedAt = now,
        isDeleted = false,
        syncPending = true
    )

    private fun makeRule(personId: Long) = RecurringRule(
        id = UUID.randomUUID().toString(),
        personId = personId,
        amountCents = 100_00L,
        type = TransactionType.GIVE,
        note = "monthly",
        frequency = RecurrenceFrequency.MONTHLY,
        startDate = now,
        nextExecutionDate = now + 86_400_000L,
        createdAt = now
    )

    // ── Schema version tests ─────────────────────────────────────────────────

    @Test
    fun backupData_defaultSchemaVersion_is4() {
        val backup = BackupData(
            persons = emptyList(),
            transactions = emptyList()
        )
        assertEquals(4, backup.schemaVersion)
        assertEquals(BackupData.CURRENT_SCHEMA_VERSION, backup.schemaVersion)
    }

    // ── Serialization round-trip tests ───────────────────────────────────────

    @Test
    fun backupData_roundTrip_personsAndTransactions() {
        val person = makePerson("Ravi Kumar")
        val tx = makeTransaction(1L, 50_075L, TransactionType.GIVE)

        val backup = BackupData(
            persons = listOf(person),
            transactions = listOf(tx)
        )

        val json = gson.toJson(backup)
        val restored = gson.fromJson(json, BackupData::class.java)

        assertEquals(1, restored.persons.size)
        assertEquals("Ravi Kumar", restored.persons[0].name)
        assertEquals(1, restored.transactions.size)
        assertEquals(50_075L, restored.transactions[0].amountCents)
        assertEquals(TransactionType.GIVE, restored.transactions[0].type)
    }

    @Test
    fun backupData_roundTrip_recurringRulesIncluded() {
        val person = makePerson("Sunita")
        val rule = makeRule(1L)

        val backup = BackupData(
            persons = listOf(person),
            transactions = emptyList(),
            recurringRules = listOf(rule)
        )

        val json = gson.toJson(backup)
        val restored = gson.fromJson(json, BackupData::class.java)

        assertEquals(1, restored.recurringRules.size)
        assertEquals(rule.id, restored.recurringRules[0].id)
        assertEquals(100_00L, restored.recurringRules[0].amountCents)
        assertEquals(RecurrenceFrequency.MONTHLY, restored.recurringRules[0].frequency)
    }

    @Test
    fun backupData_roundTrip_billImageBytes() {
        val txId = UUID.randomUUID().toString()
        val fakeImageBytes = "fake jpeg bytes for testing".toByteArray()
        val encoded = Base64.getEncoder().encodeToString(fakeImageBytes)

        val backup = BackupData(
            persons = emptyList(),
            transactions = emptyList(),
            billImagesByTxId = mapOf(txId to encoded)
        )

        val json = gson.toJson(backup)
        val restored = gson.fromJson(json, BackupData::class.java)

        assertTrue(restored.billImagesByTxId.containsKey(txId))
        val decodedBytes = Base64.getDecoder().decode(restored.billImagesByTxId[txId]!!)
        assertArrayEquals(fakeImageBytes, decodedBytes)
    }

    @Test
    fun backupData_emptyBillImages_defaultsToEmptyMap() {
        val backup = BackupData(persons = emptyList(), transactions = emptyList())
        val json = gson.toJson(backup)
        val restored = gson.fromJson(json, BackupData::class.java)

        assertNotNull(restored.billImagesByTxId)
        assertTrue(restored.billImagesByTxId.isEmpty())
    }

    @Test
    fun backupData_multipleBillImages_allPreserved() {
        val tx1 = UUID.randomUUID().toString()
        val tx2 = UUID.randomUUID().toString()
        val img1 = Base64.getEncoder().encodeToString("image1".toByteArray())
        val img2 = Base64.getEncoder().encodeToString("image2".toByteArray())

        val backup = BackupData(
            persons = emptyList(),
            transactions = emptyList(),
            billImagesByTxId = mapOf(tx1 to img1, tx2 to img2)
        )

        val json = gson.toJson(backup)
        val restored = gson.fromJson(json, BackupData::class.java)

        assertEquals(2, restored.billImagesByTxId.size)
        assertNotNull(restored.billImagesByTxId[tx1])
        assertNotNull(restored.billImagesByTxId[tx2])

        assertArrayEquals("image1".toByteArray(), Base64.getDecoder().decode(restored.billImagesByTxId[tx1]!!))
        assertArrayEquals("image2".toByteArray(), Base64.getDecoder().decode(restored.billImagesByTxId[tx2]!!))
    }

    @Test
    fun backupData_exportDate_isPopulated() {
        val before = System.currentTimeMillis()
        val backup = BackupData(persons = emptyList(), transactions = emptyList())
        val after = System.currentTimeMillis()

        assertTrue(backup.exportDate in before..after)
    }

    // ── Backward-compat: schema v2 backup (missing recurringRules/billImages) ─

    @Test
    fun backupData_v2Json_missingFieldsDefaultCorrectly() {
        // Simulate a v2 JSON backup that has no recurringRules or billImagesByTxId keys.
        val v2Json = """
            {
              "persons": [],
              "transactions": [],
              "exportDate": 1700000000000,
              "schemaVersion": 2
            }
        """.trimIndent()

        val restored = gson.fromJson(v2Json, BackupData::class.java)

        // recurringRules should default to null from Gson deserialization;
        // the import logic must handle null safely.
        // billImagesByTxId should also be null.
        // We verify that the schema version reflects the source, not the current default.
        assertEquals(2, restored.schemaVersion)
        // Gson will set missing list/map fields to null, not empty — import must guard.
        // These assertions document the behaviour BackupManager.importData() must handle.
        assertTrue("recurringRules is null or empty",
            restored.recurringRules == null || restored.recurringRules.isEmpty())
        assertTrue("billImagesByTxId is null or empty",
            restored.billImagesByTxId == null || restored.billImagesByTxId.isEmpty())
    }

    // ── Financial invariant tests ────────────────────────────────────────────

    @Test
    fun backupData_amountCents_preservedExactly() {
        // Amounts like ₹500.75 must round-trip without floating-point loss.
        val tx = makeTransaction(1L, 50_075L, TransactionType.RECEIVE)
        val backup = BackupData(persons = emptyList(), transactions = listOf(tx))

        val json = gson.toJson(backup)
        val restored = gson.fromJson(json, BackupData::class.java)

        assertEquals(50_075L, restored.transactions[0].amountCents)
        // Verify no float precision loss by checking the display value.
        assertEquals(500.75, restored.transactions[0].amount, 0.0)
    }

    @Test
    fun backupData_softDeletedTransactionsPreserved() {
        // Tombstones (isDeleted = true) must survive export/import for cloud sync propagation.
        val tx = makeTransaction(1L, 1000L, TransactionType.GIVE).copy(
            isDeleted = true,
            syncPending = true
        )
        val backup = BackupData(persons = emptyList(), transactions = listOf(tx))
        val json = gson.toJson(backup)
        val restored = gson.fromJson(json, BackupData::class.java)

        assertTrue(restored.transactions[0].isDeleted)
        assertTrue(restored.transactions[0].syncPending)
    }

    @Test
    fun backupData_archivedPersonsPreserved() {
        val archivedPerson = makePerson("Archived Person").copy(isArchived = true, isDeleted = false)
        val backup = BackupData(persons = listOf(archivedPerson), transactions = emptyList())
        val json = gson.toJson(backup)
        val restored = gson.fromJson(json, BackupData::class.java)

        assertTrue(restored.persons[0].isArchived)
        assertFalse(restored.persons[0].isDeleted)
    }

    @Test
    fun backupData_multipleTransactionsSamePersonPreserved() {
        val personId = 5L
        val txs = listOf(
            makeTransaction(personId, 1000L, TransactionType.GIVE),
            makeTransaction(personId, 500L, TransactionType.RECEIVE),
            makeTransaction(personId, 2000L, TransactionType.GIVE),
        )
        val backup = BackupData(persons = emptyList(), transactions = txs)
        val json = gson.toJson(backup)
        val restored = gson.fromJson(json, BackupData::class.java)

        assertEquals(3, restored.transactions.size)
        val totalGive = restored.transactions.filter { it.type == TransactionType.GIVE }.sumOf { it.amountCents }
        val totalReceive = restored.transactions.filter { it.type == TransactionType.RECEIVE }.sumOf { it.amountCents }
        assertEquals(3000L, totalGive)
        assertEquals(500L, totalReceive)
        // Net balance = RECEIVE - GIVE = 500 - 3000 = -2500 ("You Give")
        val netBalance = totalReceive - totalGive
        assertEquals(-2500L, netBalance)
    }
}
