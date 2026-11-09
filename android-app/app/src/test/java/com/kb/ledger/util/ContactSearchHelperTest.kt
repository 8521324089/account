package com.kb.ledger.util

import com.kb.ledger.data.model.Person
import org.junit.Assert.*
import org.junit.Test

class ContactSearchHelperTest {

    @Test
    fun `normalizeName trims whitespace, collapses internal spaces, and converts to lowercase`() {
        assertEquals("rahul kumar", ContactSearchHelper.normalizeName("Rahul Kumar"))
        assertEquals("rahul kumar", ContactSearchHelper.normalizeName("Rahul  Kumar"))
        assertEquals("rahul kumar", ContactSearchHelper.normalizeName("  Rahul   Kumar  "))
        assertEquals("rahul kumar", ContactSearchHelper.normalizeName("rahul kumar"))
        assertEquals("rahul kumar", ContactSearchHelper.normalizeName("RAHUL KUMAR"))
        assertEquals("", ContactSearchHelper.normalizeName("   "))
    }

    @Test
    fun `normalizePhone handles phone formatting variations and leading zero`() {
        // Critical requirement examples:
        // - 09431733886
        // - 9431733 886
        // - 94 31733886
        val expected = "9431733886"
        assertEquals(expected, ContactSearchHelper.normalizePhone("09431733886"))
        assertEquals(expected, ContactSearchHelper.normalizePhone("9431733 886"))
        assertEquals(expected, ContactSearchHelper.normalizePhone("94 31733886"))
        assertEquals(expected, ContactSearchHelper.normalizePhone("94-31733-886"))
        assertEquals(expected, ContactSearchHelper.normalizePhone("+91 94317 33886"))
        assertEquals(expected, ContactSearchHelper.normalizePhone("+919431733886"))
        assertEquals(expected, ContactSearchHelper.normalizePhone("919431733886"))
        assertEquals(expected, ContactSearchHelper.normalizePhone("(09431) 733886"))
    }

    @Test
    fun `normalizePhone preserves different phone numbers and empty phones`() {
        assertNotEquals(
            ContactSearchHelper.normalizePhone("9876543210"),
            ContactSearchHelper.normalizePhone("9123456789")
        )
        assertEquals("", ContactSearchHelper.normalizePhone(""))
        assertEquals("", ContactSearchHelper.normalizePhone("   "))
        assertEquals("", ContactSearchHelper.normalizePhone("---"))
    }

    @Test
    fun `duplicate contacts with spacing and phone formatting are deduplicated`() {
        val duplicates = listOf(
            Person(id = 1, name = "Rahul Kumar", phone = "09431733886"),
            Person(id = 2, name = "Rahul  Kumar", phone = "9431733 886"),
            Person(id = 3, name = " rahul kumar ", phone = "94 31733886")
        )
        val lastTxTimes = mapOf(
            1L to 1000L,
            2L to 5000L, // most recent transaction
            3L to 2000L
        )

        val groups = ContactSearchHelper.filterAndGroup(duplicates, "Rahul", lastTxTimes)
        // Must show only ONE contact result in search
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].contacts.size)
        // Must select the representative result deterministically with the most recent transaction (ID: 2)
        assertEquals(2L, groups[0].contacts[0].person.id)
        assertFalse(groups[0].isGrouped)
    }

    @Test
    fun `contacts with same name but different phone numbers remain distinct`() {
        val contacts = listOf(
            Person(id = 1, name = "Rahul Kumar", phone = "9876543210"),
            Person(id = 2, name = "Rahul Kumar", phone = "9123456789")
        )
        val groups = ContactSearchHelper.filterAndGroup(contacts, "Rahul")
        assertEquals(1, groups.size)
        // Same name group has 2 distinct contacts
        assertEquals(2, groups[0].contacts.size)
        assertTrue(groups[0].isGrouped)
        assertEquals(setOf(1L, 2L), groups[0].contacts.map { it.person.id }.toSet())
    }

    @Test
    fun `search results sort active contacts before no-transaction contacts`() {
        val contacts = listOf(
            Person(id = 1, name = "Zack NoTx", phone = "9000000001"),
            Person(id = 2, name = "Bob ActiveOld", phone = "9000000002"),
            Person(id = 3, name = "Alice ActiveNew", phone = "9000000003"),
            Person(id = 4, name = "Adam NoTx", phone = "9000000004")
        )
        val lastTxTimes = mapOf(
            2L to 1000L, // older transaction
            3L to 5000L  // newest transaction
            // 1 and 4 have no transactions
        )

        val groups = ContactSearchHelper.filterAndGroup(contacts, "", lastTxTimes)
        val contactList = groups.flatMap { it.contacts }.map { it.person.id }

        // Expected order:
        // 1. Contacts with transactions, newest first: ID 3 (Alice), then ID 2 (Bob)
        // 2. Contacts without transactions, alphabetical: ID 4 (Adam NoTx), then ID 1 (Zack NoTx)
        assertEquals(listOf(3L, 2L, 4L, 1L), contactList)
    }

    @Test
    fun `no technical IDs or record numbering appear in disambiguation labels`() {
        val contacts = listOf(
            Person(id = 101, name = "Ramesh Kumar", phone = "9876543210"),
            Person(id = 102, name = "Ramesh Kumar", phone = "9123456789")
        )
        val groups = ContactSearchHelper.filterAndGroup(contacts, "Ramesh")
        for (group in groups) {
            for (contact in group.contacts) {
                val label = contact.disambiguationLabel
                assertFalse("Label must not contain 'ID:' or '#'", label.contains("ID:") || label.contains("#"))
                assertFalse("Label must not contain 'Account '", label.contains("Account "))
                assertTrue("Label must contain human-readable phone number", label.contains("9876543210") || label.contains("9123456789"))
            }
        }
    }

    @Test
    fun `empty search query returns all persons deduplicated`() {
        val contacts = listOf(
            Person(id = 1, name = "Rahul Kumar", phone = "9431733886"),
            Person(id = 2, name = "Rahul  Kumar", phone = "09431733886"),
            Person(id = 3, name = "Anita Devi", phone = "9876543210")
        )
        val groups = ContactSearchHelper.filterAndGroup(contacts, "")
        // Rahul duplicates merged to 1, Anita is 1 -> 2 total contacts
        val allContacts = groups.flatMap { it.contacts }
        assertEquals(2, allContacts.size)
    }

    @Test
    fun `contacts with empty phone numbers are preserved and matched`() {
        val contacts = listOf(
            Person(id = 1, name = "Customer One", phone = ""),
            Person(id = 2, name = "Customer Two", phone = "   "),
            Person(id = 3, name = "Customer Three", phone = "9876543210")
        )
        val groups = ContactSearchHelper.filterAndGroup(contacts, "Customer")
        assertEquals(3, groups.flatMap { it.contacts }.size)
    }
}
