package com.kb.ledger.data.migration

import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.json.JsonSchemaValidator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Full end-to-end chained migration test: Schema v2 -> v3 -> v4 -> v5.
 *
 * Verifies that a realistic v2 database with representative customer accounts,
 * decimal transactions, legacy image references, and timestamps upgrades cleanly
 * through all intermediate migrations with zero data loss, stable foreign keys,
 * accurate currency conversion, and proper table initialization.
 */
class FullChainedMigrationTest {

    private lateinit var dbHelper: TestDatabaseHelper

    @Before
    fun setUp() {
        dbHelper = TestDatabaseHelper()
    }

    @After
    fun tearDown() {
        dbHelper.close()
    }

    private fun createV2Schema() {
        dbHelper.execute("""
            CREATE TABLE `persons` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `phone` TEXT NOT NULL,
                `contactId` TEXT
            );
        """.trimIndent())

        dbHelper.execute("""
            CREATE TABLE `transactions` (
                `id` TEXT PRIMARY KEY NOT NULL,
                `personId` INTEGER NOT NULL,
                `amount` REAL NOT NULL,
                `type` TEXT NOT NULL,
                `note` TEXT,
                `imageUri` TEXT,
                `createdAt` INTEGER NOT NULL
            );
        """.trimIndent())
    }

