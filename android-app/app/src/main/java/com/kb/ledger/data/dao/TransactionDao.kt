package com.kb.ledger.data.dao

import androidx.room.*
import com.kb.ledger.data.model.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    // ── Observe (UI-facing) ──────────────────────────────────────────────────

    @Query("""
        SELECT * FROM transactions
        WHERE personId = :personId AND isDeleted = 0
        ORDER BY createdAt DESC
    """)
    fun getTransactionsForPerson(personId: Long): Flow<List<Transaction>>

    /**
     * Net balance per person in cents.
     * Positive = "you get", Negative = "you give".
     */
    @Query("""
        SELECT personId,
        SUM(CASE WHEN type = 'RECEIVE' THEN amountCents ELSE -amountCents END) AS balanceCents
        FROM transactions
        WHERE isDeleted = 0
        GROUP BY personId
    """)
    fun getAllBalances(): Flow<List<PersonBalanceEntity>>

    /** Total GIVE amount in cents (legacy query). */
    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM transactions WHERE type = 'GIVE' AND isDeleted = 0")
    fun getTotalGiveCents(): Flow<Long>

    /**
     * Total outstanding amount owed by you (Total You Give).
     * Sum of absolute net balances where net balance < 0.
     */
    @Query("""
        SELECT COALESCE(SUM(-balanceCents), 0) FROM (
            SELECT SUM(CASE WHEN type = 'RECEIVE' THEN amountCents ELSE -amountCents END) AS balanceCents
            FROM transactions
            WHERE isDeleted = 0
            GROUP BY personId
            HAVING balanceCents < 0
        )
    """)
    fun getTotalYouGiveCents(): Flow<Long>

    /**
     * Total outstanding amount owed to you (Total You Get).
     * Sum of positive net balances where net balance > 0.
     */
    @Query("""
        SELECT COALESCE(SUM(balanceCents), 0) FROM (
            SELECT SUM(CASE WHEN type = 'RECEIVE' THEN amountCents ELSE -amountCents END) AS balanceCents
            FROM transactions
            WHERE isDeleted = 0
            GROUP BY personId
            HAVING balanceCents > 0
        )
    """)
    fun getTotalYouGetCents(): Flow<Long>

    /** Total RECEIVE amount in cents since [todayStart] (dashboard). */
    @Query("""
        SELECT COALESCE(SUM(amountCents), 0)
        FROM transactions
        WHERE type = 'RECEIVE' AND isDeleted = 0 AND createdAt >= :todayStart
    """)
    fun getTotalReceiveTodayCents(todayStart: Long): Flow<Long>

    /** Total RECEIVE amount in cents between [todayStart] and [todayEnd] (dashboard). */
    @Query("""
        SELECT COALESCE(SUM(amountCents), 0)
        FROM transactions
        WHERE type = 'RECEIVE' AND isDeleted = 0 AND createdAt >= :todayStart AND createdAt <= :todayEnd
    """)
    fun getTotalReceiveTodayCents(todayStart: Long, todayEnd: Long): Flow<Long>

    /** Observe all active transactions (used for reactive received today and activity sorting). */
    @Query("SELECT * FROM transactions WHERE isDeleted = 0")
    fun observeAllTransactions(): Flow<List<Transaction>>

    // ── Fetch (suspend) ──────────────────────────────────────────────────────

    @Query("SELECT * FROM transactions WHERE isDeleted = 0")
    suspend fun getAllTransactions(): List<Transaction>

    /** All transactions including soft-deleted tombstones (used for cloud sync & backup reconciliation). */
    @Query("SELECT * FROM transactions")
    suspend fun getAllTransactionsIncludingDeleted(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionById(id: String): Transaction?

    @Query("SELECT * FROM transactions WHERE personId = :personId AND isDeleted = 0 ORDER BY createdAt DESC")
    suspend fun getTransactionsForPersonOnce(personId: Long): List<Transaction>

    /** All transactions with pending sync (includes deleted for tombstone push). */
    @Query("SELECT * FROM transactions WHERE syncPending = 1")
    suspend fun getPendingSyncTransactions(): List<Transaction>

    // ── Write ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<Transaction>)

    @Update
    suspend fun updateTransaction(transaction: Transaction)

    @Query("UPDATE transactions SET syncPending = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE transactions SET imageFileId = :fileId, syncPending = 1, updatedAt = :now WHERE id = :id")
    suspend fun updateImageFileId(id: String, fileId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE transactions SET isDeleted = 1, syncPending = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE transactions SET isDeleted = 1, syncPending = 1, updatedAt = :now WHERE personId = :personId AND isDeleted = 0")
    suspend fun softDeleteForPerson(personId: Long, now: Long = System.currentTimeMillis())

    @Delete
    suspend fun hardDelete(transaction: Transaction)
}

/** Room projection for balance aggregation. */
data class PersonBalanceEntity(
    val personId: Long,
    val balanceCents: Long
)
