package com.kb.ledger.util

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.kb.ledger.data.model.Person
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Represents a candidate phone number attached to a picked device contact.
 */
data class PickedPhoneOption(
    val number: String,
    val label: String = ""
)

/**
 * Structured result extracted from the system contact picker without mutating the source contact.
 */
data class PickedContactResult(
    val displayName: String,
    val phoneNumbers: List<PickedPhoneOption>,
    val contactId: String? = null
)

class ContactManager(private val context: Context) {

    /**
     * Resolves display name and phone number(s) from a URI returned by Android's contact picker.
     *
     * Supports:
     * 1. Privacy-preserving single-row data URIs returned by
     *    `Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)`
     *    using transient URI permission (no READ_CONTACTS permission required).
     * 2. Contact lookup URIs (e.g. from `PickContact` fallback), safely querying entity/phone
     *    tables when permitted and handling 0, 1, or multiple phone numbers gracefully.
     */
    suspend fun resolvePickedContact(uri: Uri): PickedContactResult? = withContext(Dispatchers.IO) {
        try {
            var displayName = ""
            var contactId: String? = null
            var hasNumberColumn = false
            val rawOptions = mutableListOf<PickedPhoneOption>()

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        .takeIf { it >= 0 }
                        ?: cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        displayName = cursor.getString(nameIdx)?.trim() ?: ""
                    }

                    val cidIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                        .takeIf { it >= 0 }
                        ?: cursor.getColumnIndex(ContactsContract.Contacts._ID)
                    if (cidIdx >= 0) {
                        contactId = cursor.getString(cidIdx)
                    }

                    val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIdx >= 0) {
                        hasNumberColumn = true
                        val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                        val labelIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                        do {
                            val number = cursor.getString(numberIdx)?.trim().orEmpty()
                            if (number.isNotBlank()) {
                                val phoneType = if (typeIdx >= 0) cursor.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                                val customLabel = if (labelIdx >= 0) cursor.getString(labelIdx) else null
                                val typeLabel = formatPhoneTypeLabel(phoneType, customLabel, context.resources)
                                rawOptions.add(PickedPhoneOption(number = number, label = typeLabel))
                            }
                        } while (cursor.moveToNext())
                    }
                }
            }

            // Fallback when a contact-level URI was returned (no NUMBER column on the row itself)
            if (!hasNumberColumn) {
                try {
                    val entityUri = Uri.withAppendedPath(uri, ContactsContract.Contacts.Entity.CONTENT_DIRECTORY)
                    val projection = arrayOf(
                        ContactsContract.Contacts.Entity.CONTACT_ID,
                        ContactsContract.Contacts.Entity.DISPLAY_NAME,
                        ContactsContract.Contacts.Entity.MIMETYPE,
                        ContactsContract.Contacts.Entity.DATA1,
                        ContactsContract.Contacts.Entity.DATA2,
                        ContactsContract.Contacts.Entity.DATA3
                    )
                    context.contentResolver.query(entityUri, projection, null, null, null)?.use { c ->
                        val cidIdx = c.getColumnIndex(ContactsContract.Contacts.Entity.CONTACT_ID)
                        val nameIdx = c.getColumnIndex(ContactsContract.Contacts.Entity.DISPLAY_NAME)
                        val mimeIdx = c.getColumnIndex(ContactsContract.Contacts.Entity.MIMETYPE)
                        val data1Idx = c.getColumnIndex(ContactsContract.Contacts.Entity.DATA1)
                        val data2Idx = c.getColumnIndex(ContactsContract.Contacts.Entity.DATA2)
                        val data3Idx = c.getColumnIndex(ContactsContract.Contacts.Entity.DATA3)
                        while (c.moveToNext()) {
                            if (contactId.isNullOrBlank() && cidIdx >= 0) {
                                contactId = c.getString(cidIdx)
                            }
                            if (displayName.isBlank() && nameIdx >= 0) {
                                displayName = c.getString(nameIdx)?.trim() ?: ""
                            }
                            val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                            if (mime == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE && data1Idx >= 0) {
                                val number = c.getString(data1Idx)?.trim().orEmpty()
                                if (number.isNotBlank()) {
                                    val phoneType = if (data2Idx >= 0) c.getInt(data2Idx) else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                                    val customLabel = if (data3Idx >= 0) c.getString(data3Idx) else null
                                    val typeLabel = formatPhoneTypeLabel(phoneType, customLabel, context.resources)
                                    rawOptions.add(PickedPhoneOption(number = number, label = typeLabel))
                                }
                            }
                        }
                    }
                } catch (_: SecurityException) {
                    // Transient grant may be limited when READ_CONTACTS is denied; fallback below checks permission explicitly
                } catch (e: Exception) {
                    SafeLogger.log("ContactManager", "Entity directory query fallback failed", e)
                }

                // If entity URI was restricted and READ_CONTACTS permission is already granted, query Phone table by contactId
                if (rawOptions.isEmpty() && !contactId.isNullOrBlank() &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        val projection = arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.LABEL
                        )
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            projection,
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(contactId!!),
                            null
                        )?.use { c ->
                            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                            val numberIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            val typeIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                            val labelIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                            while (c.moveToNext()) {
                                if (displayName.isBlank() && nameIdx >= 0) {
                                    displayName = c.getString(nameIdx)?.trim() ?: ""
                                }
                                if (numberIdx >= 0) {
                                    val number = c.getString(numberIdx)?.trim().orEmpty()
                                    if (number.isNotBlank()) {
                                        val phoneType = if (typeIdx >= 0) c.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                                        val customLabel = if (labelIdx >= 0) c.getString(labelIdx) else null
                                        val typeLabel = formatPhoneTypeLabel(phoneType, customLabel, context.resources)
                                        rawOptions.add(PickedPhoneOption(number = number, label = typeLabel))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        SafeLogger.log("ContactManager", "Phone table fallback failed", e)
                    }
                }
            }

            val deduplicated = deduplicatePhoneOptions(rawOptions)
            if (displayName.isBlank() && deduplicated.isEmpty()) {
                return@withContext null
            }

            PickedContactResult(
                displayName = displayName,
                phoneNumbers = deduplicated,
                contactId = contactId
            )
        } catch (e: SecurityException) {
            SafeLogger.log("ContactManager", "SecurityException reading picked contact", e)
            null
        } catch (e: Exception) {
            SafeLogger.log("ContactManager", "Error resolving picked contact", e)
            null
        }
    }

    suspend fun fetchPhoneContacts(): List<Person> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<Person>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val contactIdIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)

            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                val phone = it.getString(phoneIndex) ?: continue
                val contactId = it.getString(contactIdIndex)
                contacts.add(Person(name = name, phone = phone, contactId = contactId))
            }
        }
        contacts
    }

    suspend fun saveToPhoneContacts(name: String, phone: String) = withContext(Dispatchers.IO) {
        val ops = ArrayList<ContentProviderOperation>()

        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build())

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build())

        if (phone.isNotBlank()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())
        }

        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            SafeLogger.log("ContactManager", "Failed to save contact to address book", e)
        }
    }

    companion object {
        /**
         * Creates a privacy-preserving system contact picker intent targeting phone-number rows.
         * Android grants transient read access to the single picked row without needing READ_CONTACTS.
         */
        fun createPhonePickIntent(): Intent =
            Intent(Intent.ACTION_PICK).apply {
                type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
            }

        /**
         * Creates a general system contact picker intent as a fallback when no phone-row picker exists.
         */
        fun createContactPickIntent(): Intent =
            Intent(Intent.ACTION_PICK).apply {
                type = ContactsContract.Contacts.CONTENT_TYPE
            }

        /**
         * Deduplicates phone options that share the same normalized phone number while preserving
         * original human-readable formatting and label of the first occurrence.
         */
        fun deduplicatePhoneOptions(options: List<PickedPhoneOption>): List<PickedPhoneOption> {
            val seen = LinkedHashSet<String>()
            val result = mutableListOf<PickedPhoneOption>()
            for (option in options) {
                val trimmedNumber = option.number.trim()
                if (trimmedNumber.isBlank()) continue
                val key = ContactSearchHelper.normalizePhone(trimmedNumber).ifBlank { trimmedNumber }
                if (seen.add(key)) {
                    result.add(option.copy(number = trimmedNumber))
                }
            }
            return result
        }

        /**
         * Formats Android [ContactsContract.CommonDataKinds.Phone.TYPE] into a concise readable label.
         */
        fun formatPhoneTypeLabel(type: Int, customLabel: String?, resources: Resources?): String {
            return if (resources != null) {
                ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, type, customLabel ?: "").toString()
            } else {
                when (type) {
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                    ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                    ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                    ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"
                    ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> customLabel?.takeIf { it.isNotBlank() } ?: "Custom"
                    else -> "Phone"
                }
            }
        }
    }
}
