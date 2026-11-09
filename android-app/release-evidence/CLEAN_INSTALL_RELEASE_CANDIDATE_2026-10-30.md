# Ledger Clean-Install Release Candidate Evidence (v1.0.1 / versionCode 2)

**Audit & Remediation Date:** 2026-10-30  
**Project:** `/Users/vivekkumar/Documents/Project/android-app/`  
**Package:** `com.kb.ledger`  
**Target Release:** Version `1.0.1` (`versionCode = 2`)  
**Evaluation Scope:** Clean-install release readiness following removal of the Google Sign-In / placeholder Web Client ID blocker.

---

## 1. Executive Summary & Remediation Overview

In the preceding release audit, the release candidate APK compiled and launched successfully on an isolated emulator, but tapping the "Sign In with Google" drawer action triggered a crash because `WEB_CLIENT_ID` in `GoogleAuthManager.kt` was set to placeholder `"REPLACE_WITH_YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"`. Furthermore, the drawer displayed misleading UI indicating that Google Drive cloud sync was operational.

### Defect Remediation Actions Taken:
1. **Disabled User-Facing Sign-In Entry Point:**
   - Completely removed the "Sign In with Google" action from the navigation drawer when the user is not authenticated.
   - Retained the "Sign Out" action conditionally only if an existing legacy account email is present in preferences, allowing graceful clearance of cached preferences without affecting Room ledger records.
2. **Defensive Guarding in Auth & Sync Engines:**
   - Added `isConfigured: Boolean` property to [GoogleAuthManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/sync/GoogleAuthManager.kt). If `WEB_CLIENT_ID` starts with `"REPLACE_WITH"`, `isConfigured` evaluates to `false`.
   - `attemptSignIn()` fails immediately with `AppResult.Error(Exception("Google Sign-In is not configured in this release candidate..."))` without invoking Android Credential Manager.
   - `signOut()` returns `AppResult.Success(Unit)` without throwing an unhandled exception when unconfigured, clearing in-memory tokens and preferences.
   - [HomeViewModel.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/viewmodel/HomeViewModel.kt), [DriveSyncManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/sync/DriveSyncManager.kt), and [DriveSyncWorker.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/sync/DriveSyncWorker.kt) guard against triggering periodic sync or background worker sync when `!authManager.isConfigured`.
   - `HomeViewModel.triggerSync()` reconciles the local partitioned backup with Room rather than attempting remote network calls.
3. **Transparent Offline Messaging in UI:**
   - Renamed drawer header status from "Google Drive: Connected" to `"Offline Mode (Local Storage)"` with subtitle `"Manual SAF Backup & Restore"`.
   - Renamed drawer backup card to `"Local Storage & Backup"`. Replaced misleading Drive promises with: `"Offline version: Cloud sync is unavailable. Use 'Export Backup' below to save an off-device copy."`
   - Refresh button safely refreshes local backup status without throwing exceptions.
4. **Data Safety Guarantees:**
   - All existing Room databases, DAO interfaces, partitioned local backups, and manual SAF export/import capabilities remain intact and 100% offline-functional.
   - No customer data or preferences are cleared when navigating drawer or account-related screens.

---

## 2. Source Code Changes