    @Test
    fun fullMigration_v2_to_v5_preservesAllDataAndConvertsAccurately() {
        createV2Schema()

        // 1. Insert representative legacy v2 persons
        dbHelper.execute("""
            INSERT INTO `persons` (`id`, `name`, `phone`, `contactId`) VALUES
            (1, 'Ramesh Kumar', '9431733886', 'c-101'),
            (2, 'Pooja Sharma', '9876543210', NULL),
            (3, 'Sunil Gupta', '09123456789', 'c-103'),
            (4, 'Deepak Verma', '+91 94317 33886', NULL),
            (5, 'Amitabh Bacchan', '9998887776', 'c-105');
        """.trimIndent())

        // 2. Insert representative legacy v2 transactions with floating point amounts
        dbHelper.execute("""
            INSERT INTO `transactions` (`id`, `personId`, `amount`, `type`, `note`, `imageUri`, `createdAt`) VALUES
            ('tx-1', 1, 500.75, 'RECEIVE', 'Advance payment', 'content://media/picker/1', 1700000000000),
            ('tx-2', 1, 1000.50, 'GIVE', 'Material cost', '/data/user/0/com.kb.ledger/files/receipts/r1.jpg', 1700001000000),
            ('tx-3', 1, 0.01, 'RECEIVE', 'Test paisa', NULL, 1700002000000),
            ('tx-4', 2, 2500.00, 'RECEIVE', 'Full settlement', NULL, 1700003000000),
            ('tx-5', 2, 1234.56, 'GIVE', 'Supplies purchase', 'content://media/picker/2', 1700004000000),
            ('tx-6', 3, 99999.99, 'RECEIVE', 'Bulk invoice', '/data/user/0/com.kb.ledger/files/receipts/r3.jpg', 1700005000000),
            ('tx-7', 3, 49.95, 'GIVE', 'Delivery charge', NULL, 1700006000000),
            ('tx-8', 4, 300.00, 'RECEIVE', 'Cash return', NULL, 1700007000000),
            ('tx-9', 5, 10.10, 'GIVE', 'Tea snack', NULL, 1700008000000),
            ('tx-10', 5, 20.20, 'RECEIVE', 'Snack reimbursement', NULL, 1700009000000);
        """.trimIndent())

        // 3. Sequentially apply migrations: v2 -> v3, v3 -> v4, v4 -> v5
        AppDatabase.MIGRATION_2_3.migrate(dbHelper.supportDatabase)
        AppDatabase.MIGRATION_3_4.migrate(dbHelper.supportDatabase)
        AppDatabase.MIGRATION_4_5.migrate(dbHelper.supportDatabase)

        // 4. Verify persons in final v5 schema
        data class PersonV5(
            val id: Long,
            val name: String,
            val phone: String,
            val contactId: String?,
            val cloudId: String?,
            val createdAt: Long,
            val updatedAt: Long,
            val isDeleted: Int,
            val syncPending: Int,
            val isArchived: Int
        )

        val persons = dbHelper.query(
            "SELECT id, name, phone, contactId, cloudId, createdAt, updatedAt, isDeleted, syncPending, isArchived FROM persons ORDER BY id ASC"
        ) { rs ->
            PersonV5(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                phone = rs.getString("phone"),
                contactId = rs.getString("contactId"),
                cloudId = rs.getString("cloudId"),
                createdAt = rs.getLong("createdAt"),
                updatedAt = rs.getLong("updatedAt"),
                isDeleted = rs.getInt("isDeleted"),
                syncPending = rs.getInt("syncPending"),
                isArchived = rs.getInt("isArchived")
            )
        }

        assertEquals("All 5 persons must be preserved", 5, persons.size)

        // Verify Person 1
        val p1 = persons[0]
        assertEquals(1L, p1.id)
        assertEquals("Ramesh Kumar", p1.name)
        assertEquals("9431733886", p1.phone)
        assertEquals("c-101", p1.contactId)
        assertTrue("cloudId must be valid UUID", JsonSchemaValidator.isValidUuid(p1.cloudId ?: ""))
        assertEquals(1700000000000L, p1.createdAt) // Earliest transaction
        assertEquals(1700002000000L, p1.updatedAt) // Latest transaction
        assertEquals(0, p1.isDeleted)
        assertEquals(1, p1.syncPending)
        assertEquals(0, p1.isArchived)

        // Verify Person 2
        val p2 = persons[1]
        assertEquals(2L, p2.id)
        assertEquals("Pooja Sharma", p2.name)
        assertEquals("9876543210", p2.phone)
        assertNull(p2.contactId)
        assertTrue(JsonSchemaValidator.isValidUuid(p2.cloudId ?: ""))
        assertEquals(1700003000000L, p2.createdAt)
        assertEquals(1700004000000L, p2.updatedAt)
        assertEquals(0, p2.isDeleted)
        assertEquals(1, p2.syncPending)
        assertEquals(0, p2.isArchived)

        // Verify cloudId uniqueness across all persons
        val cloudIds = persons.mapNotNull { it.cloudId }.toSet()
        assertEquals("Every person must receive a distinct unique cloudId", 5, cloudIds.size)

        // 5. Verify transactions in final v5 schema
        data class TxV5(
            val id: String,
            val personId: Long,
            val amountCents: Long,
            val type: String,
            val note: String?,
            val imageUri: String?,
            val imageFileId: String?,
            val createdAt: Long,
            val updatedAt: Long,
            val isDeleted: Int,
            val syncPending: Int
        )

        val txs = dbHelper.query(
            "SELECT id, personId, amountCents, type, note, imageUri, imageFileId, createdAt, updatedAt, isDeleted, syncPending FROM transactions ORDER BY id ASC"
        ) { rs ->
            TxV5(
                id = rs.getString("id"),
                personId = rs.getLong("personId"),
                amountCents = rs.getLong("amountCents"),
                type = rs.getString("type"),
                note = rs.getString("note"),
                imageUri = rs.getString("imageUri"),
                imageFileId = rs.getString("imageFileId"),
                createdAt = rs.getLong("createdAt"),
                updatedAt = rs.getLong("updatedAt"),
                isDeleted = rs.getInt("isDeleted"),
                syncPending = rs.getInt("syncPending")
            )
        }

        assertEquals("All 10 transactions must be preserved", 10, txs.size)

        val txMap = txs.associateBy { it.id }

        // Precision verification:
        // tx-1: 500.75 -> 50075L
        val tx1 = txMap["tx-1"]!!
        assertEquals(1L, tx1.personId)
        assertEquals(50075L, tx1.amountCents)
        assertEquals("RECEIVE", tx1.type)
        assertEquals("Advance payment", tx1.note)
        assertEquals("content://media/picker/1", tx1.imageUri)
        assertNull(tx1.imageFileId)
        assertEquals(0, tx1.isDeleted)
        assertEquals(1, tx1.syncPending)

        // tx-2: 1000.50 -> 100050L
        val tx2 = txMap["tx-2"]!!
        assertEquals(1L, tx2.personId)
        assertEquals(100050L, tx2.amountCents)
        assertEquals("GIVE", tx2.type)
        assertEquals("/data/user/0/com.kb.ledger/files/receipts/r1.jpg", tx2.imageUri)

        // tx-3: 0.01 -> 1L
        val tx3 = txMap["tx-3"]!!
        assertEquals(1L, tx3.amountCents)

        // tx-5: 1234.56 -> 123456L
        val tx5 = txMap["tx-5"]!!
        assertEquals(123456L, tx5.amountCents)

        // tx-6: 99999.99 -> 9999999L
        val tx6 = txMap["tx-6"]!!
        assertEquals(9999999L, tx6.amountCents)

        // 6. Verify foreign key index and constraints
        val fkIndexCount = dbHelper.query("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_transactions_personId'") { rs -> rs.getInt(1) }.first()
        assertEquals(1, fkIndexCount)

        // 7. Verify sync_operations table exists and is empty
        val syncOpCount = dbHelper.query("SELECT COUNT(*) FROM sync_operations") { rs -> rs.getInt(1) }.first()
        assertEquals(0, syncOpCount)

        // 8. Verify recurring_rules table exists and accepts records with FK to persons
        val ruleIndexCount = dbHelper.query("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_recurring_rules_personId'") { rs -> rs.getInt(1) }.first()
        assertEquals(1, ruleIndexCount)

        dbHelper.execute("""
            INSERT INTO `recurring_rules` (
                `id`, `personId`, `amountCents`, `type`, `note`, `frequency`,
                `startDate`, `nextExecutionDate`, `endDate`, `isPaused`, `createdAt`
            ) VALUES (
                'rule-test-1', 1, 50000, 'RECEIVE', 'Monthly rent', 'MONTHLY',
                1700000000000, 1702592000000, NULL, 0, 1700000000000
            );
        """.trimIndent())

        val rulesCount = dbHelper.query("SELECT COUNT(*) FROM recurring_rules") { rs -> rs.getInt(1) }.first()
        assertEquals(1, rulesCount)

        // 9. Verify balance calculation matches exact cents sum
        // Person 1: RECEIVE 500.75 (50075) - GIVE 1000.50 (100050) + RECEIVE 0.01 (1) = -49974L
        val p1Balance = dbHelper.query("""
            SELECT SUM(CASE WHEN type = 'RECEIVE' THEN amountCents ELSE -amountCents END)
            FROM transactions WHERE personId = 1
        """) { rs -> rs.getLong(1) }.first()
        assertEquals(-49974L, p1Balance)
    }

    @Test
    fun migration_v2_to_v5_personWithoutTransactions_fallbackTimestampsSafely() {
        createV2Schema()

        // Person with no transactions at all
        dbHelper.execute("INSERT INTO `persons` (`id`, `name`, `phone`, `contactId`) VALUES (99, 'Inactive Customer', '9111122222', NULL);")

        AppDatabase.MIGRATION_2_3.migrate(dbHelper.supportDatabase)
        AppDatabase.MIGRATION_3_4.migrate(dbHelper.supportDatabase)
        AppDatabase.MIGRATION_4_5.migrate(dbHelper.supportDatabase)

        val p = dbHelper.query("SELECT id, name, createdAt, updatedAt, isArchived FROM persons WHERE id = 99") { rs ->
            Triple(rs.getLong("id"), rs.getLong("createdAt"), rs.getInt("isArchived"))
        }.first()

        assertEquals(99L, p.first)
        assertTrue("createdAt should fall back to a positive epoch timestamp", p.second > 0L)
        assertEquals(0, p.third)
    }
}
