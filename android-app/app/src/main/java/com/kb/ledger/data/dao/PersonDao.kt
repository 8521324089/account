package com.kb.ledger.data.dao

import androidx.room.*
import com.kb.ledger.data.model.Person
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {

    // ── Observe (UI-facing) ──────────────────────────────────────────────────

    /** Active (non-deleted, non-archived) persons, alphabetical. */
    @Query("SELECT * FROM persons WHERE isDeleted = 0 AND isArchived = 0 ORDER BY name ASC")
    fun getActivePersons(): Flow<List<Person>>

    /** Active persons with at least one non-deleted transaction (for dashboard). */
    @Query("""
        SELECT DISTINCT p.* FROM persons p
        INNER JOIN transactions t ON p.id = t.personId
        WHERE p.isDeleted = 0 AND p.isArchived = 0 AND t.isDeleted = 0
        ORDER BY p.name COLLATE NOCASE ASC
    """)
    fun getActivePersonsWithTransactions(): Flow<List<Person>>

    /** Archived (non-deleted, archived) persons, alphabetical. */
    @Query("SELECT * FROM persons WHERE isDeleted = 0 AND isArchived = 1 ORDER BY name ASC")
    fun getArchivedPersons(): Flow<List<Person>>

    /** Archived persons with at least one non-deleted transaction (for dashboard). */
    @Query("""
        SELECT DISTINCT p.* FROM persons p
        INNER JOIN transactions t ON p.id = t.personId
        WHERE p.isDeleted = 0 AND p.isArchived = 1 AND t.isDeleted = 0
        ORDER BY p.name COLLATE NOCASE ASC
    """)
    fun getArchivedPersonsWithTransactions(): Flow<List<Person>>

    /** All non-deleted persons, alphabetical. */
    @Query("SELECT * FROM persons WHERE isDeleted = 0 ORDER BY name ASC")
    fun getAllPersons(): Flow<List<Person>>

    /** Persons with at least one non-deleted transaction, ordered by most recent activity. */
    @Query("""
        SELECT p.* FROM persons p
        INNER JOIN (
            SELECT personId, MAX(createdAt) AS lastTx
            FROM transactions
            WHERE isDeleted = 0
            GROUP BY personId
        ) t ON p.id = t.personId
        WHERE p.isDeleted = 0
        ORDER BY t.lastTx DESC
        LIMIT :limit
    """)
    fun getRecentPersons(limit: Int = 10): Flow<List<Person>>

    // ── Fetch (suspend) ──────────────────────────────────────────────────────

    @Query("SELECT * FROM persons WHERE isDeleted = 0")
    suspend fun getAllPersonsList(): List<Person>

    /** All persons including soft-deleted tombstones (used for cloud sync & backup reconciliation). */
    @Query("SELECT * FROM persons")
    suspend fun getAllPersonsIncludingDeleted(): List<Person>

    @Query("SELECT * FROM persons WHERE id = :id AND isDeleted = 0")
    suspend fun getPersonById(id: Long): Person?

    @Query("SELECT * FROM persons WHERE cloudId = :cloudId")
    suspend fun getPersonByCloudId(cloudId: String): Person?

    /** All persons with pending sync (includes deleted for tombstone push). */
    @Query("SELECT * FROM persons WHERE syncPending = 1")
    suspend fun getPendingSyncPersons(): List<Person>

    // ── Write ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPerson(person: Person): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPersons(persons: List<Person>)

    @Update
    suspend fun updatePerson(person: Person)

    /**
     * Update only the person's displayed name, preserving all other fields (id, phone, timestamps, tombstones).
     */
    @Query("UPDATE persons SET name = :newName, updatedAt = :now WHERE id = :id")
    suspend fun updatePersonName(id: Long, newName: String, now: Long = System.currentTimeMillis())

    /**
     * Attach cloudId to an existing person when establishing remote cloud identity.
     */
    @Query("UPDATE persons SET cloudId = :cloudId, updatedAt = :now WHERE id = :id")
    suspend fun updatePersonCloudId(id: Long, cloudId: String, now: Long = System.currentTimeMillis())

    /** Mark a person as synced (clear syncPending). */
    @Query("UPDATE persons SET syncPending = 0 WHERE id = :localId")
    suspend fun markSynced(localId: Long)

    /** Archive: mark isArchived = 1 + syncPending. */
    @Query("UPDATE persons SET isArchived = 1, syncPending = 1, updatedAt = :now WHERE id = :id")
    suspend fun archivePerson(id: Long, now: Long = System.currentTimeMillis())

    /** Restore: mark isArchived = 0 + syncPending. */
    @Query("UPDATE persons SET isArchived = 0, syncPending = 1, updatedAt = :now WHERE id = :id")
    suspend fun restorePerson(id: Long, now: Long = System.currentTimeMillis())

    /** Soft-delete: mark isDeleted + syncPending so tombstone is pushed to Drive. */
    @Query("UPDATE persons SET isDeleted = 1, syncPending = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Delete
    suspend fun hardDelete(person: Person)
}
