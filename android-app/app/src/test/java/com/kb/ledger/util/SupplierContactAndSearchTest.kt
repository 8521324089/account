package com.kb.ledger.util

import com.google.gson.Gson
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.PersonRole
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.data.repository.FakePersonDao
import com.kb.ledger.data.repository.FakeRecurringRuleDao
import com.kb.ledger.data.repository.FakeSyncOperationDao
import com.kb.ledger.data.repository.FakeTransactionDao
import com.kb.ledger.data.repository.OfflineFirstPersonRepository
import com.kb.ledger.data.repository.toEntity
import com.kb.ledger.domain.model.PersonDomain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated verification suite for Supplier contact creation, phone number search,
 * role isolation, duplicate prevention, and backup preservation.
 */
class SupplierContactAndSearchTest {

    // ── 1 & 2. Selecting Phone Contact & Phone Number Population ─────────────

    @Test
    fun contactManager_deduplicatePhoneOptions_handlesMultipleNumbersAndFormatting() {
        val rawOptions = listOf(
            PickedPhoneOption("+91 98765 43210", "Mobile"),
            PickedPhoneOption("9876543210", "Home"),        // Duplicate of same normalized Indian number
            PickedPhoneOption("011-23456789", "Work"),      // Distinct landline
            PickedPhoneOption("   ", "Other")               // Blank ignored
        )

        val deduped = ContactManager.deduplicatePhoneOptions(rawOptions)
        assertEquals(2, deduped.size)
        assertEquals("+91 98765 43210", deduped[0].number)
        assertEquals("Mobile", deduped[0].label)
        assertEquals("011-23456789", deduped[1].number)
        assertEquals("Work", deduped[1].label)
    }

    @Test
    fun contactManager_formatPhoneTypeLabel_returnsReadableLabels() {
        assertEquals(
            "Mobile",
            ContactManager.formatPhoneTypeLabel(
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                null,
                null
            )
        )
        assertEquals(
            "Work",
            ContactManager.formatPhoneTypeLabel(
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
                null,
                null
            )
        )
        assertEquals(
            "Office Desk",
            ContactManager.formatPhoneTypeLabel(
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM,
                "Office Desk",
                null
            )
        )
    }

    @Test
    fun splitInitialPersonQuery_populatesPhoneWhenPhoneLikeAndNameOtherwise() {
        assertEquals("" to "9876543210", ContactSearchHelper.splitInitialPersonQuery("9876543210"))
        assertEquals("" to "+91 98765 43210", ContactSearchHelper.splitInitialPersonQuery(" +91 98765 43210 "))
        assertEquals("" to "011-23456789", ContactSearchHelper.splitInitialPersonQuery("011-23456789"))
        assertEquals("ABC Distributors" to "", ContactSearchHelper.splitInitialPersonQuery("ABC Distributors"))
        assertEquals("Shop 2" to "", ContactSearchHelper.splitInitialPersonQuery("Shop 2"))
        assertEquals("" to "", ContactSearchHelper.splitInitialPersonQuery("   "))
    }

    // ── 3 & 4. Editing Phone Before Saving & Creating Supplier Without Phone ─

    @Test
    fun repository_creatingAndEditingSupplierPhone_andSupplierWithoutPhone_worksCleanly() = runBlocking {
        val personDao = FakePersonDao()
        val syncOpDao = FakeSyncOperationDao()
        val repo = OfflineFirstPersonRepository(personDao, syncOpDao)

        // Simulate contact picked with formatted number then edited before save
        val editedPhone = "+91 98765 43210"
        val saveWithPhone = repo.savePerson(
            PersonDomain(
                name = "ABC Distributors",
                phone = editedPhone,
                contactId = "contact-42",
                role = PersonRole.SUPPLIER
            )
        )
        assertTrue(saveWithPhone is AppResult.Success)
        val savedSupplier = (saveWithPhone as AppResult.Success).data.toEntity()
        assertEquals(PersonRole.SUPPLIER, savedSupplier.role)
        assertEquals("+91 98765 43210", savedSupplier.phone)
        assertEquals("contact-42", savedSupplier.contactId)

        // Supplier without phone number saves gracefully
        val saveWithoutPhone = repo.savePerson(
            PersonDomain(
                name = "Local Cash Vendor",
                phone = "",
                role = PersonRole.SUPPLIER
            )
        )
        assertTrue(saveWithoutPhone is AppResult.Success)
        val noPhoneSupplier = (saveWithoutPhone as AppResult.Success).data.toEntity()
        assertEquals(PersonRole.SUPPLIER, noPhoneSupplier.role)
        assertEquals("", noPhoneSupplier.phone)
    }

