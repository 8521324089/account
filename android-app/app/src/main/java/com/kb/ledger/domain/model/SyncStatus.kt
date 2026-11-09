package com.kb.ledger.domain.model

/**
 * Sync lifecycle state for a local record.
 *
 * [SYNCED]          Record matches Drive — no action needed.
 * [PENDING_UPLOAD]  Record was created/modified locally, not yet pushed to Drive.
 * [PENDING_DELETE]  Record was deleted locally (tombstone), not yet pushed to Drive.
 * [CONFLICT]        Drive version is newer than local — human-readable conflict state.
 * [ERROR]           Last sync attempt failed after retries.
 */
enum class SyncStatus {
    SYNCED,
    PENDING_UPLOAD,
    PENDING_DELETE,
    CONFLICT,
    ERROR
}
