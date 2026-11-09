package com.kb.ledger.data.json

import com.kb.ledger.util.AppResult
import org.junit.Assert.*
import org.junit.Test

/**
 * JVM unit tests for [JsonSchemaValidator].
 *
 * Covers:
 *  - Valid manifest / persons / transactions pass validation
 *  - Missing / blank required fields are rejected
 *  - Invalid UUID format is rejected
 *  - Invalid ISO-8601 timestamp is rejected
 *  - Invalid transaction type is rejected
 *  - amountCents <= 0 is rejected
 *  - Duplicate cloudIds within a list are rejected
 *  - Duplicate transaction IDs within a list are rejected
 *  - personCloudId referential integrity check
 *  - Multiple errors are all reported (not just first)
 *  - Deleted (tombstone) records pass validation
 */
class JsonSchemaValidatorTest {

    // ── Helper values ─────────────────────────────────────────────────────────

    private val validUuid1 = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    private val validUuid2 = "11111111-2222-3333-4444-555555555555"
    private val validUuid3 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val validTs = "2026-10-17T04:00:00Z"

    private fun validManifest() = ManifestJson(
        schemaVersion = 1,
        appVersion = "1.0",
        ledgerId = validUuid1,
        ownerId = "user@example.com",
        createdAt = validTs,
        lastSyncAt = validTs
    )

    private fun validPerson(cloudId: String = validUuid1) = PersonJson(
        cloudId = cloudId,
        localId = 1L,
        name = "Test Person",
        phone = "9876543210",
        contactId = null,
        createdAt = validTs,
        updatedAt = validTs,
        updatedBy = "user@example.com",
        isDeleted = false
    )

    private fun validTransaction(
        id: String = validUuid3,
        personCloudId: String = validUuid1
    ) = TransactionJson(
        id = id,
        personCloudId = personCloudId,
        amountCents = 50000L,
        type = "GIVE",
        note = "Test note",
        imageFileId = null,
        createdAt = validTs,
        updatedAt = validTs,
        updatedBy = null,
        isDeleted = false
    )

    // ── Manifest validation ───────────────────────────────────────────────────

    @Test
    fun `valid manifest passes`() {
        val result = JsonSchemaValidator.validateManifest(validManifest())
        assertTrue("Valid manifest should pass: $result", result.isSuccess)
    }

    @Test
    fun `manifest with schemaVersion zero fails`() {
        val result = JsonSchemaValidator.validateManifest(validManifest().copy(schemaVersion = 0))
        assertTrue("Zero schemaVersion should fail", result.isError)
    }

    @Test
    fun `manifest with blank ledgerId fails`() {
        val result = JsonSchemaValidator.validateManifest(validManifest().copy(ledgerId = ""))
        assertTrue("Blank ledgerId should fail", result.isError)
    }

    @Test
    fun `manifest with invalid ledgerId UUID fails`() {
        val result = JsonSchemaValidator.validateManifest(validManifest().copy(ledgerId = "not-a-uuid"))
        assertTrue("Non-UUID ledgerId should fail", result.isError)
        val error = result as AppResult.Error
        assertTrue("Error should mention ledgerId", error.message.contains("ledgerId"))
    }

    @Test
    fun `manifest with blank ownerId fails`() {
        val result = JsonSchemaValidator.validateManifest(validManifest().copy(ownerId = ""))
        assertTrue("Blank ownerId should fail", result.isError)
    }

    @Test
    fun `manifest with ownerId without at-sign fails`() {
        val result = JsonSchemaValidator.validateManifest(validManifest().copy(ownerId = "notanemail"))
        assertTrue("ownerId without @ should fail", result.isError)
    }

    @Test
    fun `manifest with invalid createdAt format fails`() {
        val result = JsonSchemaValidator.validateManifest(validManifest().copy(createdAt = "2026-10-17"))
        assertTrue("Invalid timestamp should fail", result.isError)
    }

    @Test
    fun `manifest with invalid lastSyncAt format fails`() {
        val result = JsonSchemaValidator.validateManifest(validManifest().copy(lastSyncAt = "2026/10/17T00:00:00Z"))
        assertTrue("Invalid timestamp should fail", result.isError)
    }

    // ── Person validation ─────────────────────────────────────────────────────

