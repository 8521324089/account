# Final Release Readiness Verification — Ledger Android App

**Date:** 2026-10-30  
**Application ID:** `com.kb.ledger`  
**Target Version:** `1.0.1` (`versionCode = 2`)  
**Target Devices / Platforms:** Android 8.0 (API 26) through Android 15 (API 35)  
**Lead Auditor:** Antigravity AI Assistant  
**Repository Working Directory:** `/Users/vivekkumar/Documents/Project/android-app/`  

---

## Executive Summary & Three-Way Release Decisions

An evidence-based release-readiness verification was conducted on the production release artifact of the Ledger Android app (`com.kb.ledger`). The audit evaluated two distinct distribution scenarios (new users vs. existing users) as well as the migration requirements for the operator's primary physical phone.

Automated tests alone were **not** assumed to guarantee release safety. Every operational claim was subjected to direct verification on the codebase, cryptographic signature tooling, and an isolated Android emulator (`emulator-5554`).

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           EXECUTIVE RELEASE DECISIONS                           │
├─────────────────────────────────────────┬───────────────────────────────────────┤
│ Distribution Target                     │ Decision                              │
├─────────────────────────────────────────┼───────────────────────────────────────┤
│ A. New User / Clean Install Release     │ GO WITH CONDITIONS                    │
│ B. Existing User / Data Update          │ NO-GO                                 │
│ C. Physical Phone Migration             │ NO-GO                                 │
└─────────────────────────────────────────┴───────────────────────────────────────┘
```

### Detailed Decision Rationale & Blockers

#### A. New User / Clean Install Release: GO WITH CONDITIONS
* **Verdict:** **GO WITH CONDITIONS**
* **Evidence:**
  1. The release APK (`app-release.apk`) installs cleanly on a fresh emulator (`Success`), launches without crashes (cold start total time 8021ms), and operates 100% autonomously offline.
  2. Core accounting calculations (RECEIVE minus GIVE, per-person net balances, dashboard aggregations) operate with integer cents precision.
  3. Local Storage Access Framework (SAF) export and import round-trips customer records, transactions, recurring rules, and genuine receipt images without corruption.
  4. Full automated test suite passes: 316/316 unit and migration tests pass across 26 suites (0 failures, 0 errors, 0 skipped).
* **Release Conditions:**
  1. **Condition 1 (Off-Device Key Backup):** The operator must independently archive the production release keystore (`ledger-release.jks`) and credentials in a secure off-device vault. Currently, the local workstation is a single point of failure.
  2. **Condition 2 (Clarify or Hide Cloud Sync UI):** The navigation drawer displays `"Sign in to enable Drive cloud sync"` and `"Sign In with Google"`. Because `WEB_CLIENT_ID` is set to a placeholder, clicking this button crashes with `IllegalStateException`. For a public release, this button should be hidden or disabled with a "Cloud sync coming soon" label to avoid misleading users.
  3. **Condition 3 (Minification Acknowledgment):** R8 code shrinking and resource minification remain disabled (`isMinifyEnabled = false`). This is acceptable for personal distribution, but public app store distribution would benefit from enabling minification after validating ProGuard rules against Room, Gson, and Compose.

#### B. Existing User / In-Place Update: NO-GO
* **Verdict:** **NO-GO**
* **Blocker 1 (Cryptographic Signature Incompatibility):** Any existing user running an earlier debug-signed build (`versionCode = 1`) cannot install `app-release.apk` as an in-place update. Android OS enforces signature consistency; attempting to install the production release APK over a debug installation fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
* **Blocker 2 (Data Loss on Forced Uninstall):** If a user attempts to resolve the signature mismatch by uninstalling the previous debug build, Android OS permanently deletes `/data/data/com.kb.ledger/`, destroying all SQLite ledger records and local receipt attachments.
* **Remediation Required:** A data-preserving migration path requires in-app SAF backup export from the old build prior to uninstallation.

#### C. Physical Phone Migration: NO-GO
* **Verdict:** **NO-GO**
* **Blocker 1 (No In-App Export Yet Created on Phone):** The physical OnePlus phone contains valuable live customer ledger data. No current in-app SAF export has been generated or copied off-device.
* **Blocker 2 (Off-Device Dual Backup Unverified):** Two independent copies of the phone's exported backup have not yet been archived or matched by SHA-256 hash.
* **Blocker 3 (Stale SQLite Baseline):** The historical raw SQLite extraction from Session 1 (1,382 persons, 12 transactions) is stale and cannot be used to verify current live ledger balances.
* **Blocker 4 (Trial Restore Pending):** A trial restore of the real phone's export has not yet been executed on an isolated emulator to reconcile individual customer balances and verify native image decoding.
* **Remediation Required:** The operator must execute the step-by-step manual export and emulator trial restore procedure documented in Section 6 before modifying the physical phone.

---

## 1. Safety Invariants & Audit Environment

Throughout this verification audit, strict safety rules were enforced:

| Safety Rule | Status | Evidence / Verification Method |
| :--- | :---: | :--- |
| **Physical Phone Isolation** | **VERIFIED** | OnePlus phone (`LR8H55PB9PMRNFUO`) was disconnected/untouched. Zero ADB commands, zero package touches, zero data clearing. |
| **Emulator Target Verification** | **VERIFIED** | ADB commands explicitly targeted `-s emulator-5554`. Verified `ro.build.characteristics=emulator` and model `sdk_gphone64_arm64`. |
| **Zero Git Destruction** | **VERIFIED** | Preserved all 14 modified files and 34 untracked project files. Zero calls to `git reset`, `git clean`, or `git checkout`. Clean whitespace verified via `git diff --check`. |
| **Signing Key Protection** | **VERIFIED** | Release keystore (`ledger-release.jks`) was never regenerated, replaced, or printed. Passwords and private key material were never exposed. |
| **Customer Privacy Preservation** | **VERIFIED** | No real customer PII, phone numbers, transaction amounts, or receipt images were dumped or recorded. Testing used 100% synthetic data. |
| **No Silent Source Fixes** | **VERIFIED** | Source code was inspected in read-only mode during this audit. All findings are reported as recommendations for separate tasks. |

---

## 2. Project Working Tree & Automated Test Verification

### Git Working Tree Audit
- **Modified Tracked Files (14 files preserved):**
  `.gitignore`, `.idea/compiler.xml`, `.idea/deploymentTargetSelector.xml`, `.idea/kotlinc.xml`, `.idea/migrations.xml`, `.idea/misc.xml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/kb/ledger/MainActivity.kt`, `app/src/main/java/com/kb/ledger/data/AppDatabase.kt`, `app/src/main/java/com/kb/ledger/data/dao/PersonDao.kt`, `app/src/main/java/com/kb/ledger/data/dao/TransactionDao.kt`, `app/src/main/java/com/kb/ledger/data/model/Person.kt`, `app/src/main/java/com/kb/ledger/data/model/Transaction.kt`, `app/src/main/java/com/kb/ledger/util/BackupManager.kt`, `app/src/main/java/com/kb/ledger/util/ContactManager.kt`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `local.properties`.
- **Untracked Project Files (34 files preserved):**
  Retained in-place including schemas (`app/schemas/`), scripts (`scripts/`), documentation, and test artifacts.
- **Git Whitespace Audit:**
  ```bash
  git diff --check
  ```
  *Result:* **Exit code 0** (Zero whitespace errors, zero conflict markers).

### Test Suite Execution & Results
All automated tests were freshly executed using certified JDK 17 (`Eclipse Temurin 17.0.21+9`):

```bash
./gradlew testDebugUnitTest --rerun-tasks
```
*Result:* **BUILD SUCCESSFUL** (Exit code 0)  
*Total Test Suites Executed:* **26 suites**  
*Total Tests Executed:* **316 tests**  
*Failures:* **0**  
*Errors:* **0**  
*Skipped:* **0**  
*Pass Rate:* **100%**  

```bash
python3 scripts/test_clean_dev_logs.py
```
*Result:* **6/6 tests passed** (Exit code 0). Verifies 7-day log retention compliance without accidental deletion of permanent docs.

### Automated Test Coverage Matrix

| Architectural Behavior | Test Suite Coverage | Status |
| :--- | :--- | :---: |
| **Ledger Balance Calculations** | `DashboardTotalsTest`, `OfflineFirstTransactionRepositoryTest` | **PASS** |
| **GIVE and RECEIVE Transactions** | `OfflineFirstTransactionRepositoryTest`, `Migration2To3Test` | **PASS** |
| **Soft-Deleted Persons & Transactions** | `PersonArchiveAndTxEditTest`, `ExternalBackupRecoveryTest` | **PASS** |
| **Backup Export & Restore (SAF)** | `BackupManagerSchemaTest`, `ExternalBackupRecoveryTest` | **PASS** |
| **Collision Handling & Idempotency** | `BackupManagerSchemaTest`, `ExternalBackupValidationIntegrationTest` | **PASS** |
| **Receipt Staging & Promotion** | `BillImageManagerTest`, `ExternalBackupRecoveryTest` | **PASS** |
| **Missing / Corrupt Attachments** | `ExternalBackupRecoveryTest`, `BackupExportSafetyAndFeedbackTest` | **PASS** |
| **Export/Import Concurrency** | `BackupExportSafetyAndFeedbackTest` | **PASS** |
| **Privacy-Safe Diagnostic Logging** | `BackupExportSafetyAndFeedbackTest`, `test_clean_dev_logs.py` | **PASS** |

### Important Behaviors NOT Covered by Automated Tests
1. **End-to-End SAF Document Picker Streaming:** Unit tests run on JVM/Robolectric and mock `ContentResolver` streams; actual Android `DocumentsUI` IPC and file descriptor streaming require on-device/emulator validation.
2. **Native Android Bitmap/Coil Rendering:** JVM tests do not exercise Android's native image decoding pipeline (`libjpeg-turbo`, `Skia`, `BitmapFactory`). Staged images with valid headers but corrupt entropy pass JVM tests but fail on Android.
3. **Google Sign-In & Drive REST API:** Disabled in unit tests; `GoogleAuthManager` runs with dummy credentials.
4. **Android Package Signature Upgrade Compatibility:** Cryptographic OS package manager rules cannot be asserted in JVM unit tests.
5. **R8/ProGuard Minification & Reflection:** Minification is currently disabled (`isMinifyEnabled = false`). If enabled in the future, reflection and Gson serialization would require explicit runtime testing.

---

## 3. Cryptographic Audit of Release APK

The production release APK was inspected directly using Android SDK build tools (`aapt` and `apksigner`):

| Property | Measured Value / Forensic Evidence |
| :--- | :--- |
| **File Location** | `app/build/outputs/apk/release/app-release.apk` |
| **File Size** | `16,019,052 bytes` (15.28 MB) |
| **SHA-256 Digest** | `42ef40cb49631767a9b5fb4c402d017443d2eabdfe7868a88ce9e5a140b0dbd1` |
| **Package Name** | `com.kb.ledger` |
| **Version Code** | `2` |
| **Version Name** | `1.0.1` |
| **SDK Constraints** | `minSdkVersion: 26` (Android 8.0), `targetSdkVersion: 35` (Android 15), `compileSdkVersion: 35` |
| **Signature Schemes** | APK Signature Scheme v2 = `true`; Scheme v1 = `false`; Schemes v3, v3.1, v4 = `false` |
| **Signer Certificate DN** | `CN=Ledger Production Release, OU=Ledger Mobile, O=KB Ledger, L=Bangalore, ST=Karnataka, C=IN` |
| **Signing Cert SHA-256** | `4A:44:9F:E0:03:66:4D:2F:10:F5:2E:44:DA:7F:9A:2F:9F:DE:0F:E5:CE:07:59:A8:29:6E:3B:01:0F:D9:A7:D5` |
| **Signing Cert SHA-1** | `C0:9C:61:DC:D0:76:9D:80:8B:87:FD:E9:9B:A5:6F:48:5A:58:FC:12` |
| **Permanent Key Match** | **CONFIRMED** — Matches intended permanent release key. |
| **Keystore Permissions** | `-rw-------` (mode `600`) on `ledger-release.jks` (restricted to current user). |
| **Git-Ignore Protection** | **CONFIRMED** — `*.jks`, `*.keystore`, and `keystore.properties` are listed in `.gitignore`. |
| **Off-Device Recovery** | **BLOCKED / NOT VERIFIED** — Keystore exists only on the local workstation. Off-device recovery must remain BLOCKED until the operator creates an independent encrypted off-device backup. |

---

## 4. Release APK Testing on Isolated Android Emulator

The production release APK (`app-release.apk`) was installed and tested on isolated Android emulator `emulator-5554` (`Android 15, API 35, ARM64`):

```bash
adb -s emulator-5554 install app/build/outputs/apk/release/app-release.apk
```
*Result:* **Success**

### Device Inspection & Launch Verification
- **Package Signature on Device:** `signatures:[395a3ae5]` (Production key confirmed).
- **Debuggable Flag:** Absent (`pkgFlags: [ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]`).
- **Cold Application Launch:**
  ```bash
  adb -s emulator-5554 shell am start -W -n com.kb.ledger/.MainActivity
  ```
  *Result:* Status: `ok`, LaunchState: `COLD`, TotalTime: `8021ms`.
  *Startup Crash Check:* **0 crashes**, zero unhandled exceptions in `logcat`.

### Functional UI & Synthetic Data Verification
1. **Main Ledger Screen:** Opens directly to dashboard in clean state.
2. **Customer Creation:** Created synthetic person `"Alice Sharma"` (`9876543210`). Contact saved and rendered immediately.
3. **GIVE Transaction:** Recorded GIVE ₹100.00. Balance displayed: `"You Give ₹100.0"`.
4. **RECEIVE Transaction:** Recorded RECEIVE ₹40.00. Net balance updated to `"You Give ₹60.0"`.
5. **Net Balance Formula:** Adheres strictly to $\text{Net Balance} = \sum \text{RECEIVE} - \sum \text{GIVE} = 40 - 100 = -60$ (Negative = "You Give").
6. **Data Persistence Across App Restart:** Closed app via `am force-stop` and relaunched. All synthetic records and balances were 100% preserved.
7. **Backup and Restore UI:** Navigation drawer opens; "Export Backup" and "Import Backup" trigger Android `DocumentsUI` SAF picker correctly.

---

## 5. Synthetic Backup and Restore Verification

To test backup and restore robustness, a synthetic dataset (`saf_exported_backup.json` and `real_image_backup.json`) was pushed to `/sdcard/Download/` and restored via the app's SAF import flow:

### Test Dataset Composition
- **Active Persons:** 4 distinct active accounts.
- **Soft-Deleted Person (Tombstone):** `"Charlie Verma"` (`isDeleted = true`).
- **Duplicate Phone Numbers:** Multiple persons sharing the same phone number to verify disambiguation without row merging.
- **Transactions:** 136 total transactions (GIVE, RECEIVE, soft-deleted tombstones).
- **Recurring Rules:** 2 recurring rules scheduled.
- **Receipt Attachments:** Header-only test stubs and a genuine 100x52px JPEG image.

### Verification Findings
1. **Schema Version Compatibility:** Supported Schema Version 3 parsed and validated cleanly.
2. **Entity Reconstruction:** Restored 3 active contacts on dashboard (`Alice Sharma`, `Ramesh Kumar`, `Synthetic Image User`) and 1 archived contact (`Bob Patel`).
3. **Tombstone Preservation:** Soft-deleted person `"Charlie Verma"` was **NOT** resurrected into the active customer list.
4. **Idempotency & Re-Import Safety:** Importing the backup a second time executed idempotently: person count remained unchanged, transaction count remained unchanged, zero duplicate records created.
5. **Collision Handling:** Existing persons matched by `cloudId` and phone guard; local auto-increment IDs remapped foreign keys without primary key collisions.
6. **Financial Balance Verification:**
   - Alice Sharma Net Balance: `You Give ₹1285.0`
   - Ramesh Kumar Net Balance: `You Give ₹80.0`
   - Synthetic Image User Net Balance: `You Give ₹50.0`
   - Total Dashboard "You Give": `₹1415.0` ($1285 + 80 + 50 = 1415$). Matches underlying ledger exactly.

---

## 6. Multi-Level Image Validation Breakdown

Image validation in `com.kb.ledger` operates across distinct levels. A valid container header does **not** guarantee that an image can be decoded or displayed by Android.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           IMAGE VALIDATION LEVELS                               │
├─────────────────────────────────────────┬───────────────┬───────────────────────┤
│ Validation Level                        │ Header Stub   │ Genuine 100px JPEG    │
├─────────────────────────────────────────┼───────────────┼───────────────────────┤
│ Level 1: Base64 Decoding & Byte Length  │ PASS (3/3)    │ PASS (1/1)            │
│ Level 2: Magic Byte Header Validation   │ PASS (3/3)    │ PASS (1/1)            │
│ Level 3: Native Android Decode & UI     │ FAIL (0/3)    │ PASS (1/1)            │
└─────────────────────────────────────────┴───────────────┴───────────────────────┘
```

