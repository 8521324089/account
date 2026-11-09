package com.kb.ledger.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kb.ledger.data.model.RecurringRule
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: RecurringRule)

    @Update
    suspend fun updateRule(rule: RecurringRule)

    @Delete
    suspend fun deleteRule(rule: RecurringRule)

    @Query("SELECT * FROM recurring_rules WHERE id = :id")
    suspend fun getRuleById(id: String): RecurringRule?

    @Query("SELECT * FROM recurring_rules ORDER BY createdAt DESC")
    fun getAllRules(): Flow<List<RecurringRule>>

    @Query("SELECT * FROM recurring_rules WHERE personId = :personId ORDER BY createdAt DESC")
    fun getRulesForPerson(personId: Long): Flow<List<RecurringRule>>

    @Query("SELECT * FROM recurring_rules WHERE isPaused = 0 AND nextExecutionDate <= :currentTime")
    suspend fun getActiveDueRules(currentTime: Long): List<RecurringRule>

    @Query("DELETE FROM recurring_rules WHERE id = :id")
    suspend fun deleteRuleById(id: String)

    /** Returns all rules as a one-shot list — used by [BackupManager] for export. */
    @Query("SELECT * FROM recurring_rules ORDER BY createdAt ASC")
    suspend fun getAllRulesList(): List<RecurringRule>
}
