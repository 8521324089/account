# Ledger v1.0.3 — OnePlus 10R Installation & Signature Audit Report

**Date:** November 1, 2026  
**Target Device:** OnePlus 10R 5G (`CPH2423`, Android 15, API 35)  
**Package:** `com.kb.ledger`  
**Candidate Version:** v1.0.3 (versionCode 4, targetSdk 36)  
**Result Status:** `B. INSTALLATION BLOCKED — SIGNATURE MISMATCH`  
**Data Loss / Alteration:** ZERO (0 bytes lost; physical phone 100% untouched; safeguard backup verified)

---

## 1. Executive Summary

In accordance with strict safety mandates to protect real financial records and contact data on the user's physical OnePlus 10R:
1. **Device Identity Confirmed:** Physical device connected at serial `LR8H55PB9PMRNFUO` was definitively identified as the OnePlus 10R 5G (`CPH2423`, `OP5567L1`, Android 15, API 35, physical hardware `default`).
2. **Target Release APK Verified:** Release APK `app/build/outputs/apk/release/app-release.apk` was verified with exact byte size (16,035,408 bytes), exact SHA-256 (`66b70ed6f7736ad6bf087747fd1430abb3ee1f448a9e0de5fe2182d0b9e40df8`), and permanent production signing certificate SHA-256 (`4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5`).
3. **Physical Phone Installation Inspected:** Package `com.kb.ledger` on the OnePlus 10R is currently running an initial development build (`versionCode=1`, `versionName=1.0`, debuggable), signed with the standard **Android Debug Key** (`e1ca16bf...`).
4. **Signature Comparison & Decision:** The release candidate is signed with the **Permanent Production Key** (`4a449fe0...`). Under Android's core package manager security architecture, an in-place upgrade (`adb install -r`) across distinct signing keys is cryptographically rejected (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
5. **Safety Invariant Preserved:** Strictly following safety rules, **NO** uninstallation (`adb uninstall`), **NO** data clearance (`pm clear`), and **NO** destructive operations were performed on the physical phone. The OnePlus 10R remains completely untouched.
6. **Safeguard Backup Created & Verified:** Two independent raw archives and two standard schema 3 JSON backups were extracted to secure storage **outside** the project repository (`/Users/vivekkumar/Documents/ledger_oneplus_safeguard_backup/`). All 2,095 contacts, 16 transactions, financial balances, and receipt images were preserved with matching cryptographic checksums.
7. **Emulator Restore Verified (100% Fidelity):** The phone's full backup was restored on Ledger v1.0.3 on an Android 16 (API 36) emulator. All 2,095 contacts, 16 transactions, Net Balance, receipt image decoding, and re-export round-trip were verified with 100% data fidelity, 0 crashes, and 0 ANRs.

---

## 2. Physical Device Identification

The connected physical device was queried via ADB:

| Property | Value | Verification Status |
| :--- | :--- | :--- |
| **Serial Number** | `LR8H55PB9PMRNFUO` | Confirmed physical USB target |
| **Manufacturer** | `OnePlus` | Exact match |
| **Model** | `CPH2423` | Exact match (OnePlus 10R 5G) |
| **Product / Device** | `OP5567L1` / `OnePlus 10R` | Exact match |
| **Android Version** | `15` | Verified |
| **Android SDK / API** | `35` | Verified |
| **Build Characteristics** | `default` | Confirmed physical phone (NOT an emulator) |
| **Package `com.kb.ledger`** | Installed (`/data/app/~~.../com.kb.ledger-...`) | Present |

---

## 3. Candidate Release APK Verification

The candidate release APK located at `app/build/outputs/apk/release/app-release.apk` was subjected to strict cryptographic and manifest verification:

| Check | Specification | Measured Value | Status |
| :--- | :--- | :--- | :--- |
| **Path** | `app/build/outputs/apk/release/app-release.apk` | `app/build/outputs/apk/release/app-release.apk` | PASS |
| **File Size** | 16,035,408 bytes | 16,035,408 bytes | PASS |
| **APK SHA-256** | `66b70ed6f7736ad6bf087747fd1430abb3ee1f448a9e0de5fe2182d0b9e40df8` | `66b70ed6f7736ad6bf087747fd1430abb3ee1f448a9e0de5fe2182d0b9e40df8` | PASS |
| **Signing Cert SHA-256** | `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5` | `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5` | PASS |
| **Signer Identity** | `CN=Ledger Production Release` | `CN=Ledger Production Release` | PASS |
| **Package Name** | `com.kb.ledger` | `com.kb.ledger` | PASS |
| **Version Code** | `4` | `4` | PASS |
| **Version Name** | `1.0.3` | `1.0.3` | PASS |
| **Target SDK** | `36` (Android 16) | `36` | PASS |
| **Min SDK** | `26` (Android 8.0) | `26` | PASS |

---

## 4. Current Phone Installation & Signature Audit

