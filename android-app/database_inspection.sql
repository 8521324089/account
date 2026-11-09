-- =============================================================================
-- database_inspection.sql
-- Android Ledger Application (com.kb.ledger)
-- Diagnostic & Forensic Database Inspection Script
-- =============================================================================
--
-- PURPOSE:
--   Comprehensive, strictly READ-ONLY diagnostic script for auditing and inspecting
--   the local Room/SQLite database ('ledger_database') of com.kb.ledger.
--
-- SAFETY GUARANTEES:
--   1. Strictly READ-ONLY: Contains NO INSERT, UPDATE, DELETE, DROP, or ALTER statements.
--   2. Safe to execute against live production copies.
--   3. Anonymized / aggregate: Designed to avoid exposing raw customer names, phone numbers,
--      or private notes in query headers or reports.
--
-- USAGE INSTRUCTIONS:
--   1. Direct command-line inspection using sqlite3:
--        sqlite3 /path/to/ledger_database < database_inspection.sql
--
--   2. In DB Browser for SQLite:
--        Open 'ledger_database', go to the 'Execute SQL' tab, paste this file, and run.
--
--   3. On-device via ADB shell (debug build):
--        adb shell "run-as com.kb.ledger sqlite3 /data/data/com.kb.ledger/databases/ledger_database" < database_inspection.sql
-- =============================================================================

.headers on
.mode box

-- -----------------------------------------------------------------------------
-- 1. DATABASE FILE METADATA & HEALTH CHECK
-- -----------------------------------------------------------------------------
SELECT '=== 1. DATABASE FILE METADATA & HEALTH CHECK ===' AS section;

SELECT 
    (SELECT user_version FROM pragma_user_version()) AS schema_version,
    (SELECT integrity_check FROM pragma_integrity_check()) AS integrity_status,
    (SELECT count(*) FROM pragma_foreign_key_check()) AS foreign_key_violations,
    (SELECT journal_mode FROM pragma_journal_mode()) AS journal_mode,
    (SELECT page_size FROM pragma_page_size()) AS page_size_bytes,
    (SELECT page_count FROM pragma_page_count()) AS total_pages,
    ((SELECT page_count FROM pragma_page_count()) * (SELECT page_size FROM pragma_page_size()) / 1024.0) AS db_size_kb;

-- -----------------------------------------------------------------------------
-- 2. SCHEMA DEFINITIONS (TABLES & INDEXES)
-- -----------------------------------------------------------------------------
SELECT '=== 2. TABLE AND INDEX DEFINITIONS ===' AS section;

SELECT 
    type,
    name,
    tbl_name,
    sql 
FROM sqlite_master 
WHERE name NOT LIKE 'sqlite_%' 
ORDER BY type, name;

-- -----------------------------------------------------------------------------
-- 3. COLUMN DEFINITIONS PER TABLE
-- -----------------------------------------------------------------------------
SELECT '=== 3. COLUMN DEFINITIONS (persons) ===' AS section;
PRAGMA table_info(persons);

SELECT '=== 3. COLUMN DEFINITIONS (transactions) ===' AS section;
PRAGMA table_info(transactions);

SELECT '=== 3. COLUMN DEFINITIONS (sync_operations) ===' AS section;
PRAGMA table_info(sync_operations);

SELECT '=== 3. COLUMN DEFINITIONS (recurring_rules) ===' AS section;
PRAGMA table_info(recurring_rules);

-- -----------------------------------------------------------------------------
-- 4. FOREIGN KEY RELATIONSHIPS
-- -----------------------------------------------------------------------------
SELECT '=== 4. FOREIGN KEY DEFINITIONS (transactions) ===' AS section;
PRAGMA foreign_key_list(transactions);

SELECT '=== 4. FOREIGN KEY DEFINITIONS (recurring_rules) ===' AS section;
PRAGMA foreign_key_list(recurring_rules);

