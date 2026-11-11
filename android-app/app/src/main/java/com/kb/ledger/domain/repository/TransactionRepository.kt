package com.kb.ledger.domain.repository

import com.kb.ledger.domain.model.TransactionDomain
import com.kb.ledger.domain.model.TransactionType
import com.kb.ledger.util.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Contract for all transaction (ledger entry) data operations.
 */
interface TransactionRepository {

    /** Observe transactions for a specific person, newest first, excluding deleted. */
    fun observeTransactionsForPerson(personLocalId: Long): Flow<List<TransactionDomain>>

    /** Observe all active transactions across all persons. */
    fun observeAllTransactions(): Flow<List<TransactionDomain>>

    /** Observe all transaction balances keyed by personLocalId. */
    fun observeAllBalances(): Flow<List<PersonBalance>>

    /** Sum of all GIVE amounts globally (legacy query). */
    fun observeTotalGive(): Flow<Long>

    /** Total outstanding amount owed by you (sum of absolute net balances where balance < 0). */
    fun observeTotalYouGive(): Flow<Long>

    /** Total outstanding amount owed to you (sum of positive net balances where balance > 0). */
    fun observeTotalYouGet(): Flow<Long>

    /** Sum of RECEIVE amounts since [todayStartMillis] (for dashboard). */
    fun observeTotalReceiveToday(todayStartMillis: Long): Flow<Long>

    /** Sum of RECEIVE amounts between [todayStartMillis] and [todayEndMillis] (for dashboard). */
    fun observeTotalReceiveToday(todayStartMillis: Long, todayEndMillis: Long): Flow<Long>

    /** Customer: Total outstanding amount owed by you to customers. */
    fun observeCustomerTotalYouGive(): Flow<Long> = observeTotalYouGive()

    /** Customer: Sum of RECEIVE amounts from customers between todayStartMillis and todayEndMillis. */
    fun observeCustomerReceiveToday(todayStartMillis: Long, todayEndMillis: Long): Flow<Long> =
        observeTotalReceiveToday(todayStartMillis, todayEndMillis)

    /** Supplier: Total payable to suppliers (positive debt, advances not offset). */
    fun observeSupplierTotalPayable(): Flow<Long> = kotlinx.coroutines.flow.flowOf(0L)

    /** Supplier: Sum of GIVE amounts paid to suppliers between todayStartMillis and todayEndMillis. */
    fun observeSupplierPaidToday(todayStartMillis: Long, todayEndMillis: Long): Flow<Long> =
        kotlinx.coroutines.flow.flowOf(0L)

    /**
     * Save a new transaction locally and mark PENDING_UPLOAD.
     * Sync worker will push to Drive.
     */
    suspend fun saveTransaction(transaction: TransactionDomain): AppResult<TransactionDomain>

    /**
     * Update an existing transaction locally and mark PENDING_UPLOAD.
     */
    suspend fun updateTransaction(transaction: TransactionDomain): AppResult<TransactionDomain>

    /**
     * Soft-delete a transaction.
     * Marks as PENDING_DELETE for Drive tombstone propagation.
     */
    suspend fun deleteTransaction(id: String): AppResult<Unit>

    /**
     * Upsert transactions received from Drive during a sync cycle.
     * Applies conflict resolution before writing locally.
     */
    suspend fun applyRemoteTransactions(remoteTransactions: List<TransactionDomain>): AppResult<Unit>

    /** Update the Drive image file ID after a successful image upload. */
    suspend fun updateImageFileId(transactionId: String, driveFileId: String): AppResult<Unit>
}

/** Aggregated balance for a person. Positive = "you get", negative = "you give". */
data class PersonBalance(
    val personLocalId: Long,
    val balanceCents: Long
) {
    val balance: Double get() = balanceCents / 100.0
}
