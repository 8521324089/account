package com.kb.ledger.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.data.repository.OfflineFirstTransactionRepository
import com.kb.ledger.data.repository.toEntity
import com.kb.ledger.domain.model.TransactionDomain
import com.kb.ledger.domain.repository.TransactionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for the Transaction screen (per-customer ledger).
 * Holds the selected person and exposes their transaction list reactively.
 *
 * Depends on [TransactionRepository] interface rather than direct Room DAOs.
 */
class TransactionViewModel @JvmOverloads constructor(
    application: Application,
    private val transactionRepository: TransactionRepository = OfflineFirstTransactionRepository(
        AppDatabase.getDatabase(application).transactionDao(),
        AppDatabase.getDatabase(application).personDao(),
        AppDatabase.getDatabase(application).syncOperationDao(),
        localBackupManager = com.kb.ledger.backup.partition.LocalPartitionedBackupManager(application)
    )
) : AndroidViewModel(application) {

    private val _selectedPerson = MutableStateFlow<Person?>(null)
    val selectedPerson: StateFlow<Person?> = _selectedPerson.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<Transaction>> = _selectedPerson
        .flatMapLatest { person ->
            if (person != null) {
                transactionRepository.observeTransactionsForPerson(person.id)
                    .map { list -> list.map { it.toEntity() } }
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectPerson(person: Person?) {
        _selectedPerson.value = person
    }

    fun addTransaction(
        personId: Long,
        amountCents: Long,
        type: TransactionType,
        note: String?,
        imageUri: String?,
        createdAt: Long? = null,
        customId: String? = null
    ) {
        viewModelScope.launch {
            val now = createdAt ?: System.currentTimeMillis()
            val domainTx = TransactionDomain(
                id = customId ?: UUID.randomUUID().toString(),
                personLocalId = personId,
                amountCents = amountCents,
                type = when (type) {
                    TransactionType.GIVE -> com.kb.ledger.domain.model.TransactionType.GIVE
                    TransactionType.RECEIVE -> com.kb.ledger.domain.model.TransactionType.RECEIVE
                },
                note = note,
                imageUri = imageUri,
                createdAt = now,
                updatedAt = now
            )
            transactionRepository.saveTransaction(domainTx)
        }
    }

    fun updateTransaction(
        id: String,
        amountCents: Long,
        type: TransactionType,
        note: String?,
        imageUri: String?,
        createdAt: Long? = null
    ) {
        viewModelScope.launch {
            val domainTx = TransactionDomain(
                id = id,
                personLocalId = _selectedPerson.value?.id ?: 0L,
                amountCents = amountCents,
                type = when (type) {
                    TransactionType.GIVE -> com.kb.ledger.domain.model.TransactionType.GIVE
                    TransactionType.RECEIVE -> com.kb.ledger.domain.model.TransactionType.RECEIVE
                },
                note = note,
                imageUri = imageUri,
                createdAt = createdAt ?: System.currentTimeMillis()
            )
            transactionRepository.updateTransaction(domainTx)
        }
    }

    fun deleteTransaction(id: String) {
        viewModelScope.launch {
            transactionRepository.deleteTransaction(id)
        }
    }
}
