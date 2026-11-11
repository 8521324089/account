package com.kb.ledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kb.ledger.data.dao.PersonDao
import com.kb.ledger.data.dao.RecurringRuleDao
import com.kb.ledger.data.dao.SyncOperationDao
import com.kb.ledger.data.dao.TransactionDao
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.RecurringRule
import com.kb.ledger.data.model.SyncOperation
import com.kb.ledger.data.model.Transaction

/**
 * Room database — version 5.
 *
 * Version history:
 *   1 → Initial schema (persons + transactions)
 *   2 → (no migration script was written; fallbackToDestructiveMigration was used)
 *   3 → Added cloudId, updatedAt, isDeleted, syncPending to persons;
 *         replaced amount:Double with amountCents:Long in transactions;
 *         added imageFileId, updatedAt, isDeleted, syncPending to transactions;
 *         added sync_operations table.
 *   4 → Added isArchived to persons.
 *   5 → Added recurring_rules table.
 *   6 → Added role to persons.
 *
 * NOTE: [fallbackToDestructiveMigration] is intentionally removed.
 * Only explicit migrations are allowed to prevent accidental data loss.
 */
@Database(
    entities = [Person::class, Transaction::class, SyncOperation::class, RecurringRule::class],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun personDao(): PersonDao
    abstract fun transactionDao(): TransactionDao
    abstract fun syncOperationDao(): SyncOperationDao
    abstract fun recurringRuleDao(): RecurringRuleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration 2 → 3: Safe, non-destructive migration.
         *
         * Preserves:
         *  - All existing persons (id, name, phone, contactId).
         *  - All existing transactions (id, personId, type, note, imageUri, createdAt).
         *  - Exact relationships (personId FK cascade).
         *
         * Migrates:
         *  - Transaction `amount` (REAL / Double) to `amountCents` (INTEGER / Long)
         *    using CAST(ROUND(amount * 100) AS INTEGER) to eliminate float rounding errors.
         *  - Generates unique RFC 4122 v4 UUID `cloudId` for every person.
         *  - Populates createdAt and updatedAt timestamps safely.
         *  - Sets isDeleted = 0, syncPending = 1 for Drive synchronization readiness.
         *  - Creates `index_transactions_personId` index.
         *  - Creates `sync_operations` table for durable offline retry queue.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Disable foreign key constraints during table rebuilding
                db.execSQL("PRAGMA foreign_keys = OFF;")

                // 1. Rebuild transactions table to convert amount (REAL) -> amountCents (INTEGER)
                // and add new columns (imageFileId, updatedAt, updatedBy, isDeleted, syncPending)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `transactions_new` (
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
                    )
                """.trimIndent())

                // Copy all existing transactions.
                // CAST(ROUND(amount * 100) AS INTEGER) guarantees fractional paise are rounded correctly
                // and avoids IEEE 754 floating point truncation errors (e.g. 500.74999999999994 -> 50075).
                db.execSQL("""
                    INSERT INTO `transactions_new` (
                        `id`, `personId`, `amountCents`, `type`, `note`, `imageUri`, `imageFileId`,
                        `createdAt`, `updatedAt`, `updatedBy`, `isDeleted`, `syncPending`
                    )
                    SELECT
                        `id`,
                        `personId`,
                        CAST(ROUND(`amount` * 100) AS INTEGER),
                        `type`,
                        `note`,
                        `imageUri`,
                        NULL,
                        `createdAt`,
                        `createdAt`,
                        NULL,
                        0,
                        1
                    FROM `transactions`
                """.trimIndent())

                db.execSQL("DROP TABLE `transactions`;")
                db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`;")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_personId` ON `transactions` (`personId`);")

                // 2. Rebuild persons table to add cloudId, createdAt, updatedAt, updatedBy, isDeleted, syncPending
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `persons_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `phone` TEXT NOT NULL,
                        `contactId` TEXT,
                        `cloudId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `updatedBy` TEXT,
                        `isDeleted` INTEGER NOT NULL DEFAULT 0,
                        `syncPending` INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())

                // Copy all existing persons.
                // - cloudId: Generated unique UUID for each person using SQLite's randomblob.
                // - createdAt: Earliest transaction timestamp for this person, or current epoch millis if no transactions.
                // - updatedAt: Latest transaction timestamp for this person, or current epoch millis if no transactions.
                // - syncPending: 1 (ready for cloud sync).
                // - isDeleted: 0.
                val currentEpochMillis = System.currentTimeMillis()
                db.execSQL("""
                    INSERT INTO `persons_new` (
                        `id`, `name`, `phone`, `contactId`, `cloudId`,
                        `createdAt`, `updatedAt`, `updatedBy`, `isDeleted`, `syncPending`
                    )
                    SELECT
                        p.`id`,
                        p.`name`,
                        p.`phone`,
                        p.`contactId`,
                        lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))),2) || '-a' || substr(lower(hex(randomblob(2))),2) || '-' || lower(hex(randomblob(6))),
                        COALESCE((SELECT MIN(t.`createdAt`) FROM `transactions` t WHERE t.`personId` = p.`id`), $currentEpochMillis),
                        COALESCE((SELECT MAX(t.`createdAt`) FROM `transactions` t WHERE t.`personId` = p.`id`), $currentEpochMillis),
                        NULL,
                        0,
                        1
                    FROM `persons` p
                """.trimIndent())

                db.execSQL("DROP TABLE `persons`;")
                db.execSQL("ALTER TABLE `persons_new` RENAME TO `persons`;")

                // 3. Create sync_operations table for durable offline queue
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sync_operations` (
                        `id` TEXT PRIMARY KEY NOT NULL,
                        `type` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        `lastErrorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())

                // Re-enable foreign key constraints
                db.execSQL("PRAGMA foreign_keys = ON;")
            }
        }

        /**
         * Migration 3 → 4: Safe, non-destructive migration.
         * Adds [isArchived] column to `persons` table with default value 0 (false).
         * Preserves all existing persons, transactions, and sync records.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `persons` ADD COLUMN `isArchived` INTEGER NOT NULL DEFAULT 0;")
            }
        }

        /**
         * Migration 4 → 5: Safe, non-destructive migration.
         * Creates `recurring_rules` table and its index for automated repeated ledger entries.
         * Preserves all existing persons, transactions, and sync records.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `recurring_rules` (
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
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rules_personId` ON `recurring_rules` (`personId`);")
            }
        }

        /**
         * Migration 5 → 6: Safe, non-destructive migration.
         * Adds [role] column to `persons` table with default value 'CUSTOMER'.
         * Preserves all existing persons, transactions, sync records, and recurring rules.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `persons` ADD COLUMN `role` TEXT NOT NULL DEFAULT 'CUSTOMER';")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ledger_database"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
