package com.kb.ledger.backup.partition

import com.google.gson.annotations.SerializedName

/**
 * Manifest representing the root index and state of the local partitioned backup.
 *
 * Stored at `context.filesDir/backup/manifest.dat` as GZIP-compressed UTF-8 JSON.
 */
data class BackupManifest(
    @SerializedName("schemaVersion") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @SerializedName("ledgerId") val ledgerId: String,
    @SerializedName("lastReconciledAt") val lastReconciledAt: Long,
    @SerializedName("lastMutationAt") val lastMutationAt: Long,
    @SerializedName("personChunkCount") val personChunkCount: Int = 1,
    @SerializedName("totalPersons") val totalPersons: Int = 0,
    @SerializedName("ruleChunkCount") val ruleChunkCount: Int = 1,
    @SerializedName("totalRules") val totalRules: Int = 0,
    @SerializedName("transactionPartitionCount") val transactionPartitionCount: Int = 0,
    @SerializedName("totalTransactions") val totalTransactions: Int = 0,
    @SerializedName("activeTransactions") val activeTransactions: Int = 0,
    @SerializedName("deletedTransactions") val deletedTransactions: Int = 0,
    @SerializedName("totalImages") val totalImages: Int = 0,
    @SerializedName("transactionPartitions") val transactionPartitions: Map<String, Int> = emptyMap() // "YYYY/MM/DD" -> chunkCount
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
