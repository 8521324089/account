package com.kb.ledger

import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.PersonRole
import com.kb.ledger.util.ContactManager
import com.kb.ledger.util.PickedContactResult
import com.kb.ledger.util.PickedPhoneOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupplierContactUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addPersonDialog_pickContactForSupplier_populatesEditableNameAndPhone() {
        var savedName = ""
        var savedPhone = ""
        var savedContactId: String? = null

        composeRule.setContent {
            MaterialTheme {
                AddPersonDialog(
                    initialName = "",
                    isSupplier = true,
                    existingPersons = emptyList(),
                    onPickContactOverride = { onPicked ->
                        onPicked(
                            PickedContactResult(
                                displayName = "Metro Wholesale",
                                phoneNumbers = listOf(PickedPhoneOption("9876543210", "Mobile")),
                                contactId = "c-101"
                            )
                        )
                    },
                    onDismiss = {},
                    onAdd = { name, phone, _, contactId ->
                        savedName = name
                        savedPhone = phone
                        savedContactId = contactId
                    }
                )
            }
        }

        // Trigger contact picker
        composeRule.onNodeWithText("Pick from Phone Contacts").performClick()

        // Verify name and phone populated cleanly
        composeRule.onNodeWithText("Metro Wholesale").assertIsDisplayed()
        composeRule.onNodeWithText("9876543210").assertIsDisplayed()

        // User edits phone number before saving
        composeRule.onNodeWithText("9876543210").performTextClearance()
        composeRule.onNodeWithText("Phone Number").performTextInput("+91 98765 99999")

        composeRule.onNodeWithText("Create").assertIsEnabled().performClick()

        assertEquals("Metro Wholesale", savedName)
        assertEquals("+91 98765 99999", savedPhone)
        assertEquals("c-101", savedContactId)
    }

    @Test
    fun addPersonDialog_contactWithMultiplePhoneNumbers_showsChooserDialogAndPopulatesChosenNumber() {
        var savedName = ""
        var savedPhone = ""

        composeRule.setContent {
            MaterialTheme {
                AddPersonDialog(
                    initialName = "",
                    isSupplier = true,
                    onPickContactOverride = { onPicked ->
                        onPicked(
                            PickedContactResult(
                                displayName = "Global Hardware",
                                phoneNumbers = listOf(
                                    PickedPhoneOption("+91 98111 22222", "Mobile"),
                                    PickedPhoneOption("011-23456789", "Work")
                                ),
                                contactId = "c-102"
                            )
                        )
                    },
                    onDismiss = {},
                    onAdd = { name, phone, _, _ ->
                        savedName = name
                        savedPhone = phone
                    }
                )
            }
        }

        composeRule.onNodeWithText("Pick from Phone Contacts").performClick()

        // Multi-number chooser dialog appears
        composeRule.onNodeWithText("Select Phone Number").assertIsDisplayed()
        composeRule.onNodeWithText("+91 98111 22222").assertIsDisplayed()
        composeRule.onNodeWithText("011-23456789").assertIsDisplayed()

        // User chooses the Work number
        composeRule.onNodeWithText("011-23456789").performClick()

        // Verify field populated with chosen number
        composeRule.onNodeWithText("011-23456789").assertIsDisplayed()
        composeRule.onNodeWithText("Create").performClick()

        assertEquals("Global Hardware", savedName)
        assertEquals("011-23456789", savedPhone)
    }

    @Test
    fun addPersonDialog_contactWithoutPhoneNumber_handlesGracefullyAndAllowsSaving() {
        var savedName = ""
        var savedPhone = "NOT_EMPTY"

        composeRule.setContent {
            MaterialTheme {
                AddPersonDialog(
                    initialName = "",
                    isSupplier = true,
                    onPickContactOverride = { onPicked ->
                        onPicked(
                            PickedContactResult(
                                displayName = "Local Cash Vendor",
                                phoneNumbers = emptyList(),
                                contactId = "c-103"
                            )
                        )
                    },
                    onDismiss = {},
                    onAdd = { name, phone, _, _ ->
                        savedName = name
                        savedPhone = phone
                    }
                )
            }
        }

        composeRule.onNodeWithText("Pick from Phone Contacts").performClick()

        composeRule.onNodeWithText("Local Cash Vendor").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Selected contact has no phone number. You can enter it manually or save without a number."
        ).assertIsDisplayed()

        composeRule.onNodeWithText("Create").assertIsEnabled().performClick()

        assertEquals("Local Cash Vendor", savedName)
        assertEquals("", savedPhone)
    }

    @Test
    fun addPersonDialog_duplicateSupplier_showsOpenExistingInsteadOfCreatingDuplicate() {
        val existingSupplier = Person(
            id = 55L,
            name = "ABC Distributors",
            phone = "9876543210",
            role = PersonRole.SUPPLIER
        )
        var openedExisting: Person? = null
        var createdNewCount = 0

        composeRule.setContent {
            MaterialTheme {
                AddPersonDialog(
                    initialName = "",
                    isSupplier = true,
                    existingPersons = listOf(existingSupplier),
                    onPickContactOverride = { onPicked ->
                        onPicked(
                            PickedContactResult(
                                displayName = "ABC Distributors",
                                phoneNumbers = listOf(PickedPhoneOption("+91 98765 43210", "Mobile"))
                            )
                        )
                    },
                    onSelectExisting = { openedExisting = it },
                    onDismiss = {},
                    onAdd = { _, _, _, _ -> createdNewCount++ }
                )
            }
        }

        composeRule.onNodeWithText("Pick from Phone Contacts").performClick()

        composeRule.onNodeWithText("Open Existing").assertIsDisplayed().performClick()

        assertNotNull(openedExisting)
        assertEquals(55L, openedExisting!!.id)
        assertEquals(0, createdNewCount)
    }

    @Test
    fun addPersonDialog_initialPhoneQuery_prepopulatesPhoneFieldNotName() {
        var savedName = ""
        var savedPhone = ""

        composeRule.setContent {
            MaterialTheme {
                AddPersonDialog(
                    initialName = "9876543210",
                    isSupplier = true,
                    onDismiss = {},
                    onAdd = { name, phone, _, _ ->
                        savedName = name
                        savedPhone = phone
                    }
                )
            }
        }

        // Phone number must be pre-filled in Phone field; user enters name
        composeRule.onNodeWithText("9876543210").assertIsDisplayed()
        composeRule.onNodeWithText("Supplier Name *").performTextInput("Kiran Traders")
        composeRule.onNodeWithText("Create").performClick()

        assertEquals("Kiran Traders", savedName)
        assertEquals("9876543210", savedPhone)
    }

    @Test
    fun transactionSearchDialog_supplierMode_searchesByPhoneAndIsolatesCustomers() {
        val customer = Person(id = 1L, name = "Ravi Customer", phone = "9000011111", role = PersonRole.CUSTOMER)
        val supplier1 = Person(id = 2L, name = "ABC Distributors", phone = "9876543210", role = PersonRole.SUPPLIER)
        val supplier2 = Person(id = 3L, name = "Delta Logistics", phone = "+91 91111 22222", role = PersonRole.SUPPLIER)

        val selectedPerson = mutableStateOf<Person?>(null)

        composeRule.setContent {
            MaterialTheme {
                TransactionSearchDialog(
                    recentPeople = emptyList(),
                    allPeople = listOf(customer, supplier1, supplier2),
                    isSupplier = true,
                    onDismiss = {},
                    onSelect = { selectedPerson.value = it },
                    onCreateNew = {}
                )
            }
        }

        // Initially displays Suppliers and phone numbers, never Customer
        composeRule.onNodeWithText("ABC Distributors").assertIsDisplayed()
        composeRule.onNodeWithText("9876543210").assertIsDisplayed()
        composeRule.onNodeWithText("Delta Logistics").assertIsDisplayed()
        composeRule.onNodeWithText("Ravi Customer").assertDoesNotExist()

        // Search by partial phone number "987654"
        composeRule.onNodeWithText("Search supplier name or number...").performTextInput("987654")
        composeRule.onNodeWithText("ABC Distributors").assertIsDisplayed()
        composeRule.onNodeWithText("9876543210").assertIsDisplayed()
        composeRule.onNodeWithText("Delta Logistics").assertDoesNotExist()

        // Customer phone query must still return 0 results in Supplier mode
        composeRule.onNodeWithText("987654").performTextClearance()
        composeRule.onNodeWithText("Search supplier name or number...").performTextInput("9000011111")
        composeRule.onNodeWithText("No matching suppliers").assertIsDisplayed()
        composeRule.onNodeWithText("Ravi Customer").assertDoesNotExist()

        // Country code + spaces search finds ABC Distributors
        composeRule.onNodeWithText("9000011111").performTextClearance()
        composeRule.onNodeWithText("Search supplier name or number...").performTextInput("+91 98765")
        composeRule.onNodeWithText("ABC Distributors").assertIsDisplayed()
        composeRule.onNodeWithText("9876543210").performClick()

        assertEquals(2L, selectedPerson.value?.id)
    }

    @Test
    fun contactManager_phonePickIntentUsesPrivacyPreservingPhoneContentType() {
        val intent = ContactManager.createPhonePickIntent()
        assertEquals(Intent.ACTION_PICK, intent.action)
        assertTrue(intent.type == ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)
    }
}

