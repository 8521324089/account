# Decision Register: `com.kb.ledger`

This register documents all confirmed architectural, business, and technical decisions for `com.kb.ledger`. Every decision records the context, chosen approach, rationale, rejected alternatives, and rules that future agents must not alter without explicit user approval.

---

## Decision 1: Independent Ledger for Each Google Account (Option A)

- **Status:** Confirmed by User
- **Category:** Architecture & Cloud Storage
- **Date Confirmed:** 2026-10-20

### Context
The application needs cloud synchronization with Google Drive so users do not lose ledger data when changing or resetting devices. We needed to choose between an independent private ledger per Google account or a multi-user/shared ledger model.

### Chosen Decision
**Option A: Independent Ledgers.**
Each Google account owns its private, isolated ledger stored in that account's Google Drive `AppDataFolder` (a private, hidden app folder not directly visible in the Drive web UI).

### Strict Rules & Invariants
1. Each Google account has its own independent dataset.
2. Signing in to a different account must **never** merge, overwrite, or cross-pollinate ledgers.
3. No shared business ledger or multi-party folder permission model is to be implemented.
4. Data must never be copied between accounts without explicit user instruction.
5. Signing out or switching accounts must **never silently delete** local records.
6. The app must store the active account email alongside local records or isolate the Room database per account.

### Rejected Alternatives
- **Shared Google Drive Folder (Option B):** Rejected because it requires complex multi-user ACL management, concurrency locking on cloud files, and introduces severe privacy/leakage risks for personal financial ledgers.

---

## Decision 2: Local-First Operation

- **Status:** Confirmed by User & Verified in Code
- **Category:** Core Operational Philosophy
- **Date Confirmed:** 2026-10-20

### Context
The ledger is used in daily retail and personal situations where network connectivity may be slow, intermittent, or absent.

### Chosen Decision
The application is strictly **local-first**. All reads and writes target the local Room database first. Google Drive sync operates strictly in the background as a supplementary backup and synchronization mechanism.

### Strict Rules & Invariants
1. The user must be able to view customers, add transactions, and record payments completely offline.
2. Everyday ledger operations must never block on a network request.
3. Network failures must not produce fatal errors in the UI; mutations must be recorded locally and queued in `sync_operations`.
4. Changes made while offline must persist durably across app restarts and process death until sync succeeds.

### Rejected Alternatives
- **Cloud-first / Online-only Architecture:** Rejected because intermittent connectivity would lock the user out of critical business transaction logging.

---

## Decision 3: Bill-Image Cloud Synchronization via Drive File IDs

- **Status:** Confirmed by User
- **Category:** Media & Storage
- **Date Confirmed:** 2026-10-20

### Context
Users attach receipts and bills to transactions. Currently, these are stored as Android local `content://` URIs. Local URIs are device-specific, volatile across OS versions, and meaningless on a second device.

### Chosen Decision
Image bytes are uploaded to a dedicated `images/` directory inside the account's Drive `AppDataFolder`. Upon upload confirmation, Google Drive assigns a stable `fileId` (e.g., `1a2b3c...`). This `fileId` is recorded in the transaction record (`Transaction.imageFileId`).

### Strict Rules & Invariants
1. Local `content://` URIs must never be assumed to be permanent or portable across devices.
2. If an image upload fails, the image and the pending upload operation must not be discarded; it must be retried via `SyncOperation`.
3. When restoring on a secondary device, images are fetched on demand from Drive using `imageFileId` and cached locally.
4. Transaction records link to images via `imageFileId`, keeping JSON payloads lightweight.

### Rejected Alternatives
- **Base64-encoded images in JSON:** Rejected because encoding image binaries in `transactions.json` causes huge JSON payloads, excessive memory usage, and severe parsing latency.
- **Relying solely on local `content://` URIs:** Rejected because URIs break when transferring data to a new device.

---

## Decision 4: Safe, Non-Destructive Database Migrations

- **Status:** Verified Implemented & Tested (Non-Destructive)
- **Category:** Data Persistence & Integrity
- **Date Implemented & Verified:** 2026-10-20

### Context
The original app used Room version 1 and version 2, and previously configured `fallbackToDestructiveMigration()`. An initial draft of `MIGRATION_2_3` in `AppDatabase.kt` contained `DROP TABLE` statements that wiped existing records.

### Chosen Decision
**Destructive migrations are forbidden in production.**
All Room migrations preserve existing user records, converting legacy data types and populating newly required columns with safe defaults.
`MIGRATION_2_3` was completely rewritten using a safe SQLite table-rebuild strategy:
1. `transactions_new` table created with `amountCents: INTEGER NOT NULL` and foreign key cascade to `persons(id)`.
2. Existing transactions copied over with `amountCents = CAST(ROUND(amount * 100) AS INTEGER)`.
3. `transactions` table replaced and `index_transactions_personId` index created.
4. `persons_new` table created with `cloudId`, `createdAt`, `updatedAt`, `isDeleted`, `syncPending`.
5. Existing persons copied over, generating unique RFC 4122 v4 UUIDs for `cloudId` and deriving timestamps from transaction history.
6. `sync_operations` table created for durable offline retry queue.
7. Automated tests in `Migration2To3Test.kt` prove 100% data preservation across realistic scenarios, empty databases, and precision boundaries.

### Strict Rules & Invariants
1. Never silently reset or drop the user's local database to resolve a schema migration issue.
2. `fallbackToDestructiveMigration()` must not be reinstated.
3. Every database migration must be accompanied by automated Room migration tests.
4. Room schema export (`exportSchema = true`) must remain enabled in `AppDatabase.kt` and `app/build.gradle.kts`.

---

## Decision 5: Safe Cloud Synchronization & Data Integrity

- **Status:** Confirmed by User
- **Category:** Cloud Synchronization
- **Date Confirmed:** 2026-10-20

### Context
Google Drive is a file store, not an ACID relational database. It lacks row-level transactions and automatic conflict resolution. Network interruptions, concurrent edits, or malformed responses can corrupt cloud data if synchronization is not guarded carefully.

### Chosen Decision
Treat cloud synchronization strictly as a data-integrity challenge. Synchronization follows an explicit, defensive, two-way sync protocol.

### Strict Rules & Invariants
1. **Never overwrite cloud data on error:** A network failure, HTTP 4xx/5xx, auth failure, or JSON parsing failure must never cause the cloud file to be overwritten.
2. **Never overwrite cloud with empty data:** An empty or invalid response must never be interpreted as "cloud data is empty; overwrite with local state".
3. **Soft-delete tombstones:** Deletions are represented by `isDeleted = true` with updated timestamps (`updatedAt`). Records are not hard-deleted until both local and remote stores acknowledge deletion.
4. **Preserve pending local changes:** Local records with `syncPending = true` must not be overwritten by incoming cloud records unless the remote record has a strictly newer timestamp.
5. **No destructive recovery without user consent:** Any action that could cause irreversible data loss requires explicit user confirmation.

### Rejected Alternatives
- **Blind overwrite (Last Write Wins without validation):** Rejected because an outdated client could erase newer records written by another device.
- **Relying on Drive for transaction management:** Rejected because Google Drive does not provide atomic multi-file transactions.

---

## Decision 6: Modern Credential Manager & OAuth Token Delegation

- **Status:** Confirmed by User
- **Category:** Authentication & Security
- **Date Confirmed:** 2026-10-20

### Context
Legacy Android Google Sign-In (`GoogleSignInClient`, `GoogleAuthUtil`) is deprecated by Google. Modern Android apps must use Google Identity `CredentialManager`.

### Chosen Decision
Use AndroidX `CredentialManager` with `googleid` (`GetGoogleIdOption`) for authentication, acquiring a Google ID Token, and delegating access token acquisition for the Google Drive REST API.

### Strict Rules & Invariants
1. No hardcoded OAuth client secrets, API keys, or private tokens in source code.
2. `WEB_CLIENT_ID` in `GoogleAuthManager.kt` must remain a placeholder until supplied via user configuration.
3. Access tokens are cached strictly in memory with an expiration TTL (50 minutes); they are never written to disk or SharedPreferences.
4. Clear separation between code implementation and manual Google Cloud Console configuration (SHA-1 fingerprint, OAuth Client ID, Drive API enablement).

### Rejected Alternatives
- **Deprecated `GoogleSignIn` / `com.google.android.gms.auth.api.signin`:** Rejected because Google is deprecating this API in modern Android versions.
- **Bundled service account credentials:** Rejected because users must authenticate with their own personal Google accounts.

---

## Decision 7: Integer Currency Representation (`amountCents: Long`)

- **Status:** Verified Implemented & Tested in Code
- **Category:** Financial Precision
- **Date Confirmed:** 2026-10-20

### Context
The initial prototype stored transaction amounts as `Double`. Floating-point numbers are susceptible to rounding errors (e.g., `0.1 + 0.2 = 0.30000000000000004`), which is unacceptable for financial ledgers.

### Chosen Decision
Store all financial amounts internally in Room, in memory, and in JSON wire DTOs as integer paise/cents: `amountCents: Long` (where ₹1.00 = 100 paise).

### Implementation Details
- In `Transaction.kt`: `amountCents: Long`, with a computed helper `val amount: Double get() = amountCents / 100.0`.
- In `TransactionDomain.kt`: `amountCents: Long`, with helper `amount: Double`.
- In `MIGRATION_2_3`: `amountCents = CAST(ROUND(amount * 100) AS INTEGER)`.
- In UI dialogs: Input is parsed from decimal text, multiplied by 100, and converted to `Long`.
- In DAOs: Aggregations use `SUM(amountCents)`.

---

## Decision 8: Decoupled Wire DTOs and Independent Versioning

- **Status:** Verified Implemented & Tested in Code
- **Category:** Serialization & API Design
- **Date Confirmed:** 2026-10-20

### Context
Serializing Room entity classes directly to Drive JSON ties cloud file formats directly to internal database implementation details. Changing a Room column would break cloud synchronization across app versions.

### Chosen Decision
Create explicit, versioned Data Transfer Objects (DTOs) in `LedgerJsonSerializer.kt`:
1. `ManifestJson` (`manifest.json`): Ledger metadata, schema version, owner ID, timestamps.
2. `PersonJson` (`persons.json`): Cloud UUID, name, phone, timestamps, soft-delete flag.
3. `TransactionJson` (`transactions.json`): Cloud UUID, personCloudId, amountCents, type, note, imageFileId, timestamps, soft-delete flag.

### Strict Rules & Invariants
- Wire DTOs use ISO-8601 UTC strings for timestamps (`yyyy-MM-dd'T'HH:mm:ss'Z'`).
- Every JSON payload has an envelope containing `schemaVersion: Int`.
- Deserialization rejects versions greater than the supported version, prompting the user to update the app.
- Deserialized DTOs are validated by `JsonSchemaValidator.kt` before being touched by Room.

---

## Decision 9: Soft Deletes with Tombstone Propagation

- **Status:** Verified Implemented & Tested in Code
- **Category:** Concurrency & Deletion
- **Date Confirmed:** 2026-10-20

### Context
If a record is immediately deleted locally (`DELETE FROM table`), a cloud sync engine cannot determine whether the missing record was deleted locally or created on another device and needs to be downloaded.

