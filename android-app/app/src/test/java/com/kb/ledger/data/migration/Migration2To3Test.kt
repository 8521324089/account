package com.kb.ledger.data.migration

import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.json.JsonSchemaValidator
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Automated migration tests verifying MIGRATION_2_3 safely preserves legacy data.
 *
 * Covers:
 *  - Empty database migration
 *  - Preservation of all persons (names, phones, contact IDs, null contact IDs)
 *  - Person without transactions (createdAt/updatedAt fallback)
 *  - Preservation of all transactions (IDs, personId, types, notes, image URIs, timestamps)
 *  - Accurate floating-point to integer cents conversion (e.g. ₹500.75 -> 50075L, ₹1000.50 -> 100050L)
 *  - Precision boundary testing (e.g. 0.01, 0.99, 1234.56)
 *  - UUID cloudId generation and uniqueness for every person
 *  - Initialization of new columns (isDeleted = 0, syncPending = 1, imageFileId = null)
 *  - Foreign key cascade preservation
 *  - Creation and readiness of sync_operations table
 */
class Migration2To3Test {

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
    fun testEmptyDatabaseMigration() {
        createV2Schema()

        // Execute MIGRATION_2_3 on empty v2 database
        AppDatabase.MIGRATION_2_3.migrate(dbHelper.supportDatabase)

        // Verify all three v3 tables exist and are empty
        val personCount = dbHelper.query("SELECT COUNT(*) FROM `persons`") { rs -> rs.getInt(1) }.first()
        val txCount = dbHelper.query("SELECT COUNT(*) FROM `transactions`") { rs -> rs.getInt(1) }.first()
        val syncOpCount = dbHelper.query("SELECT COUNT(*) FROM `sync_operations`") { rs -> rs.getInt(1) }.first()

        assertEquals(0, personCount)
        assertEquals(0, txCount)
        assertEquals(0, syncOpCount)
    }

