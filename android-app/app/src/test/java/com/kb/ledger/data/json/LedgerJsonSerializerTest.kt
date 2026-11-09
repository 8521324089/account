package com.kb.ledger.data.json

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [LedgerJsonSerializer].
 *
 * Covers:
 *  - Manifest serialise → deserialise round-trip
 *  - Persons serialise → deserialise round-trip
 *  - Transactions serialise → deserialise round-trip
 *  - Malformed JSON returns AppResult.Error
 *  - Schema version too new returns AppResult.Error with descriptive message
 *  - Empty record lists are preserved
 *  - amountCents precision (no floating-point corruption)
 *  - isDeleted tombstones are preserved
 *  - Null optional fields (note, imageFileId, contactId) survive the round-trip
 */
class LedgerJsonSerializerTest {

    private lateinit var serializer: LedgerJsonSerializer

    // Fixed test UUIDs
    private val ledgerId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    private val personCloudId = "11111111-2222-3333-4444-555555555555"
    private val txId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    @Before
    fun setup() {
        serializer = LedgerJsonSerializer()
    }

    // ── Manifest ──────────────────────────────────────────────────────────────

    @Test
    fun `manifest round-trip preserves all fields`() {
        val original = ManifestJson(
            schemaVersion = 1,
            appVersion = "1.0",
            ledgerId = ledgerId,
            ownerId = "user@example.com",
            createdAt = "2026-10-17T04:00:00Z",
            lastSyncAt = "2026-10-17T04:00:00Z"
        )

        val json = serializer.serializeManifest(original)
        val result = serializer.deserializeManifest(json)

        assertTrue("Expected Success, got $result", result.isSuccess)
        val restored = result.getOrNull()!!
        assertEquals(original.schemaVersion, restored.schemaVersion)
        assertEquals(original.ledgerId, restored.ledgerId)
        assertEquals(original.ownerId, restored.ownerId)
        assertEquals(original.appVersion, restored.appVersion)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.lastSyncAt, restored.lastSyncAt)
    }

    @Test
    fun `manifest deserialise returns Error on malformed JSON`() {
        val result = serializer.deserializeManifest("{ not valid json }")
        assertTrue("Expected Error on malformed JSON", result.isError)
    }

    @Test
    fun `manifest deserialise returns Error on empty string`() {
        val result = serializer.deserializeManifest("")
        assertTrue("Expected Error on empty string", result.isError)
    }

    // ── Persons ───────────────────────────────────────────────────────────────

    @Test
    fun `persons round-trip preserves all fields including isDeleted tombstone`() {
        val persons = listOf(
            PersonJson(
                cloudId = personCloudId,
                localId = 42L,
                name = "Rahul Sharma",
                phone = "+91 98765 43210",
                contactId = "contact-001",
                createdAt = "2026-10-17T04:00:00Z",
                updatedAt = "2026-10-18T10:00:00Z",
                updatedBy = "user@example.com",
                isDeleted = false
            ),
            PersonJson(
                cloudId = "22222222-3333-4444-5555-666666666666",
                localId = null,
                name = "Deleted Person",
                phone = "0000000000",
                contactId = null,
                createdAt = "2026-10-10T00:00:00Z",
                updatedAt = "2026-10-15T00:00:00Z",
                updatedBy = null,
                isDeleted = true  // tombstone must survive
            )
        )

        val json = serializer.serializePersons(persons)
        val result = serializer.deserializePersons(json)

        assertTrue("Expected Success, got $result", result.isSuccess)
        val restored = result.getOrNull()!!
        assertEquals(2, restored.size)

        val first = restored[0]
        assertEquals(personCloudId, first.cloudId)
        assertEquals(42L, first.localId)
        assertEquals("Rahul Sharma", first.name)
        assertEquals("+91 98765 43210", first.phone)
        assertEquals("contact-001", first.contactId)
        assertFalse(first.isDeleted)

        val second = restored[1]
        assertNull(second.localId)
        assertNull(second.contactId)
        assertNull(second.updatedBy)
        assertTrue("isDeleted tombstone must be preserved", second.isDeleted)
    }

    @Test
    fun `persons round-trip with empty list`() {
        val json = serializer.serializePersons(emptyList())
        val result = serializer.deserializePersons(json)

        assertTrue("Expected Success for empty list", result.isSuccess)
        assertEquals(0, result.getOrNull()!!.size)
    }

    @Test
    fun `persons deserialise returns Error on malformed JSON`() {
        val result = serializer.deserializePersons("not json")
        assertTrue("Expected Error on malformed JSON", result.isError)
    }

    @Test
    fun `persons deserialise returns Error when schemaVersion is too new`() {
        val futureJson = """{"schemaVersion":999,"records":[]}"""
        val result = serializer.deserializePersons(futureJson)
        assertTrue("Expected Error for future schema version", result.isError)
        val error = result as com.kb.ledger.util.AppResult.Error
        assertTrue("Error should mention schemaVersion", error.message.contains("schemaVersion"))
        assertTrue("Error should mention 999", error.message.contains("999"))
    }

    @Test
    fun `persons deserialise returns Error when schemaVersion field missing`() {
        val result = serializer.deserializePersons("""{"records":[]}""")
        assertTrue("Expected Error when schemaVersion missing", result.isError)
    }

    // ── Transactions ──────────────────────────────────────────────────────────

    @Test
    fun `transactions round-trip preserves all fields including amountCents precision`() {
        val transactions = listOf(
            TransactionJson(
                id = txId,
                personCloudId = personCloudId,
                amountCents = 50075L,  // ₹500.75 — must survive with no float corruption
                type = "GIVE",
                note = "Grocery payment",
                imageFileId = "drive-file-id-abc",
                createdAt = "2026-10-17T04:00:00Z",
                updatedAt = "2026-10-17T04:00:00Z",
                updatedBy = "user@example.com",
                isDeleted = false
            ),
            TransactionJson(
                id = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff",
                personCloudId = personCloudId,
                amountCents = 100000L,  // ₹1000.00 exactly
                type = "RECEIVE",
                note = null,
                imageFileId = null,
                createdAt = "2026-10-18T00:00:00Z",
                updatedAt = "2026-10-18T00:00:00Z",
                updatedBy = null,
                isDeleted = true  // tombstone
            )
        )

        val json = serializer.serializeTransactions(transactions)
        val result = serializer.deserializeTransactions(json)

        assertTrue("Expected Success, got $result", result.isSuccess)
        val restored = result.getOrNull()!!
        assertEquals(2, restored.size)

        val first = restored[0]
        assertEquals(txId, first.id)
        assertEquals(personCloudId, first.personCloudId)
        assertEquals(50075L, first.amountCents)  // no floating-point drift
        assertEquals("GIVE", first.type)
        assertEquals("Grocery payment", first.note)
        assertEquals("drive-file-id-abc", first.imageFileId)
        assertFalse(first.isDeleted)

        val second = restored[1]
        assertNull(second.note)
        assertNull(second.imageFileId)
        assertNull(second.updatedBy)
        assertEquals(100000L, second.amountCents)
        assertTrue("isDeleted tombstone must be preserved", second.isDeleted)
    }

    @Test
    fun `transactions round-trip with empty list`() {
        val json = serializer.serializeTransactions(emptyList())
        val result = serializer.deserializeTransactions(json)

        assertTrue("Expected Success for empty list", result.isSuccess)
        assertEquals(0, result.getOrNull()!!.size)
    }

    @Test
    fun `transactions deserialise returns Error on malformed JSON`() {
        val result = serializer.deserializeTransactions("{bad}")
        assertTrue("Expected Error on malformed JSON", result.isError)
    }

    @Test
    fun `transactions deserialise returns Error when schemaVersion too new`() {
        val futureJson = """{"schemaVersion":999,"records":[]}"""
        val result = serializer.deserializeTransactions(futureJson)
        assertTrue("Expected Error for future schema version", result.isError)
        val error = result as com.kb.ledger.util.AppResult.Error
        assertTrue(error.message.contains("999"))
    }

    @Test
    fun `amountCents stored as integer in JSON not float`() {
        val txList = listOf(
            TransactionJson(
                id = txId, personCloudId = personCloudId,
                amountCents = 10001L, type = "GIVE", note = null,
                imageFileId = null, createdAt = "2026-10-17T00:00:00Z",
                updatedAt = "2026-10-17T00:00:00Z", updatedBy = null, isDeleted = false
            )
        )
        val json = serializer.serializeTransactions(txList)
        // The JSON must store amountCents as a plain integer, not 10001.0
        assertTrue(
            "amountCents should be stored as integer (10001) not float (10001.0)",
            json.contains("\"amountCents\":10001") && !json.contains("10001.0")
        )
    }
}