### Chosen Decision
All deletions are soft deletes. A deleted record has `isDeleted = true`, `syncPending = true`, and updated `updatedAt`.
- UI queries filter out records where `isDeleted = 1`.
- Sync queries include soft-deleted records so they are serialized into Drive JSON as tombstones (`"isDeleted": true`).
- A secondary device reading the tombstone applies `isDeleted = true` locally.

---

## Decision 10: Durable Offline Operation Queue (`SyncOperation`)

- **Status:** Verified Implemented & Tested in Code
- **Category:** Background Processing & Resilience
- **Date Confirmed:** 2026-10-20

### Context
If network requests are launched directly in Coroutine scopes bound to UI or ViewModels, any network failure or process death causes pending mutations to be lost.

### Chosen Decision
Persist pending sync operations in Room under the `sync_operations` table (`SyncOperation.kt`).
- Every local mutation writes a `SyncOperation` row before or during the Room transaction.
- A WorkManager worker or sync service reads `sync_operations`, executes sync, and removes the row only upon verified Drive acknowledgment.
- Retries increment `retryCount` and record `lastErrorMessage` up to `MAX_RETRIES = 5`.

---

## Decision 11: Decoupled Offline-First Repository Layer

- **Status:** Verified Implemented & Tested in Code
- **Category:** Clean Architecture
- **Date Implemented & Verified:** 2026-10-20

### Context
ViewModels previously accessed Room DAOs directly. This made unit testing dependent on Android SQLite and prevented attaching background cloud synchronization cleanly.

### Chosen Decision
ViewModels interact exclusively with domain repository interfaces:
- `PersonRepository` implemented by `OfflineFirstPersonRepository`.
- `TransactionRepository` implemented by `OfflineFirstTransactionRepository`.

### Strict Rules & Invariants
1. `HomeViewModel` and `TransactionViewModel` must never query DAOs directly.
2. Repositories handle entity-to-domain mapping and ensure `SyncOperation` records are enqueued for local mutations.
3. Repositories have zero external/network dependencies and function 100% offline.

---

## Decision 12: Drive Multi-File Write-Ahead Ordering & Deduplication

- **Status:** Verified Implemented & Tested in Code
- **Category:** Cloud Storage & Integrity
- **Date Implemented & Verified:** 2026-10-20

### Context
Google Drive `AppDataFolder` is a file collection rather than an ACID database. Drive cannot execute multi-file transactions, and Drive files are not uniquely constrained by filename (multiple files named `manifest.json` or `persons.json` can exist in the same folder if network retries happen).

### Chosen Decision
1. **Write-Ahead Commitment Order:**
   - In `initializeCloudLedger`: `persons.json` is created first, `transactions.json` second, and `manifest.json` is committed last as the definitive ledger marker.
   - If any data file creation fails, previous files are rolled back (deleted).
   - If an interrupted state is encountered (`persons.json` exists but no `manifest.json`), `checkRemoteLedger` flags it as `RemoteLedgerCheckResult.InterruptedInitialization`, preventing silent data corruption.
2. **Deduplication:**
   - Files are grouped by name. When duplicates exist, the newest version (by Drive `version` / `modifiedTime`) is retained, and stale duplicates are deleted via `cleanupDuplicateFiles`.
3. **Overwrite Protection:**
   - `updateCloudLedger` strictly refuses to overwrite a non-empty cloud ledger with empty local records unless remote records are tombstones (`isDeleted = true`).

---

## Decision 13: Official Google OAuth 2.0 Authorization Flow for Drive

- **Status:** Verified Implemented & Tested in Code
- **Category:** Authentication & Security
- **Date Implemented & Verified:** 2026-10-20

### Context
Google Identity Credential Manager returns an OIDC ID token (JWT), which proves the user's identity but is **not** an OAuth 2.0 access token for Google REST APIs. Google Drive API calls require an OAuth 2.0 Bearer access token with the `https://www.googleapis.com/auth/drive.appdata` scope. Inventing client-side token exchanges is unsupported and breaks.

### Chosen Decision
1. **Authentication:** Credential Manager selects the account and yields the user's verified email.
2. **Authorization:** Uses Google Play Services `GoogleAuthUtil.getToken(context, account, "oauth2:https://www.googleapis.com/auth/drive.appdata")` wrapped inside an `OAuthTokenProvider` interface.
3. **In-Memory Caching:** Access tokens are cached in RAM with a 50-minute TTL (lifetime is 60 minutes) and are never written to disk.
4. **Invalidation:** If Drive API responds with HTTP 401 Unauthorized, `GoogleAuthManager.onTokenRejected()` invalidates the token and transitions state to `AuthState.TokenExpired`.
5. **JVM Testability:** `OAuthTokenProvider` is an interface with `PlayServicesOAuthTokenProvider` for Android runtime and fake implementations for unit testing without Android Play Services.

---

## Decision 14: Non-Destructive Search Disambiguation

- **Status:** Verified Implemented & Tested in Code
- **Category:** UX & Data Integrity
- **Date Implemented & Verified:** 2026-10-21

### Context
Users frequently have multiple customers with the same name (e.g., multiple "Ramesh" or "Pooja"). Joining or merging these records causes catastrophic ledger corruption, while showing identical duplicate names in a flat list causes users to record transactions under the wrong customer.

### Chosen Decision
Implement `ContactSearchHelper` to group duplicate names visually by normalized display name (ignoring casing and whitespace). Each individual customer record retains its unique database ID, phone number, balance, and transaction history. Visual disambiguation labels (`Account X of Y • Phone • ID: #id`) are displayed to guide the user without merging underlying database entities.

---

## Decision 15: Reconciled Outstanding Balance Definitions

- **Status:** Verified Implemented & Tested in Code
- **Category:** Business Logic & Financial Integrity
- **Date Implemented & Verified:** 2026-10-21

### Context
Previous dashboard calculations summed all lifetime GIVE transactions as "Total Give", confusing historical transaction volume with actual outstanding debt.

### Chosen Decision
Reconcile dashboard totals with the underlying ledger:
- **Total You Give:** Sum of net balances where balance < 0 (`SUM(-balanceCents)`). Represents money the user owes to others.
- **Total You Get:** Sum of net balances where balance > 0 (`SUM(balanceCents)`). Represents money owed to the user.
- **Received Today:** Strictly bounded by the device's local calendar day (00:00:00.000 to 23:59:59.999).
- Subtitles clarify meaning to eliminate user ambiguity.

---

## Decision 16: Safe Contact Archiving & Non-Destructive Room Migration 3 → 4

- **Status:** Verified Implemented & Tested in Code
- **Category:** Database Schema & Lifecycle
- **Date Implemented & Verified:** 2026-10-21

### Context
Inactive customers cluttered search results and dashboard views, but deleting them would erase historical transactions and balances.

### Chosen Decision
1. Add `isArchived: Boolean = false` to `Person`.
2. Safe SQLite migration: `ALTER TABLE persons ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0;`.
3. Filter active vs archived contacts via UI tabs and search exclusion.
4. Historical transactions and balances are 100% preserved. Existing contacts can be restored anytime.

---

## Decision 17: User-Initiated External Handoff for Reminders

- **Status:** Verified Implemented & Tested in Code
- **Category:** Privacy & Android Permissions
- **Date Implemented & Verified:** 2026-10-21

### Context
Automatic or silent SMS/WhatsApp transmission requires high-risk permissions and violates user trust and privacy policies.

### Chosen Decision
- Payment reminders are strictly user-approved via Android Intents (`Intent.ACTION_VIEW` for WhatsApp, `Intent.ACTION_SENDTO` for SMS, and `Intent.ACTION_SEND` for system chooser).
- Local scheduled notifications via WorkManager prompt the user rather than auto-sending messages.
- Notifications respect lockscreen privacy (`VISIBILITY_PRIVATE`), omitting sensitive financial details on lock screens.
- Contacts with settled (<= 0) or negative balances are automatically skipped.

---

## Decision 18: Deterministic Recurring Transactions & Room Migration 4 → 5

- **Status:** Verified Implemented & Tested in Code
- **Category:** Automation & Scheduling
- **Date Implemented & Verified:** 2026-10-21

### Context
Repeating ledger entries (rent, daily supplies) must not produce duplicate transactions on device reboots, retries, or app restarts.

### Chosen Decision
1. Recurring rules stored in dedicated `recurring_rules` table with foreign key cascade to `persons`.
2. Generated transactions have deterministic IDs: `id = "rec_${rule.id}_${executionDate}"`.
3. Idempotent execution: if a transaction with that ID already exists, retries do not create duplicates.
4. Month-end dates (e.g. Jan 31st) clamp to the end of shorter months (e.g. Feb 28th) without skipping months.

---

---

## Decision 20: Contact Search Normalization & Deduplication Strategy

- **Status:** Verified Implemented & Tested in Code
- **Category:** Search & Data Presentation
- **Date Implemented & Verified:** 2026-10-23

### Context
Contacts frequently enter the database with varied spacing (e.g. `Rahul Kumar` vs `Rahul  Kumar`) or phone formatting (e.g. `09431733886` with trunk 0 vs `9431733 886` vs `94 31733886`). Users reported duplicate search results cluttering the interface.

### Chosen Decision
1. **Name Normalization:** Trim leading and trailing whitespace, collapse all internal whitespace sequences (`\s+`) to a single space, and compare case-insensitively.
2. **Phone Normalization:** Strip all non-digit formatting characters (spaces, hyphens, parentheses). For standard 10-digit Indian mobile numbers (starting with digits 6–9), strip a leading trunk `0` (length 11), leading `+91` (length 13), or leading `91` (length 12) to normalize to the canonical 10-digit format (`9431733886`). Do not strip leading zero from landlines or arbitrary lengths.
3. **Display-Only Deduplication:** Normalization and deduplication are strictly applied at query/search display time using composite key `normalizedName:normalizedPhone`. Database records are never merged or deleted automatically.
4. **Deterministic Selection:** If duplicate database records match, select the representative result with the most recent ledger transaction timestamp.

---

## Decision 21: Settled Zero-Balance Contact Deletion Safety

- **Status:** Verified Implemented & Tested in Code
- **Category:** Data Safety & Lifecycle
- **Date Implemented & Verified:** 2026-10-23

### Context
Users requested the ability to clean up settled accounts from the dashboard without leaving dangling foreign key references or breaking Google Drive cloud synchronization.

### Chosen Decision
1. **Eligibility Guard:** Contact must have at least one transaction AND net balance must equal exactly `0L` (`balanceCents == 0L`). Non-zero balance contacts cannot be deleted via this action. Contacts without transactions remain hidden from the dashboard and are protected.
2. **User Confirmation:** Explicit modal confirmation showing contact name and phone number, clearly disclosing that deletion also removes associated transaction history.
3. **Safe Soft-Deletion:** Locally soft-deletes the `Person` record and all associated `Transaction` records (`isDeleted = 1`, `updatedAt = now`).
4. **Sync Tombstones:** Inserts `DELETE` sync operations in `sync_operations` for both the person and all transactions, ensuring cloud state propagates correctly without data divergence.

---

## Decision 22: Dashboard Filtering vs Search Discovery Invariant

- **Status:** Verified Implemented & Tested in Code
- **Category:** UX & Information Architecture
- **Date Implemented & Verified:** 2026-10-23

### Context
The dashboard was cluttered with imported address book contacts that had never engaged in any transactions, while users still required the ability to find and start transactions with those contacts.

