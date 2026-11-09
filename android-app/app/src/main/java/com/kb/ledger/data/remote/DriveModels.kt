package com.kb.ledger.data.remote

import com.google.gson.annotations.SerializedName
import com.kb.ledger.data.json.ManifestJson
import com.kb.ledger.data.json.PersonJson
import com.kb.ledger.data.json.TransactionJson

/**
 * Metadata for a file in Google Drive AppDataFolder.
 */
data class DriveFileMetadata(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("mimeType") val mimeType: String? = null,
    @SerializedName("modifiedTime") val modifiedTime: String? = null,
    @SerializedName("version") val version: Long? = null,
    @SerializedName("size") val size: Long? = null
)

/**
 * Drive files.list API response.
 */
data class DriveFileListResponse(
    @SerializedName("files") val files: List<DriveFileMetadata> = emptyList(),
    @SerializedName("nextPageToken") val nextPageToken: String? = null
)

/**
 * High-level snapshot of a complete ledger downloaded from Google Drive AppDataFolder.
 */
data class RemoteLedger(
    val manifest: ManifestJson,
    val manifestFileId: String,
    val persons: List<PersonJson>,
    val personsFileId: String,
    val transactions: List<TransactionJson>,
    val transactionsFileId: String
)

/**
 * Error response from Google Drive API.
 */
data class GoogleApiErrorResponse(
    @SerializedName("error") val error: GoogleApiErrorDetails? = null
)

data class GoogleApiErrorDetails(
    @SerializedName("code") val code: Int? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("errors") val errors: List<GoogleApiErrorItem>? = null
)

data class GoogleApiErrorItem(
    @SerializedName("domain") val domain: String? = null,
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("message") val message: String? = null
)