### Detailed Level Analysis
- **Level 1 (Base64 Decoding):** Verified Base64 payload decodes into non-empty binary bytes without truncation.
- **Level 2 (Container Header / Magic Bytes):** `BackupManager.kt:L564` (`isValidReceiptFile`) verifies file length $\ge 12$ bytes and matches standard magic byte prefixes:
  - JPEG: `FF D8 FF`
  - PNG: `89 50 4E 47 0D 0A 1A 0A`
  - WebP: `52 49 46 46 ... 57 45 42 50`
  *Result:* Both 16-byte stubs and genuine images pass Level 2.
- **Level 3 (Native Android Skia/Coil Decoding & UI Display):**
  - **Header-Only Stubs:** Android's `BitmapFactory` and Coil image loader fail to decode the zero-entropy payload into a bitmap. In the UI, the receipt thumbnail displays `"Image unavailable / Tap to replace"`.
  - **Genuine 100px JPEG:** Decodes into a valid Android Bitmap. The UI renders the active thumbnail image, and opening the transaction displays `"Edit Transaction"` with `"Tap image to preview"`.
- **Conclusion:** Level 3 native Android image decoding is required for receipt verification. The release APK successfully handles genuine images and fails safely on corrupt/stub images without crashing.

---

## 7. Physical-Device Backup & Migration Blockers