### Chosen Decision
1. **Dashboard Invariant:** When the search query is blank, the dashboard lists only contacts with $\ge 1$ active ledger transactions (`getActivePersonsWithTransactions`). Contacts with no transactions are hidden from the dashboard.
2. **Search Discovery:** When a search query is entered, the search scope expands across all saved contacts (both with and without transactions).
3. **Search Ranking:** Contacts with transactions are displayed first (ordered by newest transaction timestamp descending). Contacts with no transactions follow afterwards (ordered alphabetically).

---

## Decision 23: UI Polish, Customer Name Marquee, Messaging Normalization, and Exit Confirmation

- **Status:** Verified Implemented & Tested in Code
- **Category:** UI/UX & Messaging Reliability
- **Date Implemented & Verified:** 2026-10-23

### Context
1. Dark theme had poor color matching with inconsistent background, text, and card tones.
2. Long customer names occupied excessive width and pushed phone numbers off-screen or clipped them.
3. New Transaction and Statement Export interfaces needed complete redesign to meet modern ledger app standards.
4. Messaging feature failed when initiating WhatsApp or SMS intents.
5. Users requested an exit confirmation dialog before quitting the application on Android Back press.

### Chosen Decision
1. **Dark Theme:** Centralized Material 3 dark palette with deep charcoal background (`0xFF0F141C`), elevated surface cards (`0xFF181F2A` / `0xFF222B38`), high contrast readable text (`0xFFF1F5F9`), vibrant primary blue (`0xFF4F8EF7`), and distinct GIVE (`0xFFEF4444`) and GET (`0xFF22C55E`) semantic colors. Light theme remains clean and unchanged.
2. **Customer Name Marquee & Dedicated Phone Row:** Customer cards place the phone number on a separate dedicated line below the customer name so it can never be clipped. For long customer names exceeding the container, a smooth continuous horizontal marquee (`Modifier.basicMarquee(iterations = Int.MAX_VALUE)`) scrolls the text gently without layout shifts.
3. **New Transaction Redesign:** Polished modal dialog with segmented GIVE/RECEIVE buttons, hero Amount field with `₹` prefix and numeric decimal keyboard, date picker with calendar icon, optional note and bill image attachment preview, double-submission lock with progress indicator, and validation error messages.
4. **Statement Export Redesign:** Rebuilt `ExportStatementDialog` with CSV format explanation, customer header, period chips ("All Time", "This Month", "30 Days", "Custom Range"), date pickers with validation, Statement Overview summary card, and SAF `CreateDocument("text/csv")` + Share sheet options.
5. **Messaging Normalization & Package Visibility:**
   - Added `<queries>` block in `AndroidManifest.xml` targeting `com.whatsapp`, `com.whatsapp.w4b`, and `smsto` to comply with Android 11+ package visibility rules.
   - Implemented `normalizeWhatsAppPhone` to format 10-digit Indian numbers with `91` and strip trunk `0`, conforming to WhatsApp's click-to-chat URL specifications.
   - Extended message generation to support all account states (positive reminder, settled accounts, credit notices).
   - Added editable message draft before opening external messaging apps and clear fallback toasts if target app is missing.
6. **Exit Confirmation Dialog:** Configured root `BackHandler` on the main dashboard to display an `AlertDialog` asking "Do you really want to close this application?" with "Exit" (`activity.finish()`) and "Cancel".

---

## Decision 24: Database Documentation, Schema Formalization, and Future-Proofing Strategy

- **Status:** Documented & Verified
- **Category:** Database Architecture & Future-Proofing
- **Date:** 2026-10-24

### Context
Users requested a complete structural audit and visual documentation of the application's SQLite database to understand file layout, data safety, cloud synchronization guarantees, and long-term upgradeability without altering live data.

### Chosen Decision
1. **Zero Unapproved DDL/DML Modifications:** Strictly perform inspection and documentation first. Never alter existing tables, migrations, or data structures without prior user alignment.
2. **Comprehensive Documentation Artifacts:**
   - `DATABASE_SCHEMA.md`: Complete reference of tables (`persons`, `transactions`, `sync_operations`, `recurring_rules`), exact SQL DDL, column data types, foreign keys, and ER diagrams.
   - `DATABASE_VISUAL_GUIDE.md`: Visual diagrams, balance calculation logic, cloud JSON wire examples, and DB Browser / Device Explorer access instructions.
   - `database_inspection.sql`: Read-only, anonymized diagnostic SQL script for SQLite command-line and GUI forensic analysis.
   - `DATABASE_AUDIT_REPORT.md`: Formal audit of migrations 1–5, data integrity, Google Drive synchronization, bill image portability risks, and future-proofing recommendations.
3. **Identified Future-Proofing Upgrades (Awaiting User Approval):**
   - Bill image byte copying to app-private storage to prevent broken `content://` links.
   - Enclosing multi-statement repository mutations in `database.withTransaction { ... }`.
   - Incorporating `recurring_rules` in manual `BackupManager` JSON backups.

---

## Decision 25: Completely Separate Bill-Image Storage and Gallery Independence

- **Status:** Implemented & Verified in Code & Tests
- **Category:** Media Storage & Data Isolation
- **Date:** 2026-10-24

### Context
Users requested complete isolation between Ledger bill images and the phone's public Gallery. Taking a photo inside Ledger must not pollute the Gallery or create duplicate photos in DCIM/Pictures. Picking a photo from Gallery must copy its bytes into app-private storage and leave the original photo untouched. Deleting or editing a bill inside Ledger must never modify or delete the Gallery photo. Deleting the photo from Gallery must not break Ledger's bill receipt.

### Chosen Decision
1. **App-Private Storage Root:** All bill receipts are stored strictly inside `/data/data/com.kb.ledger/files/receipts/<txUUID>.jpg` (`context.filesDir/receipts/`).
2. **Camera Direct Capture via FileProvider:** Camera capture generates a temporary staging file in `context.filesDir/receipts/camera_stage_<uuid>.jpg` and provides a `FileProvider` URI to `ActivityResultContracts.TakePicture()`. The camera application writes directly into Ledger's private file. No image is written to Gallery, DCIM, or external shared storage.
3. **Gallery Selection Copy-on-Pick:** When selecting from Gallery via `ActivityResultContracts.GetContent()`, Ledger opens an input stream, reads the bytes into `context.filesDir/receipts/gallery_stage_<uuid>.jpg`, and immediately closes the stream. The original Gallery file is 100% untouched.
4. **No MediaStore Deletions:** Deleting or replacing a bill inside Ledger only deletes the local internal file. `contentResolver.delete()` is never called on external/Gallery URIs.
5. **Atomic Commit & Staging Cleanup:** On saving the transaction, the staged file is committed to `<txUUID>.jpg`. If cancelled or discarded, the staging file is purged immediately.
6. **No Substitute Workarounds:** Does NOT use `.nomedia` on shared storage or file extension renaming hacks as a substitute for true private storage.

---

## Decision 26: Date-Partitioned Local Backup Format & Reliable Google Drive Synchronization

- **Status:** Implemented & Verified in Code & Tests
- **Category:** Local Backup Architecture & Cloud Synchronization
- **Date:** 2026-10-25

### Context
Previous backup logic relied exclusively on a manual user export that generated a monolithic JSON file. Changes made between manual exports were absent from the backup, and Google Drive background synchronization lacked a production WorkManager implementation.

### Chosen Decision
1. **Authoritative Source of Truth:** SQLite Room database remains the single authoritative store. Local partitioned backups are maintained automatically on every mutation and are self-healing (rebuildable from Room on startup).
2. **Date Partitioning Hierarchy:** Transactions are partitioned by original `createdAt` timestamp in `YYYY/MM/DD` directory hierarchy under `context.filesDir/backup/transactions/`.
3. **UTC Timezone Invariant:** All date partitioning uses `ZoneOffset.UTC` consistently (`PartitionDateUtils`). Changes in device local timezones or Daylight Saving Time never move existing transactions between partitions.
4. **Partition Immutability on Business Date Edits:** Editing a transaction's note, amount, or display date updates the record in its existing creation-date partition chunk without moving it.
5. **Bounded Chunks:** Chunks target ~1,000 transactions per chunk (`chunk_0001.dat`, `chunk_0002.dat`) to prevent large single-file rewrites.
6. **Versioned GZIP Compression:** Chunks and manifest use GZIP-compressed UTF-8 JSON (`BackupChunkEnvelope`, `BackupManifest`), providing a compact binary representation without encryption.
7. **Atomic File Replacement:** Every file write targets a temporary file first (`.tmp`), flushes and syncs storage, then renames atomically.
8. **Durable Cloud Synchronization:** Independent `DriveSyncWorker` via WorkManager. Uploads pending bill images before transactions, commits data files before manifest.json, and only marks Room records synced after Drive confirmation.
9. **User-Visible Status:** Real-time visibility into local backup status (`Current`, `Reconciling`) and cloud sync status (`X transactions pending sync`, `Synced`, `Error`).

---

## Decision 27: Owner Address Book Name Resolution During Inbound Cloud Synchronization

- **Status:** Implemented & Verified in Code & Tests (222 tests passing)
- **Category:** Multi-User Data Synchronization & Identity Resolution
- **Date:** 2026-10-25

### Context
When a staff member (e.g. S1) creates a transaction for a person whose phone number does not exist in the Ledger database, the person is saved locally on S1's device with the staff-entered or address-book name and synced to the cloud. When that record reaches the owner, the owner may already have that person saved under a different personal or business name in their local phone address book.

### Chosen Decision
1. **Owner Address Book Resolution Gate:** During inbound cloud sync execution on the owner's device, incoming remote persons are queried against the owner's phone address book using `AddressBookContactResolver` / `PhoneAddressBookResolver`.
2. **Display Name Only:** If exactly one matching contact exists in the owner's address book for the normalized phone number, only the person's displayed `name` in the owner's Room database (`persons` table) is updated via `personDao.updatePersonName(id, ownerContactName)`.
3. **Preserve Staff-Supplied Name:** If no matching contact is found, or if multiple contacts match with different names (ambiguous match), or if `READ_CONTACTS` permission is denied, the staff-supplied name is preserved unchanged without blocking transaction synchronization.
4. **Stable Identity Invariants:** Stable local `id`, `phone`, `createdAt`, transaction foreign keys (`personId`), transaction amounts, balances, and soft-delete tombstones are NEVER altered as part of name resolution.
5. **Deduplication & Local Reuse:** Existing local Ledger persons are reused if matched by `cloudId` or by normalized phone (when no conflicting `cloudId` exists). Duplicate person records are never created.
6. **Local Partitioned Backup Safety:** When a person's name is resolved or updated, `LocalPartitionedBackupManager.recordPersons(...)` rewrites only the persons chunk (`backup/persons/chunks/`) and rebuilds the manifest, completely preserving all transaction date partitions.
7. **Idempotency:** Repeated sync runs execute idempotently with zero side effects or flip-flopping names.

---

## Decision 28: Schema Upgrade Baseline, Release Signing Architecture, and Build Environment Standards

- **Status:** Implemented & Verified in Code & Tests
- **Category:** Build Engineering, Release Signing & Schema Migration Safety
- **Date:** 2026-10-25

