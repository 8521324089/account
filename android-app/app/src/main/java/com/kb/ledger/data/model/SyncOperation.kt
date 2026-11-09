package com.kb.ledger.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Durable queue of pending Drive sync operations.
 * Survives process death — written to Room before the network call is made.
 * Deleted only after Drive confirms the operation succeeded.
 *
 * [type]         Operation type: UPSERT or DELETE.
 * [entityType]   "PERSON" or "TRANSACTION".
 * [entityId]     cloudId (UUID string) of the record to sync.
 * [retryCount]   Number of failed attempts. Abandoned after MAX_RETRIES.
 */
@Entity(tableName = "sync_operations")
data class SyncOperation(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val type: SyncOperationType,
    val entityType: SyncEntityType,
    val entityId: String,
    val retryCount: Int = 0,
    val lastErrorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val MAX_RETRIES = 5
    }
}

enum class SyncOperationType { UPSERT, DELETE }
enum class SyncEntityType { PERSON, TRANSACTION, IMAGE }
