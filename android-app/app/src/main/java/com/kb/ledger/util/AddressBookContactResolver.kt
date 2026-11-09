package com.kb.ledger.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of an address book contact resolution attempt by phone number.
 */
sealed class ContactResolutionResult {
    /** Exactly one appropriate contact name matched the normalized phone number. */
    data class Match(val displayName: String) : ContactResolutionResult()

    /** No contact in the address book matched the normalized phone number. */
    object NoMatch : ContactResolutionResult()

    /** Multiple distinct contact names matched the normalized phone number (ambiguous). */
    data class Ambiguous(val candidateNames: List<String>) : ContactResolutionResult()

    /** Contact permission was denied, or the content provider query failed. */
    data class PermissionDeniedOrError(val message: String) : ContactResolutionResult()
}

/**
 * Resolves contact display names from a device's address book by phone number.
 */
interface AddressBookContactResolver {
    /**
     * Resolves a contact display name matching [rawPhone].
     *
     * Invariants:
     * - Uses [ContactSearchHelper.normalizePhone] to normalize the target number.
     * - Returns [ContactResolutionResult.Match] only if exactly one distinct name matches.
     * - Returns [ContactResolutionResult.Ambiguous] if multiple distinct names share the number.
     * - Returns [ContactResolutionResult.NoMatch] if no match exists.
     * - Returns [ContactResolutionResult.PermissionDeniedOrError] if permission is missing or query errors.
     */
    suspend fun resolveContact(rawPhone: String): ContactResolutionResult
}

/**
 * Production implementation of [AddressBookContactResolver] querying Android [ContactsContract].
 *
 * Runs strictly on [Dispatchers.IO] and safely guards against permission denials and provider errors.
 */
class PhoneAddressBookResolver(
    private val context: Context
) : AddressBookContactResolver {

    override suspend fun resolveContact(rawPhone: String): ContactResolutionResult = withContext(Dispatchers.IO) {
        val normalizedTarget = ContactSearchHelper.normalizePhone(rawPhone)
        if (normalizedTarget.isBlank()) {
            return@withContext ContactResolutionResult.NoMatch
        }

        // 1. Guard against missing READ_CONTACTS permission
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return@withContext ContactResolutionResult.PermissionDeniedOrError("READ_CONTACTS permission not granted")
            }
        } catch (e: Exception) {
            return@withContext ContactResolutionResult.PermissionDeniedOrError("Permission check failed: ${e.message}")
        }

        // 2. Query phone contacts
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            ) ?: return@withContext ContactResolutionResult.NoMatch

            val matchingNames = mutableSetOf<String>()
            cursor.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val number = c.getString(phoneIdx) ?: continue
                    if (ContactSearchHelper.normalizePhone(number) == normalizedTarget) {
                        val name = c.getString(nameIdx)?.trim()
                        if (!name.isNullOrBlank()) {
                            matchingNames.add(name)
                        }
                    }
                }
            }

            when {
                matchingNames.isEmpty() -> ContactResolutionResult.NoMatch
                matchingNames.size == 1 -> ContactResolutionResult.Match(matchingNames.first())
                else -> ContactResolutionResult.Ambiguous(matchingNames.toList().sorted())
            }
        } catch (e: SecurityException) {
            ContactResolutionResult.PermissionDeniedOrError("SecurityException: ${e.message}")
        } catch (e: Exception) {
            ContactResolutionResult.PermissionDeniedOrError("Query failed: ${e.message}")
        }
    }
}
