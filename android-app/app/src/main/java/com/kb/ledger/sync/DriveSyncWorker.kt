package com.kb.ledger.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kb.ledger.backup.partition.LocalPartitionedBackupManager
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.json.PersonJson
import com.kb.ledger.data.json.TransactionJson
import com.kb.ledger.data.model.SyncEntityType
import com.kb.ledger.data.model.SyncOperationType
import com.kb.ledger.data.remote.DriveApiService
import com.kb.ledger.data.remote.GoogleAuthManager
import com.kb.ledger.data.remote.GoogleDriveRepository
import com.kb.ledger.data.remote.OkHttpDriveApiService
import com.kb.ledger.data.remote.RemoteLedgerCheckResult
import com.kb.ledger.domain.repository.SyncState
import com.kb.ledger.util.AppResult
import com.kb.ledger.util.BillImageManager
import com.kb.ledger.util.PhoneAddressBookResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * WorkManager worker that executes durable, offline-resilient synchronization with Google Drive.
 *
 * ## Guarantees
 * 1. Bill images are uploaded first so transactions never reference dangling remote image IDs.
 * 2. Multi-file commitment: data files are written before manifest.json.
 * 3. Soft-deleted tombstones are preserved until the cloud acknowledges them.
 * 4. Local Room records are only marked `syncPending = false` after Drive returns success.
 * 5. On failure, operations remain durably queued in `sync_operations` with retry backoff.
 */
class DriveSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME_PERIODIC = "ledger_periodic_drive_sync"
        const val WORK_NAME_ONE_TIME = "ledger_immediate_drive_sync"

        private val _globalSyncState = MutableStateFlow<SyncState>(SyncState.Idle)
        val globalSyncState: StateFlow<SyncState> = _globalSyncState.asStateFlow()

        fun updateSyncState(state: SyncState) {
            _globalSyncState.value = state
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val authManager = GoogleAuthManager(context)
        if (!authManager.isConfigured) {
            updateSyncState(SyncState.Offline)
            return@withContext Result.success()
        }
        val accountEmail = authManager.currentAccountEmail

        if (accountEmail.isNullOrBlank()) {
            updateSyncState(SyncState.NotAuthenticated)
            return@withContext Result.success()
        }

        val tokenResult = authManager.getDriveAccessToken(accountEmail)
        if (tokenResult !is AppResult.Success) {
            updateSyncState(SyncState.NotAuthenticated)
            return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        val token: String = tokenResult.data

        updateSyncState(SyncState.Syncing)

        val database = AppDatabase.getDatabase(context)
        val billImageManager = BillImageManager(context)
        val driveApiService: DriveApiService = OkHttpDriveApiService()
        val googleDriveRepository = GoogleDriveRepository(driveApiService)

        val syncEngine = DriveSyncEngine(
            driveApiService = driveApiService,
            googleDriveRepository = googleDriveRepository,
            personDao = database.personDao(),
            transactionDao = database.transactionDao(),
            recurringRuleDao = database.recurringRuleDao(),
            syncOperationDao = database.syncOperationDao(),
            addressBookResolver = PhoneAddressBookResolver(context),
            localBackupManager = LocalPartitionedBackupManager(context),
            resolveImageFile = { txId, imageUri ->
                resolveReceiptFile(context, billImageManager, txId, imageUri)
            }
        )

        try {
            val summary = syncEngine.executeSync(token, accountEmail)

            if (!summary.success) {
                updateSyncState(SyncState.Error(summary.networkErrors.firstOrNull() ?: "Sync failed"))
                return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
            }

            if (summary.networkErrors.isNotEmpty()) {
                updateSyncState(SyncState.Error(summary.networkErrors.first()))
                return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
            }

            val now = System.currentTimeMillis()
            updateSyncState(SyncState.Success(now))
            Result.success()
        } catch (e: Exception) {
            updateSyncState(SyncState.Error(e.message ?: "Unknown sync failure"))
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun resolveReceiptFile(
        context: Context,
        billImageManager: BillImageManager,
        txId: String,
        imageUri: String?
    ): File? {
        if (imageUri.isNullOrBlank()) return null

        // 1. Direct file path check (if not content URI)
        if (!imageUri.startsWith("content://")) {
            val file = File(imageUri)
            if (file.exists() && file.length() > 0) return file
        }

        // 2. Canonical app-private receipts directory
        val internalFile = billImageManager.internalFileForTx(txId)
        if (internalFile.exists() && internalFile.length() > 0) return internalFile

        // 3. Local partitioned backup replica
        val backupFile = File(context.filesDir, "backup/images/$txId.jpg")
        if (backupFile.exists() && backupFile.length() > 0) return backupFile

        // 4. If legacy content URI, attempt reading via ContentResolver
        if (imageUri.startsWith("content://")) {
            try {
                val uri = android.net.Uri.parse(imageUri)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val target = billImageManager.internalFileForTx(txId)
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                    if (target.exists() && target.length() > 0) return target
                }
            } catch (_: Exception) {
                // Permission expired or provider error
            }
        }

        return null
    }
}
