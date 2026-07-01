package com.kb.ledger.data.dao

import androidx.room.*
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE personId = :personId ORDER BY createdAt DESC")
    fun getTransactionsForPerson(personId: Long): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions")
    suspend fun getAllTransactions(): List<Transaction>

    @Query("SELECT SUM(amount) FROM transactions WHERE type = 'GIVE'")
    fun getTotalGive(): Flow<Double?>

    @Query("SELECT SUM(amount) FROM transactions WHERE type = 'RECEIVE' AND createdAt >= :todayStart")
    fun getTotalReceiveToday(todayStart: Long): Flow<Double?>

    @Query("SELECT personId, SUM(CASE WHEN type = 'RECEIVE' THEN amount ELSE -amount END) as balance FROM transactions GROUP BY personId")
    fun getAllBalances(): Flow<List<PersonBalance>>

    @Query("SELECT DISTINCT p.* FROM persons p INNER JOIN transactions t ON p.id = t.personId ORDER BY t.createdAt DESC LIMIT 10")
    fun getRecentPeople(): Flow<List<Person>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction)

    @Delete
    suspend fun deleteTransaction(transaction: Transaction)
}

data class PersonBalance(
    val personId: Long,
    val balance: Double
)
