package com.kb.ledger.data.repository

import com.kb.ledger.data.dao.PersonDao
import com.kb.ledger.data.dao.SyncOperationDao
import com.kb.ledger.data.dao.TransactionDao
import com.kb.ledger.data.model.SyncEntityType
import com.kb.ledger.data.model.SyncOperation
import com.kb.ledger.data.model.SyncOperationType
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.domain.model.SyncStatus
import com.kb.ledger.domain.model.TransactionDomain
import com.kb.ledger.domain.model.TransactionType
import com.kb.ledger.domain.repository.PersonBalance
import com.kb.ledger.domain.repository.TransactionRepository
import com.kb.ledger.util.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Offline-first implementation of [TransactionRepository].
 *
 * Coordinates between Room [TransactionDao], [PersonDao], and [SyncOperationDao].
 * Maintains integer paise arithmetic, soft deletes, and durable offline queueing.
 * Completely usable offline with zero remote dependencies.
 */
class OfflineFirstTransactionRepository(
    private val transactionDao: TransactionDao,
    private val personDao: PersonDao,
    private val syncOperationDao: SyncOperationDao,
    private val localBackupManager: com.kb.ledger.backup.partition.LocalPartitionedBackupManager? = null
) : TransactionRepository {

    override fun observeTransactionsForPerson(personLocalId: Long): Flow<List<TransactionDomain>> =
        transactionDao.getTransactionsForPerson(personLocalId).map { list ->
            list.map { it.toDomain() }
        }

    override fun observeAllTransactions(): Flow<List<TransactionDomain>> =
        transactionDao.observeAllTransactions().map { list ->
            list.map { it.toDomain() }
        }

    override fun observeAllBalances(): Flow<List<PersonBalance>> =
        transactionDao.getAllBalances().map { list ->
            list.map { PersonBalance(personLocalId = it.personId, balanceCents = it.balanceCents) }
        }

    override fun observeTotalGive(): Flow<Long> =
        transactionDao.getTotalGiveCents()

    override fun observeTotalYouGive(): Flow<Long> =
        transactionDao.getTotalYouGiveCents()

    override fun observeTotalYouGet(): Flow<Long> =
        transactionDao.getTotalYouGetCents()

    override fun observeTotalReceiveToday(todayStartMillis: Long): Flow<Long> =
        transactionDao.getTotalReceiveTodayCents(todayStartMillis)

    override fun observeTotalReceiveToday(todayStartMillis: Long, todayEndMillis: Long): Flow<Long> =
        transactionDao.getTotalReceiveTodayCents(todayStartMillis, todayEndMillis)

    override suspend fun updateTransaction(transaction: TransactionDomain): AppResult<TransactionDomain> = try {
        val now = System.currentTimeMillis()
        val existing = transactionDao.getTransactionById(transaction.id)
            ?: return AppResult.Error("Transaction with ID ${transaction.id} not found")

        val imageChanged = transaction.imageUri != existing.imageUri
        val resolvedImageFileId = if (imageChanged) null else (transaction.imageFileId ?: existing.imageFileId)

        // Strictly preserve original persisted creation timestamp to prevent partition drift
        val entity = transaction.toEntity().copy(
            id = transaction.id,
            personId = if (transaction.personLocalId > 0) transaction.personLocalId else existing.personId,
            createdAt = existing.createdAt,
            imageFileId = resolvedImageFileId,
            updatedAt = now,
            syncPending = true
        )

        transactionDao.updateTransaction(entity)
        localBackupManager?.recordTransactionMutation(entity)

        syncOperationDao.insert(
            SyncOperation(
                type = SyncOperationType.UPSERT,
                entityType = SyncEntityType.TRANSACTION,
                entityId = transaction.id
            )
        )

        AppResult.Success(entity.toDomain(transaction.personCloudId))
    } catch (e: Exception) {
        AppResult.Error("Failed to update transaction: ${e.message}", e)
    }

    override suspend fun saveTransaction(transaction: TransactionDomain): AppResult<TransactionDomain> = try {
        val now = System.currentTimeMillis()
        val txId = if (transaction.id.isNotBlank()) transaction.id else UUID.randomUUID().toString()

        val entity = transaction.toEntity().copy(
            id = txId,
            createdAt = if (transaction.createdAt > 0) transaction.createdAt else now,
            updatedAt = now,
            syncPending = true
        )

        transactionDao.insertTransaction(entity)
        localBackupManager?.recordTransactionMutation(entity)

        // Queue durable sync operation
        syncOperationDao.insert(
            SyncOperation(
                type = SyncOperationType.UPSERT,
                entityType = SyncEntityType.TRANSACTION,
                entityId = txId
            )
        )

        AppResult.Success(entity.toDomain(transaction.personCloudId))
    } catch (e: Exception) {
        AppResult.Error("Failed to save transaction: ${e.message}", e)
    }

    override suspend fun deleteTransaction(id: String): AppResult<Unit> = try {
        val now = System.currentTimeMillis()
        val existing = transactionDao.getTransactionById(id)
        transactionDao.softDelete(id, now)

        if (existing != null) {
            localBackupManager?.recordTransactionDeletion(id, existing.createdAt)
        }

        syncOperationDao.insert(
            SyncOperation(
                type = SyncOperationType.DELETE,
                entityType = SyncEntityType.TRANSACTION,
                entityId = id
            )
        )

        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error("Failed to delete transaction $id: ${e.message}", e)
    }

    override suspend fun updateImageFileId(transactionId: String, driveFileId: String): AppResult<Unit> = try {
        val now = System.currentTimeMillis()
        transactionDao.updateImageFileId(transactionId, driveFileId, now)
        val updated = transactionDao.getTransactionById(transactionId)
        if (updated != null) {
            localBackupManager?.recordTransactionMutation(updated)
        }

        syncOperationDao.insert(
            SyncOperation(
                type = SyncOperationType.UPSERT,
                entityType = SyncEntityType.TRANSACTION,
                entityId = transactionId
            )
        )

        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error("Failed to update image file ID: ${e.message}", e)
    }

    override suspend fun applyRemoteTransactions(remoteTransactions: List<TransactionDomain>): AppResult<Unit> = try {
        for (remote in remoteTransactions) {
            val existing = transactionDao.getTransactionById(remote.id)

            // Resolve local personId from cloudId if needed
            val personLocalId = if (remote.personLocalId > 0) {
                remote.personLocalId
            } else if (remote.personCloudId != null) {
                personDao.getPersonByCloudId(remote.personCloudId)?.id ?: continue
            } else {
                continue
            }

            if (existing == null) {
                val entity = remote.toEntity().copy(
                    personId = personLocalId,
                    syncPending = false
                )
                transactionDao.insertTransaction(entity)
            } else if (remote.updatedAt > existing.updatedAt) {
                val updatedEntity = remote.toEntity().copy(
                    personId = personLocalId,
                    syncPending = false
                )
                transactionDao.updateTransaction(updatedEntity)
            }
        }
        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error("Failed to apply remote transactions: ${e.message}", e)
    }
}

