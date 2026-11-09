package com.kb.ledger.data.repository

import com.kb.ledger.data.dao.PersonBalanceEntity
import com.kb.ledger.data.dao.PersonDao
import com.kb.ledger.data.dao.RecurringRuleDao
import com.kb.ledger.data.dao.SyncOperationDao
import com.kb.ledger.data.dao.TransactionDao
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.RecurringRule
import com.kb.ledger.data.model.SyncEntityType
import com.kb.ledger.data.model.SyncOperation
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakePersonDao : PersonDao {
    private val persons = mutableMapOf<Long, Person>()
    private val flow = MutableStateFlow<List<Person>>(emptyList())
    private var nextId = 1L

    private fun emit() {
        flow.value = persons.values.toList()
    }

    var transactionDaoRef: FakeTransactionDao? = null

    fun triggerEmit() {
        emit()
    }

    override fun getActivePersons(): Flow<List<Person>> =
        flow.map { list -> list.filter { !it.isDeleted && !it.isArchived }.sortedBy { it.name } }

    override fun getActivePersonsWithTransactions(): Flow<List<Person>> =
        flow.map { list ->
            val txDao = transactionDaoRef
            if (txDao != null) {
                val activeIdsWithTx = txDao.getAllTransactionsDirect().filter { !it.isDeleted }.map { it.personId }.toSet()
                list.filter { !it.isDeleted && !it.isArchived && it.id in activeIdsWithTx }.sortedBy { it.name }
            } else {
                list.filter { !it.isDeleted && !it.isArchived }.sortedBy { it.name }
            }
        }

    override fun getArchivedPersons(): Flow<List<Person>> =
        flow.map { list -> list.filter { !it.isDeleted && it.isArchived }.sortedBy { it.name } }

    override fun getArchivedPersonsWithTransactions(): Flow<List<Person>> =
        flow.map { list ->
            val txDao = transactionDaoRef
            if (txDao != null) {
                val idsWithTx = txDao.getAllTransactionsDirect().filter { !it.isDeleted }.map { it.personId }.toSet()
                list.filter { !it.isDeleted && it.isArchived && it.id in idsWithTx }.sortedBy { it.name }
            } else {
                list.filter { !it.isDeleted && it.isArchived }.sortedBy { it.name }
            }
        }

    override fun getAllPersons(): Flow<List<Person>> =
        flow.map { list -> list.filter { !it.isDeleted }.sortedBy { it.name } }

    override fun getRecentPersons(limit: Int): Flow<List<Person>> =
        flow.map { list -> list.filter { !it.isDeleted && !it.isArchived }.take(limit) }

    override suspend fun getAllPersonsList(): List<Person> =
        persons.values.filter { !it.isDeleted }

    override suspend fun getAllPersonsIncludingDeleted(): List<Person> =
        persons.values.toList()

    override suspend fun getPersonById(id: Long): Person? =
        persons[id]?.takeIf { !it.isDeleted }

    override suspend fun getPersonByCloudId(cloudId: String): Person? =
        persons.values.firstOrNull { it.cloudId == cloudId }

    override suspend fun getPendingSyncPersons(): List<Person> =
        persons.values.filter { it.syncPending }

    override suspend fun insertPerson(person: Person): Long {
        val id = if (person.id > 0) {
            nextId = maxOf(nextId, person.id + 1)
            person.id
        } else {
            nextId++
        }
        val entity = person.copy(id = id)
        persons[id] = entity
        emit()
        return id
    }

    override suspend fun insertPersons(persons: List<Person>) {
        persons.forEach { insertPerson(it) }
    }

    override suspend fun updatePerson(person: Person) {
        persons[person.id] = person
        emit()
    }

    override suspend fun updatePersonName(id: Long, newName: String, now: Long) {
        persons[id]?.let {
            persons[id] = it.copy(name = newName, updatedAt = now)
            emit()
        }
    }

    override suspend fun updatePersonCloudId(id: Long, cloudId: String, now: Long) {
        persons[id]?.let {
            persons[id] = it.copy(cloudId = cloudId, updatedAt = now)
            emit()
        }
    }

    override suspend fun markSynced(localId: Long) {
        persons[localId]?.let {
            persons[localId] = it.copy(syncPending = false)
            emit()
        }
    }

    override suspend fun archivePerson(id: Long, now: Long) {
        persons[id]?.let {
            persons[id] = it.copy(isArchived = true, syncPending = true, updatedAt = now)
            emit()
        }
    }

    override suspend fun restorePerson(id: Long, now: Long) {
        persons[id]?.let {
            persons[id] = it.copy(isArchived = false, syncPending = true, updatedAt = now)
            emit()
        }
    }

    override suspend fun softDelete(id: Long, now: Long) {
        persons[id]?.let {
            persons[id] = it.copy(isDeleted = true, syncPending = true, updatedAt = now)
            emit()
        }
    }

    override suspend fun hardDelete(person: Person) {
        persons.remove(person.id)
        emit()
    }
}

