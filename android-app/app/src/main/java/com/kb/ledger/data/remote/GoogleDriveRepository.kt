package com.kb.ledger.data.remote

import com.kb.ledger.data.json.JsonSchemaValidator
import com.kb.ledger.data.json.LedgerJsonSerializer
import com.kb.ledger.data.json.ManifestJson
import com.kb.ledger.data.json.PersonJson
import com.kb.ledger.data.json.TransactionJson
import com.kb.ledger.domain.model.PersonDomain
import com.kb.ledger.domain.model.TransactionDomain
import com.kb.ledger.util.AppResult
import com.kb.ledger.util.Clock
import com.kb.ledger.util.SystemClock
import java.util.UUID

/**
 * Result of checking remote Google Drive storage.
 */
sealed class RemoteLedgerCheckResult {
    /** Files exist and passed schema validation. */
    data class Found(val remoteLedger: RemoteLedger) : RemoteLedgerCheckResult()

    /** No manifest.json exists in AppDataFolder — fresh account. */
    object NotFound : RemoteLedgerCheckResult()

    /** Incomplete initialization found (e.g., persons.json exists but manifest was never committed). */
    data class InterruptedInitialization(val existingFiles: List<DriveFileMetadata>) : RemoteLedgerCheckResult()
}

/**
 * High-level repository coordinating Google Drive AppDataFolder operations.
 *
 * Design guarantees:
 * - Isolated per Google account (requires verified account email).
 * - Multi-file write-ahead commitment: data files (persons.json, transactions.json) are written
 *   before manifest.json to ensure interrupted writes never leave corrupted manifests.
 * - Deduplication: cleans up duplicate files with identical names from past interrupted retries.
 * - Validation gate: every downloaded file must pass [JsonSchemaValidator] before acceptance.
 * - Overwrite protection: prevents silently blowing away remote data with empty local state.
 */