-- -----------------------------------------------------------------------------
-- 5. ROW COUNTS & RECORD STATUS SUMMARY
-- -----------------------------------------------------------------------------
SELECT '=== 5. TABLE ROW COUNTS AND RECORD STATES ===' AS section;

SELECT 'persons: total registered' AS category, count(*) AS count FROM persons
UNION ALL
SELECT 'persons: active (isDeleted = 0 AND isArchived = 0)', count(*) FROM persons WHERE isDeleted = 0 AND isArchived = 0
UNION ALL
SELECT 'persons: archived (isArchived = 1 AND isDeleted = 0)', count(*) FROM persons WHERE isArchived = 1 AND isDeleted = 0
UNION ALL
SELECT 'persons: soft-deleted tombstones (isDeleted = 1)', count(*) FROM persons WHERE isDeleted = 1
UNION ALL
SELECT 'persons: pending cloud sync (syncPending = 1)', count(*) FROM persons WHERE syncPending = 1
UNION ALL
SELECT 'transactions: total entries', count(*) FROM transactions
UNION ALL
SELECT 'transactions: active (isDeleted = 0)', count(*) FROM transactions WHERE isDeleted = 0
UNION ALL
SELECT 'transactions: soft-deleted tombstones (isDeleted = 1)', count(*) FROM transactions WHERE isDeleted = 1
UNION ALL
SELECT 'transactions: pending cloud sync (syncPending = 1)', count(*) FROM transactions WHERE syncPending = 1
UNION ALL
SELECT 'transactions: with local bill image (imageUri IS NOT NULL)', count(*) FROM transactions WHERE imageUri IS NOT NULL AND isDeleted = 0
UNION ALL
SELECT 'transactions: with cloud bill image (imageFileId IS NOT NULL)', count(*) FROM transactions WHERE imageFileId IS NOT NULL AND isDeleted = 0
UNION ALL
SELECT 'recurring_rules: total templates', count(*) FROM recurring_rules
UNION ALL
SELECT 'recurring_rules: active (isPaused = 0)', count(*) FROM recurring_rules WHERE isPaused = 0
UNION ALL
SELECT 'recurring_rules: paused (isPaused = 1)', count(*) FROM recurring_rules WHERE isPaused = 1
UNION ALL
SELECT 'sync_operations: pending queue depth', count(*) FROM sync_operations;

-- -----------------------------------------------------------------------------
-- 6. TRANSACTION TYPE & FINANCIAL VOLUME BREAKDOWN
-- -----------------------------------------------------------------------------
SELECT '=== 6. TRANSACTION TYPES & VOLUME BREAKDOWN ===' AS section;

SELECT 
    type AS transaction_type,
    count(*) AS transaction_count,
    SUM(amountCents) AS total_cents,
    ROUND(SUM(amountCents) / 100.0, 2) AS total_rupees,
    MIN(amountCents) / 100.0 AS min_amount_rupees,
    MAX(amountCents) / 100.0 AS max_amount_rupees,
    ROUND(AVG(amountCents) / 100.0, 2) AS avg_amount_rupees
FROM transactions
WHERE isDeleted = 0
GROUP BY type;

-- -----------------------------------------------------------------------------
-- 7. CUSTOMER TRANSACTION DISTRIBUTION (HISTOGRAM)
-- -----------------------------------------------------------------------------
SELECT '=== 7. CUSTOMER TRANSACTION ACTIVITY DISTRIBUTION ===' AS section;

SELECT 
    CASE 
        WHEN tx_count = 0 THEN '0 transactions (idle/imported contact)'
        WHEN tx_count = 1 THEN '1 transaction (single entry)'
        WHEN tx_count BETWEEN 2 AND 5 THEN '2 to 5 transactions (moderate)'
        WHEN tx_count BETWEEN 6 AND 20 THEN '6 to 20 transactions (frequent)'
        ELSE '> 20 transactions (heavy activity)'
    END AS activity_tier,
    count(*) AS customer_count
