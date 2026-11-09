# `DATABASE_AUDIT_REPORT.md` — Comprehensive Database, Integrity & Sync Audit

**Application:** LedgerApp (`com.kb.ledger`)  
**Package:** `com.kb.ledger`  
**Current Database Version:** `5`  
**Status:** Audit Completed — No database modification executed  
**Date of Audit:** October 2026  

---

## Executive Summary

A comprehensive structural, forensic, and behavioral audit of the Android Ledger application's local database (`ledger_database`), Room entities, DAOs, migration scripts, offline queue, and Google Drive synchronization was conducted.

### Core Audit Verdict:
- **Structural Health:** **PASSED.** The SQLite database file passes `PRAGMA integrity_check` (`ok`) and `PRAGMA foreign_key_check` (0 violations).
- **Migration Architecture:** **PASSED.** Migrations through version 5 (`MIGRATION_2_3`, `MIGRATION_3_4`, `MIGRATION_4_5`) preserve 100% of existing customer, transaction, and relationship data. Destructive fallbacks have been eliminated.
- **Financial Precision:** **PASSED.** Financial amounts are strictly represented as 64-bit integer paise/cents (`amountCents: Long`), eliminating floating-point rounding errors.
- **Critical Findings & Future Risks Identified:**
  1. *Bill Image Portability:* Bill images are currently stored as device-local `content://` URI strings. Without copying image bytes to app-private storage or completing Drive image uploading, bill images cannot survive device transfers or system URI permission expirations.
  2. *Atomic Multi-Statement Transactions:* Multi-step repository writes (e.g., `deleteZeroBalancePerson` and `BackupManager.importData`) lack `@Transaction` / `withTransaction` blocks, leaving a theoretical risk of partial state if interrupted by process death.
  3. *SQLite Level Constraints:* Database tables lack SQL-level `CHECK (amountCents > 0)` and `CHECK (type IN ('GIVE', 'RECEIVE'))` constraints; validation currently relies entirely on application-layer Kotlin logic.
  4. *Manual JSON Backup Completeness:* The manual export in `BackupManager` omits the `recurring_rules` table and binary image data.

---

## 1. Migration Audit (Version 1 through Version 5)

### Historical Gap Analysis: Version 1 → Version 2
- **Finding:** In early prototypes prior to this architecture work, the app upgraded from schema version 1 to 2 without an explicit migration script, relying on Room's `fallbackToDestructiveMigration()`.
- **Current Protection:** The project has since exported its schema history (`2.json`, `3.json`, `4.json`, `5.json`), eliminated destructive fallback, and implemented explicit migration scripts with JVM tests.

### Audit of Implemented Migrations

#### `MIGRATION_2_3` (Non-destructive Table Rebuild)
- **Objective:** Convert legacy `amount: Double` (REAL) to `amountCents: Long` (INTEGER), add cloud sync columns, assign unique UUID `cloudId`s, and establish the `sync_operations` queue.
- **Evaluation:**
  - Foreign keys disabled (`PRAGMA foreign_keys = OFF`) during rebuild.
  - Mathematical conversion uses `CAST(ROUND(amount * 100) AS INTEGER)`, which correctly rounds IEEE 754 float approximations (e.g. `500.74999999999994` $\to$ `50075`).
  - Unique RFC 4122 v4 UUIDs generated via SQLite's `randomblob` function.
  - Person `createdAt` and `updatedAt` safely initialized from transactions' `MIN(createdAt)` / `MAX(createdAt)` with epoch fallback.
  - Foreign keys re-enabled (`PRAGMA foreign_keys = ON`).
  - **Verdict:** **EXCELLENT.** Verified non-destructive and tested via `Migration2To3Test.kt`.

#### `MIGRATION_3_4` (Customer Archiving)
- **Objective:** Add `isArchived INTEGER NOT NULL DEFAULT 0` to `persons`.
- **Evaluation:**
  - Simple, safe `ALTER TABLE persons ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0;`.
  - Zero table rebuilding, zero data loss risk.
  - **Verdict:** **EXCELLENT.** Verified and tested via `Migration3To4Test.kt`.

#### `MIGRATION_4_5` (Recurring Schedules)
- **Objective:** Create `recurring_rules` table and index `index_recurring_rules_personId`.
- **Evaluation:**
  - Standard DDL table creation with `FOREIGN KEY(personId) REFERENCES persons(id) ON UPDATE NO ACTION ON DELETE CASCADE`.
  - Proper index creation.
  - **Verdict:** **EXCELLENT.** Verified and tested via `Migration4To5Test.kt`.

#### Room Registration & Fallback Check
- **Code:** In `AppDatabase.kt`:
  ```kotlin
  Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "ledger_database")
      .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
      .build()
  ```
- **Fallback status:** No destructive fallback configured. If a migration step is missing, Room fails fast with an explanatory exception.

---