### Context
Pre-release data safety audit identified:
1. `assembleRelease` failed at `lintVitalAnalyzeRelease` under JDK 25.
2. Missing release signing configuration (produced unsigned APK).
3. Question of whether Schema Version 1 ever existed in production or required migration.
4. Version code clash (`versionCode 1` already installed on test device).

### Chosen Decision
1. **Schema Version 2 Baseline:** Git history analysis reveals that the initial Android codebase commit (`8c0f1d2`) created `AppDatabase` directly at `version = 2`. Schema Version 1 was never created, compiled, or distributed. The official, verified upgrade baseline is Schema Version 2. All migrations starting from `v2 -> v3 -> v4 -> v5` are fully verified without data loss.
2. **Release Signing Separation & Keystore Security:** Release signing is configured in `app/build.gradle.kts` via a git-ignored `keystore.properties` loading `ledger-release.jks`. The permanent release signing key is a 4096-bit RSA key (`ledger-prod-release`, SHA-256 fingerprint: `4A:44:9F:E0:03:66:4D:2F:10:F5:2E:44:DA:7F:9A:2F:9F:DE:0F:E5:CE:07:59:A8:29:6E:3B:01:0F:D9:A7:D5`). Keystore files (`*.jks`, `*.keystore`), `keystore.properties`, and passwords are strictly excluded from Git and never printed or committed. Debug builds retain the default Android debug signing identity.
3. **Version Code Progression:** Incremented `versionCode = 2` and `versionName = "1.0.1"` to allow in-place upgrades over existing version 1 installations.
4. **Certified Build Environment (JDK 17):** Eclipse Temurin JDK 17 (`17.0.21+9`) is certified as the required build JDK. This resolves AGP 8.7.2's `lintVital` parsing errors without disabling or suppressing lint checks.
6. **Cross-Key Upgrade Incompatibility:** Devices running the debug build signed with Android's default debug key cannot be upgraded in-place with the release-signed APK without uninstalling due to Android OS cryptographic signature enforcement. Physical test device data must be preserved via external backup export, never uninstalled.

---

## Decision 29: Seven-Day Operational Log Retention, Release Evidence Directory, and Privacy Hygiene Standards

- **Status:** Implemented & Verified in Code & Tests (6 retention unit tests passing)
- **Category:** Repository Engineering Hygiene, Logging Standards & Privacy Protection
- **Date:** 2026-10-25

### Context
To maintain reliable traceability across continuous development sessions without polluting the repository or accumulating stale logs, the repository requires an explicit logging structure, retention cleanup rules, auditable release evidence, and strict privacy invariants.

### Chosen Decision
1. **Three-Tier Documentation Hierarchy:**
   - **Tier 1 (Permanent Architectural Memory):** `PROJECT_CONTEXT.md`, `PROGRESS_LOG.md`, `DECISIONS.md`. Never pruned; captures durable context, milestones, and design rules.
   - **Tier 2 (Permanent Release Evidence):** `release-evidence/`. Tracked in Git; stores non-sensitive checklists (`RELEASE_CHECKLIST.md`), APK SHA-256 digests, certificate fingerprints, and test summaries for every distributed release.
   - **Tier 3 (Ephemeral Operational Logs):** `dev-logs/YYYY-MM-DD.md`. One file per calendar day documenting raw commands, tool results, and daily progress.
2. **Strict Seven-Day Retention Policy:**
   - Daily logs older than seven calendar days (relative to reference date) are pruned using `python3 scripts/clean_dev_logs.py` (or `./scripts/clean-dev-logs.sh`).
   - The retention script defaults to **DRY-RUN** mode. It strictly matches `YYYY-MM-DD.md` and preserves non-date files (`README.md`, `.gitkeep`, subdirectories).
   - If an operational log records an unresolved severe bug or release blocker, it must be escalated to Tier 1 permanent documentation before 7-day expiration.
3. **Data Sanitization & Privacy Invariant:**
   - All logs in `dev-logs/` and evidence in `release-evidence/` must strictly exclude customer names, phone numbers, transaction notes, balances, receipt images, passwords, and tokens.
4. **Git Hygiene Policy:**
   - `release-evidence/` and `dev-logs/` are tracked in Git to maintain shared engineering context across developers and agents.
   - Keystores (`*.jks`, `*.keystore`), credentials (`keystore.properties`), database files (`*.db`, `*.db-wal`), local physical device backups (`phone_database_backup/`), and temporary scratch directories are strictly excluded from Git.

---

## 30. Future Google Authentication, Multi-Account Isolation, and Existing Data Migration Policy

### Date
2026-10-25

### Status
Accepted (Architecture Audit & Policy Definition; Awaiting explicit user approval before implementation)

### Context
Ledger currently operates as a local-first offline Android ledger application with local SQLite/Room storage. Data models (`Person`, `Transaction`, `RecurringRule`) use a combination of local SQLite auto-increment IDs (`Person.id: Long`) and UUIDs (`Transaction.id: String`). Users already have existing persons, transactions, recurring rules, soft-delete tombstones, and local bill receipt images stored locally.
Before introducing Google authentication or Drive synchronization, the app must guarantee that existing local records are never silently overwritten, lost, duplicated, merged into an unrelated cloud account, or cross-pollinated between different Google accounts logging in on the same physical phone.

### Chosen Decision
1. **Option A (Local-First Release Recommended):**
   - Public v1.0 release proceeds as an offline local-first ledger app.
   - Google Sign-In drawer item is disabled or hidden in production release builds until the end-to-end multi-account migration state machine is fully implemented and tested.
2. **Immutable Identity Binding:**
   - Account identity and ledger ownership MUST bind to Google's immutable numerical Subject Identifier (`sub` / OpenID Connect UID), NOT mutable email strings (`accountEmail`).
   - A durable `ledgerId` (UUID) must be stored locally in persistent database metadata (surviving process death and updates).
3. **Multi-Account Partitioning & Isolation:**
   - The app must prevent multi-account data leakage. Logging out of Account A and logging into Account B must NEVER point Account B to Account A's local database.
   - Local databases must either be partitioned per authenticated account (`ledger_<googleSubId>.db`) or require explicit user confirmation to bind an unlinked local ledger to a newly authenticated account.
4. **Mandatory Pre-Linking Local Backup:**
   - Prior to executing any cloud linking, migration, or remote state reconciliation, an automatic, non-destructive, full local SAF backup (`BackupData`) and image bundle must be generated on local storage.
5. **No Blind Remote Overwrite:**
   - If a signing-in Google account already contains remote cloud records, the system must NEVER silently overwrite either local or remote data.
   - An explicit choice modal or non-destructive merging state machine (mapping local `Person.id` via `cloudId` UUIDs) must resolve conflicts.
6. **Tombstone and Recurring Rule Parity:**
   - Soft-delete tombstones (`isDeleted = true`) must be preserved during all sync/migration operations.
   - `RecurringRule` must be enhanced with a stable `cloudId` (UUID) and soft-delete tombstone (`isDeleted`) before recurring rules can be synced to cloud storage.
7. **Two-Phase Verification Before Local Purge:**
   - Local records and receipt files must NEVER be purged based solely on a successful network upload. They must remain durable and verified locally until remote checksum and manifest consistency are verified.

---

## 31. Collision-Safe Non-Destructive Storage Access Framework (SAF) Backup and Restore Policy

### Date
2026-10-25

### Status
Accepted & Implemented

### Context
`Person.id` is a device-local SQLite auto-increment `Long`, while `transactions` and `recurring_rules` reference persons through foreign key `personId` with `ON DELETE CASCADE`.
Previously, `BackupManager.importData` inserted records directly via `PersonDao.insertPerson` with `OnConflictStrategy.REPLACE`. When restoring a backup into a populated database, an incoming person with a colliding integer ID (e.g., `id = 1`) triggered SQLite's `INSERT OR REPLACE` deletion of the existing local person with that ID. Due to `ON DELETE CASCADE`, this silently destroyed all existing local transactions and recurring rules belonging to the local person, or reassigned transactions to an unrelated individual.

### Chosen Decision
1. **Differentiated Restore Semantics:**
   - **Empty Database:** When the database has 0 persons, incoming records are inserted preserving original backup integer IDs (`id > 0`) to maintain exact parity with the source ledger.
   - **Populated Database:** The system MUST NOT insert incoming persons using their backup integer IDs. It resolves identity non-destructively:
     1. Match existing persons by globally unique `Person.cloudId` (UUID).
     2. Fallback for legacy v2 records: match by normalized phone number if the local record lacks `cloudId`.
     3. For matched persons: Update fields via `PersonDao.updatePerson` (`UPDATE` by primary key, NEVER `REPLACE`), preserving the local `Person.id`.
     4. For new/unmatched persons: Insert with `id = 0`, forcing SQLite to allocate a brand-new, safe auto-increment primary key.
2. **Explicit Foreign Key Remapping:**
   - All transactions and recurring rules in the backup have their `personId` remapped from the backup's integer ID to the resolved local `Person.id`.
3. **Orphan Protection:**
   - If a transaction or recurring rule in the backup references a `personId` absent from the backup's person list, the restore aborts immediately with `IllegalArgumentException`, rolling back all database writes before commit.
4. **Receipt Image Integrity:**
   - Receipt images are restored using staged temp files (`import_tmp_<txId>.jpg`). Only moved to `${txId}.jpg` after the Room database transaction commits successfully. Existing non-empty receipt images are never overwritten.
5. **Tombstone Preservation:**
   - Soft-deleted persons and transactions (`isDeleted = true`) are exported and imported, preventing deleted records from being resurrected upon restore.
6. **Zero Schema Modification:**
   - Implemented within the existing Room schema v5 with zero migration or schema alterations.

---

## 32. Strict Identity Matching Guards, Disambiguation Invariants, and Self-Healing Receipt Recovery in SAF Restore

### Date
2026-10-25

### Status
Accepted & Implemented

### Context
A follow-up audit of the SAF backup/restore implementation was conducted to validate edge cases in person identity matching, phone number fallback ambiguity, foreign key remapping, and receipt-image recovery across the SQLite WAL log and POSIX filesystem.

### Chosen Decision
1. **Injective Identity Mapping (1-to-1 Enforcement):**
   - Incoming backup records are mapped to local persons using an explicit `claimedLocalPersonIds: MutableSet<Long>`.
   - Invariant: No two incoming persons can ever map to the same local person ID.
   - Corrupted backup detection: If duplicate non-blank `cloudId`s exist within the incoming backup, the restore immediately throws `IllegalArgumentException` and aborts.
2. **Strict Normalized Name Guard on Legacy Phone Fallback:**
   - Phone matching is strictly a legacy fallback for pre-v3 records where both local and incoming records lack a `cloudId`.
   - Invariant: A phone match is rejected if digits length < 7 (prevents matches on `"0"`, `""`, or dummy numbers).
   - Invariant: Matching requires exact normalized name equivalence (`normalizeName(p.name) == normalizeName(candidate.name)`). If two individuals share a phone number (e.g. family or business partners like Ramesh Patel and Suresh Patel), they are **never merged**. The incoming person is allocated a brand-new auto-increment ID (`id = 0`).
   - If multiple candidates share a phone number or if the candidate is ambiguous, the restore never guesses; it inserts the incoming person separately.
   - Precedence: `cloudId` matching takes absolute precedence over phone matching.
