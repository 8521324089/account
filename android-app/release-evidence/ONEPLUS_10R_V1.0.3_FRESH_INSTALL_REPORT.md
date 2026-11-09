# Ledger v1.0.3 — Authorized Fresh Production Installation Report (OnePlus 10R)

**Date:** November 1, 2026  
**Physical Target Device:** OnePlus 10R 5G (`CPH2423`, Serial: `LR8H55PB9PMRNFUO`, Android 15, API 35)  
**Application Package:** `com.kb.ledger`  
**Installed Candidate Version:** v1.0.3 (versionCode 4, targetSdk 36)  
**Installation Type:** Authorized Fresh Clean Production Installation  
**Final Verdict:** **`SUCCESSFUL — READY FOR ORIGINAL BUSINESS DATA ENTRY`**

---

## 1. Executive Summary

Pursuant to explicit operator authorization:
1. The old debug-signed development application (`versionCode=1`, `versionName="1.0"`, signed with Android Debug Key `e1ca16bf...`) was uninstalled from the physical OnePlus 10R.
2. The exact, verified production release APK (`app/build/outputs/apk/release/app-release.apk`) signed with the permanent production release key (`4a449fe0...`) was installed fresh.
3. The newly installed application cold-launched in 550ms, loading a completely pristine, empty ledger database (`Active (0)`, `Archived (0)`, Net Balance `₹0.0`).
4. Zero startup crashes, zero ANRs, and zero lingering test customer or transaction records were verified.
5. Storage Access Framework (SAF) DocumentsUI backup export and navigation drawer storage controls were physically verified on the device.
6. The application is confirmed clean, verified, hardened, and ready for the operator to begin entering original business records.

---

## 2. Physical Device Identity Verification

The connected device was verified via explicit ADB commands targeting serial `LR8H55PB9PMRNFUO`:

| Device Property | Value | Verification Status |
| :--- | :--- | :--- |
| **Target Serial** | `LR8H55PB9PMRNFUO` | Physical USB Target Confirmed |
| **Manufacturer** | `OnePlus` | Matches |
| **Model** | `CPH2423` (OnePlus 10R 5G) | Matches |
| **Device Product** | `OP5567L1` | Matches |
| **Android OS Version** | `15` | Verified |
| **Android API Level** | `35` | Verified |
| **Build Characteristics** | `default` | Confirmed Physical Hardware (NOT an emulator) |

---

## 3. Release APK Verification Prior to Installation

The candidate APK was strictly verified prior to executing any uninstall command:

| Verification Check | Required Specification | Measured Value | Result |
| :--- | :--- | :--- | :--- |
| **APK File Path** | `app/build/outputs/apk/release/app-release.apk` | `app/build/outputs/apk/release/app-release.apk` | PASS |
| **File Size** | 16,035,408 bytes | 16,035,408 bytes | PASS |
| **APK SHA-256 Digest** | `66b70ed6f7736ad6bf087747fd1430abb3ee1f448a9e0de5fe2182d0b9e40df8` | `66b70ed6f7736ad6bf087747fd1430abb3ee1f448a9e0de5fe2182d0b9e40df8` | **PASS (Exact)** |
| **Signer #1 SHA-256 Digest** | `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5` | `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5` | **PASS (Exact)** |
| **Signer Identity DN** | `CN=Ledger Production Release` | `CN=Ledger Production Release` | PASS |
| **Package Name** | `com.kb.ledger` | `com.kb.ledger` | PASS |
| **Version Code** | `4` | `4` | PASS |
| **Version Name** | `1.0.3` | `1.0.3` | PASS |
| **Target SDK** | `36` (Android 16) | `36` | PASS |
| **Minimum SDK** | `26` (Android 8.0) | `26` | PASS |

---

## 4. Package Removal & Fresh Installation Execution

1. **Pre-Removal State:**
   - Package: `com.kb.ledger`
   - Version: `versionCode=1`, `versionName="1.0"`
   - Signing Key: `CN=Android Debug, O=Android, C=US` (`e1ca16bf26367d7b85bf48437c4fbca8331d63561dd3d19b09dbde1034d30484`)
2. **Authorized Removal Command:**
   ```bash
   adb -s LR8H55PB9PMRNFUO uninstall com.kb.ledger
   ```
   - **Result:** `Success` (Confirmed package removed from `pm list packages`).
3. **Fresh Production Installation Command:**
   ```bash
   adb -s LR8H55PB9PMRNFUO install /Users/vivekkumar/Documents/Project/android-app/app/build/outputs/apk/release/app-release.apk
   ```
   - **OS Verification:** OxygenOS / ColorOS package security scanner verified APK with `No risks found` and prompted confirmation.
   - **Result:** `Success` (Streamed Install completed successfully).

---

## 5. Post-Installation Device Verification

The freshly installed package on the OnePlus 10R was inspected via `dumpsys package com.kb.ledger`:

