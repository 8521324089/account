# Ledger v1.0.5 — Authorized In-Place Installation Report (OnePlus 10R)

- **Date:** 2026-11-10
- **Status:** **(A) Backup verified, in-place update successful, and data checks passed**
- **Device:** OnePlus 10R 5G (`CPH2423`, `device:OP5567L1`, `ro.build.characteristics=default`)
- **ADB Serial:** `LR8H55PB9PMRNFUO` (`usb:1048576X`)
- **Android OS / API Level:** Android `15` / API `35`
- **Package Name:** `com.kb.ledger`
- **Previous Installed Version:** `1.0.4` (`versionCode = 5`, `firstInstallTime = 2026-11-01 22:31:52`, `lastUpdateTime = 2026-11-10 13:31:37`)
- **Newly Installed Version:** `1.0.5` (`versionCode = 6`, `targetSdk = 36`, `minSdk = 26`, `lastUpdateTime = 2026-11-10 14:08:06`)

---

## 1. Pre-Install Physical Device & Signing Audit

Executed via explicit `adb -s LR8H55PB9PMRNFUO` commands prior to backup/install:
- Connected devices verified via `adb devices -l`; `LR8H55PB9PMRNFUO` positively identified as `OnePlus CPH2423` (`OP5567L1`).
- Installed `v1.0.4` base APK pulled cleanly for read-only inspection and verified via `apksigner verify --print-certs`:
  - Signer DN: `CN=Ledger Production Release, OU=Ledger Mobile, O=KB Ledger, L=Bangalore, ST=Karnataka, C=IN`
  - Signer SHA-256: `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5`
- Contacts permission status: `READ_CONTACTS: granted=false`, `WRITE_CONTACTS: granted=false`.

---

## 2. Fresh Pre-Install SAF Backup Verification

Exported live from installed `v1.0.4` via Ledger Drawer → `Export Backup` (Android SAF DocumentsUI):

- **Filename:** `ledger_backup_20261110_140659.json`
- **Creation Time (Device):** `2026-11-10 14:07`
- **Exact File Size:** `1,482,901` bytes
- **Schema Version:** `4` (`exportDate = 1794299824855`)
- **SHA-256 Checksum:** `510872b88f45977dcddc852cb4502fcb505ec53fc453a400b6546b80808ef2b0`
- **Preserved Copies (all read-only `chmod 444`, identical SHA-256):**
  1. Phone SAF destination (untouched original): `/sdcard/Documents/ledger_backup_20261110_140659.json`
  2. Mac Primary Safeguard Copy: `/Users/vivekkumar/Documents/ledger_oneplus_safeguard_backup/v1.0.5_pre_install/ledger_backup_20261110_140659.json`
  3. Mac Secondary Mirror Copy (no external `/Volumes` disk mounted): `/Users/vivekkumar/.ledger_safeguard_backup_mirror/v1.0.5_pre_install/ledger_backup_20261110_140659.json`

### JSON Structural & Integrity Validation
- **JSON Parsing:** Valid complete JSON.
- **Persons:** `4,190` total (`4,189` active non-deleted, `0` archived, `1` soft-deleted tombstone); role breakdown: `4,190` `CUSTOMER`, `0` `SUPPLIER`; duplicate person IDs: `0`; malformed records: `0`.
- **Transactions:** `4` total (`2` active, `2` soft-deleted tombstones); active persons with transactions: `2`; duplicate transaction IDs: `0`; dangling `personId` references: `0`; malformed transactions: `0`.
- **Aggregate Balances:** Total active `GIVE`: `11,000` paise (`₹110.0`), Total active `RECEIVE`: `0` paise (`₹0.0`).
- **Recurring Rules:** `0`.
- **Receipt Attachments Coverage:** `0` transactions with `imageUri`/`imageFileId`; `0` entries in `billImagesByTxId` (confirmed no unprotected receipt files exist).

---

## 3. Release APK Verification (`v1.0.5`)

Verified directly against `release-evidence/v1.0.5/app-release-v1.0.5.apk` on disk:

