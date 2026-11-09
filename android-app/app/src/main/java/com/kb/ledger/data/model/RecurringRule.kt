package com.kb.ledger.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY }

/**
 * Room entity representing a scheduled recurring ledger template.
 *
 * Invariants:
 * - Templates are distinguished from actual recorded transactions.
 * - Each generated occurrence has an idempotent transaction id preventing duplicates across restarts.
 * - Preserves pause/resume controls, optional end dates, and month-end boundary clamping.
 */
@Entity(
    tableName = "recurring_rules",
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
data class RecurringRule(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val personId: Long,
    val amountCents: Long,
    val type: TransactionType,
    val note: String? = null,
    val frequency: RecurrenceFrequency,
    val startDate: Long,
    val nextExecutionDate: Long,
    val endDate: Long? = null,
    val isPaused: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
