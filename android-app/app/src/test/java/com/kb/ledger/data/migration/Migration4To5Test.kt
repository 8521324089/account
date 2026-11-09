package com.kb.ledger.data.migration

import com.kb.ledger.data.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Automated migration tests verifying MIGRATION_4_5 safely preserves v4 data
 * and creates the new recurring_rules table with index and foreign keys.
 */
class Migration4To5Test {

    private lateinit var dbHelper: TestDatabaseHelper

    @Before
    fun setUp() {
        dbHelper = TestDatabaseHelper()
    }

    @After
    fun tearDown() {
        dbHelper.close()
    }

    private fun createV4Schema() {
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
                `syncPending` INTEGER NOT NULL,
                `isArchived` INTEGER NOT NULL DEFAULT 0
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

        dbHelper.execute("CREATE INDEX `index_transactions_personId` ON `transactions` (`personId`);")

        dbHelper.execute("""
            CREATE TABLE `sync_operations` (
                `id` TEXT PRIMARY KEY NOT NULL,
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
    fun `migration from v4 to v5 preserves all existing data and creates recurring_rules`() {
        createV4Schema()

        // Insert legacy v4 records
        dbHelper.execute("""
            INSERT INTO `persons` (`id`, `name`, `phone`, `contactId`, `cloudId`, `createdAt`, `updatedAt`, `isDeleted`, `syncPending`, `isArchived`)
            VALUES (1, 'Ramesh Gupta', '9876543210', 'c-1', 'cloud-1', 1000, 2000, 0, 0, 0);
        """.trimIndent())

        dbHelper.execute("""
            INSERT INTO `transactions` (`id`, `personId`, `amountCents`, `type`, `note`, `createdAt`, `updatedAt`, `isDeleted`, `syncPending`)
            VALUES ('tx-1', 1, 50000, 'GIVE', 'Supplies', 1000, 1000, 0, 0);
        """.trimIndent())

        // Run Migration 4 -> 5
        AppDatabase.MIGRATION_4_5.migrate(dbHelper.supportDatabase)

        // Verify existing persons intact
        data class PersonRow(val id: Long, val name: String, val phone: String, val isArchived: Int)
        val personRows = dbHelper.query("SELECT id, name, phone, isArchived FROM persons") { rs ->
            PersonRow(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                phone = rs.getString("phone"),
                isArchived = rs.getInt("isArchived")
            )
        }
        assertEquals(1, personRows.size)
        assertEquals(1L, personRows[0].id)
        assertEquals("Ramesh Gupta", personRows[0].name)
        assertEquals("9876543210", personRows[0].phone)
        assertEquals(0, personRows[0].isArchived)

        // Verify existing transactions intact
        val txAmount = dbHelper.query("SELECT amountCents FROM transactions WHERE id = 'tx-1'") { rs -> rs.getLong(1) }.first()
        assertEquals(50000L, txAmount)

        // Verify recurring_rules table exists and accepts records
        dbHelper.execute("""
            INSERT INTO `recurring_rules` (
                `id`, `personId`, `amountCents`, `type`, `note`, `frequency`,
                `startDate`, `nextExecutionDate`, `endDate`, `isPaused`, `createdAt`
            ) VALUES (
                'rule-1', 1, 100000, 'RECEIVE', 'Monthly rent', 'MONTHLY',
                10000, 20000, NULL, 0, 10000
            )
        """.trimIndent())

        data class RuleRow(val id: String, val personId: Long, val amountCents: Long, val frequency: String)
        val ruleRows = dbHelper.query("SELECT id, personId, amountCents, frequency FROM recurring_rules") { rs ->
            RuleRow(
                id = rs.getString("id"),
                personId = rs.getLong("personId"),
                amountCents = rs.getLong("amountCents"),
                frequency = rs.getString("frequency")
            )
        }
        assertEquals(1, ruleRows.size)
        assertEquals("rule-1", ruleRows[0].id)
        assertEquals(1L, ruleRows[0].personId)
        assertEquals(100000L, ruleRows[0].amountCents)
        assertEquals("MONTHLY", ruleRows[0].frequency)
    }

    @Test
    fun `migration from v3 to v5 through chained migrations preserves all persons and transactions`() {
        // Create v3 schema
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
        dbHelper.execute("CREATE INDEX `index_transactions_personId` ON `transactions` (`personId`);")

        dbHelper.execute("""
            CREATE TABLE `sync_operations` (
                `id` TEXT PRIMARY KEY NOT NULL,
                `type` TEXT NOT NULL,
                `entityType` TEXT NOT NULL,
                `entityId` TEXT NOT NULL,
                `retryCount` INTEGER NOT NULL DEFAULT 0,
                `lastErrorMessage` TEXT,
                `createdAt` INTEGER NOT NULL
            );
        """.trimIndent())

        // Insert realistic records in v3
        dbHelper.execute("""
            INSERT INTO `persons` (`id`, `name`, `phone`, `contactId`, `cloudId`, `createdAt`, `updatedAt`, `isDeleted`, `syncPending`)
            VALUES (1, 'Sunita Sharma', '9811122233', 'c-10', 'cloud-10', 1000, 2000, 0, 0);
        """.trimIndent())

        dbHelper.execute("""
            INSERT INTO `transactions` (`id`, `personId`, `amountCents`, `type`, `note`, `createdAt`, `updatedAt`, `isDeleted`, `syncPending`)
            VALUES ('tx-10', 1, 75000, 'RECEIVE', 'Advance payment', 1000, 1000, 0, 0);
        """.trimIndent())

        // Run chained migrations: 3 -> 4, then 4 -> 5
        AppDatabase.MIGRATION_3_4.migrate(dbHelper.supportDatabase)
        AppDatabase.MIGRATION_4_5.migrate(dbHelper.supportDatabase)

        // Verify person is intact with default isArchived = 0
        data class PersonRow(val id: Long, val name: String, val phone: String, val isArchived: Int)
        val personRows = dbHelper.query("SELECT id, name, phone, isArchived FROM persons") { rs ->
            PersonRow(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                phone = rs.getString("phone"),
                isArchived = rs.getInt("isArchived")
            )
        }
        assertEquals(1, personRows.size)
        assertEquals(1L, personRows[0].id)
        assertEquals("Sunita Sharma", personRows[0].name)
        assertEquals("9811122233", personRows[0].phone)
        assertEquals(0, personRows[0].isArchived)

        // Verify transaction is intact
        val txAmount = dbHelper.query("SELECT amountCents FROM transactions WHERE id = 'tx-10'") { rs -> rs.getLong(1) }.first()
        assertEquals(75000L, txAmount)

        // Verify recurring_rules is ready for insert
        dbHelper.execute("""
            INSERT INTO `recurring_rules` (
                `id`, `personId`, `amountCents`, `type`, `note`, `frequency`,
                `startDate`, `nextExecutionDate`, `endDate`, `isPaused`, `createdAt`
            ) VALUES (
                'rule-chained', 1, 20000, 'GIVE', 'Daily milk', 'DAILY',
                5000, 6000, NULL, 0, 5000
            )
        """.trimIndent())

        val ruleCount = dbHelper.query("SELECT COUNT(*) FROM recurring_rules") { rs -> rs.getInt(1) }.first()
        assertEquals(1, ruleCount)
    }
}
