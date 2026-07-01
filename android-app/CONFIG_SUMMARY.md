# LedgerStandalone Configuration Summary

This document summarizes the standalone Android configuration implemented so far.

## 1. Local Database (Room)
- **Database Class:** `AppDatabase`
- **Entities:**
    - `Person`: Stores `name`, `phone`, and `contactId`.
    - `Transaction`: Stores `amount`, `type` (GIVE/RECEIVE), `note`, and `timestamp`.
- **DAOs:** `PersonDao` and `TransactionDao` for CRUD operations.

## 2. Contact Integration
- **Class:** `ContactManager`
- **Capabilities:**
    - `fetchPhoneContacts()`: Reads from Android Contacts Provider.
    - `saveToPhoneContacts()`: Writes a new contact to the phone's address book.

## 3. Data Portability (Export/Import)
- **Class:** `BackupManager`
- **Format:** JSON (using GSON).
- **Workflow:**
    - **Export:** Serializes all DB records to JSON and saves via System File Picker.
    - **Import:** Reads JSON, parses it, and populates the local Room DB.

## 4. UI Structure (Jetpack Compose)
- **`LedgerAppContent`**: Main scaffold with:
    - Top bar (Export/Import buttons).
    - Floating Action Button (+) for new customers.
    - List of customers with name and phone.
- **`AddPersonDialog`**: Popup for entering customer details with a "Save to Contacts" checkbox.

## 5. Project Dependencies
- **Core:** Kotlin 2.0, Compose 1.7+, Material3.
- **Storage:** Room 2.6.1 + KSP.
- **JSON:** Gson 2.10.1.

## How to test:
1. Open the `android-app` module in Android Studio.
2. Ensure you have a device or emulator connected (Pixel 7 recommended as requested).
3. Run the application.
4. Use the "Sync Phone Contacts" button to see the list populate.
5. Create a backup, delete the app, reinstall, and use "Import" to see the data restore.