3. **Idempotency and Tombstone Immutability:**
   - Repeated imports of the exact same backup produce identical record counts with 0 duplicates and 0 remapped IDs.
   - **Person Tombstones:** Strictly preserved via `isDeleted = p.isDeleted || matchedPerson.isDeleted`.
   - **Transaction Tombstones:** Strictly preserved via `mergedIsDeleted = existingTx.isDeleted || t.isDeleted`. If an existing transaction is soft-deleted locally, restoring an older, equal, or newer active backup will **never resurrect** it. If a backup contains a soft-deleted tombstone, it propagates to the local record.
   - **Product Policy & Limitation:**
     - Deletion is one-way: a transaction marked deleted on either side remains deleted after ordinary backup restoration.
     - A newer active backup does NOT automatically undelete a locally deleted transaction.
     - No explicit undelete or transaction recovery feature currently exists in the application.
     - Clearing application data or reinstalling is not a routine undelete recommendation because it is destructive to all local state unless an uncorrupted external backup is independently verified.
4. **Receipt-Image Recovery Guarantees, Two-Phase Promotion, and Self-Healing:**
   - **Boundary Limitation:** POSIX filesystem file operations and SQLite WAL database transactions cannot be made atomic without a distributed two-phase commit coordinator. We explicitly do NOT claim atomicity across Room and the filesystem.
   - **Staging & Two-Phase Promotion:**
     - Images are staged as `import_tmp_<txId>.jpg` and verified for valid image content (JPEG, PNG, WebP).
     - If Room transaction fails or rolls back, all `import_tmp_` staging files are deleted immediately.
     - Existing valid images (`${txId}.jpg`) are never overwritten; duplicate imported temp files are deleted. Existing 0-byte or corrupted destination files are removed prior to promotion.
     - Direct rename is attempted first. If rename fails, promotion uses a two-phase copy via intermediate `promo_tmp_<txId>.jpg`, validating complete byte length before promoting to `${txId}.jpg`.
     - Staged files are deleted **only** after the final image is verified valid on disk. If promotion fails, staged files are **retained** on disk for future recovery.
   - **Database-Aware Recovery:**
     - `BackupManager.recoverOrphanedStagingImages` accepts `TransactionDao` to verify database state.
     - Staging files belonging to uncommitted or deleted transactions are safely cleaned up and never promoted.
     - Staging files for transactions that do not reference an image are discarded.
     - Staging files for committed transactions referencing an image are safely promoted.
   - **Startup Execution:**
     - Executed off the main thread in `MainActivity` startup background dispatch (`Dispatchers.IO`) and before new imports, ensuring self-healing without blocking app startup.
5. **Zero Schema Modification:**
   - Room schema version remains at 5; zero schema modifications or migrations.

---

## 33. Process-Wide Staging Synchronization, Content Validation, and Safe Diagnostic Logging

**Date:** 2026-10-25
**Status:** Approved and Implemented
**Problem:**
1. A potential concurrency race existed between `BackupManager.importData` and `recoverOrphanedStagingImages`: if recovery ran while an import was in flight before its Room transaction committed, recovery would query Room, find no transaction record, and prematurely delete active `import_tmp_` staging files or in-flight `promo_tmp_` intermediate copies.
2. `isValidReceiptFile` previously validated only file length ($\ge$ 4 bytes), which allowed arbitrary non-image files or truncated headers $\ge$ 4 bytes to pass as valid.
3. Exception handlers in `MainActivity` startup recovery and `BackupManager` swallowed errors silently without diagnostic logs, preventing operational debugging while ensuring UI non-blocking.

**Decisions:**
1. **Process-Wide Mutex & Active Ownership Registries:**
   - Implemented `backupSyncMutex = Mutex()` covering the staging, database commit, and promotion lifecycle of `importData` as well as `recoverOrphanedStagingImages`.
   - Added thread-safe active file registries: `activeStagedFiles` and `activePromoFiles` (`Collections.synchronizedSet`).
   - Invariant: `recoverOrphanedStagingImages` strictly ignores files present in `activeStagedFiles` and `activePromoFiles`. Active operations are never stripped of in-flight staging or promotion files.
   - Crash safety: In-memory registries disappear on process death. On subsequent application startup or import, abandoned files are no longer in active sets and are cleanly healed or pruned according to Room database authority.
2. **Lightweight Image Content Validation (`isValidReceiptFile`):**
   - Added header byte-signature checks via `FileSystemOperations.readHeader(file, 32)`:
     - **JPEG:** Starts with SOI marker `0xFF, 0xD8, 0xFF` and minimum container length $\ge 16$ bytes.
     - **PNG:** Starts with 8-byte signature `0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A` and minimum length $\ge 37$ bytes.
     - **WebP:** Starts with `"RIFF"` (bytes 0–3) and `"WEBP"` (bytes 8–11) and minimum length $\ge 12$ bytes.
   - Rejects 0-byte files, truncated stubs, corrupt headers, and non-image files.
   - Level of validation: Byte-signature container verification. Proves the file begins with a valid supported image container; does not perform full pixel entropy decoding (which requires Android graphics subsystem/mocks on JVM).
3. **Privacy-Safe Diagnostic Logger (`SafeLogger`):**
   - Replaced silent exception swallowing with `SafeLogger.log(tag, message, error)`.
   - Invariant: Only operation tags, sanitized static event messages, and exception class names (`e.javaClass.simpleName`) are logged.
   - Zero Customer PII: Never logs customer names, phone numbers, transaction notes, monetary amounts, receipt binary payloads, or user file paths.
   - Non-fatal and testable: Catches internal logger errors and provides an optional `Listener` interface for deterministic JVM unit test verification.

---

## 34. Staged SAF Backup Export, Destination Stream Abstraction, and Actionable UI Feedback

**Date:** 2026-10-25
**Status:** Approved and Implemented
**Problem:**
1. SAF backup export previously constructed and serialized the complete JSON payload directly into the destination output stream opened from `context.contentResolver.openOutputStream(uri)`. If Room querying, image Base64 encoding, or JSON serialization threw an exception midway, the user-selected destination file on external storage was truncated or left in a corrupt state.
2. In `MainActivity.kt`, the `importLauncher` result was silently discarded (`uri?.let { scope.launch { backupManager.importData(it) } }`), providing zero visual feedback to the user on completion, corrupt backup rejection, or missing receipt attachments.
3. Rapid repeated taps on export or import drawer items could trigger overlapping concurrent I/O operations.

**Decisions:**
1. **App-Private Local Export Staging:**
   - In `BackupManager.exportData()`, the backup payload is first assembled and serialized to an app-private cache file (`exportStagingDir/staged_export_<uuid>.json`).
   - Before any external destination write begins, the staged JSON file is structurally validated via `validateStagedBackupFile(stageFile)`: verifies non-empty file, valid JSON syntax, presence of required `'persons'` and `'transactions'` collections, and schema version $\le 3$.
   - The validated staging file is streamed in 8 KB chunks (`ByteArray(8192)`) to the destination output stream, avoiding holding the full JSON payload and Base64 image strings in memory during transfer.
   - The temporary staging file is guaranteed to be deleted in a `finally` block on both success and failure paths.
2. **Residual SAF Limitation (Non-Atomic Destination Writes):**
   - Calling `openOutputStream(uri)` on an Android SAF URI immediately causes standard document providers (`ExternalStorageProvider`, `DownloadProvider`) to truncate the target file to 0 bytes.
   - If streaming is interrupted (e.g., process death, out-of-space, or I/O failure), the external file may remain partial or truncated.
   - Android SAF does not support cross-provider atomic file replacement (`DocumentsContract.moveDocument()`) between local app-private cache and external document provider URIs.
   - The implementation deliberately avoids deleting the external user file on failure to avoid accidentally destroying pre-existing user files.
3. **Injected Destination Stream Abstraction (`DestinationStreamProvider`):**
   - Added `DestinationStreamProvider` interface and default `ContentResolverDestinationStreamProvider` to allow deterministic failure-injection testing (serialization failure, invalid JSON rejection, streaming write interruption, large chunked streaming) on pure JVM without Android UI automation.
4. **Actionable, Non-PII UI Feedback (`BackupFeedbackHelper`):**
   - Mapped `ImportResult` and `ExportResult` to clean, user-friendly UI messages via `BackupFeedbackHelper`:
     - Success: "Backup restored successfully."
     - Partial image restore warning: "Backup restored successfully, but X bill attachment(s) could not be restored."
     - Corrupt/unreadable error: "Backup file is corrupt or unreadable. Please choose a valid backup file."
     - Incomplete records error: "Backup file is incomplete or missing required records."
     - Integrity check failure: "Backup data integrity check failed. No records were modified."
     - Picker cancellation: Dismissal of document picker returns `ImportUiMessage.None` / `ExportUiMessage.None` without showing an error.
   - Privacy guarantee: Messages never display customer names, phone numbers, transaction notes, monetary figures, internal file paths, or system tokens.
   - Dispatched on Main coroutine scope with `Toast.show()`.
5. **Concurrency Guards:**
   - Introduced `isExporting` and `isImporting` state flags in `MainActivity.kt` to drop duplicate launcher invocations and guard drawer items while backup I/O is in flight.

---

## 35. Backup Export Concurrency Coordination, Missing Attachment Detection, and Versioned Filenames

**Date:** 2026-10-25
**Status:** Approved and Implemented
**Problem:**
1. `BackupManager.exportData()` previously executed without acquiring `backupSyncMutex`, creating a concurrency race where export could read receipt files or Room records while a concurrent restore (`importData`) or startup self-healing (`recoverOrphanedStagingImages`) was actively deleting, staging, or promoting them.
2. `BackupManager.importData()` previously only accounted for missing images if a payload entry was present in `backupData.billImagesByTxId` but failed decoding, header validation, or promotion. If a backup envelope had transactions claiming an attachment (`imageUri` or `imageFileId`) but completely omitted the payload entry, those missing attachments were not reported in `missingImageTxIds`, preventing user warning.
3. In `MainActivity.kt`, the export launcher suggested a static filename `"ledger_backup.json"`, increasing accidental collision risk across repeated exports.

**Decisions:**
1. **Coordinated Backup Export via Process-Wide Mutex (`backupSyncMutex`):**
   - Wrapped `exportData()` execution in `backupSyncMutex.withLock`.
   - Inspection of lock order confirms: `exportData` does not call `importData` or `recoverOrphanedStagingImages`, and neither restore nor recovery calls `exportData`. Because the non-reentrant mutex is never nested, there is zero deadlock risk.
   - Database queries (`getAllPersonsIncludingDeleted`, `getAllTransactionsIncludingDeleted`) and internal receipt reads are now held consistent against concurrent restore and recovery operations.
   - **Residual Race Boundary Documented:** Interactive UI editing via `BillImageManager.copyToInternalStorage()` operates on single transaction receipts when the user saves a new transaction attachment. These user edits do not acquire `backupSyncMutex`. Therefore, adding `backupSyncMutex` to `exportData` coordinates backup/restore/recovery operations but does not claim full transactional isolation against concurrent interactive transaction edits.
