# `PROJECT_CONTEXT.md` — Permanent Project Reference

> **MANDATORY AGENT STARTUP PROCEDURE:** Before changing code, read `PROJECT_CONTEXT.md`, `PROGRESS_LOG.md`, and `DECISIONS.md`. Inspect the actual repository and Git status. Compare the documented state against the current files. Identify the last verified completed task, current blockers, and exact next task. Continue from that point instead of restarting the project or repeating completed work. Ask the user only when a genuinely unresolved decision or required manual action prevents safe progress.
>
> **SESSION RULE:** At the beginning of every work session, read the project memory files. Before ending any work session, update them. A new conversation cannot be assumed to have access to an old conversation's messages. The repository documentation must be sufficient to reconstruct the working context independently.

---

## 1. Project Identity & Metadata

- **Project Name:** LedgerApp (`com.kb.ledger`)
- **Package Name:** `com.kb.ledger`
- **Platform:** Android (minSdk: 26, targetSdk: 36, compileSdk: 36)
- **Primary Language:** Kotlin 2.0.21
- **UI Framework:** Jetpack Compose (Compose BOM 2024.11.00, Material3)
- **Local Database:** Room 2.6.1 with KSP 2.0.21-1.0.27
- **Current Version:** `versionCode = 6`, `versionName = "1.0.5"`
- **Build System:** Gradle 9.0, Android Gradle Plugin (AGP) 8.7.2
- **Required JDK for Command-Line / Gradle:** Eclipse Temurin JDK 17 (installed at `~/Library/Java/JavaVirtualMachines/jdk-17.0.21+9/Contents/Home`). *Note: JDK 25 triggers AGP 8.7.2 lint-vital parsing errors (`IllegalArgumentException: 25.0.3`); JDK 17 is officially supported and verified for both debug and release builds.*
- **Release Signing:** Permanent 4096-bit RSA key (`ledger-prod-release`) configured via `keystore.properties` loading `ledger-release.jks` (both excluded from Git in `.gitignore`). SHA-256 fingerprint: `4A:44:9F:E0:03:66:4D:2F:10:F5:2E:44:DA:7F:9A:2F:9F:DE:0F:E5:CE:07:59:A8:29:6E:3B:01:0F:D9:A7:D5`. Debug build retains default debug keystore.
- **Automated Tests:** 359/359 unit and migration tests passing across 33 suites (0 failures, 0 errors, 0 skipped), plus 10/10 Android instrumentation tests passing on emulator runtime.
- **Release Artifacts (v1.0.5):**
  - APK: `release-evidence/v1.0.5/app-release-v1.0.5.apk` (16,068,176 bytes, SHA-256: `bee688207b07649b702e60bd7c7fe422d32951ddc9e8a606968a8160409296b6`)
  - AAB: `release-evidence/v1.0.5/app-release-v1.0.5.aab` (15,047,478 bytes, SHA-256: `fa7ec66d7a0f52b32238063b983f8a3a29379af98413f48a30a52f6c8b88efe0`)
  - Preserved Prior Release (v1.0.4): `release-evidence/v1.0.4/app-release-v1.0.4.apk` (`29ca73857f07180c671c63cbdfca581baae73e72a141be2a3f66fb586507c003`) & `app-release-v1.0.4.aab` (`8c6f9c8771e34cbd3dfbf13ccf9e7867a7e9006c979bf71fa20796ce2b1124fc`)
- **Repository Location:** `/Users/vivekkumar/Documents/Project/android-app`

---

## 2. Final Product Goal & Expected Behavior

A reliable, local-first Android ledger application that tracks money given to and received from people, strictly preserves existing business rules, works seamlessly offline, stores each Google account's ledger independently in that account's private Google Drive `AppDataFolder`, supports reliable two-way synchronization and bill-image backup, safely handles account switching and data conflicts, and protects local and cloud data against accidental loss.

### Core Expected Behavior
1. **Offline Ledger Operation:** Every user interaction (viewing balances, adding customers, recording give/receive entries, attaching receipts) works immediately without network access.
2. **Independent Ledgers:** When authenticated with Google Account A, changes sync only to Account A's private Drive `AppDataFolder`. Signing in with Account B isolates Account B's ledger; data is never merged or cross-contaminated.
3. **Bill-Image Cloud Sync:** Locally attached receipt images are uploaded to the account's Drive `AppDataFolder/images/` and referenced by Drive `fileId`, ensuring persistence across device replacements.
4. **Data Loss Protection:** Neither schema migrations, network timeouts, invalid JSON, nor account switching will silently drop or overwrite records.

---

## 3. Original Problem & Architectural Rationale

### The Original Problem
Small businesses and individuals need to track informal credit and debit ("You Give" / "You Get") without relying on complex accounting software or paper notebooks. The initial prototype was a single-file Compose activity (`MainActivity.kt`) with local Room storage, manual JSON export via Storage Access Framework, and device-local image URIs.

**Limitations of the initial prototype:**
- If the device is lost, broken, or reset, data is lost unless the user manually exported a JSON file.
- Attaching receipts stored local `content://` URIs, which become invalid across device migrations or OS permission changes.
- Storing currency as `Double` introduced floating-point rounding errors.
- Lack of background queuing meant network operations could fail silently.

### The Chosen Solution
- **Local-First with Room:** Zero-latency UI, complete offline availability.
- **Google Drive `AppDataFolder`:** Hidden, app-specific cloud directory per user account. Zero server maintenance cost, privacy-preserving, and tied to the user's existing Google account.
- **Independent Account Ledgers:** Eliminates multi-tenant permission complexity and prevents privacy leaks.
- **Paise/Cent Long Arithmetic:** All monetary amounts represented as integer cents (`amountCents: Long`) to ensure 100% mathematical precision.

---

## 4. Preserved Business Rules

