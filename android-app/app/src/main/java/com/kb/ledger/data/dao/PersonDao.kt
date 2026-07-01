package com.kb.ledger.data.dao

import androidx.room.*
import com.kb.ledger.data.model.Person
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {
    @Query("SELECT * FROM persons")
    fun getAllPersons(): Flow<List<Person>>

    @Query("SELECT * FROM persons")
    suspend fun getAllPersonsList(): List<Person>

    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun getPersonById(id: Long): Person?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPerson(person: Person): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPersons(persons: List<Person>)

    @Delete
    suspend fun deletePerson(person: Person)
}
