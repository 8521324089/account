package com.kb.ledger.data.json

import com.kb.ledger.util.AppResult

/**
 * Validates parsed Drive JSON DTOs before they are written to the local Room database.
 *
 * Design rules:
 * - Validation never throws — always returns [AppResult].
 * - On any validation failure, the caller must NOT apply the data to Room.
 * - Each violation is reported with a precise human-readable message.
 * - A single call returns ALL violations found (not just the first one).
 */
object JsonSchemaValidator {

    private val UUID_REGEX = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE
    )

    private val ISO8601_REGEX = Regex(
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$"
    )

    private val VALID_TRANSACTION_TYPES = setOf("GIVE", "RECEIVE")

    // ── Manifest ─────────────────────────────────────────────────────────────

    fun validateManifest(m: ManifestJson): AppResult<Unit> {
        val errors = mutableListOf<String>()

        if (m.schemaVersion < 1)
            errors += "manifest: schemaVersion must be >= 1, got ${m.schemaVersion}"
        if (m.ledgerId.isBlank())
            errors += "manifest: ledgerId must not be blank"
        if (!isValidUuid(m.ledgerId))
            errors += "manifest: ledgerId '${m.ledgerId}' is not a valid UUID"
        if (m.ownerId.isBlank())
            errors += "manifest: ownerId must not be blank"
        if (!m.ownerId.contains("@"))
            errors += "manifest: ownerId '${m.ownerId}' does not look like an email"
        if (m.appVersion.isBlank())
            errors += "manifest: appVersion must not be blank"
        if (!isValidIso8601(m.createdAt))
            errors += "manifest: createdAt '${m.createdAt}' is not a valid ISO-8601 UTC timestamp"
        if (!isValidIso8601(m.lastSyncAt))
            errors += "manifest: lastSyncAt '${m.lastSyncAt}' is not a valid ISO-8601 UTC timestamp"

        return if (errors.isEmpty()) AppResult.Success(Unit)
        else AppResult.Error(errors.joinToString("; "))
    }

    // ── Persons ───────────────────────────────────────────────────────────────

    /**
     * Validates a list of PersonJson records.
     * Also checks for duplicate cloudIds within the list.
     */
    fun validatePersons(persons: List<PersonJson>): AppResult<Unit> {
        val errors = mutableListOf<String>()
        val seenCloudIds = mutableSetOf<String>()

        persons.forEachIndexed { idx, p ->
            val prefix = "persons[$idx]"

            if (p.cloudId.isBlank())
                errors += "$prefix: cloudId must not be blank"
            else if (!isValidUuid(p.cloudId))
                errors += "$prefix: cloudId '${p.cloudId}' is not a valid UUID"
            else if (!seenCloudIds.add(p.cloudId))
                errors += "$prefix: duplicate cloudId '${p.cloudId}'"

            if (p.name.isBlank())
                errors += "$prefix (cloudId=${p.cloudId}): name must not be blank"
            if (p.phone.isBlank())
                errors += "$prefix (cloudId=${p.cloudId}): phone must not be blank"
            if (!isValidIso8601(p.createdAt))
                errors += "$prefix (cloudId=${p.cloudId}): createdAt '${p.createdAt}' is not valid ISO-8601 UTC"
            if (!isValidIso8601(p.updatedAt))
                errors += "$prefix (cloudId=${p.cloudId}): updatedAt '${p.updatedAt}' is not valid ISO-8601 UTC"
        }

        return if (errors.isEmpty()) AppResult.Success(Unit)
        else AppResult.Error(errors.joinToString("; "))
    }

    // ── Transactions ──────────────────────────────────────────────────────────

    /**
     * Validates a list of TransactionJson records.
     * Also checks for duplicate IDs and referential integrity against [knownPersonCloudIds].
     *
     * @param transactions  The records to validate.
     * @param knownPersonCloudIds  Set of cloudIds from the persons list.
     *                             If null, person-reference validation is skipped
     *                             (e.g., when running in isolation).
     */
    fun validateTransactions(
        transactions: List<TransactionJson>,
        knownPersonCloudIds: Set<String>? = null
    ): AppResult<Unit> {
        val errors = mutableListOf<String>()
        val seenIds = mutableSetOf<String>()

        transactions.forEachIndexed { idx, tx ->
            val prefix = "transactions[$idx]"

            // ID
            if (tx.id.isBlank())
                errors += "$prefix: id must not be blank"
            else if (!isValidUuid(tx.id))
                errors += "$prefix: id '${tx.id}' is not a valid UUID"
            else if (!seenIds.add(tx.id))
                errors += "$prefix: duplicate id '${tx.id}'"

            // Person reference
            if (tx.personCloudId.isBlank())
                errors += "$prefix (id=${tx.id}): personCloudId must not be blank"
            else if (!isValidUuid(tx.personCloudId))
                errors += "$prefix (id=${tx.id}): personCloudId '${tx.personCloudId}' is not a valid UUID"
            else if (knownPersonCloudIds != null && tx.personCloudId !in knownPersonCloudIds)
                errors += "$prefix (id=${tx.id}): personCloudId '${tx.personCloudId}' has no matching person record"

            // Amount
            if (tx.amountCents <= 0)
                errors += "$prefix (id=${tx.id}): amountCents must be > 0, got ${tx.amountCents}"

            // Type
            if (tx.type !in VALID_TRANSACTION_TYPES)
                errors += "$prefix (id=${tx.id}): type '${tx.type}' must be one of $VALID_TRANSACTION_TYPES"

            // Timestamps
            if (!isValidIso8601(tx.createdAt))
                errors += "$prefix (id=${tx.id}): createdAt '${tx.createdAt}' is not valid ISO-8601 UTC"
            if (!isValidIso8601(tx.updatedAt))
                errors += "$prefix (id=${tx.id}): updatedAt '${tx.updatedAt}' is not valid ISO-8601 UTC"
        }

        return if (errors.isEmpty()) AppResult.Success(Unit)
        else AppResult.Error(errors.joinToString("; "))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns true if the string matches the standard UUID format
     * (8-4-4-4-12 lowercase hexadecimal, case-insensitive).
     */
    fun isValidUuid(value: String): Boolean = UUID_REGEX.matches(value)

    /**
     * Returns true if the string matches `yyyy-MM-ddTHH:mm:ssZ` (UTC Zulu format).
     * This is the format produced by [com.kb.ledger.util.Clock.nowIso8601].
     */
    fun isValidIso8601(value: String): Boolean = ISO8601_REGEX.matches(value)
}
