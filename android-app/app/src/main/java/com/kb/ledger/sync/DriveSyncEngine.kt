package com.kb.ledger.sync

import com.kb.ledger.backup.partition.LocalPartitionedBackupManager
import com.kb.ledger.data.dao.PersonDao
import com.kb.ledger.data.dao.RecurringRuleDao
import com.kb.ledger.data.dao.SyncOperationDao
import com.kb.ledger.data.dao.TransactionDao
import com.kb.ledger.data.json.JsonSchemaValidator
import com.kb.ledger.data.json.PersonJson
import com.kb.ledger.data.json.TransactionJson
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.SyncEntityType
import com.kb.ledger.data.model.SyncOperation
import com.kb.ledger.data.model.SyncOperationType
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.data.remote.DriveApiService
import com.kb.ledger.data.remote.GoogleDriveRepository
import com.kb.ledger.data.remote.RemoteLedger
import com.kb.ledger.data.remote.RemoteLedgerCheckResult
import com.kb.ledger.util.AddressBookContactResolver
import com.kb.ledger.util.AppResult
import com.kb.ledger.util.ContactResolutionResult
import com.kb.ledger.util.ContactSearchHelper
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Summary of a synchronization pass executed by [DriveSyncEngine].
 */
data class SyncResultSummary(
    val success: Boolean,
    val syncedPersonsCount: Int,
    val syncedTransactionsCount: Int,
    val blockedTransactionsCount: Int,
    val networkErrors: List<String>,
    val missingFileDiagnostics: Map<String, String>
)

/**
 * Orchestrates bidirectional synchronization between local Room storage and Google Drive AppDataFolder.
 *
 * ## Data-Safety & Owner Name Resolution Invariants:
 * 1. Transactions without receipts sync normally.
 * 2. Transactions referencing a receipt are never marked synced until the receipt is uploaded
 *    or its cloud attachment is verified.
 * 3. Missing or unreadable receipts leave the transaction pending with a clear diagnostic in [SyncOperationDao].
 * 4. Inbound staff-created records are reconciled against the OWNER's address book:
 *    - If exactly one owner contact matches by normalized phone, update ONLY the person's displayed name
 *      in the owner's database.
 *    - If no match, ambiguous matches, or contact permission is denied, preserve the staff-supplied name.
 *    - Reuses existing Ledger persons when identity is established; never creates duplicates.
 *    - Never modifies stable IDs, phone numbers, transaction foreign keys, balances, or creation dates.
 * 5. Updates the local partitioned backup safely without corrupting transaction partitions.
 * 6. Repeated synchronization is idempotent.
 */
