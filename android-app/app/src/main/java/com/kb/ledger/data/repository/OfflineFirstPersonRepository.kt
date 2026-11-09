package com.kb.ledger.data.repository

import androidx.room.withTransaction
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.dao.PersonDao
import com.kb.ledger.data.dao.SyncOperationDao
import com.kb.ledger.data.dao.TransactionDao
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.SyncEntityType
import com.kb.ledger.data.model.SyncOperation
import com.kb.ledger.data.model.SyncOperationType
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.domain.model.PersonDomain
import com.kb.ledger.domain.model.SyncStatus
import com.kb.ledger.domain.repository.PersonRepository
import com.kb.ledger.util.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Offline-first implementation of [PersonRepository].
 *
 * Coordinates between Room [PersonDao] and [SyncOperationDao].
 * All writes persist locally first, generating stable UUIDs for cloud sync
 * and queueing durable [SyncOperation] records for future background sync.
 * Completely usable offline with zero remote dependencies.
 */
class OfflineFirstPersonRepository(
    private val personDao: PersonDao,
    private val syncOperationDao: SyncOperationDao,
    private val transactionDao: TransactionDao? = null,
    private val database: AppDatabase? = null,
    private val localBackupManager: com.kb.ledger.backup.partition.LocalPartitionedBackupManager? = null
) : PersonRepository {

    override fun observeAllPersons(): Flow<List<PersonDomain>> =
        personDao.getAllPersons().map { list -> list.map { it.toDomain() } }

    override fun observeActivePersons(): Flow<List<PersonDomain>> =
        personDao.getActivePersons().map { list -> list.map { it.toDomain() } }

    override fun observeDashboardActivePersons(): Flow<List<PersonDomain>> =
        personDao.getActivePersonsWithTransactions().map { list -> list.map { it.toDomain() } }

    override fun observeArchivedPersons(): Flow<List<PersonDomain>> =
        personDao.getArchivedPersons().map { list -> list.map { it.toDomain() } }

    override fun observeDashboardArchivedPersons(): Flow<List<PersonDomain>> =
        personDao.getArchivedPersonsWithTransactions().map { list -> list.map { it.toDomain() } }

    override fun observeRecentPersons(limit: Int): Flow<List<PersonDomain>> =
        personDao.getRecentPersons(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun getPersonByLocalId(localId: Long): PersonDomain? =
        personDao.getPersonById(localId)?.toDomain()

    override suspend fun getPersonByCloudId(cloudId: String): PersonDomain? =
        personDao.getPersonByCloudId(cloudId)?.toDomain()

    override suspend fun savePerson(person: PersonDomain): AppResult<PersonDomain> = try {
        val now = System.currentTimeMillis()
        val entity = person.toEntity().let {
            it.copy(
                cloudId = it.cloudId ?: UUID.randomUUID().toString(),
                createdAt = if (it.createdAt > 0) it.createdAt else now,
                updatedAt = now,
                syncPending = true
            )
        }

        val localId = personDao.insertPerson(entity)
        val savedEntity = entity.copy(id = if (entity.id > 0) entity.id else localId)

        // Queue durable sync operation
        val cloudId = savedEntity.cloudId ?: savedEntity.id.toString()
        syncOperationDao.insert(
            SyncOperation(
                type = SyncOperationType.UPSERT,
                entityType = SyncEntityType.PERSON,
                entityId = cloudId
            )
        )
        localBackupManager?.recordPersons(personDao.getAllPersonsList())

        AppResult.Success(savedEntity.toDomain())
    } catch (e: Exception) {
        AppResult.Error("Failed to save person: ${e.message}", e)
    }

    override suspend fun deletePerson(localId: Long): AppResult<Unit> = try {
        val person = personDao.getPersonById(localId)
            ?: return AppResult.Error("Person with ID $localId not found")

        val now = System.currentTimeMillis()
        personDao.softDelete(localId, now)

        val syncEntityId = person.cloudId ?: localId.toString()
        syncOperationDao.insert(
            SyncOperation(
                type = SyncOperationType.DELETE,
                entityType = SyncEntityType.PERSON,
                entityId = syncEntityId
            )
        )
        localBackupManager?.recordPersons(personDao.getAllPersonsList())

        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error("Failed to delete person $localId: ${e.message}", e)
    }

    override suspend fun deleteZeroBalancePerson(localId: Long): AppResult<Unit> {
        return try {
            val person = personDao.getPersonById(localId)
                ?: return AppResult.Error("Customer with ID $localId not found")

            val txDao = transactionDao
                ?: return AppResult.Error("TransactionDao required for safe zero-balance deletion")

            val txs = txDao.getTransactionsForPersonOnce(localId)
            if (txs.isEmpty()) {
                return AppResult.Error("Cannot delete customer without transactions from dashboard")
            }

            val balanceCents = txs.sumOf {
                if (it.type == TransactionType.RECEIVE) it.amountCents else -it.amountCents
            }
            if (balanceCents != 0L) {
                return AppResult.Error("Cannot delete customer with non-zero balance (current balance: ₹${balanceCents / 100.0})")
            }

            val now = System.currentTimeMillis()

            // Wrap ALL writes in a single atomic database transaction.
            // If the process dies mid-operation, Room rolls back to the last committed state.
            val db = database
            if (db != null) {
                db.withTransaction {
                    personDao.softDelete(localId, now)
                    val personSyncId = person.cloudId ?: localId.toString()
                    syncOperationDao.insert(
                        SyncOperation(
                            type = SyncOperationType.DELETE,
                            entityType = SyncEntityType.PERSON,
                            entityId = personSyncId
                        )
                    )
                    txDao.softDeleteForPerson(localId, now)
                    for (tx in txs) {
                        syncOperationDao.insert(
                            SyncOperation(
                                type = SyncOperationType.DELETE,
                                entityType = SyncEntityType.TRANSACTION,
                                entityId = tx.id
                            )
                        )
                    }
                }
            } else {
                // Fallback when database reference is not provided (e.g., in unit tests with fakes).
                // Sequential writes — acceptable in test environments with in-memory fakes.
                personDao.softDelete(localId, now)
                val personSyncId = person.cloudId ?: localId.toString()
                syncOperationDao.insert(
                    SyncOperation(
                        type = SyncOperationType.DELETE,
                        entityType = SyncEntityType.PERSON,
                        entityId = personSyncId
                    )
                )
                txDao.softDeleteForPerson(localId, now)
                for (tx in txs) {
                    syncOperationDao.insert(
                        SyncOperation(
                            type = SyncOperationType.DELETE,
                            entityType = SyncEntityType.TRANSACTION,
                            entityId = tx.id
                        )
                    )
                }
            }

            localBackupManager?.recordPersons(personDao.getAllPersonsList())
            for (tx in txs) {
                localBackupManager?.recordTransactionDeletion(tx.id, tx.createdAt)
            }

            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error("Failed to safely delete customer $localId: ${e.message}", e)
        }
    }

    override suspend fun archivePerson(localId: Long): AppResult<Unit> {
        return try {
            val person = personDao.getPersonById(localId)
                ?: return AppResult.Error("Person with ID $localId not found")

            val now = System.currentTimeMillis()
            personDao.archivePerson(localId, now)

            val syncEntityId = person.cloudId ?: localId.toString()
            syncOperationDao.insert(
                SyncOperation(
                    type = SyncOperationType.UPSERT,
                    entityType = SyncEntityType.PERSON,
                    entityId = syncEntityId
                )
            )
            localBackupManager?.recordPersons(personDao.getAllPersonsList())

            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error("Failed to archive person $localId: ${e.message}", e)
        }
    }

    override suspend fun restorePerson(localId: Long): AppResult<Unit> {
        return try {
            val person = personDao.getPersonById(localId)
                ?: return AppResult.Error("Person with ID $localId not found")

            val now = System.currentTimeMillis()
            personDao.restorePerson(localId, now)

            val syncEntityId = person.cloudId ?: localId.toString()
            syncOperationDao.insert(
                SyncOperation(
                    type = SyncOperationType.UPSERT,
                    entityType = SyncEntityType.PERSON,
                    entityId = syncEntityId
                )
            )
            localBackupManager?.recordPersons(personDao.getAllPersonsList())

            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error("Failed to restore person $localId: ${e.message}", e)
        }
    }

    override suspend fun applyRemotePersons(remotePersons: List<PersonDomain>): AppResult<Unit> = try {
        for (remote in remotePersons) {
            val cloudId = remote.cloudId ?: continue
            val existing = personDao.getPersonByCloudId(cloudId)

            if (existing == null) {
                // New person from cloud
                val entity = remote.toEntity().copy(
                    id = 0, // Let Room auto-generate local ID
                    syncPending = false
                )
                personDao.insertPerson(entity)
            } else if (remote.updatedAt > existing.updatedAt) {
                // Remote is strictly newer (Last Write Wins)
                val updatedEntity = remote.toEntity().copy(
                    id = existing.id,
                    syncPending = false
                )
                personDao.updatePerson(updatedEntity)
            }
        }
        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error("Failed to apply remote persons: ${e.message}", e)
    }
}

// ── Domain / Entity Mappers ──────────────────────────────────────────────────

fun Person.toDomain(): PersonDomain = PersonDomain(
    localId = id,
    cloudId = cloudId,
    name = name,
    phone = phone,
    contactId = contactId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    updatedBy = updatedBy,
    isDeleted = isDeleted,
    syncStatus = if (syncPending) SyncStatus.PENDING_UPLOAD else SyncStatus.SYNCED,
    isArchived = isArchived
)

fun PersonDomain.toEntity(): Person = Person(
    id = localId,
    name = name,
    phone = phone,
    contactId = contactId,
    cloudId = cloudId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    updatedBy = updatedBy,
    isDeleted = isDeleted,
    syncPending = syncStatus == SyncStatus.PENDING_UPLOAD || syncStatus == SyncStatus.PENDING_DELETE,
    isArchived = isArchived
)
