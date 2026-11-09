package com.kb.ledger.domain.model

/**
 * Domain-layer representation of a customer.
 * Decoupled from both Room entities and Drive JSON.
 *
 * [cloudId]      Stable UUID used in Google Drive JSON. Null until first sync.
 * [localId]      Room auto-generated Long. Used for local DB queries only.
 * [syncStatus]   Current sync state of this record.
 * [isDeleted]    Soft-delete tombstone — record is excluded from UI but kept for sync.
 * [updatedAt]    UTC epoch millis of last local modification.
 * [updatedBy]    Google account email of who last modified (set during sync).
 */
data class PersonDomain(
    val localId: Long = 0,
    val cloudId: String? = null,
    val name: String,
    val phone: String,
    val contactId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val updatedBy: String? = null,
    val isDeleted: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.PENDING_UPLOAD,
    val isArchived: Boolean = false
)
