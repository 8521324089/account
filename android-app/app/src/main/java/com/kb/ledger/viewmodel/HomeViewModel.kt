package com.kb.ledger.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.remote.AuthState
import com.kb.ledger.data.remote.GoogleAuthManager
import com.kb.ledger.data.dao.PersonBalanceEntity
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.repository.OfflineFirstPersonRepository
import com.kb.ledger.data.repository.OfflineFirstTransactionRepository
import com.kb.ledger.data.repository.toDomain
import com.kb.ledger.data.repository.toEntity
import com.kb.ledger.domain.model.PersonDomain
import com.kb.ledger.domain.repository.PersonRepository
import com.kb.ledger.domain.repository.SyncState
import com.kb.ledger.domain.repository.TransactionRepository
import com.kb.ledger.util.AppResult
import com.kb.ledger.util.NetworkMonitor
import com.kb.ledger.backup.partition.BackupStatus
import com.kb.ledger.backup.partition.LocalPartitionedBackupManager
import com.kb.ledger.sync.DriveSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

/**
 * ViewModel for the Home screen.
 * Survives configuration changes (device rotation).
 *
 * Coordinates between [PersonRepository] and [TransactionRepository].
 * Decoupled from direct Room DAO access.
 */
class HomeViewModel @JvmOverloads constructor(
    application: Application,
    val localBackupManager: LocalPartitionedBackupManager = LocalPartitionedBackupManager(application),
    val syncManager: DriveSyncManager = DriveSyncManager(application),
    private val personRepository: PersonRepository = OfflineFirstPersonRepository(
        AppDatabase.getDatabase(application).personDao(),
        AppDatabase.getDatabase(application).syncOperationDao(),
        AppDatabase.getDatabase(application).transactionDao(),
        localBackupManager = localBackupManager
    ),
    private val transactionRepository: TransactionRepository = OfflineFirstTransactionRepository(
        AppDatabase.getDatabase(application).transactionDao(),
        AppDatabase.getDatabase(application).personDao(),
        AppDatabase.getDatabase(application).syncOperationDao(),
        localBackupManager = localBackupManager
    ),
    val authManager: GoogleAuthManager = GoogleAuthManager(application)
) : AndroidViewModel(application) {

    val networkMonitor = NetworkMonitor(application)
    val authState: StateFlow<AuthState> = authManager.authState

    val syncState: StateFlow<SyncState> = syncManager.observeSyncState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncState.Idle)

    val pendingTxCount: StateFlow<Int> = syncManager.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val syncedTxCount: StateFlow<Int> = syncManager.observeSyncedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val backupStatus: StateFlow<BackupStatus> = localBackupManager.status

    init {
        if (authManager.isConfigured) {
            syncManager.schedulePeriodicSync()
        }
        viewModelScope.launch(Dispatchers.IO) {
            localBackupManager.reconcileWithRoom(AppDatabase.getDatabase(application))
        }
    }

    fun triggerSync() {
        viewModelScope.launch {
            if (!authManager.isConfigured) {
                // Reconcile local partitioned backup safely without network
                localBackupManager.reconcileWithRoom(AppDatabase.getDatabase(getApplication()))
                return@launch
            }
            syncManager.triggerSync()
        }
    }

    // ── Persons ──────────────────────────────────────────────────────────────

    /** All non-deleted persons (for global search). */
    val persons: StateFlow<List<Person>> = personRepository.observeAllPersons()
        .map { list -> list.map { it.toEntity() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All active persons (with or without transactions) for search. */
    val activePersons: StateFlow<List<Person>> = personRepository.observeActivePersons()
        .map { list -> list.map { it.toEntity() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Dashboard active persons: ONLY contacts with >= 1 transaction. */
    val dashboardActivePersons: StateFlow<List<Person>> = personRepository.observeDashboardActivePersons()
        .map { list -> list.map { it.toEntity() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All archived persons (with or without transactions) for search. */
    val archivedPersons: StateFlow<List<Person>> = personRepository.observeArchivedPersons()
        .map { list -> list.map { it.toEntity() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Dashboard archived persons: ONLY archived contacts with >= 1 transaction. */
    val dashboardArchivedPersons: StateFlow<List<Person>> = personRepository.observeDashboardArchivedPersons()
        .map { list -> list.map { it.toEntity() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filterTab = MutableStateFlow(ContactFilterTab.ACTIVE)
    val filterTab: StateFlow<ContactFilterTab> = _filterTab.asStateFlow()

    fun setFilterTab(tab: ContactFilterTab) {
        _filterTab.value = tab
    }

    val recentPersons: StateFlow<List<Person>> = personRepository.observeRecentPersons()
        .map { list -> list.map { it.toEntity() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Balances & Transactions ──────────────────────────────────────────────

    val allBalances: StateFlow<List<PersonBalanceEntity>> = transactionRepository.observeAllBalances()
        .map { list -> list.map { PersonBalanceEntity(it.personLocalId, it.balanceCents) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalGiveCents: StateFlow<Long> = transactionRepository.observeTotalGive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalYouGiveCents: StateFlow<Long> = transactionRepository.observeTotalYouGive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalYouGetCents: StateFlow<Long> = transactionRepository.observeTotalYouGet()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * Map of personId -> latest transaction timestamp (createdAt).
     * Used for sorting search results (active transaction contacts first).
     */
    val lastTxTimes: StateFlow<Map<Long, Long>> = transactionRepository.observeAllTransactions()
        .map { txList ->
            txList.groupBy { it.personLocalId }
                .mapValues { (_, txs) -> txs.maxOf { it.createdAt } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Calculates local start and end of day boundaries in the device's current timezone.
     */
    fun getLocalDayBoundaries(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis
        return Pair(start, end)
    }

    /**
     * Dynamically computes Received Today using RECEIVE transactions falling within
     * the device's local calendar day boundaries. Reacts immediately to transaction additions,
     * edits, and deletions.
     */
    val totalReceiveTodayCents: StateFlow<Long> = transactionRepository.observeAllTransactions()
        .map { txList ->
            val (start, end) = getLocalDayBoundaries()
            txList.filter {
                it.type == com.kb.ledger.domain.model.TransactionType.RECEIVE && it.createdAt in start..end
            }.sumOf { it.amountCents }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // ── Actions ───────────────────────────────────────────────────────────────

    fun addPerson(name: String, phone: String, onComplete: (Person) -> Unit = {}) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val domainPerson = PersonDomain(
                name = name,
                phone = phone,
                cloudId = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
            val result = personRepository.savePerson(domainPerson)
            if (result is AppResult.Success) {
                onComplete(result.data.toEntity())
            }
        }
    }

    fun archivePerson(person: Person) {
        viewModelScope.launch {
            personRepository.archivePerson(person.id)
        }
    }

    fun restorePerson(person: Person) {
        viewModelScope.launch {
            personRepository.restorePerson(person.id)
        }
    }

    fun deletePerson(person: Person) {
        viewModelScope.launch {
            personRepository.deletePerson(person.id)
        }
    }

    fun deleteZeroBalancePerson(person: Person, onComplete: (AppResult<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = personRepository.deleteZeroBalancePerson(person.id)
            onComplete(result)
        }
    }

    fun signIn(activityContext: android.content.Context, onComplete: (AppResult<String>) -> Unit = {}) {
        if (!authManager.isConfigured) {
            onComplete(AppResult.Error("Google Sign-In is unavailable in this release. All data is saved locally."))
            return
        }
        viewModelScope.launch {
            val res = authManager.signIn(activityContext)
            onComplete(res)
        }
    }

    fun signOut(onComplete: (AppResult<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val res = authManager.signOut()
            onComplete(res)
        }
    }
}

enum class ContactFilterTab {
    ACTIVE,
    ARCHIVED
}