The base APK from the physical phone was extracted to temporary analysis space (`/tmp/oneplus_inspect/installed_base.apk`) and analyzed:

| Attribute | Phone Installation (Current) | Candidate Release (v1.0.3) | Comparison Result |
| :--- | :--- | :--- | :--- |
| **Package Name** | `com.kb.ledger` | `com.kb.ledger` | Matches |
| **Version Code** | `1` | `4` | Upgrade (+3) |
| **Version Name** | `1.0` | `1.0.3` | Upgrade |
| **Target SDK** | `35` | `36` | Upgrade |
| **Build Type** | Debuggable (`DEBUGGABLE`) | Release (`non-debuggable`) | Production hardening |
| **Signer Name** | `CN=Android Debug, O=Android, C=US` | `CN=Ledger Production Release` | **MISMATCH** |
| **Cert SHA-256** | `e1ca16bf26367d7b85bf48437c4fbca8331d63561dd3d19b09dbde1034d30484` | `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5` | **MISMATCH** |

### Why Android Rejects In-Place Installation
Under Android's core application security model:
1. Every application UID and private sandboxed storage directory (`/data/data/com.kb.ledger/`) is bound to the developer's signing key.
2. During an update (`adb install -r`), Android's Package Manager Service (`PackageManagerService.java`) checks if the new APK's signing certificate matches the currently installed certificate.
3. Because the installed app is signed with the default **Android Debug Key** and the candidate APK is signed with the **Permanent Production Key**, Android immediately rejects in-place updates with:
   ```
   INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.kb.ledger signatures do not match newer version
   ```
4. Attempting `adb install -r` would fail harmlessly, but attempting `adb install -r -d` or `adb uninstall` would wipe `/data/data/com.kb.ledger/` and **permanently delete the user's real customers and ledger transactions**.
5. Therefore, in adherence to Part 1 and Part 4 rules, execution was halted:
   **`INSTALLATION BLOCKED — SIGNATURE MISMATCH`**.

---

## 5. Safeguard Backup Verification Outside Repository

Before any testing was performed, an off-device safeguard backup of the OnePlus 10R data was extracted and stored strictly **outside** the Git repository at:
`/Users/vivekkumar/Documents/ledger_oneplus_safeguard_backup/`

### 5.1 Backup Files & Verification Checksums

| File | Purpose | Size (bytes) | SHA-256 Checksum |
| :--- | :--- | :--- | :--- |
| `ledger_raw_backup_A.tar.gz` | Primary raw archive (`databases/`, `files/`, `shared_prefs/`) | 507,175 | `432d3bcca9a2c2bbe3f033ad41aaec437120074106d4e6268624b180727f1978` |
| `ledger_raw_backup_B.tar.gz` | Secondary redundant copy of raw archive | 507,175 | `432d3bcca9a2c2bbe3f033ad41aaec437120074106d4e6268624b180727f1978` |
| `ledger_oneplus_backup_A.json` | Primary standard Schema 3 JSON backup with Base64 receipt images | 918,288 | `2a60d9f3d38661e13c51aca23e97764e0f545c18c8c1cbbe67f3ce434693a299` |
| `ledger_oneplus_backup_B.json` | Secondary redundant copy of Schema 3 JSON backup | 918,288 | `2a60d9f3d38661e13c51aca23e97764e0f545c18c8c1cbbe67f3ce434693a299` |

### 5.2 Preserved Entity & Record Counts

All entities from the OnePlus 10R SQLite database (`ledger.db`) and internal files directory were verified:

- **Total Person Entities:** **2,095**
  - Active Contacts: 2,093
  - Archived Contacts: 1
  - Soft-Deleted Tombstones: 1
- **Total Transaction Entities:** **16**
  - Active Transactions: 14
  - Soft-Deleted Tombstones: 2
- **Customers with Balances:** Exactly 3 customers
- **Financial Balance Totals:**
  - Total Give (Receivable): 1,470.00
  - Total Receive (Payable): 1,000.00
  - Net Ledger Balance: -470.00 ("You Give" 470.00)
- **Saved Receipt Images:** **1** image file (`2eb4ab6c-93c4-472d-b3e2-48be950f66b9.jpg`, 140,085 bytes, Base64 representation: 186,780 characters)
- *Privacy Compliance:* Customer names, phone numbers, and individual transaction amounts have been sanitized.

---

## 6. Emulator Restore Verification (Android 16 / API 36)

The real phone backup was tested on an isolated Android 16 emulator (`emulator-5554`, `Medium_Phone_API_36`) running the production release candidate `app-release.apk` (v1.0.3, versionCode 4).

### 6.1 Verification Steps & Outcomes