    @Test
    fun `valid persons list passes`() {
        val result = JsonSchemaValidator.validatePersons(listOf(validPerson()))
        assertTrue("Valid persons should pass: $result", result.isSuccess)
    }

    @Test
    fun `empty persons list passes`() {
        val result = JsonSchemaValidator.validatePersons(emptyList())
        assertTrue("Empty list should pass", result.isSuccess)
    }

    @Test
    fun `tombstone person (isDeleted=true) passes validation`() {
        val person = validPerson().copy(isDeleted = true)
        val result = JsonSchemaValidator.validatePersons(listOf(person))
        assertTrue("Tombstone person should pass", result.isSuccess)
    }

    @Test
    fun `person with blank cloudId fails`() {
        val result = JsonSchemaValidator.validatePersons(listOf(validPerson().copy(cloudId = "")))
        assertTrue("Blank cloudId should fail", result.isError)
    }

    @Test
    fun `person with invalid UUID cloudId fails`() {
        val result = JsonSchemaValidator.validatePersons(listOf(validPerson().copy(cloudId = "bad-id")))
        assertTrue("Invalid UUID cloudId should fail", result.isError)
    }

    @Test
    fun `duplicate cloudIds in persons list fails`() {
        val person1 = validPerson(cloudId = validUuid1)
        val person2 = validPerson(cloudId = validUuid1) // same UUID
        val result = JsonSchemaValidator.validatePersons(listOf(person1, person2))
        assertTrue("Duplicate cloudId should fail", result.isError)
        val error = result as AppResult.Error
        assertTrue("Error should mention duplicate", error.message.contains("duplicate"))
    }

    @Test
    fun `person with blank name fails`() {
        val result = JsonSchemaValidator.validatePersons(listOf(validPerson().copy(name = "")))
        assertTrue("Blank name should fail", result.isError)
    }

    @Test
    fun `person with blank phone fails`() {
        val result = JsonSchemaValidator.validatePersons(listOf(validPerson().copy(phone = "")))
        assertTrue("Blank phone should fail", result.isError)
    }

    @Test
    fun `person with invalid createdAt fails`() {
        val result = JsonSchemaValidator.validatePersons(listOf(validPerson().copy(createdAt = "not-a-date")))
        assertTrue("Invalid createdAt should fail", result.isError)
    }

    @Test
    fun `multiple person violations are all reported`() {
        val badPerson = PersonJson(
            cloudId = "bad-uuid",
            localId = null,
            name = "",
            phone = "",
            contactId = null,
            createdAt = "bad-date",
            updatedAt = "bad-date",
            updatedBy = null,
            isDeleted = false
        )
        val result = JsonSchemaValidator.validatePersons(listOf(badPerson))
        assertTrue("Should fail with multiple errors", result.isError)
        val error = result as AppResult.Error
        // Errors are joined with "; " so there should be more than one
        val errorCount = error.message.split(";").size
        assertTrue("Expected multiple errors, got: ${error.message}", errorCount >= 3)
    }

    // ── Transaction validation ────────────────────────────────────────────────

    @Test
    fun `valid transaction passes`() {
        val result = JsonSchemaValidator.validateTransactions(
            listOf(validTransaction()),
            knownPersonCloudIds = setOf(validUuid1)
        )
        assertTrue("Valid transaction should pass: $result", result.isSuccess)
    }

    @Test
    fun `empty transactions list passes`() {
        val result = JsonSchemaValidator.validateTransactions(emptyList())
        assertTrue("Empty list should pass", result.isSuccess)
    }

    @Test
    fun `tombstone transaction passes`() {
        val tx = validTransaction().copy(isDeleted = true)
        val result = JsonSchemaValidator.validateTransactions(listOf(tx))
        assertTrue("Tombstone transaction should pass", result.isSuccess)
    }

    @Test
    fun `transaction with blank id fails`() {
        val result = JsonSchemaValidator.validateTransactions(listOf(validTransaction().copy(id = "")))
        assertTrue("Blank id should fail", result.isError)
    }

    @Test
    fun `transaction with invalid UUID id fails`() {
        val result = JsonSchemaValidator.validateTransactions(listOf(validTransaction().copy(id = "bad")))
        assertTrue("Non-UUID id should fail", result.isError)
    }