| Attribute | Verified Value | Match |
| :--- | :--- | :---: |
| **Artifact Path** | `release-evidence/v1.0.5/app-release-v1.0.5.apk` | YES |
| **Package Name** | `com.kb.ledger` | YES |
| **Version Name / Code** | `1.0.5` / `6` | YES |
| **Min / Target / Compile SDK** | `26` / `36` / `36` | YES |
| **Exact File Size** | `16,068,176` bytes | YES |
| **APK SHA-256** | `bee688207b07649b702e60bd7c7fe422d32951ddc9e8a606968a8160409296b6` | YES |
| **Signature Scheme** | `APK Signature Scheme v2: true` (`Verifies`) | YES |
| **Signer Cert SHA-256** | `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5` (`CN=Ledger Production Release`) | YES |

---

## 4. In-Place Update Execution

- Command executed:
  ```bash
  adb -s LR8H55PB9PMRNFUO install -r release-evidence/v1.0.5/app-release-v1.0.5.apk
  ```
- Output:
  ```
  Performing Streamed Install
  Success
  ```
- Zero uninstall, cache clear, `pm clear`, `-d`, or `-t` flags used.

---

## 5. Post-Installation Verification & Pre/Post Data Comparison

### Package & Signature Continuity
- `dumpsys package com.kb.ledger`:
  - `versionCode=6`, `versionName=1.0.5`, `minSdk=26`, `targetSdk=36`
  - `firstInstallTime=2026-11-01 22:31:52` (unchanged -> true in-place update confirmed)
  - `lastUpdateTime=2026-11-10 14:08:06`
  - `READ_CONTACTS: granted=false`, `WRITE_CONTACTS: granted=false` (no broad contacts permission granted)
- Post-install APK pulled and verified via `apksigner`: SHA-256 `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5` (matches).

### Read-Only Post-Install Backup & Pre/Post Comparison
A read-only post-install SAF export (`ledger_backup_20261110_140928.json`, `1,482,901` bytes, SHA-256 `0049dbac7a1bee52dc504c1dc0efb5860c7881606e20bc4baeabd2ce60117883`, archived read-only at `/Users/vivekkumar/Documents/ledger_oneplus_safeguard_backup/v1.0.5_post_install/`) was compared field-by-field against `ledger_backup_20261110_140659.json`:

| Entity / Metric | Pre-Install (`v1.0.4`) | Post-Install (`v1.0.5`) | Diff / Discrepancies |
| :--- | :---: | :---: | :---: |
| **Backup Schema Version** | `4` | `4` | None |
| **Total Persons** | `4,190` | `4,190` | `0` discrepancies (`100% exact field match`) |
| **Role Distribution** | `CUSTOMER: 4,190` | `CUSTOMER: 4,190` | `0` unintended changes |
| **Total Transactions** | `4` (`2` active, `2` deleted tombstones) | `4` (`2` active, `2` deleted tombstones) | `0` discrepancies (`100%` match on `id`, `personId`, `amountCents`, `type`, `createdAt`, `isDeleted`, image refs) |
| **Dashboard Totals** | `Total You Give ₹110.0`, `Received Today ₹0.0` | `Total You Give ₹110.0`, `Received Today ₹0.0` | Exact match (`₹10.0` & `₹100.0` active rows) |
| **Suppliers Dashboard** | `Active (0)` | `Active (0)`, `Total Payable ₹0.0`, `Paid Today ₹0.0` | Clean empty Suppliers state |
| **Recurring Rules / Receipts** | `0` / `0` | `0` / `0` | Exact match |

### Read-Only UI & Crash Checks
- **Customers Dashboard:** Launched cleanly; `Customers | Suppliers` toggle visible; `Active (2)` and `Total You Give ₹110.0` displayed accurately.
- **Suppliers Mode & Privacy-Preserving Contact Picker Check:** Switched to `Suppliers` -> tapped `NEW ENTRY` -> tapped `+ Create New Supplier` -> confirmed `"Pick from Phone Contacts"` rendered cleanly -> tapped `"Pick from Phone Contacts"` -> verified Android system Google Contacts picker (`com.google.android.contacts/...ContactPickerActivity`) launched immediately **with zero `READ_CONTACTS` runtime permission grant**. Closed picker and canceled dialogs without creating or modifying any record, then returned to `Customers` mode.
- **Logcat Check:** Inspected recent 500 logcat lines -> `Zero crashes or migration errors`.
- **Checks Intentionally Not Performed on Live Phone:** Creating/saving a real contact/supplier or performing search writes on live user business data (already fully tested on API 35 emulator `emulator-5556` and API 36 instrumentation suite).

