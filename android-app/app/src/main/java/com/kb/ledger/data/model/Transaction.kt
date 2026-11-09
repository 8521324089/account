package com.kb.ledger.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class TransactionType { GIVE, RECEIVE }

/**
 * Room entity for a ledger transaction.
 *
 * [id]           UUID string — stable across devices (no autoGenerate).
 * [personId]     FK to Person.id (local Long).
 * [amountCents]  Amount in paise (₹1 = 100 paise). Avoids float precision issues.
 *                Use [amount] property for display.
 * [imageUri]     Device-local content:// URI. Null or stale on other devices.
 * [imageFileId]  Google Drive file ID for bill image. Null until uploaded.
 * [updatedAt]    UTC epoch millis of the last modification.
 * [isDeleted]    Soft-delete tombstone.
 * [syncPending]  True when local state has not yet been pushed to Drive.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = Person::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("personId")]
)
data class Transaction(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val personId: Long,
    val amountCents: Long,              // ₹ amount * 100
    val type: TransactionType,
    val note: String? = null,
    val imageUri: String? = null,       // device-local, may become stale
    val imageFileId: String? = null,    // Drive file ID after upload
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val updatedBy: String? = null,
    val isDeleted: Boolean = false,
    val syncPending: Boolean = true
) {
    /** Display-ready amount as Double. */
    val amount: Double get() = amountCents / 100.0
}
