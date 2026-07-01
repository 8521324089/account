package com.kb.ledger.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class TransactionType {
    GIVE, RECEIVE
}

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val personId: Long,
    val amount: Double,
    val type: TransactionType,
    val note: String?,
    val imageUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