    // ── 5, 6, 7, 8. Supplier Search: Name, Complete, Partial, Spaces, Country Code ─

    @Test
    fun supplierSearch_matchesNameCompletePartialSpacesAndCountryCode_withoutMutatingStoredPhone() {
        val originalStoredPhone = "9876543210"
        val supplier = Person(
            id = 10L,
            name = "ABC Distributors",
            phone = originalStoredPhone,
            role = PersonRole.SUPPLIER
        )

        // Exact examples from specification
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "ABC"))
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "Distributors"))
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "9876543210"))
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "987654"))
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "3210"))

        // Spaces & formatting variations
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "98765 43210"))
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "987 654"))
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "987-654-3210"))

        // Country code & trunk prefix (complete and partial)
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "+91 98765 43210"))
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "+919876543210"))
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "91 9876543210"))
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "+91 98765"))
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "91 98765"))
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "09876543210"))
        assertTrue(ContactSearchHelper.matchesQuery(supplier, "09876"))

        // Original phone must never be altered
        assertEquals(originalStoredPhone, supplier.phone)

        // Short queries or mixed text must not false-match unrelated digits
        assertFalse(ContactSearchHelper.matchesQuery(supplier, "912"))
        assertFalse(ContactSearchHelper.matchesQuery(supplier, "Shop 9"))
    }

    @Test
    fun supplierSearch_storedWithCountryCodeAndSpaces_matchesLocalAndFormattedQueries() {
        val supplierWithCc = Person(
            id = 11L,
            name = "Global Steel",
            phone = "+91 98765 43210",
            role = PersonRole.SUPPLIER
        )

        assertTrue(ContactSearchHelper.matchesQuery(supplierWithCc, "9876543210"))
        assertTrue(ContactSearchHelper.matchesQuery(supplierWithCc, "987654"))
        assertTrue(ContactSearchHelper.matchesQuery(supplierWithCc, "3210"))
        assertTrue(ContactSearchHelper.matchesQuery(supplierWithCc, "+91 98765"))
        assertTrue(ContactSearchHelper.matchesQuery(supplierWithCc, "Global"))
        assertEquals("+91 98765 43210", supplierWithCc.phone)
    }

    // ── 9, 10, 11. Strict Role Isolation & Customer Search Regression ───────

    @Test
    fun roleIsolatedSearch_preventsCrossRoleLeakageEvenWithIdenticalPhoneAndName() {
        val customer = Person(
            id = 1L,
            name = "Apex Enterprise",
            phone = "9876543210",
            role = PersonRole.CUSTOMER
        )
        val supplier = Person(
            id = 2L,
            name = "Apex Enterprise",
            phone = "9876543210",
            role = PersonRole.SUPPLIER
        )
        val supplierOnly = Person(
            id = 3L,
            name = "ABC Distributors",
            phone = "+91 98111 22222",
            role = PersonRole.SUPPLIER
        )
        val customerOnly = Person(
            id = 4L,
            name = "Ravi Store",
            phone = "9822233344",
            role = PersonRole.CUSTOMER
        )
        val all = listOf(customer, supplier, supplierOnly, customerOnly)

        // Customer search never returns SUPPLIER records
        val customerPhoneSearch = ContactSearchHelper.filterAndGroupForRole(all, PersonRole.CUSTOMER, "9811122222")
        assertTrue(customerPhoneSearch.isEmpty())

        val sharedInCustomerMode = ContactSearchHelper.filterAndGroupForRole(all, PersonRole.CUSTOMER, "9876543210")
            .flatMap { it.contacts }
            .map { it.person }
        assertEquals(1, sharedInCustomerMode.size)
        assertEquals(PersonRole.CUSTOMER, sharedInCustomerMode[0].role)
        assertEquals(1L, sharedInCustomerMode[0].id)

        // Supplier search never returns CUSTOMER records
        val supplierPhoneSearch = ContactSearchHelper.filterAndGroupForRole(all, PersonRole.SUPPLIER, "9822233344")
        assertTrue(supplierPhoneSearch.isEmpty())

        val sharedInSupplierMode = ContactSearchHelper.filterAndGroupForRole(all, PersonRole.SUPPLIER, "987654")
            .flatMap { it.contacts }
            .map { it.person }
        assertEquals(1, sharedInSupplierMode.size)
        assertEquals(PersonRole.SUPPLIER, sharedInSupplierMode[0].role)
        assertEquals(2L, sharedInSupplierMode[0].id)
    }

    @Test
    fun duplicateDetection_preventsDuplicateWithinRole_whileAllowingCrossRoleSamePhone() {
        val existing = listOf(
            Person(id = 1L, name = "ABC Distributors", phone = "9876543210", role = PersonRole.SUPPLIER),
            Person(id = 2L, name = "Ravi Kumar", phone = "9123456789", role = PersonRole.CUSTOMER)
        )

        // Same supplier name & normalized phone (with +91 and spaces) -> detected as exact duplicate
        val exactDup = ContactSearchHelper.findExactDuplicate(
            persons = existing,
            name = "  abc distributors ",
            phone = "+91 98765 43210",
            role = PersonRole.SUPPLIER
        )
        assertNotNull(exactDup)
        assertEquals(1L, exactDup!!.id)

        // Phone match with slightly different supplier name -> flagged by findPhoneDuplicate
        val phoneDup = ContactSearchHelper.findPhoneDuplicate(
            persons = existing,
            phone = "09876543210",
            role = PersonRole.SUPPLIER
        )
        assertNotNull(phoneDup)
        assertEquals(1L, phoneDup!!.id)

        // Same phone in CUSTOMER role must NOT block creating a SUPPLIER with that phone
        assertNull(
            ContactSearchHelper.findExactDuplicate(existing, "Ravi Kumar", "9123456789", PersonRole.SUPPLIER)
        )
        assertNull(
            ContactSearchHelper.findPhoneDuplicate(existing, "9123456789", PersonRole.SUPPLIER)
        )
    }

    // ── 12. Backup & Restore Preserve Supplier Phone Numbers ─────────────────

    @Test
    fun backupAndRestore_preservesSupplierPhoneNumberContactIdAndRole() = runBlocking {
        val supplier = Person(
            id = 5L,
            name = "ABC Distributors",
            phone = "+91 98765 43210",
            contactId = "cnt-99",
            role = PersonRole.SUPPLIER,
            cloudId = "supp-uuid-5",
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L
        )
        val noPhoneSupplier = Person(
            id = 6L,
            name = "Local Cash Vendor",
            phone = "",
            role = PersonRole.SUPPLIER,
            cloudId = "supp-uuid-6",
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L
        )
        val customerSamePhone = Person(
            id = 7L,
            name = "ABC Customer",
            phone = "9876543210",
            role = PersonRole.CUSTOMER,
            cloudId = "cust-uuid-7",
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L
        )

        val backup = BackupData(
            persons = listOf(supplier, noPhoneSupplier, customerSamePhone),
            transactions = listOf(
                Transaction(
                    id = "tx-supp-1",
                    personId = 5L,
                    amountCents = 150000L,
                    type = TransactionType.RECEIVE,
                    createdAt = 1700000001000L,
                    updatedAt = 1700000001000L
                )
            )
        )

        val json = Gson().toJson(backup)
        val parsed = Gson().fromJson(json, BackupData::class.java)

        val targetPersonDao = FakePersonDao()
        val targetTxDao = FakeTransactionDao()
        val targetRuleDao = FakeRecurringRuleDao()

        BackupManager.restoreDatabaseRecords(
            backupData = parsed,
            personDao = targetPersonDao,
            transactionDao = targetTxDao,
            recurringRuleDao = targetRuleDao
        )

        val restoredSuppliers = targetPersonDao.getAllPersonsListByRole(PersonRole.SUPPLIER)
        assertEquals(2, restoredSuppliers.size)

        val restoredWithPhone = restoredSuppliers.first { it.name == "ABC Distributors" }
        assertEquals("+91 98765 43210", restoredWithPhone.phone)
        assertEquals("cnt-99", restoredWithPhone.contactId)
        assertEquals(PersonRole.SUPPLIER, restoredWithPhone.role)

        val restoredNoPhone = restoredSuppliers.first { it.name == "Local Cash Vendor" }
        assertEquals("", restoredNoPhone.phone)
        assertEquals(PersonRole.SUPPLIER, restoredNoPhone.role)

        // Restored supplier remains searchable by phone & partial phone after restore
        val matches = ContactSearchHelper.filterAndGroupForRole(
            restoredSuppliers,
            PersonRole.SUPPLIER,
            "987654"
        )
        assertEquals(1, matches.size)
        assertEquals("+91 98765 43210", matches[0].contacts[0].person.phone)
    }
}

