package com.kb.ledger.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.PersonRole
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.data.repository.FakePersonDao
import com.kb.ledger.data.repository.FakeRecurringRuleDao
import com.kb.ledger.data.repository.FakeTransactionDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for Backup Schema Version 4 and backward compatibility.
 *
 * Verifies:
 * - Schema v4 export specifies schemaVersion = 4 and preserves role.
 * - Legacy schema v1, v2, v3 backups without "role" attribute default safely to CUSTOMER.
 * - Restoring two records sharing the same phone number (one CUSTOMER, one SUPPLIER)
 *   keeps them strictly separated as two independent persons without merging.
 */
class BackupSchemaV4Test {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val now = System.currentTimeMillis()

    @Test
    fun schemaV4_serialization_preservesSupplierRole() {
        val supplier = Person(
            id = 1L,
            name = "National Paints",
            phone = "9876543210",
            role = PersonRole.SUPPLIER,
            cloudId = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now
        )

        val backup = BackupData(
            persons = listOf(supplier),
            transactions = emptyList()
        )

        val json = gson.toJson(backup)
        assertTrue("JSON must contain SUPPLIER role", json.contains("\"SUPPLIER\""))

        val restored = gson.fromJson(json, BackupData::class.java)
        assertEquals(4, restored.schemaVersion)
        assertEquals(1, restored.persons.size)
        assertEquals(PersonRole.SUPPLIER, restored.persons[0].role)
    }

    @Test
    fun legacySchemaV3Json_withoutRoleField_safelyDefaultsToCustomerUponRestore() = runBlocking {
        // Construct legacy JSON simulating schema v3 where "role" was not present
        val legacyJson = """
            {
              "persons": [
                {
                  "id": 1,
                  "name": "Legacy Customer",
                  "phone": "9431733886",
                  "cloudId": "legacy-cloud-uuid-1",
                  "createdAt": 1700000000000,
                  "updatedAt": 1700000000000,
                  "isDeleted": false,
                  "syncPending": false,
                  "isArchived": false
                }
              ],
              "transactions": [
                {
                  "id": "tx-legacy-1",
                  "personId": 1,
                  "amountCents": 50000,
                  "type": "RECEIVE",
                  "createdAt": 1700000000000,
                  "updatedAt": 1700000000000,
                  "isDeleted": false,
                  "syncPending": false
                }
              ],
              "schemaVersion": 3
            }
        """.trimIndent()

        val backupData = gson.fromJson(legacyJson, BackupData::class.java)

        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        BackupManager.restoreDatabaseRecords(
            backupData = backupData,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao
        )

        val restoredPersons = personDao.getAllPersonsIncludingDeleted()
        assertEquals(1, restoredPersons.size)
        assertEquals("Legacy Customer", restoredPersons[0].name)
        assertEquals("Missing role in legacy backup must default to CUSTOMER", PersonRole.CUSTOMER, restoredPersons[0].role)
    }

    @Test
    fun restore_customerAndSupplierWithSamePhone_createsTwoSeparateRecords() = runBlocking {
        val sharedPhone = "9876543210"

        val customer = Person(
            id = 1L,
            name = "Ramesh Kumar (Customer)",
            phone = sharedPhone,
            role = PersonRole.CUSTOMER,
            cloudId = "cust-cloud-1",
            createdAt = now,
            updatedAt = now
        )

        val supplier = Person(
            id = 2L,
            name = "Ramesh Kumar (Supplier)",
            phone = sharedPhone,
            role = PersonRole.SUPPLIER,
            cloudId = "supp-cloud-2",
            createdAt = now,
            updatedAt = now
        )

        val backupData = BackupData(
            persons = listOf(customer, supplier),
            transactions = emptyList()
        )

        val personDao = FakePersonDao()
        val txDao = FakeTransactionDao()
        val ruleDao = FakeRecurringRuleDao()

        BackupManager.restoreDatabaseRecords(
            backupData = backupData,
            personDao = personDao,
            transactionDao = txDao,
            recurringRuleDao = ruleDao
        )

        val allPersons = personDao.getAllPersonsIncludingDeleted()
        assertEquals(2, allPersons.size)

        val custRecord = allPersons.first { it.role == PersonRole.CUSTOMER }
        val suppRecord = allPersons.first { it.role == PersonRole.SUPPLIER }

        assertEquals("Ramesh Kumar (Customer)", custRecord.name)
        assertEquals("Ramesh Kumar (Supplier)", suppRecord.name)
        assertEquals(sharedPhone, custRecord.phone)
        assertEquals(sharedPhone, suppRecord.phone)
        assertTrue("Customer and Supplier must have different database IDs", custRecord.id != suppRecord.id)
    }
}

