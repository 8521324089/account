package com.kb.ledger.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.remote.GoogleAuthManager
import com.kb.ledger.domain.repository.SyncRepository
import com.kb.ledger.domain.repository.SyncState
import com.kb.ledger.util.AppResult
import com.kb.ledger.util.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Production implementation of [SyncRepository] coordinating Drive sync lifecycle via WorkManager.
 */
class DriveSyncManager(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) : SyncRepository {

    private val workManager = WorkManager.getInstance(context)
    private val networkMonitor = NetworkMonitor(context)

    override fun observeSyncState(): Flow<SyncState> {
        return DriveSyncWorker.globalSyncState
    }

    /**
     * Reactively observes the number of transactions currently pending synchronization.
     * Guaranteed to reflect persistent Room database state.
     */
    fun observePendingCount(): Flow<Int> {
        return database.syncOperationDao().observeAll().map { ops ->
            ops.count { it.entityType == com.kb.ledger.data.model.SyncEntityType.TRANSACTION }
        }
    }

    /**
     * Reactively observes the number of transactions successfully synchronized to the cloud.
     */
    fun observeSyncedCount(): Flow<Int> {
        return database.transactionDao().observeAllTransactions().map { list ->
            list.count { !it.syncPending && !it.isDeleted }
        }
    }

    override suspend fun triggerSync(): AppResult<Unit> {
        val authManager = GoogleAuthManager(context)
        if (!authManager.isConfigured) {
            DriveSyncWorker.updateSyncState(SyncState.Offline)
            return AppResult.Error("Cloud synchronization is unavailable in this release. All data is saved locally.")
        }

        if (!networkMonitor.hasActiveNetwork()) {
            DriveSyncWorker.updateSyncState(SyncState.Offline)
            return AppResult.Error("Device is offline. Changes are saved locally and will sync when online.")
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            DriveSyncWorker.WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )

        return AppResult.Success(Unit)
    }

    /**
     * Sets up periodic background sync every 6 hours while connected to unmetered or metered network.
     */
    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<DriveSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            DriveSyncWorker.WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }

    override suspend fun hasPendingOperations(): Boolean {
        return database.syncOperationDao().count() > 0 ||
                database.transactionDao().getPendingSyncTransactions().isNotEmpty()
    }
}