FROM (
    SELECT 
        p.id,
        count(t.id) AS tx_count
    FROM persons p
    LEFT JOIN transactions t ON p.id = t.personId AND t.isDeleted = 0
    WHERE p.isDeleted = 0
    GROUP BY p.id
)
GROUP BY activity_tier
ORDER BY min(tx_count);

-- -----------------------------------------------------------------------------
-- 8. ORPHANED RECORD DETECTION
-- -----------------------------------------------------------------------------
SELECT '=== 8. ORPHAN RECORD INTEGRITY CHECK ===' AS section;

SELECT 
    'Orphaned transactions (personId does not exist in persons)' AS test_description,
    count(*) AS violation_count
FROM transactions t
LEFT JOIN persons p ON t.personId = p.id
WHERE p.id IS NULL
UNION ALL
SELECT 
    'Orphaned recurring_rules (personId does not exist in persons)',
    count(*)
FROM recurring_rules r
LEFT JOIN persons p ON r.personId = p.id
WHERE p.id IS NULL;

-- -----------------------------------------------------------------------------
-- 9. DUPLICATE CUSTOMER DETECTION (NAME & PHONE NORMALIZATION)
-- -----------------------------------------------------------------------------
SELECT '=== 9. DUPLICATE CUSTOMER CLUSTERS SUMMARY ===' AS section;

-- Diagnostic aggregate: count how many groups of duplicate contacts exist
SELECT 
    count(*) AS total_duplicate_groups,
    SUM(cluster_size) AS total_affected_records,
    MAX(cluster_size) AS largest_duplicate_cluster
FROM (
    SELECT 
        lower(trim(name)) AS norm_name,
        replace(replace(replace(replace(phone, ' ', ''), '-', ''), '+', ''), '(', '') AS norm_phone,
        count(*) AS cluster_size
    FROM persons
    WHERE isDeleted = 0
    GROUP BY 1, 2
    HAVING count(*) > 1
);

-- -----------------------------------------------------------------------------
-- 10. SYNC QUEUE DIAGNOSTICS
-- -----------------------------------------------------------------------------
SELECT '=== 10. PENDING SYNC QUEUE BREAKDOWN ===' AS section;

SELECT 
    entityType,
    type AS operation_type,
    retryCount,
    count(*) AS queued_operations,
    MIN(createdAt) AS oldest_operation_timestamp,
    MAX(createdAt) AS newest_operation_timestamp
FROM sync_operations
GROUP BY entityType, type, retryCount;

-- -----------------------------------------------------------------------------
-- 11. FINANCIAL CONSISTENCY: OUTSTANDING TOTALS
-- -----------------------------------------------------------------------------
SELECT '=== 11. FINANCIAL CONSISTENCY (OUTSTANDING TOTALS) ===' AS section;

SELECT 
    ROUND(COALESCE(SUM(CASE WHEN net_balance > 0 THEN net_balance ELSE 0 END), 0) / 100.0, 2) AS total_you_get_rupees,
    ROUND(COALESCE(SUM(CASE WHEN net_balance < 0 THEN -net_balance ELSE 0 END), 0) / 100.0, 2) AS total_you_give_rupees,
    count(CASE WHEN net_balance > 0 THEN 1 END) AS customers_who_owe_you,
    count(CASE WHEN net_balance < 0 THEN 1 END) AS customers_you_owe,
    count(CASE WHEN net_balance = 0 AND tx_count > 0 THEN 1 END) AS settled_customers_with_history
FROM (
    SELECT 
        p.id,
        count(t.id) AS tx_count,
        COALESCE(SUM(CASE WHEN t.type = 'RECEIVE' THEN t.amountCents ELSE -t.amountCents END), 0) AS net_balance
    FROM persons p
    LEFT JOIN transactions t ON p.id = t.personId AND t.isDeleted = 0
    WHERE p.isDeleted = 0
    GROUP BY p.id
);