class GoogleDriveRepository(
    private val driveApiService: DriveApiService,
    private val serializer: LedgerJsonSerializer = LedgerJsonSerializer(),
    private val validator: JsonSchemaValidator = JsonSchemaValidator,
    private val clock: Clock = SystemClock(),
    private val appVersion: String = "1.0"
) {

    companion object {
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val PERSONS_FILE_NAME = "persons.json"
        const val TRANSACTIONS_FILE_NAME = "transactions.json"
    }

    /**
     * Checks the authenticated account's AppDataFolder for an existing ledger.
     */
    suspend fun checkRemoteLedger(
        accessToken: String,
        accountEmail: String
    ): AppResult<RemoteLedgerCheckResult> {
        if (accountEmail.isBlank()) {
            return AppResult.Error("ACCOUNT_REQUIRED: Account email must not be blank")
        }

        val listResult = driveApiService.listAppDataFiles(accessToken)
        if (listResult is AppResult.Error) {
            return listResult
        }

        val files = (listResult as AppResult.Success).data
        val manifestFile = findNewestFile(files, MANIFEST_FILE_NAME)
        val personsFile = findNewestFile(files, PERSONS_FILE_NAME)
        val transactionsFile = findNewestFile(files, TRANSACTIONS_FILE_NAME)

        if (manifestFile == null) {
            return if (personsFile != null || transactionsFile != null) {
                // Interrupted state: data files exist but manifest was never committed
                AppResult.Success(RemoteLedgerCheckResult.InterruptedInitialization(files))
            } else {
                AppResult.Success(RemoteLedgerCheckResult.NotFound)
            }
        }

        // If manifest exists, download and validate the complete ledger
        val fetchResult = fetchCompleteLedger(accessToken, manifestFile, personsFile, transactionsFile)
        return when (fetchResult) {
            is AppResult.Success -> AppResult.Success(RemoteLedgerCheckResult.Found(fetchResult.data))
            is AppResult.Error -> fetchResult
        }
    }

    /**
     * Downloads and validates an existing remote ledger.
     */
    suspend fun fetchRemoteLedger(
        accessToken: String,
        accountEmail: String
    ): AppResult<RemoteLedger> {
        if (accountEmail.isBlank()) {
            return AppResult.Error("ACCOUNT_REQUIRED: Account email must not be blank")
        }

        val listResult = driveApiService.listAppDataFiles(accessToken)
        if (listResult is AppResult.Error) return listResult

        val files = (listResult as AppResult.Success).data
        val manifestFile = findNewestFile(files, MANIFEST_FILE_NAME)
            ?: return AppResult.Error("LEDGER_NOT_FOUND: No manifest.json found in AppDataFolder for $accountEmail")
        val personsFile = findNewestFile(files, PERSONS_FILE_NAME)
            ?: return AppResult.Error("INCOMPLETE_LEDGER: manifest.json exists but persons.json is missing")
        val transactionsFile = findNewestFile(files, TRANSACTIONS_FILE_NAME)
            ?: return AppResult.Error("INCOMPLETE_LEDGER: manifest.json exists but transactions.json is missing")

        return fetchCompleteLedger(accessToken, manifestFile, personsFile, transactionsFile)
    }

    private suspend fun fetchCompleteLedger(
        accessToken: String,
        manifestFile: DriveFileMetadata,
        personsFile: DriveFileMetadata?,
        transactionsFile: DriveFileMetadata?
    ): AppResult<RemoteLedger> {
        if (personsFile == null || transactionsFile == null) {
            return AppResult.Error("INCOMPLETE_LEDGER: Missing persons.json or transactions.json file")
        }

        // 1. Download manifest
        val manifestDownload = driveApiService.downloadFileContent(accessToken, manifestFile.id)
        if (manifestDownload is AppResult.Error) return manifestDownload
        val manifestResult = serializer.deserializeManifest((manifestDownload as AppResult.Success).data)
        if (manifestResult is AppResult.Error) return manifestResult
        val manifest = (manifestResult as AppResult.Success).data
        val manifestValidation = validator.validateManifest(manifest)
        if (manifestValidation is AppResult.Error) return manifestValidation

        // 2. Download persons
        val personsDownload = driveApiService.downloadFileContent(accessToken, personsFile.id)
        if (personsDownload is AppResult.Error) return personsDownload
        val personsResult = serializer.deserializePersons((personsDownload as AppResult.Success).data)
        if (personsResult is AppResult.Error) return personsResult
        val persons = (personsResult as AppResult.Success).data
        val personsValidation = validator.validatePersons(persons)
        if (personsValidation is AppResult.Error) return personsValidation

        // 3. Download transactions
        val txDownload = driveApiService.downloadFileContent(accessToken, transactionsFile.id)
        if (txDownload is AppResult.Error) return txDownload
        val txResult = serializer.deserializeTransactions((txDownload as AppResult.Success).data)
        if (txResult is AppResult.Error) return txResult
        val transactions = (txResult as AppResult.Success).data
        val personCloudIds = persons.map { it.cloudId }.toSet()
        val txValidation = validator.validateTransactions(transactions, personCloudIds)
        if (txValidation is AppResult.Error) return txValidation

        return AppResult.Success(
            RemoteLedger(
                manifest = manifest,
                manifestFileId = manifestFile.id,
                persons = persons,
                personsFileId = personsFile.id,
                transactions = transactions,
                transactionsFileId = transactionsFile.id
            )
        )
    }

    /**
     * Initializes a fresh cloud ledger in AppDataFolder.
     * Writes data files first, then commits manifest.json.
     */
    suspend fun initializeCloudLedger(
        accessToken: String,
        accountEmail: String,
        initialPersons: List<PersonJson> = emptyList(),
        initialTransactions: List<TransactionJson> = emptyList()
    ): AppResult<RemoteLedger> {
        if (accountEmail.isBlank()) {
            return AppResult.Error("ACCOUNT_REQUIRED: Account email must not be blank")
        }

        // Clean up any stale partial files before initialization
        cleanupDuplicateFiles(accessToken)

        val nowIso = clock.nowIso8601()
        val ledgerId = UUID.randomUUID().toString()

        // 1. Create persons.json
        val personsJson = serializer.serializePersons(initialPersons)
        val personsFileResult = driveApiService.createAppDataFile(
            accessToken = accessToken,
            name = PERSONS_FILE_NAME,
            content = personsJson
        )
        if (personsFileResult is AppResult.Error) return personsFileResult
        val personsFile = (personsFileResult as AppResult.Success).data

        // 2. Create transactions.json
        val txJson = serializer.serializeTransactions(initialTransactions)
        val txFileResult = driveApiService.createAppDataFile(
            accessToken = accessToken,
            name = TRANSACTIONS_FILE_NAME,
            content = txJson
        )
        if (txFileResult is AppResult.Error) {
            // Rollback persons.json to avoid orphaned files
            driveApiService.deleteFile(accessToken, personsFile.id)
            return txFileResult
        }
        val txFile = (txFileResult as AppResult.Success).data

        // 3. Commit manifest.json last (the atomic commitment point)
        val manifest = ManifestJson(
            schemaVersion = LedgerJsonSerializer.CURRENT_MANIFEST_VERSION,
            appVersion = appVersion,
            ledgerId = ledgerId,
            ownerId = accountEmail,
            createdAt = nowIso,
            lastSyncAt = nowIso
        )
        val manifestJson = serializer.serializeManifest(manifest)
        val manifestFileResult = driveApiService.createAppDataFile(
            accessToken = accessToken,
            name = MANIFEST_FILE_NAME,
            content = manifestJson
        )
        if (manifestFileResult is AppResult.Error) {
            // Rollback data files
            driveApiService.deleteFile(accessToken, personsFile.id)
            driveApiService.deleteFile(accessToken, txFile.id)
            return manifestFileResult
        }
        val manifestFile = (manifestFileResult as AppResult.Success).data

        return AppResult.Success(
            RemoteLedger(
                manifest = manifest,
                manifestFileId = manifestFile.id,
                persons = initialPersons,
                personsFileId = personsFile.id,
                transactions = initialTransactions,
                transactionsFileId = txFile.id
            )
        )
    }

    /**
     * Updates an existing cloud ledger with updated persons and transactions.
     * Prevents empty overwrites if remote data was non-empty.
     */
    suspend fun updateCloudLedger(
        accessToken: String,
        accountEmail: String,
        existingLedger: RemoteLedger,
        updatedPersons: List<PersonJson>,
        updatedTransactions: List<TransactionJson>
    ): AppResult<RemoteLedger> {
        if (accountEmail.isBlank()) {
            return AppResult.Error("ACCOUNT_REQUIRED: Account email must not be blank")
        }

        // Overwrite protection: prevent blowing away non-empty remote with 0 records
        // unless all remote records are tombstones
        val hasActiveRemotePersons = existingLedger.persons.any { !it.isDeleted }
        val hasActiveRemoteTxs = existingLedger.transactions.any { !it.isDeleted }
        val hasActiveLocalPersons = updatedPersons.any { !it.isDeleted }
        val hasActiveLocalTxs = updatedTransactions.any { !it.isDeleted }

        if ((hasActiveRemotePersons || hasActiveRemoteTxs) && (!hasActiveLocalPersons && !hasActiveLocalTxs)) {
            return AppResult.Error("OVERWRITE_PROTECTION: Refusing to overwrite non-empty cloud ledger with empty local state")
        }

        // Validate before pushing
        val personValidation = validator.validatePersons(updatedPersons)
        if (personValidation is AppResult.Error) return personValidation
        val personCloudIds = updatedPersons.map { it.cloudId }.toSet()
        val txValidation = validator.validateTransactions(updatedTransactions, personCloudIds)
        if (txValidation is AppResult.Error) return txValidation

        // 1. Update persons.json
        val personsJson = serializer.serializePersons(updatedPersons)
        val updatePersonsResult = driveApiService.updateFileContent(
            accessToken = accessToken,
            fileId = existingLedger.personsFileId,
            content = personsJson
        )
        if (updatePersonsResult is AppResult.Error) return updatePersonsResult

        // 2. Update transactions.json
        val txJson = serializer.serializeTransactions(updatedTransactions)
        val updateTxResult = driveApiService.updateFileContent(
            accessToken = accessToken,
            fileId = existingLedger.transactionsFileId,
            content = txJson
        )
        if (updateTxResult is AppResult.Error) return updateTxResult

        // 3. Commit manifest.json with updated lastSyncAt
        val updatedManifest = existingLedger.manifest.copy(
            lastSyncAt = clock.nowIso8601(),
            appVersion = appVersion
        )
        val manifestJson = serializer.serializeManifest(updatedManifest)
        val updateManifestResult = driveApiService.updateFileContent(
            accessToken = accessToken,
            fileId = existingLedger.manifestFileId,
            content = manifestJson
        )
        if (updateManifestResult is AppResult.Error) return updateManifestResult

        return AppResult.Success(
            existingLedger.copy(
                manifest = updatedManifest,
                persons = updatedPersons,
                transactions = updatedTransactions
            )
        )
    }

    /**
     * Cleans up duplicate files in AppDataFolder, retaining only the newest by version/modifiedTime.
     */
    suspend fun cleanupDuplicateFiles(accessToken: String): AppResult<Int> {
        val listResult = driveApiService.listAppDataFiles(accessToken)
        if (listResult is AppResult.Error) return listResult

        val files = (listResult as AppResult.Success).data
        val groupedByName = files.groupBy { it.name }
        var deletedCount = 0

        for ((_, fileList) in groupedByName) {
            if (fileList.size > 1) {
                // Keep the newest, delete all older duplicates
                val sorted = fileList.sortedByDescending { it.version ?: it.modifiedTime?.hashCode()?.toLong() ?: 0L }
                val toDelete = sorted.drop(1)
                for (file in toDelete) {
                    driveApiService.deleteFile(accessToken, file.id)
                    deletedCount++
                }
            }
        }

        return AppResult.Success(deletedCount)
    }

    private fun findNewestFile(files: List<DriveFileMetadata>, targetName: String): DriveFileMetadata? {
        val matches = files.filter { it.name == targetName }
        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches[0]

        // If duplicates exist, pick the newest
        return matches.maxByOrNull { it.version ?: it.modifiedTime?.hashCode()?.toLong() ?: 0L }
    }
}
