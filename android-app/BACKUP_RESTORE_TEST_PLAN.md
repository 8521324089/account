# `BACKUP_RESTORE_TEST_PLAN.md` — Backup, Restore, Image Persistence, and Data Safety Test Plan

**Application:** LedgerApp (`com.kb.ledger`)  
**Last Updated:** October 2026  
**Purpose:** Define automated and manual tests required to verify that existing transactions and their bill images survive an upgrade and can be recovered from a verified backup.

---

## Automated Tests (Already Implemented)

### JVM Unit Tests — No Device Required

| Test File | Tests | What is Covered |
|---|---|---|
| [`LocalPartitionedBackupManagerTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/backup/partition/LocalPartitionedBackupManagerTest.kt) | 10 tests | Date partitioning hierarchy (`YYYY/MM/DD`), UTC timezone policy, partition immutability on business date edits, soft delete tombstones, bounded chunk splitting on high-volume days, GZIP corruption recovery, separate bill image storage, and benchmark latency/compression |
| [`DriveSyncWorkerLogicTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/sync/DriveSyncWorkerLogicTest.kt) | 2 tests | Cloud synchronization logic ensuring transactions with bill images are NOT marked synced until their images are successfully uploaded to Google Drive |
| [`BackupManagerSchemaTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/BackupManagerSchemaTest.kt) | 14 tests | Schema v3 round-trips: persons, transactions, recurring rules, bill image bytes (Base64), backward compat with v2 JSON, financial amount precision, soft-delete preservation, archived persons |
| [`BillImageManagerTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/BillImageManagerTest.kt) | 20 tests | File path naming, isolation between transactions, large image storage, overwrite behavior, temp file cleanup, Base64 encode-decode round-trip, BackupData schema v3 image inclusion |
| [`BackupManagerSchemaTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/BackupManagerSchemaTest.kt) | included above | v2 backward compat: missing `recurringRules`/`billImagesByTxId` fields default safely |
| [`OfflineFirstPersonRepositoryTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/repository/OfflineFirstPersonRepositoryTest.kt) | 4 tests | Person save / sync ops |
| [`OfflineFirstTransactionRepositoryTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/repository/OfflineFirstTransactionRepositoryTest.kt) | 7 tests | Transaction save, balance calculation, soft delete, image file ID, and automatic partitioned backup updates |
| [`PersonArchiveAndTxEditTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/repository/PersonArchiveAndTxEditTest.kt) | 6 tests | Zero-balance deletion, archiving isolation |
| [`DashboardTotalsTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/repository/DashboardTotalsTest.kt) | 6 tests | Financial aggregation correctness |
| [`Migration2To3Test.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/migration/Migration2To3Test.kt) | 5 tests | Amount conversion, UUID generation, timestamp backfill |
| [`Migration3To4Test.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/migration/Migration3To4Test.kt) | 3 tests | Archival column, data preservation |
| [`Migration4To5Test.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/migration/Migration4To5Test.kt) | 4 tests | Recurring rules table, index, FK cascade |
| [`LedgerJsonSerializerTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/json/LedgerJsonSerializerTest.kt) | 13 tests | JSON serialization/deserialization for manifest, persons, transactions |
| [`JsonSchemaValidatorTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/json/JsonSchemaValidatorTest.kt) | 29 tests | Field validation, UUID format, positive amounts, FK integrity |
| [`GoogleDriveRepositoryTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/remote/GoogleDriveRepositoryTest.kt) | 18 tests | Write-ahead ordering, rollback on failure, deduplication, overwrite protection |
| [`ExternalBackupRecoveryTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/ExternalBackupRecoveryTest.kt) | 50 tests | Non-destructive ID mapping, phone fallback guard, tombstones non-resurrection, two-phase receipt promotion, intermediate staging, DB-aware recovery, process-wide mutex coordination, byte-signature validation |
| [`ExternalBackupValidationIntegrationTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/ExternalBackupValidationIntegrationTest.kt) | 11 tests | Phase 2 Isolated Validation: Schema v3 export & SHA-256 byte comparison, clean DB restore & balance arithmetic, populated DB repeat idempotency & phone ambiguity guard, and 8 deterministic failure/recovery scenarios |

**Run all automated tests:**
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew testDebugUnitTest --no-daemon
```

---

## Instrumentation Tests (Recommended — Require Device or Emulator)

These tests require a real Android device or emulator with `androidTest` source set.

> [!NOTE]
> These tests are defined here as a specification. Implement them in `app/src/androidTest/` using Room's `MigrationTestHelper` and `ApplicationProvider`.

### IT-1: Bill Image — Select, Save, Reopen

**Purpose:** Verify that a bill image attached to a transaction is readable after the app is closed and reopened.

**Steps:**
1. Open a transaction.
2. Tap "Attach Bill".
3. Select an image from the gallery.
4. Verify that `BillImageManager.hasInternalImage(txId) == true`.
5. Verify that `transaction.imageUri` now points to the internal `filesDir/receipts/<txId>.jpg` path (not `content://`).
6. Kill and reopen the app.
7. Open the same transaction — the image must still be visible.

**Expected:** Image is readable from internal path after restart.  
**Failure:** Image does not appear = content:// URI was stored and expired.

---

### IT-2: Bill Image — Survives Database Migration

**Purpose:** Verify that bill images and their transaction associations survive a Room migration.

**Steps (using MigrationTestHelper):**
1. Create a v4 database with a transaction having `imageUri = "content://..."`.
2. Copy a fake JPEG into `filesDir/receipts/<txId>.jpg` to simulate a pre-existing internal copy.
3. Run `MIGRATION_4_5`.
4. Verify that the transaction still exists with the same `id`, `personId`, and `imageUri`.
5. Verify the image file at `filesDir/receipts/<txId>.jpg` is still present.

**Expected:** Transaction record and image file survive migration intact.

---

### IT-3: Backup Export — Includes Bill Images

**Purpose:** Verify that `BackupManager.exportData()` includes image bytes for all transactions with internal copies.

**Setup:**
1. Insert 2 persons, 3 transactions (2 with images, 1 without).
2. Copy fake JPEG bytes to `filesDir/receipts/<txId>.jpg` for the 2 image transactions.

**Steps:**
1. Call `backupManager.exportData(outputUri)`.
2. Read the exported JSON and parse as `BackupData`.

**Assertions:**
- `backupData.schemaVersion == 3`
- `backupData.persons.size == 2`
- `backupData.transactions.size == 3`
- `backupData.billImagesByTxId.size == 2` (only transactions with internal images)
- `backupData.recurringRules.size >= 0`
- For each image txId: `Base64.getDecoder().decode(encoded)` matches the original bytes.
- `ExportResult.missingImageTxIds` lists the transaction without an image if it had a non-null `imageUri`.

---

### IT-4: Backup Import — Restores Records and Images Atomically

**Purpose:** Verify that `BackupManager.importData()` restores persons, transactions, and bill images; and that failure mid-import does not corrupt the database.

**Part A — Successful import:**
1. Create a v3 BackupData JSON with 3 persons, 5 transactions, 2 recurring rules, 2 bill images (base64).
2. Write to a temp URI.
3. Call `backupManager.importData(tempUri)`.

**Assertions:**
- `ImportResult.success == true`
- `ImportResult.restoredImageCount == 2`
- `db.personDao().getAllPersonsList().size == 3`
- `db.transactionDao().getAllTransactions().size == 5`
- `db.recurringRuleDao().getAllRulesList().size == 2`
- `File(filesDir, "receipts/<txId1>.jpg").exists() == true`
- `File(filesDir, "receipts/<txId2>.jpg").exists() == true`
- Decoded image bytes match the originals.

**Part B — Interrupted import (simulated failure):**
1. Create a v3 BackupData JSON with valid persons but a null/corrupt transactions list.
2. Call `backupManager.importData(corruptUri)`.

**Assertions:**
- `ImportResult.success == false`
- `ImportResult.error` is non-null and descriptive.
- Database is unchanged (person count before == after).

---

### IT-5: Backup Import — Preserves Existing Images

**Purpose:** Verify that importing a backup does not overwrite an existing valid internal image.

**Steps:**
1. Write `"original"` bytes to `filesDir/receipts/<txId>.jpg`.
2. Create a backup with the same `txId` containing `"replacement"` bytes.
3. Call `backupManager.importData(backupUri)`.

**Assertion:**
- `File(filesDir, "receipts/<txId>.jpg").readBytes()` still equals `"original"` bytes (existing file preserved).

---

### IT-6: Failed Drive Upload — Image Preserved Locally

**Purpose:** Verify that a bill image is not lost when the Drive upload fails.

**Steps:**
1. Attach a bill image to a transaction (internal copy created).
2. Disable network (airplane mode or mock DriveApiService to fail).
3. Trigger background sync.

**Assertions:**
- `transaction.syncPending == true` (retry queued).
- `transaction.imageFileId == null` (upload not confirmed).
- `filesDir/receipts/<txId>.jpg` still exists with original bytes.
- Re-enabling network and retrying sync eventually sets `imageFileId` to a valid Drive file ID.

---

### IT-7: Restore After Account Sign-Out and Sign-In

**Purpose:** Verify that re-signing in with the same account restores cloud ledger data.

**Steps:**
1. Add 2 customers and 3 transactions.
2. Trigger Drive sync (verify cloud write succeeds).
3. Sign out.
4. Sign in with the same account.
5. Trigger Drive pull.

**Assertions:**
- All 2 customers restored.
- All 3 transactions restored with correct `amountCents` and `type`.
- Balances are mathematically consistent.

---

### IT-8: Missing or Damaged Attachment Reported

**Purpose:** Verify that a missing internal image is reported rather than silently replaced with an empty reference.

**Steps:**
1. Insert a transaction with `imageUri = "/data/data/com.kb.ledger/files/receipts/<txId>.jpg"` pointing to a non-existent file.
2. Call `backupManager.exportData(outputUri)`.

**Assertions:**
- `ExportResult.missingImageTxIds` contains `<txId>`.
- `ExportResult.success == true` (backup is not aborted due to missing image).
- Exported JSON's `billImagesByTxId` does NOT contain `<txId>`.

---

### IT-9: Interrupted Backup (Process Death During Export)

**Purpose:** Verify that an interrupted export does not corrupt the original database.

**Simulation approach (JVM):**
1. Throw an exception during the JSON write phase.
2. Verify the database is unchanged.

**Assertion:** Original database unchanged; output file may be partial but is never silently committed as valid.

---

### IT-10: Customer Balances and Transaction History Survive Upgrade

**Purpose:** End-to-end verification that balances survive an app upgrade (database migration).

**Steps:**
1. Install an older APK.
2. Add 3 customers with multiple transactions; verify balances on screen.
3. Install the new APK (simulated by running `MIGRATION_4_5` on top of an existing v4 DB).
4. Open the app.

**Assertions:**
- All 3 customers visible with correct names.
- All transactions present with correct amounts.
- Dashboard balances match the pre-upgrade values exactly (no rounding).
- Recurring rules not lost.

---

### IT-11: Recovery from Migration Failure Without Data Loss

**Purpose:** Verify that if a migration fails (e.g., throws an exception), Room does not corrupt existing data.

**Test approach:**
- Register a deliberately failing migration in a test Room database.
- Verify that Room throws `IllegalStateException` with a descriptive message.
- Verify the original v4 database file is not modified.

**Expected:** Room throws, app does not open, original data is intact.

---

## Manual Verification Checklist (Real Device)

Run on the connected Android device after each release candidate build.

### M-1: App Upgrade Preserves Data

- [ ] Install previous APK from `scratch/installed_base.apk`.
- [ ] Add 2 customers, add GIVE and RECEIVE transactions for each.
- [ ] Note balances ("You Give" / "You Get") for each customer.
- [ ] Attach a bill image to at least one transaction.
- [ ] Install the new APK with `adb install -r`.
- [ ] Open the app — confirm all customers and transactions are present.
- [ ] Confirm balances match pre-upgrade values.
- [ ] Confirm the bill image is still viewable.

### M-2: Manual Export and Import

- [ ] Open drawer → Export Backup → save to Files app.
- [ ] Verify the exported JSON is parseable and contains all customers.
- [ ] Open drawer → Import Backup → select the file.
- [ ] Verify `restoredImageCount` notification matches expected image count.
- [ ] Confirm customers and transactions appear after import.

### M-3: Bill Image Persistence After Reboot

- [ ] Attach a bill image to a transaction.
- [ ] Reboot the device.
- [ ] Open the transaction — image must still be visible.

### M-4: Offline Operation

- [ ] Enable airplane mode.
- [ ] Add a customer and a transaction with a bill image.
- [ ] Confirm the transaction appears in the ledger immediately.
- [ ] Re-enable network.
- [ ] Confirm sync indicator disappears (or Drive push succeeds).

### M-5: Account Switch Safety

- [ ] Note all customer records under Account A.
- [ ] Sign out → Sign in with Account B.
- [ ] Verify Account A's customers are NOT visible.
- [ ] Sign out → Sign in with Account A.
- [ ] Verify Account A's customers are restored correctly.

---

## Test Data Baseline

Use the following synthetic test data for reproducible instrumentation tests. Do NOT use real user data.

```kotlin
val testPersons = listOf(
    Person(id = 0, name = "Test Ravi", phone = "9000000001", cloudId = "uuid-ravi", ...),
    Person(id = 0, name = "Test Sita", phone = "9000000002", cloudId = "uuid-sita", ...)
)
val testTransactions = listOf(
    Transaction(id = "tx-001", personId = 1, amountCents = 10000L, type = GIVE, ...),
    Transaction(id = "tx-002", personId = 1, amountCents = 5000L,  type = RECEIVE, ...),
    Transaction(id = "tx-003", personId = 2, amountCents = 20000L, type = GIVE, ...)
)
// Expected balances:
// Test Ravi: RECEIVE - GIVE = 5000 - 10000 = -5000 ("You Give ₹50.00")
// Test Sita: RECEIVE - GIVE = 0 - 20000 = -20000 ("You Give ₹200.00")
```

---

## Phase 2 Isolated External Backup Validation Execution Report (2026-10-25)

### Environment & Safety Blocker Status
- **Physical Device:** Primary physical device (`LR8H55PB9PMRNFUO`, OnePlus Nord CE 2 5G) was detected attached via USB (`transport_id: 11`).
- **Safety Blocker Enforced:** In strict compliance with Safety Rules 5 & 6, all direct ADB commands, emulator APK deployments, and Gradle `connectedAndroidTest` runs were blocked to prevent accidental targeting or mutation of the physical device and its live ledger data.
- **Isolated Test Harness:** Phase 2 external backup validation was executed via dedicated isolated integration test suite [`ExternalBackupValidationIntegrationTest.kt`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/ExternalBackupValidationIntegrationTest.kt) using synthetic entities, in-memory DAOs, and disposable temporary file directories.

### Test Execution Summary

| Test ID | Scope | Synthetic Setup | Expected Result | Actual Result | Status |
|---|---|---|---|---|---|
| `testPhase2A` | Export & Validate | 5 persons (1 active, 1 archived, 1 deleted, 2 shared phone), 6 txs (active GIVE/RECEIVE, 1 tombstone), 2 rules, 3 images (JPEG, PNG, WebP) | Schema v3, nonempty JSON, tombstones preserved, exact SHA-256 byte hashes match | 5 persons, 6 txs, 2 rules, 3 images exported; SHA-256 hashes matched byte-for-byte; valid FKs | **PASS** |
| `testPhase2B` | Clean Restore | Empty database, clean receipts directory, Schema v3 backup payload | All entities restored; receipts placed at canonical paths; dashboard balances match GIVE/RECEIVE arithmetic | Restored 5 persons, 6 txs, 2 rules; Alice net -₹85.00, Bob net +₹150.00, Ramesh net -₹80.00, Total net -₹15.00 | **PASS** |
| `testPhase2C` | Repeat Import | Populated database with locally soft-deleted transaction and ambiguous phone match | Idempotent; 0 duplicate entities; local tombstones preserved; different names sharing phone never merged | Entity counts remained identical; locally deleted tx not resurrected; incoming legacy person allocated new ID | **PASS** |
| `testPhase2D1` | Truncated JSON | JSON cut halfway | Throws `JsonSyntaxException`; import aborted before write; 0 DB corruption | `JsonSyntaxException` caught; zero database mutations | **PASS** |
| `testPhase2D2` | Corrupt Images | Truncated JPEG (<16 B), corrupt PNG, truncated WebP (<12 B) | Validation rejects files; missing images recorded; transactions still imported safely | All 3 corrupt images rejected by `isValidReceiptFile`; safe recovery | **PASS** |
| `testPhase2D3` | Promotion Failure | Simulated I/O error during promotion | Database committed; staged file retained on disk; missing image reported | Staged file retained; final file not exposed; missing image reported | **PASS** |
| `testPhase2D4` | DB Commit Abort | Simulated SQLite failure before commit | Staged temp images deleted; 0 records committed in DB | Temp images purged; 0 records written to DB | **PASS** |
| `testPhase2D5` | Post-Commit Death | DB committed with image ref; process died before promotion | Startup recovery promotes staged image to canonical path | Startup recovery successfully promoted receipt file; hashes match | **PASS** |
| `testPhase2D6` | Repeated Recovery | Repeated startup recovery calls on healthy directory | Idempotent; returns 0; leaves existing valid receipts intact | Returns 0; receipts untouched | **PASS** |
| `testPhase2D7` | Existing Valid vs Invalid | Destination has valid JPEG; incoming replacement is corrupt or duplicate | Existing valid receipt preserved; corrupt replacement rejected; duplicate discarded | Existing valid file untouched; SHA-256 hash intact | **PASS** |
| `testPhase2D8` | Orphan References | Transaction or rule references non-existent person ID | Throws `IllegalArgumentException`; fails fast; zero DB corruption | `IllegalArgumentException` thrown; orphan records rejected | **PASS** |

---

## Phase 3 Android Emulator SAF UI & Process Recovery Validation Report (2026-10-25)

### Device Isolation & Environment
- **Device Disconnection Verified:** Primary OnePlus physical phone was verified completely disconnected from USB. Zero devices were attached.
- **Dedicated Target:** Android Emulator `Medium_Phone_API_37.0` (`emulator-5554`, `transport_id: 12`) launched and verified as the single, exclusive ADB target. Every command strictly targeted `-s emulator-5554`.
- **Target APK:** Debug build (`app-debug.apk`) installed with `run-as com.kb.ledger` access on `emulator-5554`.

### Test Categorization & Verification Breakdown

The 10 emulator tests fall into 4 clearly differentiated execution categories:

#### Category A: Interactive Android SAF UI Tests (4 tests)
- `EMU-SAF-1` (**Live SAF Export UI**): Seeded synthetic entities, txs, rules, and images. Opened drawer $\to$ tapped "Export Backup" $\to$ SAF `CreateDocument` picker opened $\to$ saved `ledger_backup.json` to `/sdcard/Download/`. Result: File generated (40,071 bytes, schema v3). All 5 persons, rules, tombstones exported. Decoded image SHA-256 hashes matched original bytes. (**PASS**)
- `EMU-SAF-2` (**Clean Disposable Restore via SAF UI**): Cleared app state (`pm clear`). Opened drawer $\to$ tapped "Import Backup" $\to$ selected `ledger_backup.json` in system document picker. Result: 5 persons, primary transactions, rules restored. Canonical receipts created in `files/receipts/`. Calculated balances match. (**PASS**)
- `EMU-SAF-3` (**Repeat Import Idempotency via SAF UI**): Re-imported `ledger_backup.json` into the populated database via SAF UI. Result: Exactly 5 persons, 6 primary transactions, 2 recurring rules. Zero duplicate rows in SQLite. (**PASS**)
- `EMU-SAF-8` (**Existing Valid Receipt Non-Overwrite via SAF UI**): Imported backup containing replacement image bytes for existing valid receipt. Result: `tx-img-jpeg.jpg` retained original SHA-256 hash byte-for-byte; replacement image safely discarded. (**PASS**)

#### Category B: SAF UI Negative / Malformed Input Tests (2 tests)
- `EMU-SAF-4` (**Truncated JSON Import**): Pushed 50% truncated backup to `/sdcard/Download/`. Selected in SAF OpenDocument picker. Result: Fails fast with `JsonSyntaxException: EOFException`. Database and filesystem remained 100% untouched. (**PASS**)
- `EMU-SAF-5` (**Corrupt Image Container Handling**): Imported backup with corrupt image payload. Result: Magic-byte validation rejected corrupt bytes; corrupt file never created on disk; transaction record safely imported. (**PASS**)

#### Category C: Filesystem Fault Injection (1 test)
- `EMU-SAF-9` (**Promotion Fault Injection via Read-Only Permissions**): Made `files/receipts` directory read-only (`chmod 500`); attempted promotion. Result: Promotion failed safely; staged file retained on disk without data loss; permissions restored cleanly. (**PASS**)

#### Category D: Cold-Boot Process Restart & Startup Recovery Tests (3 tests)
*Note on Execution Mode: These tests verify startup recovery following simulated interruption using deterministic staging-file setup on disk followed by cold restart (`am force-stop` followed by `am start`). They were not asynchronous mid-stream process kills during an in-flight background worker.*
- `EMU-SAF-6` (**Post-Commit Interruption Healing**): Pre-conditioned state: Transaction committed in DB with image ref; staged file `import_tmp_tx-post-commit.jpg` on disk. Force-stopped app and restarted. Result: `MainActivity` startup recovery detected staged file, verified DB transaction authority, and promoted staged receipt to canonical `tx-post-commit.jpg`. (**PASS**)
- `EMU-SAF-7` (**Repeated Startup Recovery Idempotency**): Launched app repeatedly on healthy receipts directory. Result: Returns 0; receipts remain intact with identical SHA-256 hashes. (**PASS**)
- `EMU-SAF-10` (**Uncommitted Staging Cleanup After Process Kill**): Pre-conditioned state: Uncommitted staged file on disk with NO database record (simulating process death before DB commit). Force-stopped app and restarted. Result: Startup recovery checked DB authority, found no committed tx, and purged uncommitted staging file. (**PASS**)

---

## Phase 4 Staged SAF Export Failure Safety & UI Feedback Report (2026-10-25)

### Scope & Architectural Upgrades
1. **SAF Export Failure Safety:** Complete serialization to app-private staging file (`context.cacheDir/export_staging/staged_export_<uuid>.json`), structural validation before touching external storage, chunked streaming (8 KB buffer) to SAF destination, and guaranteed cleanup in `finally`.
2. **Actionable UI Feedback:** Replaced silent launcher returns with `BackupFeedbackHelper` mapping `ImportResult` and `ExportResult` to clean, actionable, non-PII Toasts on the Compose UI Main coroutine.
3. **Concurrency Prevention:** Introduced `isExporting` and `isImporting` state guards dropping duplicate taps and disabling drawer items during active I/O.
4. **Deterministic Failure Injection:** Injected `DestinationStreamProvider` allowing pure JVM testing without Android UI automation.

### Test Execution Breakdown (`BackupExportSafetyAndFeedbackTest.kt` — 14 Tests)

| Test ID | Scope | Verification Mode | Description & Invariant | Status |
|---|---|---|---|---|
| `T4-EXP-1` | Pre-write Failure | JVM (Injected DB Failure) | Person query fails with SQLite error. Staging file is cleaned up, destination stream is NEVER opened. | **PASS** |
| `T4-EXP-2` | Structural Validation | JVM (Direct File Tests) | `validateStagedBackupFile` rejects non-existent, empty, truncated JSON, missing persons, missing txs, and future schema version 999. Valid envelope returns null. | **PASS** |
| `T4-EXP-3` | Mid-Stream Interruption | JVM (Injected Stream Failure) | Destination stream throws `IOException("Disk full")` midway. Export returns failure, staging file is cleaned up in `finally`, destination file is NOT deleted. | **PASS** |
| `T4-EXP-4` | Temporary File Cleanup | JVM (Success & Failure Paths) | On success, staging directory is empty. On stream open failure, staging directory is empty. | **PASS** |
| `T4-EXP-5` | Chunked Multi-Buffer Streaming | JVM (Synthetic 100 KB+ Payload) | 150 persons, 300 transactions (>12 chunks of 8 KB). Streams completely without holding full payload in memory; parses back with 100% entity and schema integrity. | **PASS** |
| `T4-UI-1` | Import Success Mapping | JVM Unit Test | `ImportResult(success = true)` maps to `ImportUiMessage.Success("Backup restored successfully.")`. | **PASS** |
| `T4-UI-2` | Import Partial Image Warning | JVM Unit Test | Missing images map to singular ("1 bill attachment could not be restored.") and plural ("3 bill attachments could not be restored.") non-fatal warnings. | **PASS** |
| `T4-UI-3` | Corrupt JSON Error Mapping | JVM Unit Test | Malformed / corrupt JSON maps to `ImportUiMessage.Error("Backup file is corrupt or unreadable. Please choose a valid backup file.")`. | **PASS** |
| `T4-UI-4` | Incomplete Records Error Mapping | JVM Unit Test | Missing required persons/transactions arrays map to `ImportUiMessage.Error("Backup file is incomplete or missing required records.")`. | **PASS** |
| `T4-UI-5` | Integrity Error Mapping | JVM Unit Test | Foreign key / unknown person error maps to `ImportUiMessage.Error("Backup data integrity check failed. No records were modified.")`. | **PASS** |
| `T4-UI-6` | Generic Fatal Error Mapping | JVM Unit Test | Uncategorized errors map to `ImportUiMessage.Error("Backup restoration failed. No changes were made to your data.")`. | **PASS** |
| `T4-UI-7` | Picker Cancellation Handling | JVM Unit Test | Dismissing document picker without selecting a file returns `ImportUiMessage.None` (no error or toast displayed). | **PASS** |
| `T4-UI-8` | Export Feedback Mapping | JVM Unit Test | Maps export success, missing image warning, and export failure to clean non-PII UI messages. | **PASS** |
| `T4-UI-9` | Privacy / Zero-PII Invariant | JVM Unit Test | Sensitive customer names, phones, amounts, tokens, and internal file paths are strictly filtered and never leak into UI messages. | **PASS** |

### Verified vs. Residual Risk Boundaries

1. **Behaviors Verified by Tests (JVM Unit & Integration Tests):**
   - Database serialization abort occurs before destination URI stream is opened.
   - Corrupt, missing, or future schema JSON is rejected before destination stream is opened.
   - Stream interruptions partway through writing are safely caught without crashing.
   - Staged temporary files are reliably purged in `finally` blocks across all paths.
   - Large backups (>100 KB) stream cleanly across multiple 8 KB buffer chunks.
   - All import/export outcomes map to generic, actionable, zero-PII messages.
   - Document picker cancellation is handled silently without error alerts.
   - Rapid double taps on export/import are dropped via `isExporting` and `isImporting` guards.

2. **Behaviors Verified on Android Emulator (`emulator-5554`):**
   - Live SAF document creation and import through Android system file picker.
   - Idempotent repeated imports without duplicate primary keys.
   - Non-overwrite of existing valid receipts with identical hashes.
   - Startup self-healing of committed staged files and cleanup of uncommitted staging files after cold restart.

3. **Behaviors Verified via Injected Failure:**
   - Database query exceptions before serialization.
   - Corrupted staging files during envelope validation.
   - Mid-stream `IOException` (disk full / broken pipe) during destination stream writing.
   - Filesystem permission failure (`chmod 500`) during receipt promotion.

4. **Residual Provider-Specific Limitations (Explicitly Unverified):**
   - **SAF Non-Atomic Truncation:** Standard Android document providers (`ExternalStorageProvider`, `DownloadProvider`, Google Drive SAF) truncate the target file to 0 bytes upon `ContentResolver.openOutputStream(uri)`. If an export is interrupted mid-stream due to abrupt process termination or OS shutdown, the external destination file may remain truncated or partially written. Cross-provider atomic replace (`DocumentsContract.moveDocument()`) is unsupported across different provider authorities (local cache vs. external SAF). The app deliberately avoids deleting external files upon failure to prevent destroying pre-existing user backups.

---

## Phase 5 Targeted Remediation: Backup Export Concurrency, Missing Attachments & Versioned Filenames (2026-10-25)

### Scope & Architectural Upgrades
1. **Export Mutex Coordination (`BackupManager.exportData`):**
   - Coordinated via `backupSyncMutex.withLock` so export cannot read receipt files or entities while a restore (`importData`) or startup self-healing (`recoverOrphanedStagingImages`) is modifying them.
   - Lock order inspected: no nested acquisition, zero deadlock risk.
   - Accurately documented residual race boundary: interactive UI editing via `BillImageManager.copyToInternalStorage()` operates on single transaction receipts outside the backup mutex; full transactional isolation is not claimed.
2. **Missing Attachment Accounting on Import (`BackupManager.importData`):**
   - Cross-references transactions in incoming backup having `!imageUri.isNullOrBlank() || !imageFileId.isNullOrBlank()` against `backupData.billImagesByTxId`.
   - Populates `missingImageTxIds = LinkedHashSet<String>()` alongside decode failures, corrupt headers, and promotion failures, completely preventing double-counting.
   - Preserves attachment metadata in Room and maintains idempotency and tombstone preservation across repeat imports.
3. **Locale-Stable Versioned Export Filenames (`BackupManager.generateDefaultExportFilename`):**
   - Suggests `ledger_backup_yyyyMMdd_HHmmss.json` using `Locale.US` to prevent collisions in document providers while avoiding platform-specific number formatting issues.
   - Preserves compatibility and SAF provider safety (does not delete or overwrite files programmatically).

### Test Execution Breakdown (`BackupExportSafetyAndFeedbackTest.kt` — 16 Added Tests, 30 Total)

| Test ID | Scope | Verification Mode | Description & Invariant | Status |
|---|---|---|---|---|
| `T5-CONC-1` | Mutex Waiting & Unlocking | JVM Coroutines (`CompletableDeferred`) | Export waits while `backupSyncMutex` is held by an in-flight operation; proceeds immediately after unlock. | **PASS** |
| `T5-CONC-2` | Exception Releases Mutex | JVM Injected Query Exception | Thrown exception in export releases `backupSyncMutex` in `finally`; mutex is immediately free for acquisition. | **PASS** |
| `T5-CONC-3` | Cancellation Propagation & Mutex Release | JVM Coroutine Cancellation | Cancelling in-flight export coroutine rethrows `CancellationException` and releases `backupSyncMutex` cleanly. | **PASS** |
| `T5-CONC-4` | Sequential Export No Deadlock | JVM Sequential Invocations | Repeated sequential invocations of export acquire and release non-reentrant mutex without deadlocking. | **PASS** |
| `T5-CONC-5` | Consistency of References & Payloads | JVM Byte-for-Byte Check | Exported JSON contains both entity references and Base64 image payload matching internal disk bytes exactly. | **PASS** |
| `T5-ATT-1` | Metadata With No Payload | JVM Synthetic Backup | Transaction claiming `imageUri` but missing from `billImagesByTxId` is reported in `missingImageTxIds`; metadata in DB preserved. | **PASS** |
| `T5-ATT-2` | Drive ID Only With No Payload | JVM Synthetic Backup | Transaction claiming `imageFileId` without local `imageUri` but missing payload is reported in `missingImageTxIds`. | **PASS** |
| `T5-ATT-3` | Corrupt Payload | JVM Header Validation | Corrupt payload fails header validation and is reported in `missingImageTxIds`. | **PASS** |
| `T5-ATT-4` | Promotion Failure | JVM Injected FS Error | Promotion failure via `fileSystem` is reported in `missingImageTxIds`; temp file retained on disk for recovery. | **PASS** |
| `T5-ATT-5` | Multiple Failure Conditions | JVM Deduplication | Transaction with multiple failure triggers is deduplicated via `LinkedHashSet` and reported exactly once. | **PASS** |
| `T5-ATT-6` | Clean Backup (No Attachments) | JVM Synthetic Clean Backup | Transactions without attachments return `restoredImageCount = 0` and empty `missingImageTxIds`. | **PASS** |
| `T5-ATT-7` | Repeat Import Idempotency | JVM Sequential Restore | Repeated import of same backup reports identical missing attachments without duplicate DB records. | **PASS** |
| `T5-NAME-1` | Versioned Filename Generation | JVM Multi-Locale Check | Default export filename matches `ledger_backup_yyyyMMdd_HHmmss.json`; verified identical across US, German, Arabic, and Hindi locales. | **PASS** |

---

## Phase 6 Targeted Remediation: Coroutine Cancellation & Deleted Transaction Warnings (2026-10-30)

### Scope & Architectural Upgrades
1. **Coroutine Cancellation Propagation:**
   - Explicit `catch (e: CancellationException) { throw e }` blocks added before generic `catch (e: Exception)` in both inner image-extraction read loop and outer staging/streaming block.
   - `coroutineContext.ensureActive()` calls added to transactions loop for responsive cooperative cancellation.
   - Mutex release (`backupSyncMutex.withLock`) and staging file cleanup (`stagedFile.delete()`) guaranteed in `finally`.
   - Cancellation rethrows `CancellationException` rather than returning `ExportResult(success = false)`.
2. **Exclusion of Soft-Deleted Transactions from Warnings:**
   - In `exportData()`, `missingImageTxIds` guards `if (!tx.isDeleted)`.
   - In `importData()`, soft-deleted transactions (`tx.isDeleted == true`) are filtered out from `missingImageTxIds` during missing payload checks, payload decode error handlers, and promotion error handlers.
   - Tombstones (`isDeleted = true`) and attachment metadata (`imageUri`, `imageFileId`) are strictly preserved in exported backups and Room storage without modification.
3. **Clarified Concurrency Test Names:**
   - Renamed `export_noDeadlockFromNestedLockAcquisition` to `export_sequentialInvocationsAcquireAndReleaseMutexWithoutDeadlock` with documentation clarifying sequential lock acquisition without claiming nested locking.

### Regression Test Execution Breakdown

| Test ID | Scope | Verification Mode | Description & Invariant | Status |
|---|---|---|---|---|
| `T6-CANC-1` | Cancellation During Image Read | JVM Injected Cancellation | Simulated cancellation during image file reading propagates `CancellationException` and releases mutex rather than converting into missing-attachment warning. | **PASS** |
| `T6-DEL-1` | Import Deleted Tx With Missing Attachment | JVM Synthetic Backup | Soft-deleted transaction with missing attachment payload is excluded from `missingImageTxIds` warning, while active transaction is reported; tombstones and metadata preserved in DB. | **PASS** |
| `T6-DEL-2` | Export Deleted Tx With Missing Attachment | JVM Synthetic DB | Soft-deleted transaction with missing local receipt is excluded from `missingImageTxIds` warning, while active transaction is reported; tombstones and metadata preserved in exported JSON. | **PASS** |



