package com.kb.ledger.domain.repository

import com.kb.ledger.domain.model.PersonDomain
import com.kb.ledger.util.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Contract for all person (customer) data operations.
 * Implementations coordinate between Room and Google Drive.
 */
interface PersonRepository {

    /** Observe all non-deleted persons. Updates reactively from Room. */
    fun observeAllPersons(): Flow<List<PersonDomain>>

    /** Observe active (non-archived, non-deleted) persons. */
    fun observeActivePersons(): Flow<List<PersonDomain>>

    /** Observe active persons with at least one transaction (for dashboard display). */
    fun observeDashboardActivePersons(): Flow<List<PersonDomain>>

    /** Observe archived (non-deleted) persons. */
    fun observeArchivedPersons(): Flow<List<PersonDomain>>

    /** Observe archived persons with at least one transaction (for dashboard display). */
    fun observeDashboardArchivedPersons(): Flow<List<PersonDomain>>

    /** Archive a person by local ID. */
    suspend fun archivePerson(localId: Long): AppResult<Unit>

    /** Restore an archived person by local ID. */
    suspend fun restorePerson(localId: Long): AppResult<Unit>

    /** Observe persons who have at least one transaction (for "Recent" list). */
    fun observeRecentPersons(limit: Int = 10): Flow<List<PersonDomain>>

    /** Get a single person by local Room ID. */
    suspend fun getPersonByLocalId(localId: Long): PersonDomain?

    /** Get a single person by their cloud UUID. */
    suspend fun getPersonByCloudId(cloudId: String): PersonDomain?

    /**
     * Insert or update a person locally (Room) and mark as PENDING_UPLOAD.
     * The sync worker will push to Drive on next sync cycle.
     */
    suspend fun savePerson(person: PersonDomain): AppResult<PersonDomain>

    /**
     * Soft-delete a person and all their transactions.
     * Marks records as PENDING_DELETE for Drive tombstone propagation.
     */
    suspend fun deletePerson(localId: Long): AppResult<Unit>

    /**
     * Safely delete a zero-net-balance person from the dashboard.
     * Verifies that the person has at least one transaction and net balance is zero.
     * Soft-deletes the person and all their transactions, enqueuing tombstones for sync.
     */
    suspend fun deleteZeroBalancePerson(localId: Long): AppResult<Unit>

    /**
     * Upsert persons received from Drive during a sync cycle.
     * Applies conflict resolution before writing locally.
     */
    suspend fun applyRemotePersons(remotePersons: List<PersonDomain>): AppResult<Unit>
}
