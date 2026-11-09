#!/usr/bin/env python3
"""
Seed synthetic data directly into the isolated emulator's SQLite database
and receipts directory for SAF UI export/restore validation.
"""
import sqlite3
import subprocess
import os
import hashlib

DB_PATH = "/tmp/synthetic_ledger_database.db"
RECEIPTS_DIR = "/tmp/synthetic_receipts"
os.makedirs(RECEIPTS_DIR, exist_ok=True)

# Remove stale DB if exists
if os.path.exists(DB_PATH):
    os.remove(DB_PATH)

conn = sqlite3.connect(DB_PATH)
cursor = conn.cursor()

# 1. Create Schema Version 5 Tables
cursor.execute("""
CREATE TABLE IF NOT EXISTS `persons` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `name` TEXT NOT NULL,
    `phone` TEXT NOT NULL,
    `contactId` TEXT,
    `cloudId` TEXT,
    `createdAt` INTEGER NOT NULL,
    `updatedAt` INTEGER NOT NULL,
    `updatedBy` TEXT,
    `isDeleted` INTEGER NOT NULL,
    `syncPending` INTEGER NOT NULL,
    `isArchived` INTEGER NOT NULL
);
""")

cursor.execute("""
CREATE TABLE IF NOT EXISTS `transactions` (
    `id` TEXT NOT NULL,
    `personId` INTEGER NOT NULL,
    `amountCents` INTEGER NOT NULL,
    `type` TEXT NOT NULL,
    `note` TEXT,
    `imageUri` TEXT,
    `imageFileId` TEXT,
    `createdAt` INTEGER NOT NULL,
    `updatedAt` INTEGER NOT NULL,
    `updatedBy` TEXT,
    `isDeleted` INTEGER NOT NULL,
    `syncPending` INTEGER NOT NULL,
    PRIMARY KEY(`id`),
    FOREIGN KEY(`personId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
);
""")
cursor.execute("CREATE INDEX IF NOT EXISTS `index_transactions_personId` ON `transactions` (`personId`);")

cursor.execute("""
CREATE TABLE IF NOT EXISTS `sync_operations` (
    `id` TEXT NOT NULL,
    `type` TEXT NOT NULL,
    `entityType` TEXT NOT NULL,
    `entityId` TEXT NOT NULL,
    `retryCount` INTEGER NOT NULL,
    `lastErrorMessage` TEXT,
    `createdAt` INTEGER NOT NULL,
    PRIMARY KEY(`id`)
);
""")

cursor.execute("""
CREATE TABLE IF NOT EXISTS `recurring_rules` (
    `id` TEXT NOT NULL,
    `personId` INTEGER NOT NULL,
    `amountCents` INTEGER NOT NULL,
    `type` TEXT NOT NULL,
    `note` TEXT,
    `frequency` TEXT NOT NULL,
    `startDate` INTEGER NOT NULL,
    `nextExecutionDate` INTEGER NOT NULL,
    `endDate` INTEGER,
    `isPaused` INTEGER NOT NULL,
    `createdAt` INTEGER NOT NULL,
    PRIMARY KEY(`id`),
    FOREIGN KEY(`personId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
);
""")
cursor.execute("CREATE INDEX IF NOT EXISTS `index_recurring_rules_personId` ON `recurring_rules` (`personId`);")

# Room Master Table
cursor.execute("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT);")
cursor.execute("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '56bed596b10e46bacb580a10f46c529b');")

now = 1729860000000

# 2. Insert Synthetic Persons
# Alice (id 1), Bob (id 2, archived), Charlie (id 3, deleted), Ramesh (id 4), Suresh (id 5, sharing phone)
persons = [
    (1, "Alice Sharma", "9876543210", "c-1", "cloud-alice-1", now - 100000, now, None, 0, 1, 0),
    (2, "Bob Patel", "9876543211", "c-2", "cloud-bob-2", now - 100000, now, None, 0, 1, 1),
    (3, "Charlie Verma (Deleted)", "9876543212", "c-3", "cloud-charlie-3", now - 100000, now, None, 1, 1, 0),
    (4, "Ramesh Kumar", "9876543299", "c-4", "cloud-ramesh-4", now - 100000, now, None, 0, 1, 0),
    (5, "Suresh Kumar", "9876543299", "c-5", "cloud-suresh-5", now - 100000, now, None, 0, 1, 0)
]
cursor.executemany("INSERT INTO persons VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);", persons)

# 3. Create Synthetic Receipts & Insert Transactions
jpeg_bytes = bytes([
    0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
    0x01, 0x01, 0x00, 0x60, 0x00, 0x60, 0x00, 0x00, 0xFF, 0xD9
])
png_bytes = bytes([
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
    0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
    0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89, 0x00, 0x00, 0x00,
    0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82
])
webp_bytes = bytes([
    0x52, 0x49, 0x46, 0x46, 0x20, 0x00, 0x00, 0x00,
    0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20
])

with open(os.path.join(RECEIPTS_DIR, "tx-img-jpeg.jpg"), "wb") as f:
    f.write(jpeg_bytes)
with open(os.path.join(RECEIPTS_DIR, "tx-img-png.jpg"), "wb") as f:
    f.write(png_bytes)
with open(os.path.join(RECEIPTS_DIR, "tx-img-webp.jpg"), "wb") as f:
    f.write(webp_bytes)

print(f"JPEG SHA256: {hashlib.sha256(jpeg_bytes).hexdigest()}")
print(f"PNG  SHA256: {hashlib.sha256(png_bytes).hexdigest()}")
print(f"WebP SHA256: {hashlib.sha256(webp_bytes).hexdigest()}")

txs = [
    ("tx-img-jpeg", 1, 2500, "GIVE", "JPEG bill", "/data/user/0/com.kb.ledger/files/receipts/tx-img-jpeg.jpg", None, now - 50000, now, None, 0, 1),
    ("tx-img-png", 2, 15000, "RECEIVE", "PNG bill", "/data/user/0/com.kb.ledger/files/receipts/tx-img-png.jpg", None, now - 50000, now, None, 0, 1),
    ("tx-img-webp", 4, 8000, "GIVE", "WebP bill", "/data/user/0/com.kb.ledger/files/receipts/tx-img-webp.jpg", None, now - 50000, now, None, 0, 1),
    ("tx-alice-act", 1, 10000, "GIVE", "Active tx 1", None, None, now - 50000, now, None, 0, 1),
    ("tx-alice-rec", 1, 4000, "RECEIVE", "Active tx 2", None, None, now - 50000, now, None, 0, 1),
    ("tx-del-1", 1, 1000, "GIVE", "Deleted tx", None, None, now - 50000, now, None, 1, 1)
]
cursor.executemany("INSERT INTO transactions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);", txs)

rules = [
    ("rule-1", 1, 5000, "GIVE", "Monthly rent", "MONTHLY", now, now + 86400000, None, 0, now),
    ("rule-2", 2, 10000, "RECEIVE", "Weekly stipend", "WEEKLY", now, now + 86400000, None, 0, now)
]
cursor.executemany("INSERT INTO recurring_rules VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);", rules)

conn.commit()
conn.close()
print("Synthetic SQLite database created successfully at:", DB_PATH)
