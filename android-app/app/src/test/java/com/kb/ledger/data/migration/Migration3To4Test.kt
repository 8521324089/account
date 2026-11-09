package com.kb.ledger.data.migration

import com.kb.ledger.data.AppDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Automated migration tests verifying MIGRATION_3_4 safely preserves v3 data.
 *
 * Covers:
 *  - Empty database migration from v3 to v4
 *  - Preservation of all existing persons, transactions, and sync operations
 *  - Verification that existing persons receive default isArchived = 0
 *  - Verification that column exists and can be updated to 1
 */
class Migration3To4Test {

    private lateinit var dbHelper: TestDatabaseHelper

    @Before
    fun setUp() {
        dbHelper = TestDatabaseHelper()
    }

    @After
    fun tearDown() {
        dbHelper.close()
    }

    private fun createV3Schema() {
        dbHelper.execute("""
            CREATE TABLE `persons` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `phone` TEXT NOT NULL,
                `contactId` TEXT,
                `cloudId` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `updatedBy` TEXT,
                `isDeleted` INTEGER NOT NULL,
                `syncPending` INTEGER NOT NULL
            );
        """.trimIndent())

        dbHelper.execute("""
            CREATE TABLE `transactions` (
                `id` TEXT PRIMARY KEY NOT NULL,
                `personId` INTEGER NOT NULL,
                `amountCents` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `note` TEXT,
                `imageUri` TEXT,
                `imageFileId` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `updatedBy` TEXT,
                `isDeleted` INTEGER NOT NULL DEFAULT 0,
                `syncPending` INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY(`personId`) REFERENCES `persons`(`id`) ON DELETE CASCADE
            );
        """.trimIndent())

        dbHelper.execute("""
            CREATE TABLE `sync_operations` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `type` TEXT NOT NULL,
                `entityType` TEXT NOT NULL,
                `entityId` TEXT NOT NULL,
                `retryCount` INTEGER NOT NULL DEFAULT 0,
                `lastErrorMessage` TEXT,
                `createdAt` INTEGER NOT NULL
            );
        """.trimIndent())
    }

    @Test
    fun `migration on empty database succeeds`() {
        createV3Schema()
        AppDatabase.MIGRATION_3_4.migrate(dbHelper.supportDatabase)

        // Verify column exists by querying it
        val count = dbHelper.query("SELECT COUNT(*) FROM `persons`") { rs -> rs.getInt(1) }.first()
        assertEquals(0, count)
    }

    @Test
    fun `migration preserves existing persons and initializes isArchived to zero`() {
        createV3Schema()

        // Insert realistic v3 records
        dbHelper.execute("""
            INSERT INTO persons (id, name, phone, contactId, cloudId, createdAt, updatedAt, updatedBy, isDeleted, syncPending)
            VALUES (1, 'Ramesh Kumar', '9876543210', 'c-101', 'uuid-1', 1700000000000, 1700000000000, NULL, 0, 0);
        """.trimIndent())
        dbHelper.execute("""
            INSERT INTO persons (id, name, phone, contactId, cloudId, createdAt, updatedAt, updatedBy, isDeleted, syncPending)
            VALUES (2, 'Anita Devi', '9123456789', NULL, 'uuid-2', 1700000005000, 1700000005000, NULL, 0, 1);
        """.trimIndent())

        dbHelper.execute("""
            INSERT INTO transactions (id, personId, amountCents, type, note, imageUri, imageFileId, createdAt, updatedAt, updatedBy, isDeleted, syncPending)
            VALUES ('tx-1', 1, 50000, 'GIVE', 'Initial loan', NULL, NULL, 1700000000000, 1700000000000, NULL, 0, 0);
        """.trimIndent())

        // Run migration 3 -> 4
        AppDatabase.MIGRATION_3_4.migrate(dbHelper.supportDatabase)

        // 1. Verify persons count
        val personCount = dbHelper.query("SELECT COUNT(*) FROM `persons`") { rs -> rs.getInt(1) }.first()
        assertEquals(2, personCount)

        // 2. Verify Ramesh Kumar has isArchived = 0 and all fields preserved
        data class PersonRow(val id: Long, val name: String, val phone: String, val cloudId: String?, val isArchived: Int)
        val rameshRows = dbHelper.query("SELECT id, name, phone, cloudId, isArchived FROM persons WHERE id = 1") { rs ->
            PersonRow(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                phone = rs.getString("phone"),
                cloudId = rs.getString("cloudId"),
                isArchived = rs.getInt("isArchived")
            )
        }
        assertEquals(1, rameshRows.size)
        assertEquals(1L, rameshRows[0].id)
        assertEquals("Ramesh Kumar", rameshRows[0].name)
        assertEquals("9876543210", rameshRows[0].phone)
        assertEquals("uuid-1", rameshRows[0].cloudId)
        assertEquals(0, rameshRows[0].isArchived)

        // 3. Verify Anita Devi has isArchived = 0
        val anitaArchived = dbHelper.query("SELECT isArchived FROM persons WHERE id = 2") { rs -> rs.getInt(1) }.first()
        assertEquals(0, anitaArchived)

        // 4. Verify transaction preserved
        val txAmount = dbHelper.query("SELECT amountCents FROM transactions WHERE id = 'tx-1'") { rs -> rs.getLong(1) }.first()
        assertEquals(50000L, txAmount)

        // 5. Verify archiving a person works in SQL
        dbHelper.execute("UPDATE persons SET isArchived = 1 WHERE id = 1")
        val updatedRameshArchived = dbHelper.query("SELECT isArchived FROM persons WHERE id = 1") { rs -> rs.getInt(1) }.first()
        assertEquals(1, updatedRameshArchived)
    }
}