class DriveSyncEngine(
    private val driveApiService: DriveApiService,
    private val googleDriveRepository: GoogleDriveRepository,
    private val personDao: PersonDao,
    private val transactionDao: TransactionDao,
    private val recurringRuleDao: RecurringRuleDao,
    private val syncOperationDao: SyncOperationDao,
    private val addressBookResolver: AddressBookContactResolver? = null,
    private val localBackupManager: LocalPartitionedBackupManager? = null,
    private val resolveImageFile: suspend (txId: String, imageUri: String?) -> File?
) {

    suspend fun executeSync(token: String, accountEmail: String): SyncResultSummary {
        val pendingTxs = transactionDao.getPendingSyncTransactions()
        val blockedTxIds = mutableSetOf<String>()
        val networkErrors = mutableListOf<String>()
        val missingFileDiagnostics = mutableMapOf<String, String>()

        // 1. Process receipt images for pending transactions
        for (tx in pendingTxs) {
            if (tx.isDeleted) continue
            val hasImageRef = !tx.imageUri.isNullOrBlank()
            val hasCloudAttachment = !tx.imageFileId.isNullOrBlank()

            if (hasImageRef && !hasCloudAttachment) {
                val localImage = resolveImageFile(tx.id, tx.imageUri)
                if (localImage != null && localImage.exists() && localImage.length() > 0) {
                    try {
                        val uploadResult = driveApiService.createAppDataBinaryFile(
                            accessToken = token,
                            name = "receipt_${tx.id}.jpg",
                            bytes = localImage.readBytes(),
                            mimeType = "image/jpeg"
                        )
                        when (uploadResult) {
                            is AppResult.Success -> {
                                transactionDao.updateImageFileId(tx.id, uploadResult.data.id)
                            }
                            is AppResult.Error -> {
                                blockedTxIds.add(tx.id)
                                networkErrors.add("Image upload failed for ${tx.id}: ${uploadResult.message}")
                                recordSyncDiagnostic(tx.id, "Image upload failed: ${uploadResult.message}")
                            }
                        }
                    } catch (e: Exception) {
                        blockedTxIds.add(tx.id)
                        networkErrors.add("Image upload exception for ${tx.id}: ${e.message}")
                        recordSyncDiagnostic(tx.id, "Image upload error: ${e.message}")
                    }
                } else {
                    // Receipt file is missing or unreadable
                    blockedTxIds.add(tx.id)
                    val diagnostic = if (tx.imageUri?.startsWith("content://") == true) {
                        "Legacy receipt URI expired or inaccessible; tap to replace receipt before syncing"
                    } else {
                        "Receipt file missing or unreadable (${tx.imageUri}); tap to replace receipt before syncing"
                    }
                    missingFileDiagnostics[tx.id] = diagnostic
                    recordSyncDiagnostic(tx.id, diagnostic)
                }
            }
        }

        // 2. Check remote cloud ledger state
        val checkResult = googleDriveRepository.checkRemoteLedger(token, accountEmail)
        if (checkResult is AppResult.Error) {
            networkErrors.add(checkResult.message)
            return SyncResultSummary(
                success = false,
                syncedPersonsCount = 0,
                syncedTransactionsCount = 0,
                blockedTransactionsCount = blockedTxIds.size,
                networkErrors = networkErrors,
                missingFileDiagnostics = missingFileDiagnostics
            )
        }

        val remoteState = (checkResult as AppResult.Success).data
        val remoteLedger = if (remoteState is RemoteLedgerCheckResult.Found) remoteState.remoteLedger else null

        // 3. Inbound Reconciliation (Staff -> Owner Address Book Resolution)
        if (remoteLedger != null) {
            reconcileInboundRemoteLedger(remoteLedger)
        }

        // 4. Prepare local DTOs for cloud update
        val allPersons = personDao.getAllPersonsIncludingDeleted()
        val formatIsoUtc: (Long) -> String = { epochMilli ->
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.format(java.util.Date(epochMilli))
        }

        val personCloudIdMap = allPersons.associate { p ->
            val resolvedCloudId = if (!p.cloudId.isNullOrBlank() && JsonSchemaValidator.isValidUuid(p.cloudId)) {
                p.cloudId
            } else {
                UUID.nameUUIDFromBytes("person_${p.id}".toByteArray()).toString()
            }
            p.id to resolvedCloudId
        }

        val remotePersonMap = remoteLedger?.persons?.associateBy { it.cloudId } ?: emptyMap()

        val personsJsonList = allPersons.map { p ->
            val resolvedCloudId = personCloudIdMap[p.id]!!
            val remotePerson = remotePersonMap[resolvedCloudId]
            // Preserve staff-supplied name in cloud ledger if established remotely
            val cloudName = remotePerson?.name ?: p.name

            PersonJson(
                cloudId = resolvedCloudId,
                localId = p.id,
                name = cloudName,
                phone = p.phone,
                contactId = p.contactId,
                createdAt = formatIsoUtc(p.createdAt),
                updatedAt = formatIsoUtc(p.updatedAt),
                updatedBy = null,
                isDeleted = p.isDeleted
            )
        }

        // Prepare transactions DTOs (re-fetched so any newly assigned imageFileId is present)
        val allTxs = transactionDao.getAllTransactionsIncludingDeleted()
        val eligibleTxs = allTxs.filter { !blockedTxIds.contains(it.id) }
        val txJsonList = eligibleTxs.map { t ->
            val personCloudId = personCloudIdMap[t.personId]
                ?: UUID.nameUUIDFromBytes("person_${t.personId}".toByteArray()).toString()
            val resolvedTxId = if (JsonSchemaValidator.isValidUuid(t.id)) {
                t.id
            } else {
                UUID.nameUUIDFromBytes("tx_${t.id}".toByteArray()).toString()
            }
            TransactionJson(
                id = resolvedTxId,
                personCloudId = personCloudId,
                amountCents = t.amountCents,
                type = t.type.name,
                note = t.note,
                imageFileId = t.imageFileId,
                createdAt = formatIsoUtc(t.createdAt),
                updatedAt = formatIsoUtc(t.updatedAt),
                updatedBy = t.updatedBy,
                isDeleted = t.isDeleted
            )
        }

        // 5. Update cloud ledger
        val pushResult = when (remoteState) {
            is RemoteLedgerCheckResult.NotFound,
            is RemoteLedgerCheckResult.InterruptedInitialization -> {
                googleDriveRepository.initializeCloudLedger(
                    accessToken = token,
                    accountEmail = accountEmail,
                    initialPersons = personsJsonList,
                    initialTransactions = txJsonList
                )
            }
            is RemoteLedgerCheckResult.Found -> {
                googleDriveRepository.updateCloudLedger(
                    accessToken = token,
                    accountEmail = accountEmail,
                    existingLedger = remoteState.remoteLedger,
                    updatedPersons = personsJsonList,
                    updatedTransactions = txJsonList
                )
            }
        }

        if (pushResult is AppResult.Error) {
            networkErrors.add(pushResult.message)
            val pendingOps = syncOperationDao.getAll()
            for (op in pendingOps) {
                syncOperationDao.incrementRetry(op.id, pushResult.message)
            }
            return SyncResultSummary(
                success = false,
                syncedPersonsCount = 0,
                syncedTransactionsCount = 0,
                blockedTransactionsCount = blockedTxIds.size,
                networkErrors = networkErrors,
                missingFileDiagnostics = missingFileDiagnostics
            )
        }

        // 6. Sync recurring rules (best effort)
        try {
            val allRules = recurringRuleDao.getAllRulesList()
            val rulesJson = com.google.gson.Gson().toJson(allRules)
            val appDataFilesResult = driveApiService.listAppDataFiles(token)
            if (appDataFilesResult is AppResult.Success) {
                val existingRuleFile = appDataFilesResult.data.find { it.name == "recurring_rules.json" }
                if (existingRuleFile != null) {
                    driveApiService.updateFileContent(token, existingRuleFile.id, rulesJson)
                } else if (allRules.isNotEmpty()) {
                    driveApiService.createAppDataFile(token, "recurring_rules.json", rulesJson)
                }
            }
        } catch (_: Exception) {
            // Best effort
        }

        // 7. Mark eligible records as synced in Room and clear completed sync operations
        for (p in allPersons) {
            personDao.markSynced(p.id)
            syncOperationDao.deleteByEntity(SyncEntityType.PERSON, p.cloudId ?: p.id.toString())
        }

        var syncedTxCount = 0
        for (t in eligibleTxs) {
            transactionDao.markSynced(t.id)
            syncOperationDao.deleteByEntity(SyncEntityType.TRANSACTION, t.id)
            syncedTxCount++
        }

        return SyncResultSummary(
            success = true,
            syncedPersonsCount = allPersons.size,
            syncedTransactionsCount = syncedTxCount,
            blockedTransactionsCount = blockedTxIds.size,
            networkErrors = networkErrors,
            missingFileDiagnostics = missingFileDiagnostics
        )
    }

    /**
     * Inbound reconciliation reconciling remote persons & transactions into the owner's database.
     * Implements Owner Address Book Name Resolution according to business requirements.
     */
    private suspend fun reconcileInboundRemoteLedger(remoteLedger: RemoteLedger): Boolean {
        var hasLocalMutation = false
        val allLocalPersons = personDao.getAllPersonsIncludingDeleted()
        val localByCloudId = allLocalPersons.filter { !it.cloudId.isNullOrBlank() }
            .associateBy { it.cloudId!! }.toMutableMap()
        val localByNormPhone = allLocalPersons.filter { it.phone.isNotBlank() }
            .groupBy { ContactSearchHelper.normalizePhone(it.phone) }

        // A. Reconcile Persons
        for (rp in remoteLedger.persons) {
            val normPhone = ContactSearchHelper.normalizePhone(rp.phone)
            val resolution = addressBookResolver?.resolveContact(rp.phone) ?: ContactResolutionResult.NoMatch

            val localMatch = localByCloudId[rp.cloudId]
            if (localMatch != null) {
                // Identity established by cloudId
                val targetName = when (resolution) {
                    is ContactResolutionResult.Match -> resolution.displayName
                    is ContactResolutionResult.NoMatch,
                    is ContactResolutionResult.Ambiguous,
                    is ContactResolutionResult.PermissionDeniedOrError -> {
                        localMatch.name.ifBlank { rp.name }
                    }
                }
                if (targetName != localMatch.name) {
                    personDao.updatePersonName(localMatch.id, targetName)
                    localByCloudId[rp.cloudId] = localMatch.copy(name = targetName)
                    hasLocalMutation = true
                }
            } else {
                // Check if an existing local person matches by phone without cloudId
                val candidates = localByNormPhone[normPhone].orEmpty()
                val candidateWithoutCloudId = candidates.singleOrNull { it.cloudId.isNullOrBlank() }

                if (candidateWithoutCloudId != null) {
                    // Reuse existing local person
                    val targetName = when (resolution) {
                        is ContactResolutionResult.Match -> resolution.displayName
                        is ContactResolutionResult.NoMatch,
                        is ContactResolutionResult.Ambiguous,
                        is ContactResolutionResult.PermissionDeniedOrError -> {
                            candidateWithoutCloudId.name.ifBlank { rp.name }
                        }
                    }
                    if (targetName != candidateWithoutCloudId.name) {
                        personDao.updatePersonName(candidateWithoutCloudId.id, targetName)
                        hasLocalMutation = true
                    }
                    personDao.updatePersonCloudId(candidateWithoutCloudId.id, rp.cloudId)
                    localByCloudId[rp.cloudId] = candidateWithoutCloudId.copy(
                        name = targetName,
                        cloudId = rp.cloudId
                    )
                    hasLocalMutation = true
                } else {
                    // Brand new person from staff
                    val targetName = when (resolution) {
                        is ContactResolutionResult.Match -> resolution.displayName
                        is ContactResolutionResult.NoMatch,
                        is ContactResolutionResult.Ambiguous,
                        is ContactResolutionResult.PermissionDeniedOrError -> rp.name
                    }
                    val newPerson = Person(
                        name = targetName,
                        phone = rp.phone,
                        contactId = rp.contactId,
                        cloudId = rp.cloudId,
                        createdAt = parseIsoUtc(rp.createdAt),
                        updatedAt = parseIsoUtc(rp.updatedAt),
                        updatedBy = rp.updatedBy,
                        isDeleted = rp.isDeleted,
                        syncPending = false
                    )
                    val newId = personDao.insertPerson(newPerson)
                    localByCloudId[rp.cloudId] = newPerson.copy(id = newId)
                    hasLocalMutation = true
                }
            }
        }

        // B. Reconcile Transactions
        for (rtx in remoteLedger.transactions) {
            val localPerson = localByCloudId[rtx.personCloudId] ?: continue
            val existingTx = transactionDao.getTransactionById(rtx.id)
            if (existingTx == null) {
                val txType = try {
                    TransactionType.valueOf(rtx.type.trim().uppercase())
                } catch (_: Exception) {
                    TransactionType.RECEIVE
                }
                val newTx = Transaction(
                    id = rtx.id,
                    personId = localPerson.id,
                    amountCents = rtx.amountCents,
                    type = txType,
                    note = rtx.note,
                    imageUri = null,
                    imageFileId = rtx.imageFileId,
                    createdAt = parseIsoUtc(rtx.createdAt),
                    updatedAt = parseIsoUtc(rtx.updatedAt),
                    updatedBy = rtx.updatedBy,
                    isDeleted = rtx.isDeleted,
                    syncPending = false
                )
                transactionDao.insertTransaction(newTx)
                localBackupManager?.recordTransactionMutation(newTx)
                hasLocalMutation = true
            }
        }

        // C. Safely update local partitioned backup if persons changed
        if (hasLocalMutation) {
            localBackupManager?.recordPersons(personDao.getAllPersonsList())
        }

        return hasLocalMutation
    }

    private fun parseIsoUtc(isoString: String?): Long {
        if (isoString.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            Instant.parse(isoString).toEpochMilli()
        } catch (_: Exception) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.parse(isoString)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }

    private suspend fun recordSyncDiagnostic(txId: String, diagnostic: String) {
        val existingOps = syncOperationDao.getAll().filter {
            it.entityType == SyncEntityType.TRANSACTION && it.entityId == txId
        }
        if (existingOps.isNotEmpty()) {
            for (op in existingOps) {
                syncOperationDao.incrementRetry(op.id, diagnostic)
            }
        } else {
            syncOperationDao.insert(
                SyncOperation(
                    type = SyncOperationType.UPSERT,
                    entityType = SyncEntityType.TRANSACTION,
                    entityId = txId,
                    lastErrorMessage = diagnostic,
                    retryCount = 1
                )
            )
        }
    }
}