These core rules govern calculations throughout the entire system and must not be altered without explicit user authorization:

1. **`GIVE` Transactions:** Money given by the user to another person.
2. **`RECEIVE` Transactions:** Money received by the user from another person.
3. **Net Balance Calculation:**
   $$\text{Net Balance} = \sum \text{RECEIVE} - \sum \text{GIVE}$$
4. **Balance Sign Conventions:**
   - **Negative Balance ($< 0$):** Displayed as **"You Give"** (user owes money).
   - **Positive Balance ($> 0$):** Displayed as **"You Get"** (person owes user money).
   - **Zero Balance ($= 0$):** Displayed as **"Settled"**.
5. **Dashboard Aggregations:**
   - **Total Give:** Sum of all active (non-deleted) `GIVE` transactions.
   - **Received Today:** Sum of all active `RECEIVE` transactions recorded since 00:00:00 UTC/local time of the current day.

---

## 5. Current Architecture & System Design

```
┌────────────────────────────────────────────────────────┐
│                   Presentation Layer                   │
│   MainActivity.kt  │  HomeViewModel  │  TxViewModel    │
│   Jetpack Compose UI  │  StateFlow  │  Lifecycle Aware │
└──────────────────────────┬─────────────────────────────┘
                           │ (ViewModels consume Domain Repositories)
┌──────────────────────────▼─────────────────────────────┐
│                      Domain Layer                      │
│   Models: PersonDomain, TransactionDomain, SyncStatus  │
│   Contracts: PersonRepository, TransactionRepository,  │
│              SyncRepository                            │
└──────────────────────────┬─────────────────────────────┘
                           │
┌──────────────────────────▼─────────────────────────────┐
│                       Data Layer                       │
│  ┌───────────────────────┐   ┌──────────────────────┐  │
│  │   Repositories (v3)   │   │   JSON / Wire Layer  │  │
│  │ OfflineFirstPersonRepo│   │ LedgerJsonSerializer │  │
│  │ OfflineFirstTxRepo    │   │ JsonSchemaValidator  │  │
│  │ [SyncRepository - P]  │   │ (DTOs: Manifest,     │  │
│  │                       │   │  Persons, Tx)        │  │
│  └───────────┬───────────┘   └──────────────────────┘  │
│              │                                         │
│  ┌───────────▼───────────┐   ┌──────────────────────┐  │
│  │      Local (Room)     │   │ Remote / Auth (v4)   │  │
│  │ AppDatabase (v3, Exp) │   │ GoogleAuthManager    │  │
│  │ PersonDao             │   │ (CredMan + OAuth)    │  │
│  │ TransactionDao        │   │ DriveApiService      │  │
│  │ SyncOperationDao      │   │ GoogleDriveRepository│  │
│  └───────────────────────┘   └──────────────────────┘  │
│  ┌──────────────────────────────────────────────────┐  │
│  │ Local Utilities: BackupManager, ContactManager,  │  │
│  │                  NetworkMonitor, Clock, AppResult│  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

### A. Local Database Design (Room v5 — Verified Safe & Deployed)
- Database class: [AppDatabase.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/AppDatabase.kt)
- Schema Export: Enabled (`exportSchema = true`), schemas retained in `app/schemas/com.kb.ledger.data.AppDatabase/` (`2.json`, `3.json`, `4.json`, `5.json`).
- Migrations:
  - `MIGRATION_2_3`: Safe, non-destructive table rebuild migration. Verified by 5 automated tests in [Migration2To3Test.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/migration/Migration2To3Test.kt).
  - `MIGRATION_3_4`: Adds `isArchived INTEGER NOT NULL DEFAULT 0` column to `persons`. Verified by tests in [Migration3To4Test.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/migration/Migration3To4Test.kt).
  - `MIGRATION_4_5`: Creates `recurring_rules` table with foreign key to `persons.id` (ON DELETE CASCADE) and index `index_recurring_rules_personId`. Verified by tests in [Migration4To5Test.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/migration/Migration4To5Test.kt).
  - Chained `MIGRATION_3_4` and `MIGRATION_4_5` verified live on connected phone: upgraded live database from v3 to v5 with 100% data preservation (1,382 persons, 12 transactions).
- Entities:
  - **`Person`** ([Person.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/model/Person.kt)): `id: Long` (auto), `name: String`, `phone: String`, `contactId: String?`, `cloudId: String?` (UUID), `createdAt: Long`, `updatedAt: Long`, `updatedBy: String?`, `isDeleted: Boolean`, `syncPending: Boolean`, `isArchived: Boolean`.
  - **`Transaction`** ([Transaction.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/model/Transaction.kt)): `id: String` (UUID), `personId: Long` (FK $\to$ Person.id ON DELETE CASCADE), `amountCents: Long`, `type: TransactionType` (GIVE/RECEIVE), `note: String?`, `imageUri: String?` (local content URI), `imageFileId: String?` (Drive file ID), `createdAt: Long`, `updatedAt: Long`, `updatedBy: String?`, `isDeleted: Boolean`, `syncPending: Boolean`.
  - **`SyncOperation`** ([SyncOperation.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/model/SyncOperation.kt)): `id: String` (UUID), `type: SyncOperationType` (UPSERT/DELETE), `entityType: SyncEntityType` (PERSON/TRANSACTION/IMAGE), `entityId: String`, `retryCount: Int`, `lastErrorMessage: String?`, `createdAt: Long`.
  - **`RecurringRule`** ([RecurringRule.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/model/RecurringRule.kt)): `id: String` (UUID), `personId: Long`, `amountCents: Long`, `type: TransactionType`, `note: String?`, `frequency: String`, `startDate: Long`, `nextExecutionDate: Long`, `endDate: Long?`, `isPaused: Boolean`, `createdAt: Long`.
- DAOs:
  - [PersonDao.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/dao/PersonDao.kt)
  - [TransactionDao.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/dao/TransactionDao.kt)
  - [SyncOperationDao.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/dao/SyncOperationDao.kt)
  - [RecurringRuleDao.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/dao/RecurringRuleDao.kt)

### B. Repository Layer (Verified Complete)
- [OfflineFirstPersonRepository.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/repository/OfflineFirstPersonRepository.kt): Implements `PersonRepository`. Handles domain mapping, archiving/unarchiving, and durable `SyncOperation` enqueueing on mutations.
- [OfflineFirstTransactionRepository.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/repository/OfflineFirstTransactionRepository.kt): Implements `TransactionRepository`. Preserves integer cents arithmetic, supports transaction editing (`updateTransaction`), soft deletes, and durable `SyncOperation` enqueueing.
- ViewModels ([HomeViewModel.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/viewmodel/HomeViewModel.kt), [TransactionViewModel.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/viewmodel/TransactionViewModel.kt)) depend on repository interfaces.

### C. Serialization & Validation Layer (Verified Complete)
- Serializer: [LedgerJsonSerializer.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/json/LedgerJsonSerializer.kt) (13 tests).
- Validator: [JsonSchemaValidator.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/json/JsonSchemaValidator.kt) (39 tests).

### D. Authentication & Drive Storage Layer (Verified Complete)
- [GoogleAuthManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/remote/GoogleAuthManager.kt): Combines AndroidX Credential Manager for sign-in with `OAuthTokenProvider` (`GoogleAuthUtil`) for OAuth 2.0 access token acquisition with scope `https://www.googleapis.com/auth/drive.appdata`. Includes in-memory 50-minute TTL caching and `onTokenRejected()` invalidation.
- [DriveApiService.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/remote/DriveApiService.kt): OkHttp-based Drive REST API v3 service for `appDataFolder` operations (`listAppDataFiles` with auto-pagination, `downloadFileContent`, `createAppDataFile` multipart, `updateFileContent` PATCH media, `deleteFile`, and HTTP error classification).
- [GoogleDriveRepository.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/remote/GoogleDriveRepository.kt): High-level Drive repository enforcing write-ahead commitment order (`persons.json`, `transactions.json`, then `manifest.json`), deduplication of multiple files with identical names, schema validation gating before applying downloaded records, and overwrite protection preventing destruction of non-empty cloud ledgers with empty local data.