class FakeTransactionDao : TransactionDao {
    private val transactions = mutableMapOf<String, Transaction>()
    private val flow = MutableStateFlow<List<Transaction>>(emptyList())
    var personDaoRef: FakePersonDao? = null

    fun getAllTransactionsDirect(): List<Transaction> = transactions.values.toList()

    private fun emit() {
        flow.value = transactions.values.toList()
        personDaoRef?.triggerEmit()
    }

    override fun observeAllTransactions(): Flow<List<Transaction>> =
        flow.map { list -> list.filter { !it.isDeleted } }

    override fun getTransactionsForPerson(personId: Long): Flow<List<Transaction>> =
        flow.map { list ->
            list.filter { it.personId == personId && !it.isDeleted }
                .sortedByDescending { it.createdAt }
        }

    override fun getAllBalances(): Flow<List<PersonBalanceEntity>> =
        flow.map { list ->
            list.filter { !it.isDeleted }
                .groupBy { it.personId }
                .map { (personId, txs) ->
                    val balanceCents = txs.sumOf { tx ->
                        if (tx.type == TransactionType.RECEIVE) tx.amountCents else -tx.amountCents
                    }
                    PersonBalanceEntity(personId, balanceCents)
                }
        }

    override fun getTotalGiveCents(): Flow<Long> =
        flow.map { list ->
            list.filter { !it.isDeleted && it.type == TransactionType.GIVE }
                .sumOf { it.amountCents }
        }

    override fun getTotalYouGiveCents(): Flow<Long> =
        getAllBalances().map { balances ->
            balances.filter { it.balanceCents < 0 }.sumOf { -it.balanceCents }
        }

    override fun getTotalYouGetCents(): Flow<Long> =
        getAllBalances().map { balances ->
            balances.filter { it.balanceCents > 0 }.sumOf { it.balanceCents }
        }

    override fun getTotalReceiveTodayCents(todayStart: Long): Flow<Long> =
        flow.map { list ->
            list.filter { !it.isDeleted && it.type == TransactionType.RECEIVE && it.createdAt >= todayStart }
                .sumOf { it.amountCents }
        }

    override fun getTotalReceiveTodayCents(todayStart: Long, todayEnd: Long): Flow<Long> =
        flow.map { list ->
            list.filter { !it.isDeleted && it.type == TransactionType.RECEIVE && it.createdAt >= todayStart && it.createdAt <= todayEnd }
                .sumOf { it.amountCents }
        }

    override suspend fun getAllTransactions(): List<Transaction> =
        transactions.values.filter { !it.isDeleted }

    override suspend fun getAllTransactionsIncludingDeleted(): List<Transaction> =
        transactions.values.toList()

    override suspend fun getTransactionById(id: String): Transaction? =
        transactions[id]

    override suspend fun getTransactionsForPersonOnce(personId: Long): List<Transaction> =
        transactions.values.filter { it.personId == personId && !it.isDeleted }
            .sortedByDescending { it.createdAt }

    override suspend fun getPendingSyncTransactions(): List<Transaction> =
        transactions.values.filter { it.syncPending }