| File | Change Description |
| :--- | :--- |
| [GoogleAuthManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/sync/GoogleAuthManager.kt) | Added `isConfigured: Boolean`. Guarded `attemptSignIn()` and `signOut()` to fail/succeed safely offline without launching Credential Manager with a placeholder client ID. |
| [HomeViewModel.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/viewmodel/HomeViewModel.kt) | Guarded `init` periodic sync schedule on `authManager.isConfigured`. Updated `triggerSync()` to safely reconcile local backup when offline. Guarded `signIn()` against unhandled exceptions. |
| [DriveSyncManager.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/sync/DriveSyncManager.kt) | Guarded `triggerSync()` on `authManager.isConfigured`, updating `SyncState.Offline` and returning `AppResult.Error` immediately when unconfigured. |
| [DriveSyncWorker.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/sync/DriveSyncWorker.kt) | Guarded `doWork()` to update `SyncState.Offline` and return `Result.success()` early when unconfigured. |
| [MainActivity.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/main/java/com/kb/ledger/MainActivity.kt) | Removed "Sign In with Google" drawer action. Renamed drawer header to "Offline Mode (Local Storage)" and storage card to "Local Storage & Backup". Added clear notice that cloud sync is disabled. Removed unused collector variables. |
| [CleanInstallReleaseSafetyTest.kt](file:///Users/vivekkumar/Documents/Project/android-app/app/src/test/java/com/kb/ledger/release/CleanInstallReleaseSafetyTest.kt) | Added 6 dedicated unit tests verifying placeholder client ID rejection, safe sign-out, offline ledger operations, SAF independence, and offline sync state. |

---

## 3. Automated Test Suite & Build Verification

All verification commands executed using the certified environment (Eclipse Temurin JDK 17, Android SDK Build-Tools 34.0.0).

```bash
./gradlew testDebugUnitTest --rerun-tasks
python3 scripts/test_clean_dev_logs.py
git diff --check
./gradlew assembleRelease
```

### Verification Command Results:
- `./gradlew testDebugUnitTest --rerun-tasks`: **EXIT CODE 0**  
  - Total test suites: **27**
  - Total unit tests executed: **322**
  - Failures: **0**, Errors: **0**, Skipped: **0** (100% Pass Rate).
- `python3 scripts/test_clean_dev_logs.py`: **EXIT CODE 0**  
  - 6/6 test cases passed.
- `git diff --check`: **EXIT CODE 0**  
  - Zero whitespace errors or conflict markers.
- `./gradlew assembleRelease`: **EXIT CODE 0 (BUILD SUCCESSFUL in 12s)**  
  - 50 tasks executed cleanly. All `lintVital` checks passed without suppression.

---

## 4. Release Candidate APK Identity & Cryptographic Signature

### 1. APK File Artifact Identity
- **Relative Path:** `app/build/outputs/apk/release/app-release.apk`
- **File Size:** `16,019,052 bytes` (15.28 MB)
- **SHA-256 Digest:**  
  `4cc2bceeda409f48db6bf8a8fe5f4a79aa72c7a51dcfa737bd10488e4386739b`

### 2. Package Badging & Version Metadata
- **Package Name:** `com.kb.ledger`
- **Version Code:** `2`
- **Version Name:** `1.0.1`
- **Min SDK Version:** `26` (Android 8.0 Oreo)
- **Target SDK Version:** `35` (Android 15)
- **Compile SDK:** `35`
- **Application Label:** `LedgerApp`

### 3. Cryptographic Signature & Certificate Verification (`apksigner`)
```
Verifies: true
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Number of signers: 1
Signer #1 certificate DN: CN=Ledger Production Release, OU=Ledger Mobile, O=KB Ledger, L=Bangalore, ST=Karnataka, C=IN
Signer #1 certificate SHA-256 digest: 4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5
Signer #1 certificate SHA-1 digest: c09c61dcd0769d808b87fde99ba56f485a58fc12
Signer #1 certificate MD5 digest: 0f95d19475e791f4fd45eb06b60dd7c2
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 4096
Signer #1 public key SHA-256 digest: cd888b4bdba9792915b98d9f3911e3b3c9dd216b1056d0e4c49c2e55c95f43aa
```
- **Permanent Production Key Confirmed:** The SHA-256 certificate digest (`4a449fe003664d2f10f52e44da7f9a2f9fde0fe5ce0759a8296e3b010fd9a7d5`) matches the permanent production key verified in `v1.0.1_release_evidence.md` and configured in `keystore.properties`.
- **No Keystore Regeneration:** The original keystore was preserved and reused without modification.

---

## 5. Isolated Emulator Clean-Install Smoke Test Results

**Testing Target:** Isolated emulator `emulator-5554` (`ro.build.characteristics = emulator`). Physical OnePlus device remained completely disconnected and untouched.

| Test Step | Action Taken | Observed Result | Status |
| :--- | :--- | :--- | :---: |
| **1. Clean Install** | `adb -s emulator-5554 uninstall com.kb.ledger`<br>`adb -s emulator-5554 install app-release.apk` | Uninstalled cleanly; installed new release candidate APK (`Success`). | **PASS** |
| **2. Cold Launch** | `adb shell am start -W -n com.kb.ledger/.MainActivity` | `Status: ok`, `LaunchState: COLD`, Activity resumed. | **PASS** |
| **3. Zero Startup Crash** | `adb logcat -d -s AndroidRuntime:E` | Zero fatal exceptions or runtime crashes reported. | **PASS** |
| **4. Drawer Navigation & UI Audit** | Opened navigation drawer via menu button (`x=75, y=148`). | - Drawer Header: `"Offline Mode (Local Storage)"` / `"Manual SAF Backup & Restore"`.<br>- Drawer Card: `"Local Storage & Backup"` / `"Offline version: Cloud sync is unavailable."`<br>- `"Sign In with Google"` action: **Completely absent**.<br>- Refresh icon tapped: Handled safely, zero exceptions. | **PASS** |
| **5. Synthetic Customer Creation** | Tapped FAB -> "+ Create New Customer" -> Entered name `"Synthetic Pe"` -> Saved. | Customer persisted to local Room database; immediately searchable. | **PASS** |
| **6. Synthetic GIVE Transaction** | Opened customer detail -> Tapped `"YOU GAVE ₹"` -> Entered amount `5003` -> Saved. | Transaction recorded; balance decreased to `"Total you will give: ₹5003.0"`. | **PASS** |
| **7. Synthetic RECEIVE Transaction** | Tapped `"YOU GOT ₹"` -> Entered amount `7000` -> Saved. | Transaction recorded; balance increased from -₹5003 to `"Total you will get: ₹1997.0"`. | **PASS** |
| **8. Net Balance Formula Validation** | Verified formula: $\sum \text{RECEIVE} - \sum \text{GIVE}$. | ₹7,000.00 (RECEIVE) - ₹5,003.00 (GIVE) = +₹1,997.00. Exact match on customer screen (`"Total you will get ₹1997.0"`) and dashboard (`"You Get ₹1997.0"`, `"Received Today ₹7000.0"`). | **PASS** |
| **9. Process Kill & Data Persistence** | `adb shell am force-stop com.kb.ledger`<br>`adb shell am start -n com.kb.ledger/.MainActivity` | App relaunched; immediately displayed `"Active (1)"`, `"Received Today ₹7000.0"`, and customer `"Synthetic Pe"` with balance `"You Get ₹1997.0"`. | **PASS** |
| **10. SAF Export Round-Trip** | Drawer -> `"Export Backup"` -> Selected Downloads folder -> Tapped `"SAVE"`. | Backup successfully written to `/sdcard/Download/ledger_backup_20261030_120617.json` (946 bytes, Schema Version 3, 1 person, 2 transactions). | **PASS** |
| **11. Clean Wipe Simulation** | `adb shell pm clear com.kb.ledger`<br>Relaunched app. | Verified app was completely wiped to clean state: `"Active (0)"`, `"Received Today ₹0.0"`. | **PASS** |
| **12. SAF Restore Round-Trip** | Drawer -> `"Import Backup"` -> Selected `ledger_backup_20261030_120617.json`. | Instantaneous restoration: Dashboard immediately reflected `"Active (1)"`, `"Received Today ₹7000.0"`, and `"Synthetic Pe: You Get ₹1997.0"`. | **PASS** |

---

## 6. Cloud Synchronization Status Confirmation

- **Drive Sync Inactive:** Cloud synchronization is intentionally and completely disabled in this release candidate.
- **No Crash Vector:** Because the UI entry points have been removed and manager/worker classes fail-fast when `!isConfigured`, no user action can trigger the unhandled placeholder configuration exception.
- **Clear User Communication:** All user-facing references to operational Google Drive cloud sync have been replaced with honest offline ledger notices explaining that manual SAF export/import is the supported backup mechanism.

---

## 7. Permanent Signing-Key Recovery Procedure

> [!WARNING]
> **Operator Action Required:** The permanent production signing key (`ledger-release.jks`) currently resides solely on the development workstation. Do NOT commit the keystore or passwords to Git. The checklist item for off-device recovery remains **BLOCKED / NOT TESTED** until the operator creates an independently verified, encrypted off-device backup.

### Safe Manual Procedure for the Operator:

To securely archive the production keystore and its configuration without exposing plaintext credentials to logs, history, or untrusted media:

1. **Create an Encrypted Tarball of the Signing Credentials:**
   On the development machine, run the following command using OpenSSL or GPG:
   ```bash
   tar -cf - ledger-release.jks keystore.properties | gpg --symmetric --cipher-algo AES256 -o ~/Desktop/ledger-prod-signing-recovery-20261030.tar.gpg
   ```
   *(Enter a strong, unique passphrase known only to authorized release managers).*

2. **Verify Archive Decryptability Independently:**
   In an isolated temporary directory, test decrypting the backup to confirm integrity:
   ```bash
   mkdir -p /tmp/keystore-recovery-test
   gpg --decrypt ~/Desktop/ledger-prod-signing-recovery-20261030.tar.gpg | tar -xf - -C /tmp/keystore-recovery-test
   
   # Compare SHA-256 of the recovered keystore against the project keystore:
   shasum -a 256 /tmp/keystore-recovery-test/ledger-release.jks
   shasum -a 256 /Users/vivekkumar/Documents/Project/android-app/ledger-release.jks
   
   # Clean up the test directory immediately:
   rm -rf /tmp/keystore-recovery-test
   ```

3. **Store Off-Device:**
   Copy `ledger-prod-signing-recovery-20261030.tar.gpg` to:
   - An encrypted, offline hardware drive (e.g. air-gapped USB stored in a physical safe).
   - An encrypted corporate secret manager / vault (e.g. 1Password for Teams / HashiCorp Vault).

4. **Operator Confirmation:**
   Once completed, remove the local desktop archive (`rm ~/Desktop/ledger-prod-signing-recovery-20261030.tar.gpg`) and update the checklist to mark off-device recovery verified.

---

## 8. Remaining Operational Blockers

1. **Off-Device Keystore Backup:** Pending operator execution and confirmation of the off-device recovery procedure.
2. **Physical Phone Signature Mismatch:** The physical OnePlus phone (`LR8H55PB9PMRNFUO`) is running a debug-signed build. The Android OS package manager rejects installing this production-signed release candidate over the debug build with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
3. **Physical Device Ledger Migration:** Upgrading the physical phone requires performing an in-app SAF JSON export on the phone, verifying the export file hash off-device, validating image attachments, performing a trial restore on the isolated emulator, and only then reinstalling the app on the phone.

---

## 9. Final Release Decision Breakdown

### A. Limited Clean-Install Beta: **GO WITH CONDITIONS**
- **Verdict:** **APPROVED FOR LIMITED BETA**.
- **Rationale:** The application is 100% offline-stable, all 322 automated tests pass, the Google Sign-In defect has been eliminated, cold launch succeeds, currency math is exact, and manual SAF export/import functions reliably.
- **Condition:** Operator must execute the off-device keystore backup before distributing to external beta testers.

### B. Public Clean-Install Distribution: **GO WITH CONDITIONS**
- **Verdict:** **APPROVED FOR CLEAN-INSTALL DISTRIBUTION WITH CONDITIONS**.
- **Rationale:** The clean-install release candidate APK (`app-release.apk`, SHA-256: `4cc2bceeda409f48db6bf8a8fe5f4a79aa72c7a51dcfa737bd10488e4386739b`) is signed with the permanent production certificate, meets all data-safety invariants, and contains zero misleading cloud sync promises.
- **Conditions:**
  1. Off-device key backup confirmed by operator.
  2. Public store listing must state that cloud sync is currently unavailable and that backup is via local file export/import.

### C. Update Existing Installations Containing Important Data: **NO-GO**
- **Verdict:** **STRICT NO-GO**.
- **Rationale:** Any user currently running an earlier debug-signed build will encounter `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Attempting to uninstall the old build without an independently verified in-app SAF backup will result in permanent, unrecoverable data loss.

### D. Physical Phone Migration: **NO-GO**
- **Verdict:** **STRICT NO-GO**.
- **Rationale:** The physical phone holds live, non-synthetic records under a debug signing key. Physical phone migration is blocked until the operator executes the safe in-app export procedure documented in `real_device_backup_verification_procedure.md` and successfully completes a trial restore on an emulator.
