package com.kb.ledger.data.migration

import com.kb.ledger.data.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Automated migration tests verifying MIGRATION_5_6 safely preserves v5 data,
 * adds the role column with default 'CUSTOMER', and allows both CUSTOMER and SUPPLIER
 * records, including different roles sharing the exact same mobile number.
 */
class Migration5To6Test {

    private lateinit var dbHelper: TestDatabaseHelper

    @Before
    fun setUp() {
        dbHelper = TestDatabaseHelper()
    }

    @After
    fun tearDown() {
        dbHelper.close()
    }

    private fun createV5Schema() {
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

        dbHelper.execute("""
            CREATE TABLE `recurring_rules` (
                `id` TEXT NOT NULL,
                `personId` INTEGER NOT NULL,
                `amountCents` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `note` TEXT,
                `frequency` TEXT NOT NULL,
                `startDate` INTEGER NOT NULL,
                `nextExecutionDate` INTEGER NOT NULL,
                `endDate` INTEGER,
                `isPaused` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`personId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            );
        """.trimIndent())
        dbHelper.execute("CREATE INDEX `index_recurring_rules_personId` ON `recurring_rules` (`personId`);")
    }

    @Test
    fun migration_v5_to_v6_preservesAllDataAndAssignsDefaultCustomerRole() {
        createV5Schema()

        // Insert legacy v5 records
        dbHelper.execute("""
            INSERT INTO `persons` (`id`, `name`, `phone`, `contactId`, `cloudId`, `createdAt`, `updatedAt`, `isDeleted`, `syncPending`, `isArchived`)
            VALUES (1, 'Ramesh Gupta', '9876543210', 'c-1', 'cloud-1', 1000, 2000, 0, 0, 0);
        """.trimIndent())

        dbHelper.execute("""
            INSERT INTO `persons` (`id`, `name`, `phone`, `contactId`, `cloudId`, `createdAt`, `updatedAt`, `isDeleted`, `syncPending`, `isArchived`)
            VALUES (2, 'Priya Sharma', '9123456789', 'c-2', 'cloud-2', 1500, 2500, 0, 0, 1);
        """.trimIndent())

        dbHelper.execute("""
            INSERT INTO `transactions` (`id`, `personId`, `amountCents`, `type`, `note`, `createdAt`, `updatedAt`, `isDeleted`, `syncPending`)
            VALUES ('tx-1', 1, 50000, 'GIVE', 'Supplies', 1000, 1000, 0, 0);
        """.trimIndent())

        // Run Migration 5 -> 6
        AppDatabase.MIGRATION_5_6.migrate(dbHelper.supportDatabase)

        // Verify existing persons intact and have role = 'CUSTOMER'
        data class PersonRow(val id: Long, val name: String, val phone: String, val isArchived: Int, val role: String)
        val personRows = dbHelper.query("SELECT id, name, phone, isArchived, role FROM persons ORDER BY id ASC") { rs ->
            PersonRow(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                phone = rs.getString("phone"),
                isArchived = rs.getInt("isArchived"),
                role = rs.getString("role")
            )
        }

        assertEquals(2, personRows.size)
        assertEquals("Ramesh Gupta", personRows[0].name)
        assertEquals("CUSTOMER", personRows[0].role)
        assertEquals(0, personRows[0].isArchived)

        assertEquals("Priya Sharma", personRows[1].name)
        assertEquals("CUSTOMER", personRows[1].role)
        assertEquals(1, personRows[1].isArchived)

        // Verify transactions intact
        val txCount = dbHelper.query("SELECT COUNT(*) FROM transactions WHERE id = 'tx-1'") { rs -> rs.getInt(1) }.first()
        assertEquals(1, txCount)
    }

    @Test
    fun migration_v5_to_v6_allowsSupplierInsertionWithSameMobileAsCustomer() {
        createV5Schema()

        // Insert customer in v5
        dbHelper.execute("""
            INSERT INTO `persons` (`id`, `name`, `phone`, `contactId`, `cloudId`, `createdAt`, `updatedAt`, `isDeleted`, `syncPending`, `isArchived`)
            VALUES (10, 'Rahul Verma (Customer)', '9876543210', 'c-10', 'cloud-10', 1000, 2000, 0, 0, 0);
        """.trimIndent())

        // Run Migration 5 -> 6
        AppDatabase.MIGRATION_5_6.migrate(dbHelper.supportDatabase)

        // Insert supplier with the EXACT SAME mobile number '9876543210'
        dbHelper.execute("""
            INSERT INTO `persons` (`id`, `name`, `phone`, `contactId`, `cloudId`, `createdAt`, `updatedAt`, `isDeleted`, `syncPending`, `isArchived`, `role`)
            VALUES (20, 'Rahul Traders (Supplier)', '9876543210', 'c-20', 'cloud-20', 3000, 3000, 0, 0, 0, 'SUPPLIER');
        """.trimIndent())

        data class RoleRow(val id: Long, val name: String, val phone: String, val role: String)
        val rows = dbHelper.query("SELECT id, name, phone, role FROM persons WHERE phone = '9876543210' ORDER BY id ASC") { rs ->
            RoleRow(rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getString("role"))
        }

        assertEquals(2, rows.size)
        assertEquals(10L, rows[0].id)
        assertEquals("CUSTOMER", rows[0].role)
        assertEquals(20L, rows[1].id)
        assertEquals("SUPPLIER", rows[1].role)
    }
}

