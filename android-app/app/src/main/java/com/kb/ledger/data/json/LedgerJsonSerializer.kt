package com.kb.ledger.data.json

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import com.kb.ledger.util.AppResult

// ─────────────────────────────────────────────────────────────────────────────
// Wire DTOs — exactly what is written to / read from Drive JSON files.
// These are intentionally separate from Room entities and domain models.
// ─────────────────────────────────────────────────────────────────────────────

/** Top-level envelope shared by all ledger JSON files. */
data class JsonEnvelope<T>(
    @SerializedName("schemaVersion") val schemaVersion: Int,
    @SerializedName("records") val records: T
)

/** `manifest.json` — ledger identity and sync metadata. */
data class ManifestJson(
    @SerializedName("schemaVersion") val schemaVersion: Int,
    @SerializedName("appVersion") val appVersion: String,
    @SerializedName("ledgerId") val ledgerId: String,
    @SerializedName("ownerId") val ownerId: String,
    @SerializedName("createdAt") val createdAt: String,   // ISO-8601 UTC
    @SerializedName("lastSyncAt") val lastSyncAt: String  // ISO-8601 UTC
)

/** One record in `persons.json`. */
data class PersonJson(
    @SerializedName("cloudId") val cloudId: String,
    @SerializedName("localId") val localId: Long?,        // nullable — remote device may not have a local ID
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("contactId") val contactId: String?,
    @SerializedName("createdAt") val createdAt: String,   // ISO-8601 UTC
    @SerializedName("updatedAt") val updatedAt: String,   // ISO-8601 UTC
    @SerializedName("updatedBy") val updatedBy: String?,
    @SerializedName("isDeleted") val isDeleted: Boolean
)

/** One record in `transactions.json`. */
data class TransactionJson(
    @SerializedName("id") val id: String,
    @SerializedName("personCloudId") val personCloudId: String,
    @SerializedName("amountCents") val amountCents: Long, // paise; always integer
    @SerializedName("type") val type: String,             // "GIVE" | "RECEIVE"
    @SerializedName("note") val note: String?,
    @SerializedName("imageFileId") val imageFileId: String?,
    @SerializedName("createdAt") val createdAt: String,   // ISO-8601 UTC
    @SerializedName("updatedAt") val updatedAt: String,   // ISO-8601 UTC
    @SerializedName("updatedBy") val updatedBy: String?,
    @SerializedName("isDeleted") val isDeleted: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// Serializer
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Converts between JSON strings (as stored on Google Drive) and typed DTOs.
 *
 * Contract:
 * - All serialization produces valid UTF-8 JSON.
 * - All deserialization returns [AppResult.Error] on malformed JSON or
 *   schema-version mismatches — never throws, never silently returns null data.
 * - Callers must validate with [JsonSchemaValidator] before using the results.
 *
 * Supported schema versions:
 *   Manifest:     [CURRENT_MANIFEST_VERSION]
 *   Persons:      [CURRENT_PERSONS_VERSION]
 *   Transactions: [CURRENT_TRANSACTIONS_VERSION]
 */
class LedgerJsonSerializer {

    companion object {
        const val CURRENT_MANIFEST_VERSION = 1
        const val CURRENT_PERSONS_VERSION = 1
        const val CURRENT_TRANSACTIONS_VERSION = 1
    }

    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .create()

    // ── Manifest ─────────────────────────────────────────────────────────────

    fun serializeManifest(manifest: ManifestJson): String =
        gson.toJson(manifest)

    fun deserializeManifest(json: String): AppResult<ManifestJson> =
        runCatching {
            val result = gson.fromJson(json, ManifestJson::class.java)
                ?: return AppResult.Error("manifest.json parsed to null")
            if (result.schemaVersion > CURRENT_MANIFEST_VERSION) {
                return AppResult.Error(
                    "manifest.json schemaVersion ${result.schemaVersion} is newer than supported " +
                        "version $CURRENT_MANIFEST_VERSION — please update the app"
                )
            }
            AppResult.Success(result)
        }.getOrElse { t ->
            AppResult.Error("Failed to parse manifest.json: ${t.message}", t)
        }

    // ── Persons ───────────────────────────────────────────────────────────────

    /**
     * Serializes persons into the Drive format:
     * `{ "schemaVersion": 1, "records": [ ... ] }`
     */
    fun serializePersons(persons: List<PersonJson>): String =
        gson.toJson(JsonEnvelope(schemaVersion = CURRENT_PERSONS_VERSION, records = persons))

    /**
     * Deserializes the `persons.json` content.
     * Returns [AppResult.Error] if the JSON is malformed or the schema version
     * is newer than this app supports.
     */
    fun deserializePersons(json: String): AppResult<List<PersonJson>> =
        runCatching {
            // Use raw type to extract schemaVersion before full parse
            @Suppress("UNCHECKED_CAST")
            val raw = gson.fromJson(json, Map::class.java)
                ?: return AppResult.Error("persons.json parsed to null")

            val version = (raw["schemaVersion"] as? Number)?.toInt()
                ?: return AppResult.Error("persons.json missing schemaVersion field")

            if (version > CURRENT_PERSONS_VERSION) {
                return AppResult.Error(
                    "persons.json schemaVersion $version is newer than supported " +
                        "version $CURRENT_PERSONS_VERSION — please update the app"
                )
            }

            @Suppress("UNCHECKED_CAST")
            val envelope = gson.fromJson(
                json,
                com.google.gson.reflect.TypeToken.getParameterized(
                    JsonEnvelope::class.java,
                    com.google.gson.reflect.TypeToken.getArray(PersonJson::class.java).type
                ).type
            ) as? JsonEnvelope<Array<PersonJson>>
                ?: return AppResult.Error("persons.json envelope has unexpected structure")

            AppResult.Success(envelope.records.toList())
        }.getOrElse { t ->
            AppResult.Error("Failed to parse persons.json: ${t.message}", t)
        }

    // ── Transactions ──────────────────────────────────────────────────────────

    /**
     * Serializes transactions into the Drive format:
     * `{ "schemaVersion": 1, "records": [ ... ] }`
     */
    fun serializeTransactions(transactions: List<TransactionJson>): String =
        gson.toJson(JsonEnvelope(schemaVersion = CURRENT_TRANSACTIONS_VERSION, records = transactions))

    /**
     * Deserializes the `transactions.json` content.
     * Returns [AppResult.Error] if malformed or schema is too new.
     */
    fun deserializeTransactions(json: String): AppResult<List<TransactionJson>> =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val raw = gson.fromJson(json, Map::class.java)
                ?: return AppResult.Error("transactions.json parsed to null")

            val version = (raw["schemaVersion"] as? Number)?.toInt()
                ?: return AppResult.Error("transactions.json missing schemaVersion field")

            if (version > CURRENT_TRANSACTIONS_VERSION) {
                return AppResult.Error(
                    "transactions.json schemaVersion $version is newer than supported " +
                        "version $CURRENT_TRANSACTIONS_VERSION — please update the app"
                )
            }

            @Suppress("UNCHECKED_CAST")
            val envelope = gson.fromJson(
                json,
                com.google.gson.reflect.TypeToken.getParameterized(
                    JsonEnvelope::class.java,
                    com.google.gson.reflect.TypeToken.getArray(TransactionJson::class.java).type
                ).type
            ) as? JsonEnvelope<Array<TransactionJson>>
                ?: return AppResult.Error("transactions.json envelope has unexpected structure")

            AppResult.Success(envelope.records.toList())
        }.getOrElse { t ->
            AppResult.Error("Failed to parse transactions.json: ${t.message}", t)
        }
}