The primary physical phone (`OnePlus Nord CE 2 5G`) contains live business ledger data. Under no circumstances should destructive operations or in-place APK installation be attempted directly on that device.

### Audit Corrections to Previous Documentation
1. **Keystore Security vs. Off-Device Recovery:** Previous documents marked keystore backup as PASS. This was corrected: local filesystem permissions (mode 600) and `.gitignore` are **PASS**, but off-device disaster recovery is **BLOCKED** until the operator creates an independent off-device archive.
2. **Rollback APK vs. Phone Compatibility:** A preserved debug APK exists (`app-debug.apk`), but it cannot be used to update or rollback a release build. Furthermore, the release APK cannot update the phone's debug build.
3. **Stale SQLite Extraction:** The 1,382-person SQLite database extracted during Session 1 is historical and stale. It must not be used as a current production baseline.
4. **Physical Export Status:** Remains **NOT TESTED** until the operator manually exports a current JSON backup from the phone app.

### Required Manual Operator Steps (Before Any Device Upgrade)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│             MANDATORY OPERATOR PHYSICAL PHONE VERIFICATION STEPS            │
└─────────────────────────────────────────────────────────────────────────────┘
  [Step 1] In-App Backup Export on Physical Phone
           Open LedgerApp -> Navigation Drawer -> Export Backup -> Save to Downloads.
                                    │
                                    ▼
  [Step 2] Dual Off-Device Preservation
           Copy exported JSON to workstation via USB or Drive.
           Create Copy A (working) and Copy B (read-only cold archive).
                                    │
                                    ▼
  [Step 3] SHA-256 Hash Matching
           Run: shasum -a 256 phone_backup_copy_A.json phone_backup_copy_B.json
           Verify exact hash match.
                                    │
                                    ▼
  [Step 4] Isolated Emulator Trial Restore
           Push backup to emulator-5554: adb -s emulator-5554 push backup.json /sdcard/Download/
           In emulator release app: Drawer -> Import Backup -> Select backup.json.
                                    │
                                    ▼
  [Step 5] Customer Balance & Record Reconciliation
           Verify: Person count, transaction count, tombstones.
           Reconcile 5 top customer balances individually against phone screen.
                                    │
                                    ▼
  [Step 6] Receipt Image Native Display Test
           Open transactions with attached receipts.
           Confirm thumbnails render and preview dialogs open without error.
                                    │
                                    ▼
  [Step 7] Retain Original Phone App Untouched
           Keep physical phone untouched until Steps 1-6 are fully verified.
