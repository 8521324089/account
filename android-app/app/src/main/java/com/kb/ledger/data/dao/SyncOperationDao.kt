package com.kb.ledger.data.dao

import androidx.room.*
import com.kb.ledger.data.model.SyncOperation
import com.kb.ledger.data.model.SyncEntityType
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOperationDao {

    @Query("SELECT * FROM sync_operations ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SyncOperation>>

    @Query("SELECT * FROM sync_operations ORDER BY createdAt ASC")
    suspend fun getAll(): List<SyncOperation>

    @Query("SELECT COUNT(*) FROM sync_operations")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) > 0 FROM sync_operations")
    fun hasPending(): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(op: SyncOperation)

    @Query("DELETE FROM sync_operations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE sync_operations SET retryCount = retryCount + 1, lastErrorMessage = :error WHERE id = :id")
    suspend fun incrementRetry(id: String, error: String?)

    @Query("DELETE FROM sync_operations WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteByEntity(entityType: SyncEntityType, entityId: String)

    @Query("DELETE FROM sync_operations")
    suspend fun deleteAll()
}
