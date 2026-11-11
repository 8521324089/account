package com.kb.ledger.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Role of a ledger entity: Customer or Supplier.
 */
enum class PersonRole {
    CUSTOMER,
    SUPPLIER
}

/**
 * Room entity for a customer / supplier / person.
 *
 * [role]         Ledger role: CUSTOMER (default) or SUPPLIER.
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
    val isArchived: Boolean = false,
    @ColumnInfo(defaultValue = "CUSTOMER")
    val role: PersonRole = PersonRole.CUSTOMER
)