## 2. Data Integrity and Transaction Safety Audit

### Currency Representation & Arithmetic
- **Storage:** `amountCents: Long` in SQLite and Kotlin.
- **Display:** Display properties divide by `100.0` strictly at render time.
- **Aggregations:** SQL queries use `SUM(amountCents)` and `SUM(CASE WHEN type = 'RECEIVE' THEN amountCents ELSE -amountCents END)`.
- **Risk:** Zero risk of binary floating-point rounding errors.

### Constraints & Defensive Typing
- **Strengths:**
  - `personId` foreign keys enforce referential integrity with `ON DELETE CASCADE`.
  - `index_transactions_personId` prevents full table scans on balance queries.
  - Kotlin enums (`TransactionType`, `RecurrenceFrequency`, `SyncOperationType`, `SyncEntityType`) restrict valid states in application memory.
- **Weaknesses:**
  - The SQLite schema does not define table-level `CHECK` constraints:
    - No `CHECK (amountCents > 0)` on `transactions` or `recurring_rules`.
    - No `CHECK (type IN ('GIVE', 'RECEIVE'))` on `transactions`.
    - No `CHECK (length(trim(name)) > 0)` on `persons`.
  - While the UI validates these inputs, any direct SQLite execution or future repository bug could theoretically store invalid records.

### Atomic Transaction Boundaries
- **Strengths:**
  - Single-row inserts and updates in `PersonDao` and `TransactionDao` are inherently atomic in SQLite.
- **Weakness:**
  - In [OfflineFirstPersonRepository.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/repository/OfflineFirstPersonRepository.kt), `deleteZeroBalancePerson()` performs:
    1. `personDao.softDelete(localId, now)`
    2. `syncOperationDao.insert(personSyncOp)`
    3. `txDao.softDeleteForPerson(localId, now)`
    4. Loop over `txs`: `syncOperationDao.insert(txSyncOp)`
  - These calls are executed sequentially across coroutines without an enclosing `database.withTransaction { ... }` or `@Transaction` annotation. An unexpected application termination mid-loop would leave partial tombstone states.
  - In [BackupManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/BackupManager.kt), `importData()` iterates through lists of persons and transactions with individual `insertPerson()` and `insertTransaction()` calls. If parsing or writing fails halfway through, the database remains in an incomplete state.

### Idempotency & Conflict Resolution
- **Recurring Schedules:** Occurrences generate deterministic IDs (`rec_${rule.id}_${nextDate}`). Even if background execution triggers repeatedly, SQLite primary key collision prevents duplicate financial entries.
- **Cloud Conflict Resolution:** The app uses Last-Write-Wins (LWW) based on `updatedAt: Long` (epoch milliseconds).
  - *Risk:* LWW is subject to clock skew across multiple devices if a device's system clock is misconfigured.

---

## 3. Backups, Images, and Google Drive Synchronization Audit

### Data Distribution: Local vs. Cloud
| Entity / Asset | Stored Locally in SQLite | Stored in Google Drive `appDataFolder` | Included in Manual JSON Export |
| :--- | :---: | :---: | :---: |
| **Customers (`persons`)** | YES | YES (`persons.json`) | YES |
| **Transactions (`transactions`)** | YES | YES (`transactions.json`) | YES |
| **Sync Queue (`sync_operations`)** | YES | NO (Local outbox only) | NO |
| **Recurring Rules (`recurring_rules`)** | YES | NO (Local only currently) | **NO (Missing)** |
| **Bill Images (Receipt Photos)** | Local `content://` URI only | Planned (`/images/`) | **NO (Missing)** |
| **Ledger Certificate** | NO | YES (`manifest.json`) | NO |

### Cloud Sync Safety Mechanisms (Verified in `GoogleDriveRepository.kt`)
1. **Write-Ahead Multi-File Commitment:**
   - Data files (`persons.json`, `transactions.json`) are uploaded and committed before `manifest.json`.
   - If an upload fails midway, data files are cleaned up and `manifest.json` is never written. A remote check flags the state as `InterruptedInitialization`, preventing corrupt restores.
2. **Strict Overwrite Protection (`OVERWRITE_PROTECTION`):**
   - The sync engine refuses to push empty local records over active remote records, preventing fresh app installs from wiping cloud data on first connect.
3. **Download Validation Gate (`JsonSchemaValidator`):**
   - Remote files are strictly validated against schema version, required UUIDs, and foreign key relationships before touching Room.
4. **Account Isolation:**
   - Files are stored exclusively in the authenticated account's private `appDataFolder`. Data is never accessible to other Google accounts or stored in public Drive folders.