    @Test
    fun testRealisticDataMigrationPreservesAllFields() {
        createV2Schema()

        val t1 = 1700000000000L
        val t2 = 1700001000000L
        val t3 = 1700002000000L

        // Insert legacy persons:
        // Person 1: has contactId, has 2 transactions
        dbHelper.execute("INSERT INTO `persons` (id, name, phone, contactId) VALUES (1, 'Alice Smith', '+1234567890', 'contact_alice');")
        // Person 2: null contactId, has 1 transaction with image URI
        dbHelper.execute("INSERT INTO `persons` (id, name, phone, contactId) VALUES (2, 'Bob Jones', '+9876543210', NULL);")
        // Person 3: person without any transactions
        dbHelper.execute("INSERT INTO `persons` (id, name, phone, contactId) VALUES (3, 'Charlie Brown', '+1122334455', 'contact_charlie');")

        // Insert legacy transactions:
        // Tx 1: Person 1, GIVE ₹500.75, with note
        dbHelper.execute("""
            INSERT INTO `transactions` (id, personId, amount, type, note, imageUri, createdAt)
            VALUES ('tx-uuid-1', 1, 500.75, 'GIVE', 'Loan for books', NULL, $t1);
        """.trimIndent())

        // Tx 2: Person 1, RECEIVE ₹200.25, no note
        dbHelper.execute("""
            INSERT INTO `transactions` (id, personId, amount, type, note, imageUri, createdAt)
            VALUES ('tx-uuid-2', 1, 200.25, 'RECEIVE', NULL, NULL, $t2);
        """.trimIndent())

        // Tx 3: Person 2, GIVE ₹1000.50, with note and content:// image URI
        dbHelper.execute("""
            INSERT INTO `transactions` (id, personId, amount, type, note, imageUri, createdAt)
            VALUES ('tx-uuid-3', 2, 1000.50, 'GIVE', 'Dinner bill', 'content://media/external/images/media/42', $t3);
        """.trimIndent())

        // Execute MIGRATION_2_3
        AppDatabase.MIGRATION_2_3.migrate(dbHelper.supportDatabase)

        // ── Verify Persons ────────────────────────────────────────────────────────
        data class MigratedPerson(
            val id: Long,
            val name: String,
            val phone: String,
            val contactId: String?,
            val cloudId: String?,
            val createdAt: Long,
            val updatedAt: Long,
            val updatedBy: String?,
            val isDeleted: Int,
            val syncPending: Int
        )

        val persons = dbHelper.query("SELECT * FROM `persons` ORDER BY `id` ASC") { rs ->
            MigratedPerson(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                phone = rs.getString("phone"),
                contactId = rs.getString("contactId"),
                cloudId = rs.getString("cloudId"),
                createdAt = rs.getLong("createdAt"),
                updatedAt = rs.getLong("updatedAt"),
                updatedBy = rs.getString("updatedBy"),
                isDeleted = rs.getInt("isDeleted"),
                syncPending = rs.getInt("syncPending")
            )
        }

        assertEquals(3, persons.size)

        // Person 1 (Alice)
        val alice = persons[0]
        assertEquals(1L, alice.id)
        assertEquals("Alice Smith", alice.name)
        assertEquals("+1234567890", alice.phone)
        assertEquals("contact_alice", alice.contactId)
        assertNotNull(alice.cloudId)
        assertTrue("cloudId must be valid UUID", JsonSchemaValidator.isValidUuid(alice.cloudId!!))
        assertEquals(t1, alice.createdAt) // earliest tx
        assertEquals(t2, alice.updatedAt) // latest tx
        assertNull(alice.updatedBy)
        assertEquals(0, alice.isDeleted)
        assertEquals(1, alice.syncPending)

        // Person 2 (Bob)
        val bob = persons[1]
        assertEquals(2L, bob.id)
        assertEquals("Bob Jones", bob.name)
        assertEquals("+9876543210", bob.phone)
        assertNull(bob.contactId)
        assertNotNull(bob.cloudId)
        assertTrue("cloudId must be valid UUID", JsonSchemaValidator.isValidUuid(bob.cloudId!!))
        assertNotEquals(alice.cloudId, bob.cloudId)
        assertEquals(t3, bob.createdAt)
        assertEquals(t3, bob.updatedAt)
        assertNull(bob.updatedBy)
        assertEquals(0, bob.isDeleted)
        assertEquals(1, bob.syncPending)

        // Person 3 (Charlie — no transactions)
        val charlie = persons[2]
        assertEquals(3L, charlie.id)
        assertEquals("Charlie Brown", charlie.name)
        assertEquals("+1122334455", charlie.phone)
        assertEquals("contact_charlie", charlie.contactId)
        assertNotNull(charlie.cloudId)
        assertTrue("cloudId must be valid UUID", JsonSchemaValidator.isValidUuid(charlie.cloudId!!))
        assertNotEquals(alice.cloudId, charlie.cloudId)
        assertNotEquals(bob.cloudId, charlie.cloudId)
        assertTrue(charlie.createdAt > 0)
        assertTrue(charlie.updatedAt > 0)
        assertEquals(0, charlie.isDeleted)
        assertEquals(1, charlie.syncPending)

        // ── Verify Transactions ───────────────────────────────────────────────────
        data class MigratedTx(
            val id: String,
            val personId: Long,
            val amountCents: Long,
            val type: String,
            val note: String?,
            val imageUri: String?,
            val imageFileId: String?,
            val createdAt: Long,
            val updatedAt: Long,
            val updatedBy: String?,
            val isDeleted: Int,
            val syncPending: Int
        )

        val transactions = dbHelper.query("SELECT * FROM `transactions` ORDER BY `createdAt` ASC") { rs ->
            MigratedTx(
                id = rs.getString("id"),
                personId = rs.getLong("personId"),
                amountCents = rs.getLong("amountCents"),
                type = rs.getString("type"),
                note = rs.getString("note"),
                imageUri = rs.getString("imageUri"),
                imageFileId = rs.getString("imageFileId"),
                createdAt = rs.getLong("createdAt"),
                updatedAt = rs.getLong("updatedAt"),
                updatedBy = rs.getString("updatedBy"),
                isDeleted = rs.getInt("isDeleted"),
                syncPending = rs.getInt("syncPending")
            )
        }

        assertEquals(3, transactions.size)

        // Tx 1: ₹500.75 -> 50075 paise
        val tx1 = transactions[0]
        assertEquals("tx-uuid-1", tx1.id)
        assertEquals(1L, tx1.personId)
        assertEquals(50075L, tx1.amountCents)
        assertEquals("GIVE", tx1.type)
        assertEquals("Loan for books", tx1.note)
        assertNull(tx1.imageUri)
        assertNull(tx1.imageFileId)
        assertEquals(t1, tx1.createdAt)
        assertEquals(t1, tx1.updatedAt)
        assertNull(tx1.updatedBy)
        assertEquals(0, tx1.isDeleted)
        assertEquals(1, tx1.syncPending)

        // Tx 2: ₹200.25 -> 20025 paise
        val tx2 = transactions[1]
        assertEquals("tx-uuid-2", tx2.id)
        assertEquals(1L, tx2.personId)
        assertEquals(20025L, tx2.amountCents)
        assertEquals("RECEIVE", tx2.type)
        assertNull(tx2.note)
        assertNull(tx2.imageUri)
        assertNull(tx2.imageFileId)
        assertEquals(t2, tx2.createdAt)
        assertEquals(t2, tx2.updatedAt)
        assertNull(tx2.updatedBy)
        assertEquals(0, tx2.isDeleted)
        assertEquals(1, tx2.syncPending)

        // Tx 3: ₹1000.50 -> 100050 paise, image URI preserved
        val tx3 = transactions[2]
        assertEquals("tx-uuid-3", tx3.id)
        assertEquals(2L, tx3.personId)
        assertEquals(100050L, tx3.amountCents)
        assertEquals("GIVE", tx3.type)
        assertEquals("Dinner bill", tx3.note)
        assertEquals("content://media/external/images/media/42", tx3.imageUri)
        assertNull(tx3.imageFileId)
        assertEquals(t3, tx3.createdAt)
        assertEquals(t3, tx3.updatedAt)
        assertNull(tx3.updatedBy)
        assertEquals(0, tx3.isDeleted)
        assertEquals(1, tx3.syncPending)

        // ── Verify sync_operations table exists and can be written to ───────────
        dbHelper.execute("""
            INSERT INTO `sync_operations` (id, type, entityType, entityId, retryCount, lastErrorMessage, createdAt)
            VALUES ('sync-op-1', 'UPSERT', 'PERSON', 'cloud-id-1', 0, NULL, 1700000000000);
        """.trimIndent())

        val opCount = dbHelper.query("SELECT COUNT(*) FROM `sync_operations`") { rs -> rs.getInt(1) }.first()
        assertEquals(1, opCount)
    }