2. **Missing Attachment Payload Accounting & Warning:**
   - In `BackupManager.importData()`, transactions declaring attachment metadata (`!imageUri.isNullOrBlank() || !imageFileId.isNullOrBlank()`) are cross-referenced with `backupData.billImagesByTxId`.
   - If the payload entry is absent, the transaction ID is registered in `missingImageTxIds = LinkedHashSet<String>()`.
   - Decode failures, invalid container headers, and promotion failures also register into the same set, ensuring multiple failure conditions for a single transaction are never double-counted.
   - Attachment metadata on Room transaction entities is strictly preserved (never silently wiped), maintaining idempotency and tombstone preservation across repeated imports.
   - When missing attachments are detected, `BackupFeedbackHelper` surfaces an actionable warning toast: "Backup restored successfully, but X bill attachment(s) could not be restored."
3. **Locale-Stable Timestamped Export Filename Suggestion:**
   - Added `BackupManager.generateDefaultExportFilename(nowMs: Long)` producing `ledger_backup_yyyyMMdd_HHmmss.json`.
   - Enforces `Locale.US` to guarantee standard ASCII digits `0-9` across all device locales (e.g. Arabic, Hindi).
   - `MainActivity.kt` export launcher suggests this versioned filename.
   - Note: This reduces accidental file collisions in document providers, but does not prevent overwrite if the user deliberately selects an existing file in the SAF picker.
4. **SAF Truncation Limitation:**
   - App-private staging ensures that any database error, encoding failure, or JSON malformation prevents the destination stream from ever opening.
   - However, once `contentResolver.openOutputStream(uri)` is opened, Android document providers truncate the target file. If a failure (e.g. process death, disk full, or I/O interruption) occurs during chunked streaming, the destination file may remain partial or truncated.

---

## 36. Backup Coroutine Cancellation Propagation and Soft-Deleted Attachment Exclusion

**Date:** 2026-10-30
**Status:** Approved and Implemented
**Problem:**
1. `BackupManager.exportData()` previously caught generic `Exception`, which swallowed Kotlin's `CancellationException` and either returned `ExportResult(success = false)` or converted cancellation during image extraction into a false missing-attachment warning, breaking structured concurrency and preventing caller cancellation.
2. In `BackupManager.kt` (`importData` and `exportData`), soft-deleted transactions (`isDeleted == true`) that lacked attachment payloads or internal image files were inadvertently included in `missingImageTxIds`, producing confusing user-facing warnings ("Backup restored successfully, but X bill attachments could not be restored") for deleted records.
3. In `BackupExportSafetyAndFeedbackTest.kt`, test `export_noDeadlockFromNestedLockAcquisition` claimed to verify nested lock acquisition, whereas Kotlin's `Mutex` is non-reentrant and the test actually exercised sequential export calls.

**Decisions:**
1. **Coroutine Cancellation Propagation:**
   - In `BackupManager.exportData()`, explicit `catch (e: CancellationException) { throw e }` blocks were added before generic `catch (e: Exception)` in both the inner image-extraction read loop and the outer staging/streaming block.
   - `coroutineContext.ensureActive()` calls were placed in the transaction loop to ensure responsive cooperative cancellation.
   - In `importData()`, `CancellationException` is also rethrown across payload decoding, DB transaction execution (with temp file cleanup), and promotion loops.
   - Mutex release (`backupSyncMutex.withLock`) and staging file cleanup (`stagedFile.delete()`) are guaranteed to execute via `finally` blocks before rethrowing.
   - Cancellation rethrows `CancellationException` rather than returning `ExportResult(success = false)`.
2. **Soft-Deleted Attachment Warning Exclusion:**
   - In `exportData()`, `missingImageTxIds` calculation guards `if (!tx.isDeleted)` before recording a missing internal receipt.
   - In `importData()`, soft-deleted transactions (`tx.isDeleted == true`) are filtered out from `missingImageTxIds` during missing payload checks, payload decode error handlers, and promotion error handlers.
   - Tombstones (`isDeleted = true`) and their attachment metadata (`imageUri`, `imageFileId`) are strictly preserved in exported backups and Room storage without modification.
   - Active transactions with missing attachments continue to be counted at most once (`LinkedHashSet`).
3. **Concurrency Test Clarification:**
   - Renamed `export_noDeadlockFromNestedLockAcquisition` to `export_sequentialInvocationsAcquireAndReleaseMutexWithoutDeadlock` with documentation clarifying that it tests sequential lock acquisition/release without deadlocks, without claiming nested locking or concurrent interleaving.

---

## 37. Final Release Readiness Verification, Multi-Tier Image Validation, and Signature Incompatibility Standards

**Date:** 2026-10-30
**Status:** Approved & Formally Verified
**Category:** Release Engineering, Cryptography & Data Safety Standards

### Context
Prior to distributing Ledger v1.0.1 (`versionCode = 2`), a formal, evidence-based release audit was required to evaluate readiness for clean installations, existing user updates, and migration from a physical OnePlus device running a debug-signed build.

### Decisions
1. **Three-Way Differentiated Release Verdicts:**
   - **Clean Install (New Users):** `GO WITH CONDITIONS`. Safe for offline use; 316/316 unit tests pass, cold start succeeds on isolated emulator, integer currency math verified. Conditions: secure off-device keystore backup, disable misleading Google Drive UI.
   - **In-Place Update (Existing Users):** `NO-GO`. Android OS enforces package signing consistency. Installing the production-signed release APK over an existing debug-signed installation fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstalling the old build causes irreversible data destruction.
   - **Physical Phone Migration:** `NO-GO`. Prerequisites pending: on-device in-app SAF export, dual off-device copies with matching SHA-256 hashes, and trial restore/reconciliation on an isolated emulator.
2. **Three-Tier Image Validation Standards:**
   - Image validation must distinguish three levels:
     - Level 1: Base64 decoding & byte integrity.
     - Level 2: Container magic byte header validation (`isValidReceiptFile`).
     - Level 3: Native Android image decoding (`BitmapFactory`/`Skia`) and UI thumbnail rendering.
   - A valid magic byte header alone is NOT proof that an image can be decoded or displayed. Zero-entropy stubs pass Level 1 and 2 but fail Level 3. Level 3 native decoding is mandatory for receipt verification.
3. **Decoupling Keystore Permissions from Off-Device Recovery:**
   - Local keystore permissions (`chmod 600`) and Git-ignore exclusions are verified, but off-device recovery must remain `BLOCKED` until the operator creates an independent encrypted off-device backup.
4. **Stale SQLite Baseline Exclusion:**
   - Historical raw SQLite extractions (e.g. Session 1 baseline of 1,382 persons / 12 txs) are stale and must never serve as production reconciliation baselines. Baselines must be established from fresh in-app exports.

---

## 38. Google Sign-In Production Defect Remediation and Clean-Install Release Candidate Verification

**Date:** 2026-10-30
**Status:** Approved & Formally Verified
**Category:** Defect Remediation, User Safety & Release Candidate Standards

### Context
During release-readiness auditing of Ledger v1.0.1 (`versionCode = 2`), tapping "Sign In with Google" in the navigation drawer crashed the application because `WEB_CLIENT_ID` in `GoogleAuthManager.kt` was set to placeholder `"REPLACE_WITH_YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"`. In addition, the drawer displayed misleading claims that Google Drive cloud sync was operational. Cloud sync was not operational.

### Decisions
1. **Elimination of Crash Entry Point:**
   - The "Sign In with Google" navigation item is completely removed from the navigation drawer in production release builds when unauthenticated.
   - Retained "Sign Out" conditionally only if a legacy email was previously cached in preferences, allowing graceful local preference clearance without altering Room database records.
2. **Defensive Fail-Safe Guarding Across Auth & Sync Architecture:**
   - Added `val isConfigured: Boolean` in `GoogleAuthManager.kt`. If the Web Client ID starts with `"REPLACE_WITH"`, `isConfigured` is false.
   - `attemptSignIn()` returns `AppResult.Error` immediately without launching Android Credential Manager.
   - `signOut()` returns `AppResult.Success(Unit)` without calling unconfigured Credential Manager methods.
   - `HomeViewModel`, `DriveSyncManager`, and `DriveSyncWorker` guard against initiating periodic sync or work requests when `!isConfigured`.
   - `HomeViewModel.triggerSync()` reconciles Room with local partitioned backups rather than calling remote network services.
3. **Transparent Offline Messaging:**
   - Replaced misleading Drive claims with honest offline ledger notices: `"Offline Mode (Local Storage)"`, `"Manual SAF Backup & Restore"`, and `"Offline version: Cloud sync is unavailable. Use 'Export Backup' below to save an off-device copy."`
4. **Autonomous Local Ledger & SAF Backup Integrity:**
   - All local Room databases, DAOs, balance calculations, customer records, and SAF export/import operations function with 100% autonomy without Google authentication or network connectivity.
5. **Release Candidate Verification (APK SHA-256: `4cc2bceeda409f48db6bf8a8fe5f4a79aa72c7a51dcfa737bd10488e4386739b`):**
   - 322/322 unit tests passed. Clean install on isolated emulator `emulator-5554` succeeded with zero crashes.
   - Mathematical formula $\sum \text{RECEIVE} - \sum \text{GIVE}$ verified (+₹1997.0 from RECEIVE ₹7000.0 and GIVE ₹5003.0).
   - Data persistence across force-stop verified. SAF export and full restore verified.
6. **Four-Way Differentiated Release Verdicts:**
   - **Limited Clean-Install Beta:** `GO WITH CONDITIONS` (Operator off-device key backup required).
   - **Public Clean-Install Distribution:** `GO WITH CONDITIONS` (Clear offline-only store listing).
   - **Existing User Update:** `NO-GO` (Cross-key signature incompatibility).
   - **Physical Phone Migration:** `NO-GO` (Pending on-device in-app export and emulator reconciliation).

---

## Decision 39: Android ANR Root Cause Resolution, Deterministic UI Smoke Test Standards, Native Receipt Image Decoding, and Version 1.0.2 Release Gate

**Date:** 2026-10-30
**Status:** Approved & Formally Verified
**Category:** ANR Investigation, Quality Assurance, Native Media Decoding & Final Release Gate

### Context
Following Session 30 clean-install release candidate testing, recorded emulator history showed an Android ANR dialog, an investigation of `ANR in com.kb.ledger`, an app force-stop, and later disabling of Android ANR dialogs. In addition, the smoke test produced unexpected inputs: an intended GIVE amount of ₹500 resulted in ₹5,003, and the synthetic customer name was truncated. Before approving release, a formal investigation was mandated to determine the exact root cause, repeat the smoke test deterministically, verify genuine JPEG receipt restoration with native Android decoding, bump versionCode to 3 / versionName to 1.0.2, and issue final release gate verdicts.

### Decisions
1. **ANR Root Cause & Codebase Non-Involvement:**
   - System trace analysis (`dumpsys activity lastanr-traces`) revealed the event was an `Input dispatching timed out` waiting for a KeyEvent on `PointerEventDispatcher0` (5,751 ms) at 11:59:32 IST.
   - The main thread was idle in `nativePollOnce` waiting for the Android Looper queue. Zero instances of `runBlocking` or `Thread.sleep` exist in `com.kb.ledger`. Room database explicitly prohibits main thread queries (`allowMainThreadQueries` absent). `com.kb.ledger` consumed only 3.2% CPU.
   - Root cause was host/emulator CPU overload (load average 24.8, graphics composer consuming 55% CPU) combined with concurrent `uiautomator dump` holding global accessibility inspection locks on `system_server` (`IAccessibilityManager took 3992ms`) while rapid `adb shell input text` was firing into Gboard.
   - Re-enabling Android ANR dialogs (`show_anr_dialogs = 1`) confirmed that the ANR is not reproducible during production usage or deterministic automation.