// ── Domain / Entity Mappers ──────────────────────────────────────────────────

fun Transaction.toDomain(personCloudId: String? = null): TransactionDomain = TransactionDomain(
    id = id,
    personLocalId = personId,
    personCloudId = personCloudId,
    amountCents = amountCents,
    type = when (type) {
        com.kb.ledger.data.model.TransactionType.GIVE -> TransactionType.GIVE
        com.kb.ledger.data.model.TransactionType.RECEIVE -> TransactionType.RECEIVE
    },
    note = note,
    imageUri = imageUri,
    imageFileId = imageFileId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    updatedBy = updatedBy,
    isDeleted = isDeleted,
    syncStatus = if (syncPending) SyncStatus.PENDING_UPLOAD else SyncStatus.SYNCED
)

fun TransactionDomain.toEntity(): Transaction = Transaction(
    id = id,
    personId = personLocalId,
    amountCents = amountCents,
    type = when (type) {
        TransactionType.GIVE -> com.kb.ledger.data.model.TransactionType.GIVE
        TransactionType.RECEIVE -> com.kb.ledger.data.model.TransactionType.RECEIVE
    },
    note = note,
    imageUri = imageUri,
    imageFileId = imageFileId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    updatedBy = updatedBy,
    isDeleted = isDeleted,
    syncPending = syncStatus == SyncStatus.PENDING_UPLOAD || syncStatus == SyncStatus.PENDING_DELETE
)