    @Test
    fun testAmountRoundingPrecision() {
        createV2Schema()

        dbHelper.execute("INSERT INTO `persons` (id, name, phone) VALUES (1, 'Test Person', '123');")

        val amounts = listOf(
            0.01 to 1L,
            0.09 to 9L,
            0.10 to 10L,
            0.99 to 99L,
            1.00 to 100L,
            10.25 to 1025L,
            500.75 to 50075L,
            1234.56 to 123456L,
            999999.99 to 99999999L
        )

        amounts.forEachIndexed { idx, (doubleAmt, _) ->
            dbHelper.execute("""
                INSERT INTO `transactions` (id, personId, amount, type, note, createdAt)
                VALUES ('tx-$idx', 1, $doubleAmt, 'GIVE', 'Test $idx', ${1700000000000L + idx});
            """.trimIndent())
        }

        AppDatabase.MIGRATION_2_3.migrate(dbHelper.supportDatabase)

        amounts.forEachIndexed { idx, (_, expectedCents) ->
            val actualCents = dbHelper.query("SELECT amountCents FROM `transactions` WHERE id = 'tx-$idx'") { rs ->
                rs.getLong(1)
            }.first()
            assertEquals("Precision mismatch for index $idx", expectedCents, actualCents)
        }
    }

    @Test
    fun testPersonCloudIdUniquenessAcrossManyRecords() {
        createV2Schema()

        for (i in 1..25) {
            dbHelper.execute("INSERT INTO `persons` (id, name, phone) VALUES ($i, 'Person $i', 'Phone $i');")
        }

        AppDatabase.MIGRATION_2_3.migrate(dbHelper.supportDatabase)

        val cloudIds = dbHelper.query("SELECT cloudId FROM `persons`") { rs -> rs.getString(1) }
        assertEquals(25, cloudIds.size)

        val distinctCloudIds = cloudIds.toSet()
        assertEquals("All generated cloudIds must be distinct", 25, distinctCloudIds.size)

        for (cloudId in cloudIds) {
            assertNotNull(cloudId)
            assertTrue("Each cloudId must be a valid UUID, got $cloudId", JsonSchemaValidator.isValidUuid(cloudId))
        }
    }

    @Test
    fun testForeignKeyCascadeOnMigratedTables() {
        createV2Schema()

        dbHelper.execute("INSERT INTO `persons` (id, name, phone) VALUES (1, 'Alice', '111');")
        dbHelper.execute("INSERT INTO `transactions` (id, personId, amount, type, createdAt) VALUES ('t1', 1, 50.0, 'GIVE', 1000);")
        dbHelper.execute("INSERT INTO `transactions` (id, personId, amount, type, createdAt) VALUES ('t2', 1, 75.0, 'RECEIVE', 2000);")

        AppDatabase.MIGRATION_2_3.migrate(dbHelper.supportDatabase)

        // Verify foreign key cascade is active: deleting person deletes their transactions
        dbHelper.execute("PRAGMA foreign_keys = ON;")
        dbHelper.execute("DELETE FROM `persons` WHERE id = 1;")

        val remainingTxCount = dbHelper.query("SELECT COUNT(*) FROM `transactions` WHERE personId = 1") { rs -> rs.getInt(1) }.first()
        assertEquals(0, remainingTxCount)
    }
}