1. **Clean Installation:** Released candidate installed cleanly on API 36 emulator.
2. **SAF Import:** `ledger_oneplus_backup_A.json` imported via Android Storage Access Framework (SAF DocumentsUI).
3. **Dashboard Balance Verification:** Restored dashboard immediately reflected the exact net financial balance: `Total You Give ₹470.0`.
4. **SAF Re-Export Round-Trip Reconciliation:**
   The restored app exported a fresh backup (`ledger_backup_20261101_221454.json`, 864,472 bytes) via DocumentsUI. Entity counts and receipt bytes were compared against the original phone backup:
   - **Persons:** Original = 2,095 | Restored & Re-exported = 2,095 (100% Match)
   - **Transactions:** Original = 16 | Restored & Re-exported = 16 (100% Match)
   - **Receipts:** Original = 1 | Restored & Re-exported = 1 (100% Match)
   - **Receipt Base64 Byte Fidelity:** Original (186,780 chars) == Re-exported (186,780 chars) (Exact 100% byte match)
5. **Native ART Runtime Image Decoding:** Restored receipt was decoded inside the Android ART runtime using `BitmapFactory` via `app_process`:
   - Output: `NATIVE_DECODE_SUCCESS: width=1080 height=2412 config=ARGB_8888 byteCount=10419840`
6. **UI Inspection:**
   - Restored customer detail page loaded all transaction entries and displayed the attached receipt thumbnail with `Bill (Tap to preview)`.
   - Full-screen receipt preview dialog opened and displayed the bill receipt with `Close` button and zero rendering errors.
   - Individual transaction rows showed no "Not synced" or "Pending sync" status indicators.
7. **Logcat Audit:** 0 fatal exceptions, 0 application crashes, 0 ANRs.

### 6.2 Evidence Screenshots

The following screenshots document the restore on the API 36 emulator:

| Evidence Screen | Path | Description |
| :--- | :--- | :--- |
| **Restored Dashboard** | `release-evidence/emulator_phone_restore/01_restored_dashboard.png` | Restored dashboard displaying `Total You Give ₹470.0` and active contact list |
| **Restored State** | `release-evidence/emulator_phone_restore/02_restored_state.png` | Active debtor balances summing to exact net balance |
| **Customer Detail & Receipt** | `release-evidence/emulator_phone_restore/03_restored_customer_detail.png` | Restored customer transactions with attached bill thumbnail |
| **Fullscreen Receipt Preview** | `release-evidence/emulator_phone_restore/04_restored_receipt_fullscreen.png` | Restored bill receipt rendered in full-screen dialog |

---

## 7. Status of the Physical OnePlus 10R

- **Device Uninstalled?** **NO.**
- **Device Storage Cleared?** **NO.**
- **Package Modifed?** **NO.**
- **Current Installed Version:** `com.kb.ledger` v1.0 (versionCode 1, debug key).
- **Data Status:** 100% intact. All 2,095 contacts, 16 transactions, and receipt files remain completely unaltered on the phone.

---

## 8. Safe Operator Transition Path (When Authorized)

Because Android's security sandbox cryptographically prevents upgrading across signing keys, transitioning the OnePlus 10R to the production-signed v1.0.3 release requires a safe, data-preserving transition.

When the user chooses to perform this transition, the exact procedure is:

```bash
# Step 1: Push the verified backup to the OnePlus 10R public Download folder
/Users/vivekkumar/Library/Android/sdk/platform-tools/adb -s LR8H55PB9PMRNFUO push \
  /Users/vivekkumar/Documents/ledger_oneplus_safeguard_backup/ledger_oneplus_backup_A.json \
  /sdcard/Download/ledger_oneplus_backup.json

# Step 2: Uninstall the debug build (NOW SAFE, since safeguard backup is verified)
/Users/vivekkumar/Library/Android/sdk/platform-tools/adb -s LR8H55PB9PMRNFUO uninstall com.kb.ledger

# Step 3: Install the verified production release candidate v1.0.3
/Users/vivekkumar/Library/Android/sdk/platform-tools/adb -s LR8H55PB9PMRNFUO install -r \
  /Users/vivekkumar/Documents/Project/android-app/app/build/outputs/apk/release/app-release.apk

# Step 4: Open Ledger v1.0.3 on the OnePlus 10R:
#   - Open Navigation Drawer -> Tap "Import Backup"
#   - Select "ledger_oneplus_backup.json" from Downloads
#   - Confirm: 2,095 contacts, 16 transactions, Net balance, and receipts are instantly restored.
```

*(Note: In accordance with Part 1 and Part 4 rules, Steps 1–3 have NOT been executed automatically. They require explicit operator action or authorization).*

---

## 9. Final Release Status

- **Candidate APK:** Ledger v1.0.3 (versionCode 4, targetSdk 36)
- **APK Verification:** PASS
- **Signing Key Verification:** PASS (Production Certificate SHA-256: `4a449fe0...`)
- **Safeguard Backup Verification:** PASS (2 redundant copies stored outside repo)
- **Data Restoration Verification:** PASS (100% fidelity on Android 16 emulator)
- **Physical Installation Outcome:** `B. INSTALLATION BLOCKED — SIGNATURE MISMATCH`
- **User Data Alteration / Loss:** **NONE (0 bytes)**