### Bill Image Lifecycle & Vulnerability
- **Current Behavior:** The UI captures an image URI via `ActivityResultContracts.GetContent()` or `PickVisualMedia()` and stores the raw string (e.g. `content://media/picker/...`) in `transactions.imageUri`.
- **Identified Failure Modes:**
  1. *Lack of Persistable Grant:* Transient content grants expire upon phone reboot if `contentResolver.takePersistableUriPermission()` is not invoked.
  2. *Device Lock-in:* A `content://` URI references local gallery indices on that specific physical device. When restored on another phone or synced via cloud, the URI is meaningless and fails to render.
  3. *Unimplemented Cloud Upload:* While the schema provisioned `imageFileId: TEXT`, background workers uploading binary image data to Drive `appDataFolder/images/` are not yet active in the current build.

---

## 4. Categorized Future-Proofing Recommendations

### Category 1: Confirmed Problems (High Priority)
1. **Bill Image Persistence:**
   - *Problem:* Local `content://` URIs break across device migrations and gallery deletions.
   - *Fix:* Copy selected bill images into the app's internal private storage (`context.filesDir/receipts/${txId}.jpg`) upon selection. Store the internal path locally and upload the binary to Drive `appDataFolder/images/` when online.
2. **Missing `recurring_rules` in Manual Export:**
   - *Problem:* `BackupManager.kt` exports only `persons` and `transactions`. Users who export backups lose their recurring templates.
   - *Fix:* Update `BackupData` class to version 3, including `val recurringRules: List<RecurringRule> = emptyList()`.

### Category 2: Potential Risks Requiring Further Verification (Medium Priority)
1. **Non-Atomic Multi-Row Writes:**
   - *Risk:* If app process terminates during `deleteZeroBalancePerson` or `BackupManager.importData`, tables are left in an inconsistent partial state.
   - *Fix:* Wrap repository multi-statement sequences in `database.withTransaction { ... }`.
2. **Clock Skew in Last-Write-Wins (LWW):**
   - *Risk:* If a user's phone clock is set incorrectly into the future, its edits will permanently overwrite newer edits from properly synchronized devices.
   - *Fix:* Combine epoch timestamps with a monotonic logical revision counter (`version: Long`) on each record.

### Category 3: Recommended Improvements (Code Quality & Robustness)
1. **Schema Check Constraints:**
   - In a future migration (e.g., v6), add SQL-level `CHECK` constraints to guarantee valid positive amounts and recognized transaction types at the SQLite layer.
2. **Duplicate Customer Merging UI:**
   - While `ContactSearchHelper` hides duplicate search entries dynamically, providing an explicit "Merge Contacts" tool in the UI would allow users to consolidate legacy duplicate records cleanly.

### Category 4: Changes Requiring an Explicit Migration or Backup Plan
1. **Adding Schema Check Constraints or Table Alters:**
   - SQLite requires table rebuilding to add `CHECK` constraints to existing tables. Any future migration doing this must strictly follow the non-destructive pattern established in `MIGRATION_2_3`.
2. **Drive Cloud Storage for Recurring Rules:**
   - Adding `recurring_rules.json` to Google Drive `appDataFolder` will require incrementing `CURRENT_MANIFEST_VERSION` from `1` to `2` and updating `JsonSchemaValidator` to maintain backward compatibility with v1 cloud backups.

---

## 5. Verification & Test Suite Execution

### Automated Unit & Migration Tests
Command executed:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --rerun
```
Results:
- **Total Tests Executed:** **144**
- **Test Failures:** **0**
- **Test Errors:** **0**
- **Test Skipped:** **0**
- **Success Rate:** **100%**

#### Database-Specific Test Coverage:
- `Migration2To3Test.kt`: 5 tests verifying amount conversion, UUID generation, timestamp backfill, and data preservation.
- `Migration3To4Test.kt`: 3 tests verifying archival column addition and data survival.
- `Migration4To5Test.kt`: 4 tests verifying recurring rules table creation, index creation, and foreign key cascade.
- `LedgerJsonSerializerTest.kt`: 13 tests verifying serialization/deserialization of manifest, persons, and transactions.
- `JsonSchemaValidatorTest.kt`: 29 tests verifying field validation, UUID formatting, amount positivity, and foreign key link integrity.
- `GoogleDriveRepositoryTest.kt`: 18 tests verifying write-ahead ordering, rollback on failure, deduplication, and overwrite protection.
- `DashboardTotalsTest.kt`: 6 tests verifying financial aggregations and boundary calculations.
- `PersonArchiveAndTxEditTest.kt`: 6 tests verifying safe zero-balance deletion and archiving isolation.

### SQLite Physical Database File Health
Evaluated on database snapshot (`ledger_database`, version 5):
- `PRAGMA integrity_check;` $\to$ **`ok`**
- `PRAGMA foreign_key_check;` $\to$ **`0 violations`**
- Total customers: **1,382** (1,382 active, 0 orphaned)
- Total transactions: **12** (12 active, 0 orphaned)
- Outstanding balance consistency: Verified mathematically consistent with ledger transactions.
