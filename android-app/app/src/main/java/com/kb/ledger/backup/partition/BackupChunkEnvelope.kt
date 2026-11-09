package com.kb.ledger.backup.partition

import com.google.gson.annotations.SerializedName

/**
 * Versioned envelope for a compressed backup chunk file.
 *
 * Stored on disk as GZIP-compressed UTF-8 JSON.
 *
 * @param schemaVersion Format schema version (currently 1).
 * @param chunkType Type of data stored in this chunk ("TRANSACTIONS", "PERSONS", "RECURRING_RULES").
 * @param partitionKey Date partition key ("yyyy/MM/dd") for transactions, or category name.
 * @param chunkIndex 1-based index when a partition is split across multiple chunks.
 * @param recordCount Number of records in this chunk.
 * @param updatedAt Timestamp when this chunk was written.
 * @param records List of typed records.
 */
data class BackupChunkEnvelope<T>(
    @SerializedName("schemaVersion") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @SerializedName("chunkType") val chunkType: String,
    @SerializedName("partitionKey") val partitionKey: String,
    @SerializedName("chunkIndex") val chunkIndex: Int = 1,
    @SerializedName("recordCount") val recordCount: Int,
    @SerializedName("updatedAt") val updatedAt: Long = System.currentTimeMillis(),
    @SerializedName("records") val records: List<T>
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val TYPE_TRANSACTIONS = "TRANSACTIONS"
        const val TYPE_PERSONS = "PERSONS"
        const val TYPE_RECURRING_RULES = "RECURRING_RULES"
    }
}