2. **Deterministic UI Automation Standards:**
   - **Name Truncation Root Cause & Fix:** Gboard active composition was prematurely aborted by `input keyevent 111` (Escape) before words were committed. Switching to deterministic word-by-word space-commit typing (`input text <word>` followed by `keyevent 62` SPACE) guarantees Gboard commits every word. Name `Release Test Customer` entered and verified with 100% exactness.
   - **Unexpected Amount (₹5,003) Root Cause & Fix:** In Session 30, a blind coordinate tap aimed at "Save Entry" landed on key '3' of the open numeric keypad because the dialog had shifted. Eliminated blind coordinate taps; implemented pre-submission field verification and dynamic bounds resolution, ensuring exact entry of ₹500.00 and ₹700.00.
   - **ElementTree Boolean Check Standard:** In Python's `xml.etree.ElementTree`, leaf elements with zero child nodes evaluate to `False` in a boolean context (`bool(element) == (len(element) > 0)`). UI test scripts must always use explicit `if elem is not None:` checks to prevent falsy evaluation of valid leaf UI nodes.
3. **Native Receipt Image Decoding & SAF Restore Verification:**
   - Prepared synthetic backup containing 1 person, 2 transactions (GIVE ₹500.00, RECEIVE ₹1200.00), and a genuine 200x200 JPEG receipt (JFIF markers, 7,700 bytes, SHA-256 `ff362708...`).
   - Restored into `com.kb.ledger` via Android `DocumentsUI` SAF interface.
   - Verified native Android image decoding in ART runtime via `android.graphics.BitmapFactory.decodeFile()` (`200x200, ARGB_8888, byteCount=160000`).
   - Confirmed in-app Coil thumbnail rendered without errors; confirmed `"Image unavailable"` placeholder was completely absent.
   - In-app SAF round-trip export confirmed the restored image file exists in internal storage and was exported with 100% exact SHA-256 byte equality.
   - Verified net balance: `Total you will get: ₹700.0` (₹1200.00 - ₹500.00 = +₹700.00).
4. **Release Candidate v1.0.2 (`versionCode = 3`):**
   - Cleanly bumped `versionCode = 3` and `versionName = "1.0.2"` in `app/build.gradle.kts`.
   - Release APK built (`app-release.apk`, SHA-256: `ca51205534d0afa807e475c1ce346e8b904515b11d0e2aaeb3b3d20ebe81583c`).
   - Signing certificate verified against permanent production key: `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5`.
   - All 322 automated tests pass across 27 suites.