### E. Customer Features & Utilities (Verified Complete)
- [ContactSearchHelper.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/ContactSearchHelper.kt): Normalized grouping and disambiguation of identical contact names without merging records.
- [PaymentReminderHelper.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/PaymentReminderHelper.kt) & [PaymentReminderWorker.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/PaymentReminderWorker.kt): Local reminder generation, WhatsApp/SMS intent dispatch, settled-balance suppression, and lockscreen privacy (`VISIBILITY_PRIVATE`).
- [StatementExportHelper.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/StatementExportHelper.kt): CSV statement generator with opening/closing balances, chronological running balance, and date range filters.
- [BiometricSecurityManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/BiometricSecurityManager.kt): AndroidX BiometricPrompt with PIN/password fallback and session-aware app lock screen.
- [RecurringScheduleEngine.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/RecurringScheduleEngine.kt): Deterministic transaction generation (`rec_${rule.id}_${date}`), month-end date clamping, and idempotency across restarts.
- [AnalyticsEngine.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/AnalyticsEngine.kt): Cashflow breakdown, net balance, monthly timeline, and top debtors/creditors.
- [ThemePreferenceManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/ThemePreferenceManager.kt): System Default, Light, and Dark theme persistence.
- Localization: English ([values/strings.xml](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/res/values/strings.xml)) and Hindi ([values-hi/strings.xml](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/res/values-hi/strings.xml)).

---

## 6. Implementation Roadmap & Current Status

| Stage | Title | Status | Evidence / Notes |
|---|---|---|---|
| **Stage 1** | Inspection & Architecture Definition | **Verified Complete** | Baseline codebase inspected, business rules identified, dependencies audited. |
| **Stage 2** | Architecture Refactoring | **Verified Complete** | Domain models, repository interfaces, and ViewModels created and decoupled from direct DAO access. |
| **Stage 3** | Database Migrations, Serialization & Tests | **Verified Complete** | Safe non-destructive `MIGRATION_2_3`, Room schema export enabled (`2.json`, `3.json`), repositories implemented, 67 unit and migration tests passing. |
| **Stage 4** | Google Auth & Drive Storage Integration | **Verified Complete** | Supported OAuth authorization (`drive.appdata` scope), `DriveApiService`, `GoogleDriveRepository`, UI account controls in `MainActivity.kt`, 37 remote tests added (104 total tests passing, 100% success rate). |
| **Stage 5** | UX Improvements & Bug Fixes (Phase 1) | **Verified Complete & Deployed** | Duplicate search grouping, predictable back navigation, dashboard totals reconciliation, archived contacts (v4), transaction editing, missing image recovery, pending sync UI removal. |
| **Stage 6** | Customer-Focused Features (Phase 2) | **Verified Complete & Deployed** | Payment reminders, statement export, biometric lock, recurring transactions (v5), visual analytics, English/Hindi localization, theme selection. 136 total tests passing. Live USB installed and verified on OnePlus Nord CE 2 5G. |
| **Stage 7** | Bill-Image Persistence, Backup Safety & Atomicity Fixes | **Verified Complete** | `BillImageManager` (internal storage copy-on-pick, migration), `BackupManager` v3 (recurringRules, bill image bytes, `withTransaction`), `deleteZeroBalancePerson` wrapped in `withTransaction`, `DATA_SAFETY_AND_RECOVERY.md`, `BACKUP_RESTORE_TEST_PLAN.md`, 30 new tests. 174 total tests passing. |
| **Stage 8** | Drive Image Upload Worker & Release Readiness | **Next Priority** | WorkManager Drive image upload pipeline, `applyRemotePersons/Transactions` withTransaction wrap. |