    @Test
    fun `duplicate transaction ids fail`() {
        val tx1 = validTransaction(id = validUuid3)
        val tx2 = validTransaction(id = validUuid3) // same UUID
        val result = JsonSchemaValidator.validateTransactions(listOf(tx1, tx2))
        assertTrue("Duplicate id should fail", result.isError)
        val error = result as AppResult.Error
        assertTrue(error.message.contains("duplicate"))
    }

    @Test
    fun `transaction amountCents zero fails`() {
        val result = JsonSchemaValidator.validateTransactions(listOf(validTransaction().copy(amountCents = 0)))
        assertTrue("Zero amountCents should fail", result.isError)
    }

    @Test
    fun `transaction amountCents negative fails`() {
        val result = JsonSchemaValidator.validateTransactions(listOf(validTransaction().copy(amountCents = -100)))
        assertTrue("Negative amountCents should fail", result.isError)
    }

    @Test
    fun `transaction with invalid type fails`() {
        val result = JsonSchemaValidator.validateTransactions(listOf(validTransaction().copy(type = "LEND")))
        assertTrue("Invalid type should fail", result.isError)
        val error = result as AppResult.Error
        assertTrue("Error should mention type", error.message.contains("type"))
    }

    @Test
    fun `transaction RECEIVE type passes`() {
        val result = JsonSchemaValidator.validateTransactions(
            listOf(validTransaction().copy(type = "RECEIVE")),
            knownPersonCloudIds = setOf(validUuid1)
        )
        assertTrue("RECEIVE type should pass", result.isSuccess)
    }

    @Test
    fun `transaction with unknown personCloudId fails when known set provided`() {
        val result = JsonSchemaValidator.validateTransactions(
            listOf(validTransaction()),
            knownPersonCloudIds = setOf(validUuid2) // validUuid1 not in set
        )
        assertTrue("Unknown personCloudId should fail", result.isError)
        val error = result as AppResult.Error
        assertTrue(error.message.contains("personCloudId"))
    }

    @Test
    fun `transaction with unknown personCloudId passes when known set is null`() {
        // When running in isolation without persons list, referential check is skipped
        val result = JsonSchemaValidator.validateTransactions(
            listOf(validTransaction()),
            knownPersonCloudIds = null
        )
        assertTrue("Should pass when knownPersonCloudIds is null", result.isSuccess)
    }

    @Test
    fun `transaction invalid createdAt fails`() {
        val result = JsonSchemaValidator.validateTransactions(
            listOf(validTransaction().copy(createdAt = "17-10-2026"))
        )
        assertTrue("Invalid createdAt should fail", result.isError)
    }

    // ── UUID and ISO-8601 helper tests ────────────────────────────────────────

    @Test
    fun `isValidUuid accepts lowercase UUID`() {
        assertTrue(JsonSchemaValidator.isValidUuid("a1b2c3d4-e5f6-7890-abcd-ef1234567890"))
    }

    @Test
    fun `isValidUuid accepts uppercase UUID`() {
        assertTrue(JsonSchemaValidator.isValidUuid("A1B2C3D4-E5F6-7890-ABCD-EF1234567890"))
    }

    @Test
    fun `isValidUuid rejects short string`() {
        assertFalse(JsonSchemaValidator.isValidUuid("a1b2c3d4"))
    }

    @Test
    fun `isValidUuid rejects string with wrong separator`() {
        assertFalse(JsonSchemaValidator.isValidUuid("a1b2c3d4_e5f6_7890_abcd_ef1234567890"))
    }

    @Test
    fun `isValidIso8601 accepts valid timestamp`() {
        assertTrue(JsonSchemaValidator.isValidIso8601("2026-10-17T04:00:00Z"))
    }

    @Test
    fun `isValidIso8601 rejects date-only string`() {
        assertFalse(JsonSchemaValidator.isValidIso8601("2026-10-17"))
    }

    @Test
    fun `isValidIso8601 rejects timestamp with offset instead of Z`() {
        assertFalse(JsonSchemaValidator.isValidIso8601("2026-10-17T04:00:00+05:30"))
    }

    @Test
    fun `isValidIso8601 rejects empty string`() {
        assertFalse(JsonSchemaValidator.isValidIso8601(""))
    }
}
