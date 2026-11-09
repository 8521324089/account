# Ledger Pre-Release & Release Checklist

Every distributed build of Ledger (`com.kb.ledger`) must satisfy all checklist items below prior to deployment or distribution. This document establishes non-negotiable data-safety, release engineering, and privacy standards.

---

## 1. Release Engineering & Build Verification

- [ ] **Clean Release Build on Certified JDK:**  
  Release APK must compile cleanly with `./gradlew clean assembleRelease` using the certified JDK (Eclipse Temurin JDK 17). All `lintVital` checks must pass without disabling or suppressing lint rules.
- [ ] **Release APK Cryptographic Signature:**  
  The APK must be signed using the permanent 4096-bit RSA production key (`ledger-prod-release`). Signature must be validated with Android SDK `apksigner`:
  ```bash
  apksigner verify --verbose --print-certs app-release.apk
  ```
  Confirm Scheme v2 = `true`, and match the official SHA-256 certificate digest.
- [ ] **Version Code & Metadata Progression:**  
  Verify `versionCode` strictly increments (e.g., from `1` to `2`) and `versionName` reflects the release (e.g., `"1.0.1"`). Verify using `aapt dump badging app-release.apk`.
- [ ] **Keystore & Secret Protection:**  
  Confirm `ledger-release.jks` and `keystore.properties` are strictly excluded from Git in `.gitignore` and have restricted local filesystem permissions (`chmod 600`).
- [ ] **Secure Off-Device Keystore Backup:**  
  The permanent production keystore and its credentials must be securely archived in an encrypted off-device vault. Never mark off-device recovery as passed merely because the key exists locally. Must remain BLOCKED until verified off-device copy exists.
- [ ] **Preserved Rollback APK Verification:**  
  Verify a rollback APK exists in `app/build/outputs/apk/debug/app-debug.apk`. The existence of a rollback APK must be tracked separately from proof that it can update or roll back the currently installed phone app.
- [ ] **Physical Device Signature Compatibility:**  
  Physical phone signing compatibility remains NOT VERIFIED until the certificate of the package currently installed on the phone is independently inspected and checked against the target APK.

---

## 2. Automated Test Suite & Room Migration Verification

- [ ] **Full Automated Unit & Integration Tests:**  
  Execute `./gradlew testDebugUnitTest`. 100% of tests must pass (0 failures, 0 errors, 0 skipped).
- [ ] **Chained Room Migration Tests:**  
  Every supported prior schema version (baseline: Schema Version 2) must have an automated chained migration test (`v2 -> v3 -> v4 -> v5`) verifying:
  - Exact currency preservation (paise/cents arithmetic, zero floating-point rounding errors).
  - Customer ID stability and foreign key preservation (`personId`).
  - Active and tombstone record preservation (`isDeleted`).
  - Archival flags and recurring rule table creation.
- [ ] **No Destructive Database Logic:**  
  Confirm `fallbackToDestructiveMigration()`, `deleteDatabase()`, `clearAllTables()`, and table drops are completely absent from production runtime code.

---

## 3. Data Safety, Backup, & Recovery Invariants

- [ ] **External Backup Export & Restore (SAF):**  
  Verify that `BackupManager.exportData` and `BackupManager.importData` correctly round-trip:
  - All customer records and stable IDs.
  - Transactions, timestamps, and exact GIVE/RECEIVE types.
  - Soft-delete tombstones (`isDeleted = true`).
  - Recurring rules and schedules.
  - Embedded binary receipt images (Base64 JPEG payload, not dangling URIs).
- [ ] **Native Android Image Decoding & Display:**  
  Distinguish Base64 validity, magic byte header validation, and native Android image decoding with app UI display. A valid header alone is NOT proof of an image being decodable. Require native decoding by Android and in-app display.
- [ ] **Physical-Device In-App Export & Trial Restore:**  
  Physical-device export and restore remain NOT TESTED until a real in-app export from the physical phone has been restored and reconciled on an isolated emulator.
- [ ] **Current Production Baseline Verification:**  
  An old SQLite backup must NOT be represented as a valid current backup if its record counts or schema are stale. Baseline counts must be established from a fresh export.
- [ ] **Non-Destructive Restore Guarantee:**  
  Verify that backup import uses transactional UPSERT logic. It must never wipe or delete pre-existing unmentioned records.
- [ ] **Corruption Resilience:**  
  Verify that truncated JSON files, invalid syntax, and random non-JSON binaries fail safely before touching Room SQLite tables.
- [ ] **In-Place Upgrade Audit:**  
  Verify upgrade behavior over compatible previous builds. If an existing device runs a debug-signed build, document explicitly that Android OS blocks cross-key in-place upgrade (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and ensure the device is NOT uninstalled or wiped.
- [ ] **Zero Destructive Actions on Production Data:**  
  Never clear app storage, uninstall production builds, wipe devices, or reset live databases during testing.

---

## 4. Privacy & Engineering Hygiene Standards

- [ ] **Sanitized Logs & Evidence:**  
  All development logs in `dev-logs/` and release evidence in `release-evidence/` must strictly exclude:
  - Customer names, phone numbers, and contact book details.
  - Transaction amounts, notes, and financial balances.
  - Receipt image contents or user files.
  - Passwords, keystore secrets, or OAuth tokens.
- [ ] **Log Retention Compliance:**  
  Verify operational logs in `dev-logs/` adhere to the 7-day retention policy (`python3 scripts/clean_dev_logs.py`). Permanent milestones must be recorded in `PROGRESS_LOG.md` and `PROJECT_CONTEXT.md` before daily logs expire.
- [ ] **Honest Limitations Reporting:**  
  Record every verification item as **PASS**, **FAIL**, or **NOT TESTED**. Never mark a requirement as passed based on assumptions.