---

## 7. Build, Test & Development Environment

### Prerequisites
- macOS system with Android Studio installed.
- Java Home: `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (Java 25).
- Android SDK: `/Users/vivekkumar/Library/Android/sdk` (configured in `local.properties`).

### Commands
- **Run all unit & migration tests:**
  ```bash
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest --no-daemon
  ```
- **Assemble Debug APK:**
  ```bash
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --no-daemon
  ```
- **Install on USB Device:**
  ```bash
  /Users/vivekkumar/Library/Android/sdk/platform-tools/adb -s LR8H55PB9PMRNFUO install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **Launch Application:**
  ```bash
  /Users/vivekkumar/Library/Android/sdk/platform-tools/adb -s LR8H55PB9PMRNFUO shell "am start -W -n com.kb.ledger/.MainActivity"
  ```

---

## 8. Summary of Critical User Decisions

See [DECISIONS.md](file:///Users/vivekkumar/Documents/Project/android-app/DECISIONS.md) for the complete register:
- **Decision 1:** Independent ledger per Google account (`AppDataFolder`). No shared-ledger permissions.
- **Decision 2:** Local-first operation. Offline-first Room database; Drive syncs in background.
- **Decision 3:** Bill-image sync via Google Drive file IDs. No Base64 in JSON.
- **Decision 4:** Safe, non-destructive database migrations. Verified implemented (v2 $\to$ v3 $\to$ v4 $\to$ v5).
- **Decision 5:** Data-integrity-first cloud synchronization. No blind overwrites.
- **Decision 6:** Modern Credential Manager API. No hardcoded secrets.
- **Decision 7:** Integer currency representation (`amountCents: Long`). Verified implemented.
- **Decision 8:** Decoupled wire DTOs and independent schema versioning. Verified implemented.
- **Decision 9:** Soft deletes with tombstone propagation. Verified implemented.
- **Decision 10:** Durable offline operation queue (`SyncOperation`). Verified implemented.
- **Decision 11:** Decoupled offline-first repository layer. Verified implemented.
- **Decision 12:** Duplicate name grouping without row merging. Verified implemented.
- **Decision 13:** Predictable back navigation via Compose BackHandler. Verified implemented.
- **Decision 14:** Non-destructive contact archival (Room v4). Verified implemented.
- **Decision 15:** In-place transaction editing with validation. Verified implemented.
- **Decision 16:** Clear dashboard net balances ("You Give" / "You Get"). Verified implemented.
- **Decision 17:** Privacy-first, user-approved payment reminders. Verified implemented.
- **Decision 18:** Deterministic recurring transactions & Room Migration 4 $\to$ 5. Verified implemented.
- **Decision 19:** Biometric & device credential security architecture. Verified implemented.

---

## 9. File & Component Reference Index

### Documentation
- [PROJECT_CONTEXT.md](file:///Users/vivekkumar/Documents/Project/android-app/PROJECT_CONTEXT.md) — Permanent reference
- [PROGRESS_LOG.md](file:///Users/vivekkumar/Documents/Project/android-app/PROGRESS_LOG.md) — Chronological work log
- [DECISIONS.md](file:///Users/vivekkumar/Documents/Project/android-app/DECISIONS.md) — Numbered decision register
- [DATABASE_SCHEMA.md](file:///Users/vivekkumar/Documents/Project/android-app/DATABASE_SCHEMA.md) — Complete database architecture, exact DDL, and column reference
- [DATABASE_VISUAL_GUIDE.md](file:///Users/vivekkumar/Documents/Project/android-app/DATABASE_VISUAL_GUIDE.md) — Visual guide, balance rules, and DB Browser / Device Explorer instructions
- [database_inspection.sql](file:///Users/vivekkumar/Documents/Project/android-app/database_inspection.sql) — Read-only SQLite diagnostic inspection script
- [DATABASE_AUDIT_REPORT.md](file:///Users/vivekkumar/Documents/Project/android-app/DATABASE_AUDIT_REPORT.md) — Comprehensive structural, migration, and future-proofing audit report
- [DATA_SAFETY_AND_RECOVERY.md](file:///Users/vivekkumar/Documents/Project/android-app/DATA_SAFETY_AND_RECOVERY.md) — Data locations, backup/restore safety guarantees, bill image lifecycle, risk register
- [BACKUP_RESTORE_TEST_PLAN.md](file:///Users/vivekkumar/Documents/Project/android-app/BACKUP_RESTORE_TEST_PLAN.md) — Automated and manual test specifications for backup, restore, and image persistence

### Application Source
- Entry Point: [MainActivity.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/MainActivity.kt)
- Database: [AppDatabase.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/AppDatabase.kt)
- Repositories:
  - [OfflineFirstPersonRepository.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/repository/OfflineFirstPersonRepository.kt)
  - [OfflineFirstTransactionRepository.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/repository/OfflineFirstTransactionRepository.kt)
- Models:
  - [Person.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/model/Person.kt)
  - [Transaction.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/model/Transaction.kt)
  - [SyncOperation.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/model/SyncOperation.kt)
  - [RecurringRule.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/model/RecurringRule.kt)
- DAOs:
  - [PersonDao.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/dao/PersonDao.kt)
  - [TransactionDao.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/dao/TransactionDao.kt)
  - [SyncOperationDao.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/dao/SyncOperationDao.kt)
  - [RecurringRuleDao.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/dao/RecurringRuleDao.kt)
- JSON Wire DTOs & Validation:
  - [LedgerJsonSerializer.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/json/LedgerJsonSerializer.kt)
  - [JsonSchemaValidator.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/json/JsonSchemaValidator.kt)
- Remote & Auth:
  - [GoogleAuthManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/remote/GoogleAuthManager.kt)
  - [DriveApiService.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/remote/DriveApiService.kt)
  - [GoogleDriveRepository.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/remote/GoogleDriveRepository.kt)
- ViewModels:
  - [HomeViewModel.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/viewmodel/HomeViewModel.kt)
  - [TransactionViewModel.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/viewmodel/TransactionViewModel.kt)
- Local Partitioned Backup:
  - [LocalPartitionedBackupManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/backup/partition/LocalPartitionedBackupManager.kt)
  - [BackupChunkEnvelope.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/backup/partition/BackupChunkEnvelope.kt)
  - [BackupManifest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/backup/partition/BackupManifest.kt)
  - [PartitionDateUtils.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/backup/partition/PartitionDateUtils.kt)
  - [CompressedFileHelper.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/backup/partition/CompressedFileHelper.kt)
- Cloud Synchronization & WorkManager:
  - [DriveSyncEngine.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/sync/DriveSyncEngine.kt)
  - [DriveSyncWorker.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/sync/DriveSyncWorker.kt)
  - [DriveSyncManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/sync/DriveSyncManager.kt)
- Utilities & UI Components:
  - [AddressBookContactResolver.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/AddressBookContactResolver.kt)
  - [ContactSearchHelper.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/ContactSearchHelper.kt)
  - [PaymentReminderHelper.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/PaymentReminderHelper.kt)
  - [PaymentReminderWorker.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/PaymentReminderWorker.kt)
  - [StatementExportHelper.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/StatementExportHelper.kt)
  - [BiometricSecurityManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/BiometricSecurityManager.kt)
  - [RecurringScheduleEngine.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/RecurringScheduleEngine.kt)
  - [AnalyticsEngine.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/AnalyticsEngine.kt)
  - [ThemePreferenceManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/ThemePreferenceManager.kt)
  - [BackupFeedbackHelper.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/BackupFeedbackHelper.kt)
  - [ExportStatementDialog.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/ui/ExportStatementDialog.kt)
  - [PaymentReminderDialog.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/ui/PaymentReminderDialog.kt)
  - [RecurringRulesDialog.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/ui/RecurringRulesDialog.kt)
  - [AnalyticsDialog.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/ui/AnalyticsDialog.kt)

### Tests (322 Passing Tests Across 27 Suites — 100% Success Rate)
- Owner Address Book Name Resolution Tests:
  - [OwnerAddressBookResolutionTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/sync/OwnerAddressBookResolutionTest.kt) (9 tests)
- Partitioned Backup Tests:
  - [LocalPartitionedBackupManagerTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/backup/partition/LocalPartitionedBackupManagerTest.kt) (22 tests)
- Cloud Synchronization Tests:
  - [DriveSyncWorkerLogicTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/sync/DriveSyncWorkerLogicTest.kt) (10 tests)
- Migration Tests:
  - [Migration2To3Test.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/migration/Migration2To3Test.kt) (5 tests)
  - [Migration3To4Test.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/migration/Migration3To4Test.kt) (3 tests)
  - [Migration4To5Test.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/migration/Migration4To5Test.kt) (4 tests)
- Repository & Domain Tests:
  - [OfflineFirstPersonRepositoryTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/repository/OfflineFirstPersonRepositoryTest.kt) (4 tests)
  - [OfflineFirstTransactionRepositoryTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/repository/OfflineFirstTransactionRepositoryTest.kt) (7 tests)
  - [DashboardTotalsTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/repository/DashboardTotalsTest.kt) (6 tests)
  - [PersonArchiveAndTxEditTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/repository/PersonArchiveAndTxEditTest.kt) (6 tests)
- Serialization & Validation:
  - [LedgerJsonSerializerTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/json/LedgerJsonSerializerTest.kt) (13 tests)
  - [JsonSchemaValidatorTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/json/JsonSchemaValidatorTest.kt) (29 tests)
- Remote & Drive:
  - [GoogleAuthManagerTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/remote/GoogleAuthManagerTest.kt) (7 tests)
  - [DriveApiServiceTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/remote/DriveApiServiceTest.kt) (12 tests)
  - [GoogleDriveRepositoryTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/data/remote/GoogleDriveRepositoryTest.kt) (18 tests)
- Clean-Install Release Safety & Unconfigured Auth:
  - [CleanInstallReleaseSafetyTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/release/CleanInstallReleaseSafetyTest.kt) (6 tests)
- Utility & Feature Tests:
  - [ContactSearchHelperTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/ContactSearchHelperTest.kt) (7 tests)
  - [PaymentReminderHelperTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/PaymentReminderHelperTest.kt) (6 tests)
  - [StatementExportHelperTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/StatementExportHelperTest.kt) (6 tests)
  - [RecurringScheduleEngineTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/RecurringScheduleEngineTest.kt) (6 tests)
  - [AnalyticsEngineTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/AnalyticsEngineTest.kt) (6 tests)
  - [BackupManagerSchemaTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/BackupManagerSchemaTest.kt) (12 tests)
  - [BillImageManagerTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/BillImageManagerTest.kt) (16 tests)
  - [ExternalBackupRecoveryTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/ExternalBackupRecoveryTest.kt) (50 tests)
  - [ExternalBackupValidationIntegrationTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/ExternalBackupValidationIntegrationTest.kt) (11 tests)
  - [BackupExportSafetyAndFeedbackTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/util/BackupExportSafetyAndFeedbackTest.kt) (30 tests)
- Engineering Script Tests:
  - `scripts/test_clean_dev_logs.py` (6 unit tests verifying safe seven-day log retention)

---

## 13. Engineering Hygiene, Operational Logging & Release Evidence System

To ensure long-term maintainability, traceability, and customer data privacy without repo bloat:

### 1. Ephemeral Operational Logs (`dev-logs/`)
- Located at project root: `dev-logs/YYYY-MM-DD.md`.
- One Markdown file per calendar day documenting tasks, commands, file changes, and observed verification results.
- **Seven-Day Retention Policy:** Retention is managed via `python3 scripts/clean_dev_logs.py` (or `./scripts/clean-dev-logs.sh`). The script keeps exactly 7 calendar days of logs (`today` and prior 6 days) and prunes older dated logs.
- **Dry-Run by Default:** Execution without flags is non-destructive and displays proposed deletions. Actual deletion requires `--apply`. Non-date files (`README.md`, `.gitkeep`, subfolders) are strictly ignored and preserved.
- **Permanent Escalation:** If a daily log records an unresolved serious defect, data-loss incident, or release blocker, the facts must be escalated to `PROGRESS_LOG.md` or `DECISIONS.md` before the log expires.

### 2. Permanent Release Evidence (`release-evidence/`)
- Contains auditable, non-sensitive verification records for each distributed APK.
- Standardized checklist: `release-evidence/RELEASE_CHECKLIST.md`.
- Release-specific evidence: e.g. `release-evidence/v1.0.1_release_evidence.md` recording artifact SHA-256, version metadata, signing certificate fingerprint, migration coverage, and test results.
- **Permanent Retention:** Files in `release-evidence/` are NOT subject to 7-day retention and are permanently tracked in Git.

### 3. Strict Privacy & Sanitization Invariant
All development logs in `dev-logs/` and release evidence in `release-evidence/` must **strictly exclude**:
- Customer names, phone numbers, and contact book details.
- Transaction amounts, notes, and financial balances.
- Receipt images or binary user files.
- Keystores, passwords, private keys, and OAuth authentication tokens.

---

## 14. Future Google Authentication Architecture Audit & Migration Findings

### 1. Current Authentication Status
- **Plumbing Exists, Real Auth Inactive:** [GoogleAuthManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/remote/GoogleAuthManager.kt) integrates Credential Manager and Google Play Services `GoogleAuthUtil` for `https://www.googleapis.com/auth/drive.appdata`. However, `WEB_CLIENT_ID` is set to placeholder `"REPLACE_WITH_YOUR_WEB_CLIENT_ID..."`. Sign-in attempts return `AUTH_CONFIG_MISSING`. Offline operation is 100% autonomous.
- **Account Identity Storage:** Currently persists only `saved_account_email` in `google_auth_prefs`.

### 2. Local Identity Architecture & Gaps
- **Local SQLite Auto-Increment IDs:** [Person.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/model/Person.kt) uses `id: Long` (auto-increment primary key). In contrast, [Transaction.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/model/Transaction.kt) uses a UUID `id: String`, but references `personId: Long`.
- **SAF Import Identifier Collision Risk & Hardened Lifecycle (RESOLVED):** In [BackupManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/BackupManager.kt), restored records use non-destructive identity mapping (`cloudId` matching, name-guarded phone normalization fallback, `@Update` for existing matches, and fresh `id = 0` auto-increment allocation for new persons). Transaction and recurring rule foreign keys are remapped, eliminating primary key collisions and `ON DELETE CASCADE` risks. Transaction tombstones (`isDeleted = existingTx.isDeleted || t.isDeleted`) are strictly preserved, preventing deleted transactions from ever being resurrected. Import, recovery, and export are synchronized process-wide via `backupSyncMutex` (with `activeStagedFiles`/`activePromoFiles` registries), preventing export from reading in-flux receipts during recovery/restore and preventing recovery cleanup from deleting active staging files. Missing attachment payloads in incoming backups are accounted for and surfaced via non-fatal warnings without wiping metadata or breaking repeat-import idempotency. Receipt images are validated for real container signatures (JPEG, PNG, WebP) and promoted via two-phase staging, with privacy-safe diagnostic logging via `SafeLogger`. SAF export defaults to locale-stable timestamped filenames (`ledger_backup_yyyyMMdd_HHmmss.json`) to minimize accidental collisions. Staging protects external destinations prior to write, though the residual non-atomic SAF truncation limitation on interrupted destination streams remains accurately documented.
- **Recurring Rules Unsynchronized:** [RecurringRule.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/data/model/RecurringRule.kt) has no `cloudId`, no `updatedAt`, no `isDeleted` soft-delete tombstone, and is hard-deleted from SQLite.
- **No Durable Ledger ID:** A UUID `ledgerId` exists ephemerally in `manifest.dat` and `manifest.json`, but is not stored in persistent local database metadata.

### 3. Critical Multi-Account Data-Loss & Leakage Risks
- **Account-Switching Cross-Contamination:** The app does not partition SQLite databases per account. If User A logs out and User B logs in, User A's unencrypted local ledger remains in place and will be uploaded to User B's Google Drive if User B's cloud ledger is empty.
- **Blind Overwrite vs Blind Merge:** If User B already has a remote ledger, merging it directly into User A's local database causes cross-tenant record mixing and potential local data destruction.
- **Mutable Identifier Risk:** Binding ownership to email strings (`accountEmail`) breaks on email alias changes or primary email renames. It must bind to Google's immutable numerical Subject Identifier (`sub`).

### 4. Release Strategy Recommendation (Option A Implemented)
- **Implemented for Release Candidate v1.0.2 (versionCode 3):** **Option A (Local-First Release)**.
  - Release candidate operates as a 100% stable, offline-first ledger application.
  - The "Sign In with Google" drawer action has been removed from production release builds.
  - `GoogleAuthManager`, `HomeViewModel`, `DriveSyncManager`, and `DriveSyncWorker` are defensively guarded against placeholder Web Client IDs (`isConfigured = false`), failing-fast safely offline without launching Credential Manager.
  - Misleading Drive cloud sync statements were replaced with transparent notices that the release operates in local offline mode and supports manual SAF backup export/import.
  - Verified on isolated emulator `emulator-5554` across customer creation, GIVE/RECEIVE transaction arithmetic, data persistence, and SAF export/restore round-trip with genuine JPEG receipts.
  - Complete release audit documented in [FINAL_RELEASE_GATE_2026-10-30.md](file:///Users/vivekkumar/Documents/Project/android-app/release-evidence/FINAL_RELEASE_GATE_2026-10-30.md).

### 5. Release Candidate v1.0.3 (versionCode 4) — Android 16 & Camera Receipt Release
- **Target Platform:** Android 16 / API 36 (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`).
- **Camera Receipt Attachment Architecture:**
  - Integrated `ActivityResultContracts.TakePicture()` delegating to system camera without manifest `CAMERA` permissions.
  - Integrated `ActivityResultContracts.PickVisualMedia()` for permissionless modern gallery photo picking.
  - Non-exported `${applicationId}.fileprovider` with `FLAG_GRANT_WRITE_URI_PERMISSION` grant flags.
  - Uncommitted captures staged in `files/staging/`; atomic promotion to `files/receipts/<txId>.jpg` only upon explicit user confirmation ("Use Photo").
  - `ReceiptImageProcessor` decodes safely, strips sensitive GPS EXIF metadata, corrects rotation via EXIF orientation matrix, downsamples images exceeding 2048px to prevent OOM, and re-encodes to clean JPEG.
- **UI/UX Polish:**
  - Standardized on 4dp/8dp spacing system and 48dp minimum touch targets.
  - Polished receipt chooser bottom sheet / dialog with distinct icons and labels.
  - Interactive preview dialog with contextual actions ("Retake" for camera, "Choose Another" for gallery, "Use Photo", and "Cancel").
  - Fullscreen receipt viewer (`BillImagePreviewDialog`).
  - 12 verified visual evidence screenshots archived in `release-evidence/ui/`.
- **SAF Backup & Restore Integration:**
  - Receipts participate in SAF JSON backups under `billImagesByTxId`.
  - Restored JPEG images verified via native ART `BitmapFactory.decodeFile()` (`width=1440, height=1920, config=ARGB_8888`).
  - Cold-restart and data-wipe restore verified on emulator `emulator-5554`.
- **Release Verification & Evidence:**
  - 340 automated unit tests passing across 28 suites (0 failures, 0 errors, 0 skipped).
  - Complete release audit documented in [FINAL_PRODUCTION_READINESS_2026-10-30.md](file:///Users/vivekkumar/Documents/Project/android-app/release-evidence/FINAL_PRODUCTION_READINESS_2026-10-30.md).

### 6. OnePlus 10R Physical Device Safeguard Audit & Cryptographic Blocker (2026-11-01)
- **Target Device:** OnePlus 10R 5G (`CPH2423`, `LR8H55PB9PMRNFUO`, Android 15, API 35, physical hardware `default`).
- **Installed State:** Early development build (`versionCode=1`, `versionName="1.0"`), signed with Android Debug Key (`e1ca16bf...`).
- **Release State:** Production candidate v1.0.3 (`versionCode=4`, `versionName="1.0.3"`, `targetSdk=36`), signed with permanent release key (`4a449fe0...`).
- **Installation Blocker:** Android OS package manager rejects in-place update across keys (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Reinstallation would permanently wipe `/data/data/com.kb.ledger/`. Execution halted: **`B. INSTALLATION BLOCKED — SIGNATURE MISMATCH`**.
- **Safeguard Backups Outside Repository:** Two compressed raw archives (`432d3bcca9a2...`) and two standard schema 3 JSON backups (`2a60d9f3d386...`) saved to `/Users/vivekkumar/Documents/ledger_oneplus_safeguard_backup/`.
- **Preserved Real Entities:** 2,095 contacts, 16 transactions, Net Balance -₹470.00, 1 receipt image (140,085 bytes).
- **Emulator Verification:** 100% round-trip fidelity restored and re-exported on Android 16 (API 36) emulator with native ART decode (`1080x2412, ARGB_8888`), 0 crashes, 0 ANRs.
- **Physical Phone Status:** 100% untouched. 0 bytes lost or altered. Documented in [ONEPLUS_10R_INSTALLATION_REPORT.md](file:///Users/vivekkumar/Documents/Project/android-app/release-evidence/ONEPLUS_10R_INSTALLATION_REPORT.md).

### 7. Authorized Fresh Production Installation on OnePlus 10R (2026-11-01)
- **Target Device:** OnePlus 10R 5G (`CPH2423`, Serial: `LR8H55PB9PMRNFUO`, Android 15, API 35).
- **Authorization:** Operator authorized removal of old debug build and fresh installation of production-signed v1.0.3 with clean database for original business records.
- **Execution:** Old debug build uninstalled (`adb uninstall com.kb.ledger`). Candidate APK installed (`adb install app-release.apk`).
- **Installed State:** Version `1.0.3`, `versionCode = 4`, `targetSdk = 36`, signed with permanent production certificate (`4a449fe0...`).
- **Runtime Verification:** Cold launch 550ms (`Status: ok`), pristine initial database (`Active (0)`, Net Balance `₹0.0`), 0 crashes, 0 ANRs, SAF export picker verified.
- **Operator Readiness:** App is verified clean, hardened, and ready for operator original business data entry. Documented in [ONEPLUS_10R_V1.0.3_FRESH_INSTALL_REPORT.md](file:///Users/vivekkumar/Documents/Project/android-app/release-evidence/ONEPLUS_10R_V1.0.3_FRESH_INSTALL_REPORT.md).

### 8. Release Candidate v1.0.4 (versionCode 5) — Customer & Supplier Separation + Transaction Deletion (2026-11-09)
- **Target Platform:** Android 16 / API 36 (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`).
- **Release Version:** `v1.0.4`, `versionCode = 5`.
- **Customer & Supplier Separation:**
  - Dual-mode segmented toggle (`Customers | Suppliers`) placed directly above search bar with $\ge 48\text{dp}$ touch targets.
  - Entity role field: `PersonRole { CUSTOMER, SUPPLIER }` added to `Person` (Room schema v6) and `PersonDomain`.
  - Non-destructive Room migration: `ALTER TABLE persons ADD COLUMN role TEXT NOT NULL DEFAULT 'CUSTOMER'`.
  - Same-phone independence: Customers and suppliers may share identical mobile numbers with zero merging or collision.
  - Separate queries, DAOs, ViewModels, and search filtering.
  - Supplier accounting rules:
    - GOODS RECEIVED (RECEIVE): Increases payable ("You Owe").
    - YOU PAID (GIVE): Decreases payable (Reduces "You Owe").
    - Net Payable = $\sum \text{RECEIVE} - \sum \text{GIVE}$.
    - Card 1: Total Payable = $\sum \max(\text{Net Payable}, 0)$ (non-offsetting advances).
    - Card 2: Paid Today = $\sum \text{GIVE}$ transactions on current calendar date.
- **Individual Transaction Deletion:**
  - Overflow menu (`MoreVert`) on each transaction row with "Edit Transaction" and "Delete Transaction".
  - Confirmation dialog with explicit party name, transaction type, formatted amount, and balance recalculation warning.
  - Non-destructive soft delete (`isDeleted = 1`), immediate balance recalculation, and receipt file preservation.
- **Backup & Restore Schema v4:**
  - Export schema bumped to version 4 with explicit role preservation.
  - Backward compatible with v1-v3 legacy backups.
- **Release Verification & Evidence:**
  - 350 unit tests passing (0 failures, 0 skipped).
  - Clean dev logs check passing (6/6).
  - Release APK SHA-256: `29ca73857f07180c671c63cbdfca581baae73e72a141be2a3f66fb586507c003`.
  - Release AAB SHA-256: `8c6f9c8771e34cbd3dfbf13ccf9e7867a7e9006c979bf71fa20796ce2b1124fc`.
  - Production key SHA-256: `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5`.
  - Complete release audit documented in [V1.0.4_CUSTOMER_SUPPLIER_AND_DELETE_VERIFICATION.md](file:///Users/vivekkumar/Documents/Project/android-app/release-evidence/V1.0.4_CUSTOMER_SUPPLIER_AND_DELETE_VERIFICATION.md).

### 9. Supplier Contact Fix & Authorized OnePlus 10R Rollout — v1.0.5 / versionCode 6 (2026-11-10)
- **Bug Fix Scope:**
  - Added privacy-preserving system contact picker (`ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE` via `ActivityResultContracts.StartActivityForResult`, requiring zero `READ_CONTACTS` runtime permission) inside `AddPersonDialog` for both Supplier and Customer creation, with contact-lookup/entity fallback, multi-number disambiguation dialog, and graceful no-phone handling.
  - Enhanced [`ContactSearchHelper`](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/util/ContactSearchHelper.kt) to match full/partial phone numbers, spaces, trunk `0`, and country codes (`+91`, `91`, international prefixes) without mutating stored phone strings, while enforcing role isolation (`filterAndGroupForRole`) and numeric-vs-name query prefill (`splitInitialPersonQuery`).
  - Displayed active/archived Suppliers immediately on the Suppliers dashboard (even prior to first transaction) while keeping Customer dashboard `Active (N)` behavior unchanged.
  - Added duplicate-supplier detection (`findExactDuplicate` / `findPhoneDuplicate` + `HomeViewModel.addPerson`) scoped strictly per `PersonRole`.
- **Database Schema:** Room Schema 6 and SAF Backup Schema 4 preserved unchanged (zero migrations needed).
- **Automated Verification:** 359/359 unit tests passing across 33 suites + 10/10 Android instrumentation tests passing (`SupplierContactUiTest` + `DriveIntegrationTest`) + 6/6 dev-log retention tests + `git diff --check` clean.
- **Physical OnePlus 10R Rollout (`CPH2423`, `LR8H55PB9PMRNFUO`, Android 15 / API 35):**
  - Fresh pre-install SAF backup exported (`ledger_backup_20261110_140659.json`, `1,482,901` bytes, SHA-256 `510872b88f45977dcddc852cb4502fcb505ec53fc453a400b6546b80808ef2b0`) and preserved unchanged in dual local off-device directories (`/Users/vivekkumar/Documents/ledger_oneplus_safeguard_backup/v1.0.5_pre_install/` and `/Users/vivekkumar/.ledger_safeguard_backup_mirror/v1.0.5_pre_install/`).
  - Installed `release-evidence/v1.0.5/app-release-v1.0.5.apk` in-place over `v1.0.4` via `adb -s LR8H55PB9PMRNFUO install -r` -> `Success`.
  - Post-install verification: `versionName=1.0.5`, `versionCode=6`, signing cert `4a449fe0...`, `READ_CONTACTS=false`, `Total You Give ₹110.0`, `Active (2)` customers, Suppliers toggle & `ContactPickerActivity` entry verified read-only without creating test records, `4,190` person rows and `4` transaction rows (2 active, 2 deleted tombstones) matched 100% against pre-install backup, 0 crashes in logcat.
  - Full installation report: [ONEPLUS_10R_V1.0.5_INSTALLATION_REPORT_2026-11-10.md](file:///Users/vivekkumar/Documents/Project/android-app/release-evidence/v1.0.5/ONEPLUS_10R_V1.0.5_INSTALLATION_REPORT_2026-11-10.md).
