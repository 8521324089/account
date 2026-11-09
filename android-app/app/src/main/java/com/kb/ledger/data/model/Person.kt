package com.kb.ledger.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a customer / person.
 *
 * [cloudId]      Stable UUID for Google Drive JSON identity.
 *                Null until the record has been synced at least once.
 * [updatedAt]    UTC epoch millis of the last modification. Used for conflict resolution.
 * [isDeleted]    Soft-delete tombstone. Excluded from UI queries but kept for Drive sync.
 * [syncPending]  True when local state has not yet been pushed to Drive.
 */
@Entity(tableName = "persons")
data class Person(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val phone: String,
    val contactId: String? = null,
    val cloudId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val updatedBy: String? = null,
    val isDeleted: Boolean = false,
    val syncPending: Boolean = true,
    val isArchived: Boolean = false
)