    override suspend fun insertTransaction(transaction: Transaction) {
        transactions[transaction.id] = transaction
        emit()
    }

    override suspend fun insertTransactions(transactions: List<Transaction>) {
        transactions.forEach { insertTransaction(it) }
    }

    override suspend fun updateTransaction(transaction: Transaction) {
        transactions[transaction.id] = transaction
        emit()
    }

    override suspend fun markSynced(id: String) {
        transactions[id]?.let {
            transactions[id] = it.copy(syncPending = false)
            emit()
        }
    }

    override suspend fun updateImageFileId(id: String, fileId: String, now: Long) {
        transactions[id]?.let {
            transactions[id] = it.copy(imageFileId = fileId, syncPending = true, updatedAt = now)
            emit()
        }
    }

    override suspend fun softDelete(id: String, now: Long) {
        transactions[id]?.let {
            transactions[id] = it.copy(isDeleted = true, syncPending = true, updatedAt = now)
            emit()
        }
    }

    override suspend fun softDeleteForPerson(personId: Long, now: Long) {
        transactions.values.filter { it.personId == personId && !it.isDeleted }.forEach { tx ->
            transactions[tx.id] = tx.copy(isDeleted = true, syncPending = true, updatedAt = now)
        }
        emit()
    }

    override suspend fun hardDelete(transaction: Transaction) {
        transactions.remove(transaction.id)
        emit()
    }
}

class FakeSyncOperationDao : SyncOperationDao {
    private val ops = mutableMapOf<String, SyncOperation>()
    private val flow = MutableStateFlow<List<SyncOperation>>(emptyList())

    private fun emit() {
        flow.value = ops.values.toList()
    }

    override fun observeAll(): Flow<List<SyncOperation>> = flow
    override suspend fun getAll(): List<SyncOperation> = ops.values.toList()
    override suspend fun count(): Int = ops.size
    override fun hasPending(): Flow<Boolean> = flow.map { it.isNotEmpty() }

    override suspend fun insert(op: SyncOperation) {
        ops[op.id] = op
        emit()
    }

    override suspend fun deleteById(id: String) {
        ops.remove(id)
        emit()
    }

    override suspend fun incrementRetry(id: String, error: String?) {
        ops[id]?.let {
            ops[id] = it.copy(retryCount = it.retryCount + 1, lastErrorMessage = error)
            emit()
        }
    }

    override suspend fun deleteByEntity(entityType: SyncEntityType, entityId: String) {
        ops.entries.removeIf { it.value.entityType == entityType && it.value.entityId == entityId }
        emit()
    }

    override suspend fun deleteAll() {
        ops.clear()
        emit()
    }
}

class FakeRecurringRuleDao : RecurringRuleDao {
    private val rules = mutableMapOf<String, RecurringRule>()
    private val flow = MutableStateFlow<List<RecurringRule>>(emptyList())

    private fun emit() {
        flow.value = rules.values.toList()
    }

    override suspend fun insertRule(rule: RecurringRule) {
        rules[rule.id] = rule
        emit()
    }

    override suspend fun updateRule(rule: RecurringRule) {
        rules[rule.id] = rule
        emit()
    }

    override suspend fun deleteRule(rule: RecurringRule) {
        rules.remove(rule.id)
        emit()
    }

    override suspend fun getRuleById(id: String): RecurringRule? = rules[id]

    override fun getAllRules(): Flow<List<RecurringRule>> = flow

    override fun getRulesForPerson(personId: Long): Flow<List<RecurringRule>> =
        flow.map { list -> list.filter { it.personId == personId } }

    override suspend fun getActiveDueRules(currentTime: Long): List<RecurringRule> =
        rules.values.filter { !it.isPaused && it.nextExecutionDate <= currentTime }

    override suspend fun deleteRuleById(id: String) {
        rules.remove(id)
        emit()
    }

    override suspend fun getAllRulesList(): List<RecurringRule> =
        rules.values.sortedBy { it.createdAt }
}