```

---

## 8. Product Claims & Account / Cloud Behavior Audit

An inspection of the codebase and runtime UI revealed several discrepancies between product claims and operational reality:

| Feature / UI Claim | Operational Reality | Finding / Severity |
| :--- | :--- | :--- |
| **Offline Ledger Operation** | 100% operational. All ledger views, transactions, balances, and search work without network. | **VERIFIED PASS** |
| **Local SAF Backup & Restore** | Fully functional offline. Exports and imports JSON backups via Android DocumentsUI. | **VERIFIED PASS** |
| **"Sign In with Google"** | Drawer displays sign-in button. However, `GoogleAuthManager.WEB_CLIENT_ID` is a placeholder string. | **INOPERABLE / CRASH RISK** |
| **"Sign in to enable Drive sync"** | Clicking sign-in throws `IllegalStateException: Google Web Client ID is not configured`. | **MISLEADING UI** |
| **Cloud Upload / Download** | WorkManager sync engine is written but cannot authenticate without a valid Client ID. | **INOPERABLE** |
| **Multi-Account Partitioning** | Room database is not partitioned per Google account. Account switching could mix records. | **DESIGN GAP** |

### UI Recommendation
For the initial release, the navigation drawer header should display `"Ledger (Offline Mode)"`, and the Google Sign-In drawer item should be disabled or hidden until multi-account database partitioning and Cloud Console OAuth configuration are complete.

---

## 9. Comprehensive Fact vs. Assumption Separation

| Verification Item | Category | Evidence / Exact Status |
| :--- | :---: | :--- |
| Release APK Exists and Compiles | Fact | `app-release.apk` (16,019,052 bytes) built cleanly via JDK 17. |
| Release APK Cryptographic Signature | Fact | Scheme v2 valid, signed by `CN=Ledger Production Release...` (RSA 4096-bit). |
| Certificate SHA-256 Fingerprint | Fact | `4A:44:9F:E0:03:66:4D:2F:10:F5:2E:44:DA:7F:9A:2F:9F:DE:0F:E5:CE:07:59:A8:29:6E:3B:01:0F:D9:A7:D5`. |
| Automated Unit Tests Pass | Fact | 316/316 tests pass across 26 suites (0 failures, 0 errors). |
| Release APK Launches on Clean Emulator | Fact | Cold start 8021ms, zero startup crashes, UI operational. |
| Arithmetic & Financial Invariants | Fact | Paise integer math (`amountCents`), RECEIVE minus GIVE net balances verified. |
| Synthetic SAF Backup/Restore | Fact | Tested on emulator; tombstones preserved, idempotency confirmed. |
| Genuine Image Decoding on Android | Fact | Tested on emulator; 100px test JPEG decodes natively in UI. |
| Local Keystore Permissions Restricted | Fact | File mode 600, `.gitignore` entries verified. |
| Off-Device Keystore Recovery | **Assumption / Gap** | **BLOCKED** — Keystore exists only on local workstation. |
| Google Drive Cloud Sync Operational | **Assumption / Gap** | **INOPERABLE** — Dummy Web Client ID in source code. |
| In-Place Upgrade Compatibility on Phone | **Assumption / Gap** | **BLOCKED** — Android blocks cross-key debug-to-release updates. |
| Real-Device Current Ledger State | **Untested** | **NOT TESTED** — Real phone has not executed an in-app export. |
| Physical-Device Image Attachment Audit | **Untested** | **NOT TESTED** — Live receipts on phone have not been inspected. |

---

## 10. Summary Checklist & Deliverables

- [x] Executive release decisions rendered (New User: GO WITH CONDITIONS; Existing User: NO-GO; Phone Migration: NO-GO).
- [x] Full Git working tree preserved without destructive commands.
- [x] Automated tests freshly executed (316 passing tests, 0 failures).
- [x] Release APK cryptographic identity and signatures audited.
- [x] Clean install and functional verification executed on isolated emulator (`emulator-5554`).
- [x] Synthetic backup, restore, collision handling, and tombstones verified.
- [x] Three-level image validation breakdown completed.
- [x] Real-device migration blockers and step-by-step operator guide documented.
- [x] Misleading product claims and Google Drive sync status clarified.
- [x] Clear separation of verified facts, assumptions, and untested items delivered.