| Property | Value | Verification Status |
| :--- | :--- | :--- |
| **Installed Package** | `com.kb.ledger` | Confirmed |
| **Installed Version Code** | `4` | Confirmed |
| **Installed Version Name** | `1.0.3` | Confirmed |
| **Target SDK** | `36` (Android 16) | Confirmed |
| **Min SDK** | `26` (Android 8.0) | Confirmed |
| **Package Flags** | `[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]` | Confirmed (`DEBUGGABLE` removed) |
| **Signer Certificate SHA-256** | `4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5` | Confirmed Production Key |
| **Signer Subject DN** | `CN=Ledger Production Release, OU=Ledger Mobile, O=KB Ledger, L=Bangalore, ST=Karnataka, C=IN` | Confirmed Production Signer |
| **Content Providers** | `com.kb.ledger.fileprovider` (`androidx.core.content.FileProvider`) | Confirmed Registered |

---

## 6. Functional & Runtime UI Audit on Physical Phone

### 6.1 Application Launch & Crash Inspection
- **Launch Command:** `am start -W -n com.kb.ledger/.MainActivity`
- **Launch Result:** `Status: ok`, `LaunchState: COLD`, `TotalTime: 550ms`
- **Logcat Audit:** `logcat -d` audited across process execution:
  - Fatal Exceptions in `com.kb.ledger`: **0**
  - Application Not Responding (ANR) events: **0**

### 6.2 Clean Initial Database Verification
- UI elements verified from physical screen dump:
  - `Total You Give`: `₹0.0`
  - `Received Today`: `₹0.0`
  - `Active (0)` contacts
  - `Archived (0)` contacts
- **Lingering Test Data Check:** Verified that NO previous test contacts, transactions, or synthetic debts exist on the phone. The database is in a completely pristine, empty state.

### 6.3 Local Storage & Backup Screen Verification
- Opened navigation drawer:
  - App Header: `LedgerApp`
  - Mode Subtitle: `Offline Mode (Local Storage)` / `Manual SAF Backup & Restore`
  - Status Card: `Local backup: Current (0 txs)`
  - Informational Notice: `"Offline version: Cloud sync is unavailable. Use 'Export Backup' below to save an off-device copy."`
  - Actions: `Sync Phone Contacts`, `Export Backup`, `Import Backup`, `Analytics & Reports`, `Recurring Transactions`, `Appearance`, `App Lock`.
- Tapped `Export Backup`:
  - Storage Access Framework (SAF) DocumentsUI opened cleanly on the physical phone targeting `Download/` folder with default filename `ledger_backup_YYYYMMDD_HHMMSS.json` and `SAVE` button.

### 6.4 Receipt & Transaction Capabilities Architecture
- The application incorporates the verified `ReceiptChooserDialog` ("Take Photo" via `ActivityResultContracts.TakePicture()` and "Choose from Gallery" via `ActivityResultContracts.PickVisualMedia()`) with zero manifest `CAMERA` permissions.
- Individual transaction rows have been verified in the compiled binary to have per-transaction "Pending sync" / "Not synced" labels removed, ensuring clean presentation for new entries.

---

## 7. Physical Evidence Screenshots

The following screenshots were pulled directly from the OnePlus 10R:

| Evidence Screen | Path | Description |
| :--- | :--- | :--- |
| **Clean Dashboard** | `release-evidence/oneplus_fresh_install/01_clean_dashboard.png` | Pristine dashboard showing `₹0.0`, `Active (0)`, and `New Entry` button |
| **Drawer & Storage Controls** | `release-evidence/oneplus_fresh_install/02_drawer_storage_backup.png` | Navigation drawer displaying offline mode notice and SAF backup actions |
| **Select Customer Dialog** | `release-evidence/oneplus_fresh_install/03_select_customer_dialog.png` | New customer entry dialog opened from dashboard |
| **SAF DocumentsUI Export** | `release-evidence/oneplus_fresh_install/04_saf_export_documentsui.png` | Android Storage Access Framework picker launching for off-device backup |

---

## 8. Guidance for Original Business Data Entry & Safety

The application is now prepared for original business data entry. 

### Critical Data Safety Directives:
1. **Local-First Architecture:** This production version operates in 100% offline, local-first mode. Google Drive cloud synchronization is not currently configured or connected. Data resides solely on your OnePlus 10R hardware until explicitly exported.
2. **First Backup Recommendation:** Immediately after entering your initial batch of original customers and transactions, open the navigation drawer and tap **"Export Backup"**. Save the JSON file to your device `Download/` folder.
3. **Independent Off-Device Storage:** Copy the exported backup JSON file off the phone to at least one independent, secure storage location (e.g. computer, external drive, or personal secure storage).
4. **Routine Backup Cadence:** Re-export a backup periodically after significant business transaction entries or balance reconciliations.

---

## 9. Final Verdict

**Ledger v1.0.3 successfully installed as a fresh production application on OnePlus 10R.**

- Candidate Version: v1.0.3 (versionCode 4, targetSdk 36)
- Physical Device: OnePlus 10R 5G (CPH2423)
- Signing Certificate: Permanent Production Key (`4a449fe0...`)
- Installation Status: Fresh Clean Install Completed
- User Data Safety: Clean empty database ready for operator entry

