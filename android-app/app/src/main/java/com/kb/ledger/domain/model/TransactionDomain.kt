package com.kb.ledger.domain.model

/**
 * Domain-layer representation of a ledger transaction.
 *
 * [id]            UUID string — stable across devices.
 * [personCloudId] References [PersonDomain.cloudId] in cloud JSON.
 * [personLocalId] References [PersonDomain.localId] in Room.
 * [amountCents]   Amount in smallest currency unit (paise) to avoid float precision issues.
 *                 Display value = amountCents / 100.0
 * [type]          GIVE (you gave money) or RECEIVE (you got money back).
 * [imageUri]      Device-local content:// URI. May be null or stale on other devices.
 * [imageFileId]   Drive file ID for bill image. Null until uploaded.
 * [syncStatus]    Current sync state.
 * [isDeleted]     Soft-delete tombstone.
 */
data class TransactionDomain(
    val id: String,
    val personLocalId: Long,
    val personCloudId: String? = null,
    val amountCents: Long,            // e.g., ₹500.00 → 50000L
    val type: TransactionType,
    val note: String? = null,
    val imageUri: String? = null,
    val imageFileId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val updatedBy: String? = null,
    val isDeleted: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.PENDING_UPLOAD
) {
    /** Display-ready amount as Double (amountCents / 100). */
    val amount: Double get() = amountCents / 100.0
}

enum class TransactionType { GIVE, RECEIVE }
