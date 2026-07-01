package com.kb.ledger.util

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

data class BackupData(
    val persons: List<Person>,
    val transactions: List<Transaction>,
    val exportDate: Long = System.currentTimeMillis()
)

class BackupManager(private val context: Context) {
    private val gson = Gson()
    private val database = AppDatabase.getDatabase(context)

    suspend fun exportData(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val persons = database.personDao().getAllPersonsList()
            val transactions = database.transactionDao().getAllTransactions()
            val backupData = BackupData(persons, transactions)
            val json = gson.toJson(backupData)

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(json)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun importData(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        json.append(line)
                    }
                }
            }

            val type = object : TypeToken<BackupData>() {}.type
            val backupData: BackupData = gson.fromJson(json.toString(), type)

            // Import data
            for (person in backupData.persons) {
                database.personDao().insertPerson(person)
            }
            for (transaction in backupData.transactions) {
                database.transactionDao().insertTransaction(transaction)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