5. **Formal Four-Way Release Gate Verdicts:**
   - **Limited Clean-Install Beta:** `GO`. Approved for fresh/new beta users.
   - **Public Clean-Install Release:** `GO WITH CONDITIONS`. Approved once operator creates off-device encrypted key backup.
   - **Existing-User Data-Preserving Update:** `NO-GO`. Cryptographic signature mismatch (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
   - **Physical OnePlus Migration:** `NO-GO`. Live phone data must be safely exported in-app and trial-restored on emulator first.

---

## Decision 40: Camera Capture Receipt Attachment Flow, UI/UX Overhaul, Android 16 (API 36) Migration, and Version 1.0.3 Release Gate

**Date:** 2026-11-01
**Status:** Approved & Formally Verified
**Category:** Camera Integration, Privacy Sanitization, Design System, Platform Upgrade & Final Release Gate

### Context
To prepare Ledger for general production release, the bill/receipt attachment flow required a working camera capture option alongside modern gallery picking, a professional and consistent UI/UX overhaul across all primary screens, Android 16 (API 36) submission compliance, automated test suite expansion, and comprehensive emulator-based release gate verification.

### Decisions
1. **Camera Capture & FileProvider Architecture:**
   - Integrated native `ActivityResultContracts.TakePicture()` in `MainActivity.kt` and `BillImageManager.kt`.
   - Configured non-exported FileProvider (`${applicationId}.fileprovider`) in `AndroidManifest.xml` backed by `res/xml/file_paths.xml` (`files/receipts/`). Avoids requesting broad `android.permission.CAMERA` in the application manifest.
   - Integrated Android Photo Picker (`ActivityResultContracts.PickVisualMedia()`) for permission-free gallery image selection.
   - Built two-phase atomic staging pipeline: incoming captures and picks are isolated in `files/staging/`. Existing valid receipts remain untouched until the user explicitly confirms the new image via "Use Photo". Temporary files are pruned on cancellation or failure.
   - Built `ReceiptImageProcessor`: decodes safely, strips sensitive GPS EXIF metadata, corrects rotation via EXIF orientation matrix, downsamples images exceeding 2048px to prevent OOM, and re-encodes to clean JPEG.
   - Added Compose dialogs in `ui/BillImageDialogs.kt`: `BillImageSourceDialog` ("Take Photo", "Choose from Gallery", "Cancel"), `ReceiptCapturePreviewDialog` ("Retake" / "Choose Another", "Use Photo", "Cancel"), and `BillImagePreviewDialog` for fullscreen viewing.
2. **Android 16 / API 36 Target Upgrade:**
   - Upgraded `compileSdk = 36`, `targetSdk = 36`, `versionCode = 4`, `versionName = "1.0.3"` in `app/build.gradle.kts`.
   - Verified Android 16 behavioral compatibility (photo picker, edge-to-edge layout, predictive back, FileProvider URI permissions).
3. **Professional UI/UX Polish:**
   - Standardized on 4dp/8dp spacing increments and 48dp minimum touch targets.
   - Strictly preserved accounting invariants: $\text{Net Balance} = \sum \text{RECEIVE} - \sum \text{GIVE}$ (GIVE = debit / amber-red, RECEIVE = credit / emerald-green).
   - Captured and verified 12 visual evidence screenshots archived in `release-evidence/ui/`.
4. **Automated & Emulator Verification:**
   - Freshly executed `./gradlew testDebugUnitTest` under JDK 17: **340/340 tests passing across 28 suites (0 failures, 0 errors, 0 skipped)**.
   - Executed `scripts/e2e_camera_and_release_verification.py` across 17 steps on `emulator-5554` (Android 17 / API 37):
     - Clean install and launch: PASS.
     - Customer creation and exact financial arithmetic (+₹200.0 from GIVE ₹500 and RECEIVE ₹700): PASS.
     - Camera capture via `com.android.camera2`: PASS.
     - Retake flow and Use Photo confirmation: PASS.
     - Thumbnail rendering on transaction row and fullscreen preview: PASS.
     - Cold restart persistence: PASS.
     - SAF JSON backup export: PASS (`billImagesByTxId` present).
     - Native ART runtime `BitmapFactory.decodeFile()`: PASS (`1440x1920, ARGB_8888, byteCount=11059200`).
     - `pm clear` full data wipe and SAF restore: PASS (Customer, balances, and thumbnail restored).
     - Logcat crash & ANR audit: **0 crashes, 0 ANRs (PASS)**.
   - Executed `scripts/test_clean_dev_logs.py`: 6/6 tests passing.
   - Executed `git diff --check`: Clean (0 whitespace/conflict errors).
5. **Release Artifacts (v1.0.3, versionCode 4):**
   - Release APK: `app/build/outputs/apk/release/app-release.apk` (16,035,408 bytes, SHA-256 `d6345e0910852b93eb1bf722ede2582ea4a02dcfd9f82f46f2acb128c0314ea2`).
   - Release AAB: `app/build/outputs/bundle/release/app-release.aab` (15,012,738 bytes, SHA-256 `848748025f6380922baa799f599dce03eef0c153390e78607d848a00886ff579`).
   - Signed with permanent production key (`ledger-prod-release`, 4096-bit RSA). Signer #1 SHA-256: `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5`.
6. **Formal Four-Way Release Gate Verdicts:**
   - **Track A (Camera & Receipt Feature):** `GO`. Fully verified end-to-end on emulator with native ART decoding.
   - **Track B (Professional UI & Usability):** `GO`. Fully audited and verified across 12 screens.
   - **Track C (Clean-Install Public Google Play Release):** `GO WITH CONDITIONS`. Approved once operator creates off-device encrypted key backup.
   - **Track D (Existing-User / Physical OnePlus Update):** `NO-GO`. Cryptographic signature mismatch; operator must export in-app SAF backup on phone and trial-restore on emulator first.

---

## Decision 41: Per-Transaction Sync Message Removal, Aggregate Sync Counter Backlog, and Final Evidence Reconciliation (v1.0.3)

**Date:** 2026-11-01
**Status:** Implemented, Rebuilt & Formally Verified on Android 16 (API 36) Emulator
**Category:** UI/UX De-cluttering, Data Integrity & Next-Release Backlog

### Context
In v1.0.3 testing on the Android 16 emulator, each transaction row displayed an individual "Pending sync" badge alongside the transaction date/time with a cloud icon. In this offline release, cloud sync is unconfigured, making per-transaction sync status confusing, distracting, and misleading. Furthermore, reconciliation of earlier automated command retries vs. final successful executions was required.

### Decisions
1. **Removal of Per-Transaction Sync Badge:**
   - Removed the `if (tx.syncPending) { Surface { ... "Pending sync" ... } } else { Icon(CloudDone) }` block from `TransactionItem` in `MainActivity.kt`.
   - The transaction row now cleanly presents the formatted date/time (`dd MMM yyyy, hh:mm a`), transaction note, amount, debit/credit styling, and receipt thumbnail without per-row cloud badges.
   - Preserved all persistent models and fields: `syncPending`, `imageFileId`, `cloudId`, Room entity columns, database migrations, tombstone records, and background sync worker logic remain 100% untouched.
2. **Next-Release Aggregate Sync Counter Backlog (NOT in v1.0.3):**
   - Formalized specification for future release UI: `Pending sync: X of Y transactions`.
   - **Backlog Invariants:**
     - Must appear once in a central location (e.g., home dashboard sync header or app settings), NEVER on individual transaction rows.
     - Must count eligible active transactions consistently and distinguish successfully synced transactions from pending ones.
     - Must handle retry and failure states safely without miscounting tombstones.
     - Must only become visible when cloud sync is actually configured and operational.
3. **Automated & Emulator Verification of UI Correction:**
   - Executed `scripts/verify_sync_ui_correction.py` on Android 16 (`emulator-5554`, `ro.build.characteristics=emulator`, API 36):
     - Verified customer creation and entry of GIVE (₹450.00) and RECEIVE (₹900.00 with camera receipt).
     - Inspected transaction list UI: verified 0 occurrences of "Pending sync", "Not synced", or cloud sync icons.
     - Inspected `EditTransactionDialog`: verified clean transaction details without any sync labels.
     - Logcat audit: 0 FATAL EXCEPTIONs, 0 ANRs.
     - Captured fresh screenshots: `release-evidence/sync_ui_verification/01_customer_detail_transactions.png` and `02_edit_transaction_detail.png`.
4. **Rebuilt Release Artifacts (v1.0.3, versionCode 4):**
   - Re-executed `./gradlew testDebugUnitTest --rerun-tasks`: 340/340 unit tests passing across 28 suites.
   - Rebuilt APK: `app/build/outputs/apk/release/app-release.apk` (16,035,408 bytes, SHA-256 `66b70ed6f7736ad6bf087747fd1430abb3ee1f448a9e0de5fe2182d0b9e40df8`).
   - Rebuilt AAB: `app/build/outputs/bundle/release/app-release.aab` (15,011,500 bytes, SHA-256 `65943ef98c0a1446d5b3ccdf7d7311b051441b792b51492bede2e28cffc1c452`).
   - Signing certificate verified: `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5` (Exact permanent release key match).

---

## Decision 42: OnePlus 10R Physical Device Safeguard Audit, Cryptographic Upgrade Blocker Enforcement, and Off-Device Backup Verification

**Date:** 2026-11-01
**Status:** Completed & Formally Verified
**Category:** Physical Device Safety, Cryptographic Security & Data Preservation

### Context
User requested safe installation of Ledger v1.0.3 on their physical OnePlus 10R phone containing real financial records and contacts. Preserving existing customer records, balances, transactions, and receipt files took absolute priority over installation.

### Decisions & Verified Outcomes
1. **Physical Phone Identity Verification:**
   - Physical device at serial `LR8H55PB9PMRNFUO` verified via ADB properties: Manufacturer `OnePlus`, Model `CPH2423` (OnePlus 10R 5G), Product `OP5567L1`, Android 15 (API 35), build characteristics `default` (physical hardware).
2. **Release Candidate APK Integrity Check:**
   - Release APK `app/build/outputs/apk/release/app-release.apk` verified: 16,035,408 bytes, SHA-256 `66b70ed6f7736ad6bf087747fd1430abb3ee1f448a9e0de5fe2182d0b9e40df8`.
   - Permanent production certificate verified: `CN=Ledger Production Release`, SHA-256 `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5`.
   - Target SDK: 36 (Android 16), Version Code: 4, Version Name: 1.0.3.
3. **Cryptographic Signature Audit & Installation Blocker:**
   - Base APK pulled from OnePlus 10R to temporary inspection space.
   - Installed app was verified as `versionCode=1`, `versionName=1.0`, signed with standard **Android Debug Key** (`CN=Android Debug`, SHA-256 `e1ca16bf26367d7b85bf48437c4fbca8331d63561dd3d19b09dbde1034d30484`).
   - Candidate release is signed with **Permanent Production Key** (`4a449fe0...`).
   - Under Android security architecture, an in-place upgrade (`adb install -r`) across distinct signing keys is cryptographically rejected (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
   - Performing an uninstallation or forced install would delete `/data/data/com.kb.ledger/` and destroy real customer financial records.
   - **Verdict:** In-place installation halted immediately: **`B. INSTALLATION BLOCKED — SIGNATURE MISMATCH`**.
4. **Safeguard Backup Outside Repository:**
   - Created full off-device safeguard backups outside git workspace at `/Users/vivekkumar/Documents/ledger_oneplus_safeguard_backup/`:
     - Two compressed raw archives: `ledger_raw_backup_A.tar.gz` and `ledger_raw_backup_B.tar.gz` (507,175 bytes, SHA-256 `432d3bcca9a2c2bbe3f033ad41aaec437120074106d4e6268624b180727f1978`).
     - Two standard schema 3 JSON backups with Base64 receipts: `ledger_oneplus_backup_A.json` and `ledger_oneplus_backup_B.json` (918,288 bytes, SHA-256 `2a60d9f3d38661e13c51aca23e97764e0f545c18c8c1cbbe67f3ce434693a299`).
   - Ground truth records verified: 2,095 persons (2,093 active, 1 archived, 1 tombstone), 16 transactions (14 active, 2 tombstones), Net Balance -₹470.00 (You Give ₹470.00), 1 receipt image (140,085 bytes).
5. **Emulator Restore Verification (Android 16 / API 36):**
   - Restored phone backup on `emulator-5554` running candidate release APK.
   - Dashboard immediately reflected exact Net Balance `Total You Give ₹470.0`.
   - Re-exported backup and reconciled against phone backup: 2,095 persons (100%), 16 transactions (100%), 1 receipt image (100% exact byte equality).
   - Native ART `BitmapFactory` decode verified via `app_process`: `1080x2412, ARGB_8888, byteCount=10419840`.
   - Customer detail and full-screen receipt preview displayed with 0 errors.
   - Logcat audit: 0 crashes, 0 ANRs.
6. **Physical Phone Safety Guarantee:**
   - The OnePlus 10R was NOT uninstalled, cleared, or modified. All user data remains 100% intact.

---

## Decision 43: Customer & Supplier Ledger Separation and Individual Transaction Deletion (v1.0.4)

**Date:** 2026-11-09
**Status:** Completed & Formally Verified
**Category:** Domain Modeling, UI/UX, Accounting & Data Integrity

### Context
Users requested separating customer ledgers from supplier ledgers and providing an individual transaction deletion capability without destabilizing the local-first architecture, backup fidelity, or existing financial records.

### Decisions & Verified Outcomes
1. **Customer & Supplier Role Separation:**
   - Added `PersonRole { CUSTOMER, SUPPLIER }` to `Person` entity and `PersonDomain`.
   - Bumped Room schema to version 6 with non-destructive migration (`ALTER TABLE persons ADD COLUMN role TEXT NOT NULL DEFAULT 'CUSTOMER'`).
   - Phone numbers are completely independent across roles: `ContactSearchHelper` keys entities by `${person.role.name}|||$normName|||$normPhone`, strictly preventing duplicate deduplication or merging across roles.
2. **Dedicated Supplier Accounting Rules:**
   - GOODS RECEIVED (RECEIVE): Goods/credit received from supplier (Increases payable / "You Owe").
   - YOU PAID (GIVE): Payment made to supplier (Decreases payable / Reduces "You Owe").
   - Net Supplier Payable: $\sum \text{RECEIVE} - \sum \text{GIVE}$ ($> 0$: You Owe; $< 0$: Advance; $= 0$: Settled).
   - Dashboard Card 1 (Total Payable): $\sum \max(\text{Net Payable}, 0)$ so supplier advances never offset debts owed to other suppliers.
   - Dashboard Card 2 (Paid Today): Sum of all supplier `GIVE` transactions on the current calendar date.
   - Action buttons adapt contextually to "YOU PAID ₹" and "GOODS RECEIVED ₹" with distinct colors.
3. **Safe Individual Transaction Deletion:**
   - Added accessible overflow menu (`MoreVert`) on each transaction row with "Edit Transaction" and "Delete Transaction".
   - Added confirmation dialog displaying party name, transaction type, formatted amount, and explicit warning that balance will recalculate immediately.
   - Uses non-destructive soft deletion (`isDeleted = 1`), queues sync tombstones, recalculates Room balances reactively, and preserves receipt files on disk.
4. **SAF Backup Schema v4:**
   - Bumped backup schema version to 4 with explicit `role` serialization.
   - Fully backward-compatible with legacy v1–v3 backups.
5. **Emulator Verification:**
   - 350/350 unit tests passing.
   - In-place upgrade, supplier accounting, transaction deletion, same-phone independence, and SAF round-trip verified on Android 16 (`emulator-5554`).
   - 8 evidence screenshots archived in `release-evidence/v1.0.4/`.

---

## Decision 44: Privacy-Preserving System Contact Picker for Suppliers, Enhanced Phone Search, Duplicate Prevention & Authorized v1.0.5 Physical Rollout

**Date:** 2026-11-10
**Status:** Implemented, Verified & Deployed In-Place on Physical OnePlus 10R
**Category:** Contacts Privacy, Search Architecture, Bug Fix & Safe Rollout

### Context
On Ledger v1.0.4 on the physical OnePlus 10R (`CPH2423`, Android 15 / API 35, `READ_CONTACTS=false`), Customer mode displayed phone numbers because address-book entries had previously been synced into `PersonRole.CUSTOMER`. Switching to Supplier mode isolated `PersonRole.SUPPLIER` (0 entries initially), exposing four gaps:
1. `AddPersonDialog` had no system contact picker to populate Name and Phone when creating a Supplier (or Customer).
2. Searching a phone number before tapping `"Add '<query>' as New Supplier"` populated Name instead of Phone.
3. `ContactSearchHelper.matchesQuery` did not strip country/trunk prefixes on partial queries (e.g., `+91 98765`, `09876`).
4. Zero-transaction newly created Suppliers were hidden from the empty-search Suppliers dashboard (`getActivePersonsWithTransactionsByRole`).

### Decisions & Verified Outcomes
1. **Privacy-Preserving System Contact Picker (`ContactManager`):**
   - Uses `Intent(Intent.ACTION_PICK).apply { type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE }` rather than forcing broad runtime `READ_CONTACTS` grants; relies on Android's transient single-row URI permission grant on the picked `data/` URI.
   - `ContactManager.resolvePickedContact` also falls back gracefully for contact lookup/entity URIs when accessible, deduplicates multi-number contacts via `deduplicatePhoneOptions`, triggers an in-app `"Select Phone Number"` dialog if multiple distinct numbers exist, and handles 0-phone contacts with clear inline guidance.
   - Both Name and Phone fields remain editable before saving.
2. **Search & Duplicate Prevention (`ContactSearchHelper` & `HomeViewModel`):**
   - Enhanced digit-level matching inside `matchesQuery` for purely numeric/phone queries (spaces, hyphens, `+91`/`91`, trunk `0`, and international prefixes) without mutating stored numbers and without false-matching mixed text queries.
   - Added `splitInitialPersonQuery`, `filterAndGroupForRole`, `findExactDuplicate`, and `findPhoneDuplicate` scoped strictly within `PersonRole` so Customers and Suppliers sharing identical phone numbers remain independent while exact same-role duplicates are blocked (`Open Existing`).
   - Updated Suppliers dashboard `dashboardActivePersons` / `dashboardArchivedPersons` to surface all user-created Suppliers even before their first transaction, while preserving Customer dashboard filtering (`getActivePersonsWithTransactionsByRole`) unchanged.
3. **Zero Schema Churn:**
   - Kept Room Schema v6 and Backup Schema v4 completely untouched.
4. **Verification & Authorized Physical Rollout (`v1.0.5`, `versionCode 6`):**
   - **Unit Tests:** 359/359 passed across 33 suites (including 9 new tests in `SupplierContactAndSearchTest`).
   - **Instrumentation Tests (`emulator-5554`):** 10/10 passed (`SupplierContactUiTest` 7 tests + `DriveIntegrationTest` 3 tests).
   - **API 35 Emulator E2E (`emulator-5556`):** Verified native picker without `READ_CONTACTS`, multi-number pick, duplicate detection, search queries, Customer isolation, and SAF Schema 4 backup/restore.
   - **OnePlus 10R (`LR8H55PB9PMRNFUO`) Deployment:** Exported & validated fresh v1.0.4 SAF backup (`ledger_backup_20261110_140659.json`, `1,482,901` bytes, SHA-256 `510872b8...`, dual local copies), installed `app-release-v1.0.5.apk` (`bee68820...`, cert `4a449fe0...`) via `adb install -r`, confirmed 100% pre/post match across 4,190 persons (`CUSTOMER`) and 4 transactions (2 active, 2 tombstones), verified Suppliers/ContactPicker read-only, and confirmed 0 crashes/migration errors in logcat.
