package com.kb.ledger.util

import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.PersonRole
import java.util.Locale

/**
 * Represents an individual contact with contextual disambiguation details.
 * Internal IDs and record numbering are omitted from user-visible labels.
 */
data class PersonWithDisambiguation(
    val person: Person,
    val disambiguationLabel: String,
    val lastTxTime: Long? = null
)

/**
 * Represents a group of contacts sharing the same normalized name.
 */
data class ContactGroup(
    val normalizedName: String,
    val displayName: String,
    val contacts: List<PersonWithDisambiguation>
) {
    val isGrouped: Boolean get() = contacts.size > 1
}

object ContactSearchHelper {

    /**
     * Normalizes a name:
     * - Trims leading and trailing whitespace.
     * - Collapses multiple internal whitespace characters to a single space.
     * - Converts to lowercase.
     */
    fun normalizeName(name: String?): String {
        if (name.isNullOrBlank()) return ""
        return name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.getDefault())
    }

    /**
     * Normalizes a phone number for comparison:
     * 1. Trims leading and trailing whitespace.
     * 2. Removes all spaces within the phone number (including multiple consecutive spaces).
     * 3. Removes hyphens, parentheses, dots, and slashes.
     * 4. Handles Indian 10-digit mobile numbers consistently:
     *    - A leading trunk prefix '0' (11 digits, starts with '0' followed by [6-9]\d{9}) is stripped.
     *    - Leading '+91' or '91' prefix followed by [6-9]\d{9} is stripped.
     *    For example: "09431733886", "9431733 886", and "94 31733886" all normalize to "9431733886".
     * 5. Does not strip leading zeros from landlines or other non-mobile numbers.
     */
    fun normalizePhone(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        val trimmed = phone.trim()
        val cleaned = trimmed.replace(Regex("[\\s\\-\\(\\)\\./]"), "")

        // Indian 10-digit mobile number starting with 6, 7, 8, or 9:
        // - Trunk prefix '0' followed by 10-digit mobile (length 11, starts with 0[6-9])
        if (cleaned.length == 11 && cleaned.startsWith("0") && cleaned[1] in '6'..'9') {
            return cleaned.substring(1)
        }
        // - International prefix '+91' followed by 10-digit mobile (length 13, starts with +91[6-9])
        if (cleaned.startsWith("+91") && cleaned.length == 13 && cleaned[3] in '6'..'9') {
            return cleaned.substring(3)
        }
        // - Country code prefix '91' followed by 10-digit mobile (length 12, starts with 91[6-9])
        if (cleaned.startsWith("91") && cleaned.length == 12 && cleaned[2] in '6'..'9') {
            return cleaned.substring(2)
        }

        return cleaned
    }

    /**
     * Legacy normalization helper (normalizes name).
     */
    fun normalize(name: String): String = normalizeName(name)

    /**
     * Composite comparison key: role + "|||" + normalized name + "|||" + normalized phone.
     * Including role ensures Customers and Suppliers sharing the same name and mobile number
     * are never treated as duplicate records and are kept strictly distinct.
     */
    fun comparisonKey(person: Person): String {
        val normName = normalizeName(person.name)
        val normPhone = normalizePhone(person.phone)
        return "${person.role.name}|||$normName|||$normPhone"
    }

    /**
     * Checks if a person matches the user search query.
     * Supports full and partial matches across names and phone numbers (including
     * numbers formatted with spaces, hyphens, or leading country/trunk codes).
     * Never mutates the stored person phone number.
     */
    fun matchesQuery(person: Person, query: String): Boolean {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return true

        val normName = normalizeName(person.name)
        val queryNormName = normalizeName(trimmedQuery)
        if (queryNormName.isNotEmpty() && normName.contains(queryNormName)) return true

        val normPhone = normalizePhone(person.phone)
        val queryNormPhone = normalizePhone(trimmedQuery)
        if (queryNormPhone.isNotEmpty() && normPhone.contains(queryNormPhone)) return true

        // Direct raw contains fallback for partial matches
        val queryLower = trimmedQuery.lowercase(Locale.getDefault())
        if (person.name.lowercase(Locale.getDefault()).contains(queryLower)) return true
        if (person.phone.contains(trimmedQuery)) return true

        // Enhanced digit-based matching when the query is phone-like (contains digits, no letters)
        if (!trimmedQuery.any { it.isLetter() }) {
            val queryDigits = trimmedQuery.filter { it.isDigit() }
            if (queryDigits.isNotEmpty()) {
                val personDigits = person.phone.filter { it.isDigit() }
                if (personDigits.contains(queryDigits)) return true

                if (normPhone.length == 10 && normPhone[0] in '6'..'9') {
                    if ("91$normPhone".contains(queryDigits) ||
                        "0091$normPhone".contains(queryDigits) ||
                        "0$normPhone".contains(queryDigits)
                    ) {
                        return true
                    }
                }

                // Full international query matching stored national number without country code
                if (personDigits.length >= 7 && queryDigits.length > personDigits.length && queryDigits.endsWith(personDigits)) {
                    return true
                }

                // Partial query starting with country code or trunk zero (e.g. "+91 98765", "91 98765", "09876")
                val stripped = stripLeadingCountryOrTrunkForSearch(trimmedQuery, queryDigits)
                if (stripped != null && stripped.length >= 4) {
                    if (normPhone.contains(stripped) || personDigits.contains(stripped)) return true
                }
            }
        }

        return false
    }

    private fun stripLeadingCountryOrTrunkForSearch(trimmedQuery: String, queryDigits: String): String? {
        if (queryDigits.isEmpty()) return null

        if (trimmedQuery.startsWith("+")) {
            if (trimmedQuery.startsWith("+91") && queryDigits.startsWith("91") && queryDigits.length > 2) {
                return queryDigits.substring(2)
            }
            val sepIdx = trimmedQuery.indexOfFirst { it == ' ' || it == '-' }
            if (sepIdx in 2..5) {
                val afterCc = trimmedQuery.substring(sepIdx + 1).filter { it.isDigit() }
                if (afterCc.length >= 4) return afterCc
            }
            if (queryDigits.length >= 11) {
                return queryDigits.substring(1)
            }
        }

        if ((trimmedQuery.startsWith("91 ") || trimmedQuery.startsWith("91-")) && queryDigits.length > 2) {
            return queryDigits.substring(2)
        }

        if (queryDigits.startsWith("0") && queryDigits.length >= 5) {
            return queryDigits.substring(1)
        }

        return null
    }

    /**
     * Selects the representative person among duplicates sharing the same name and phone.
     * Prefers the contact with the most recent transaction timestamp, then highest ID.
     */
    fun selectRepresentative(persons: List<Person>, lastTxTimes: Map<Long, Long>): Person {
        return persons.maxWithOrNull(
            compareBy<Person> { lastTxTimes[it.id] ?: -1L }
                .thenBy { it.id }
        ) ?: persons.first()
    }

    /**
     * Filters, deduplicates, and sorts a list of persons for search:
     *
     * 1. Filters matching contacts by query.
     * 2. Deduplicates contacts sharing the same normalized name AND normalized phone number,
     *    deterministically choosing the representative with the most recent transaction.
     * 3. Sorts results:
     *    - Group 1: Contacts with transactions, ordered by most recent transaction first (lastTxTime DESC).
     *    - Group 2: Contacts without transactions, ordered alphabetically by name (NOCASE ASC).
     * 4. Preserves distinction between contacts with the same name but different phone numbers.
     * 5. Omits internal IDs and record numbers from disambiguation labels.
     */
    fun searchAndDeduplicate(
        persons: List<Person>,
        query: String,
        lastTxTimes: Map<Long, Long> = emptyMap()
    ): List<PersonWithDisambiguation> {
        val matched = persons.filter { matchesQuery(it, query) }

        // Group by comparison key (normName + normPhone) to identify exact duplicate records
        val duplicateGroups = LinkedHashMap<String, MutableList<Person>>()
        for (p in matched) {
            val key = comparisonKey(p)
            duplicateGroups.getOrPut(key) { mutableListOf() }.add(p)
        }

        // Select representative for each duplicate group
        val deduplicated = duplicateGroups.values.map { group ->
            selectRepresentative(group, lastTxTimes)
        }

        // Count how many distinct contacts have the same normalized name
        val nameCounts = deduplicated.groupingBy { normalizeName(it.name) }.eachCount()

        // Wrap into PersonWithDisambiguation without internal IDs or record numbers
        val withDisambiguation = deduplicated.map { person ->
            val normName = normalizeName(person.name)
            val sharesName = (nameCounts[normName] ?: 0) > 1
            val phoneText = person.phone.trim().ifBlank { "No phone" }
            val label = if (sharesName) {
                phoneText
            } else {
                phoneText
            }
            PersonWithDisambiguation(
                person = person,
                disambiguationLabel = label,
                lastTxTime = lastTxTimes[person.id]
            )
        }

        // Sort:
        // 1. Contacts with transactions (lastTxTime != null && > 0), ordered by lastTxTime DESC.
        // 2. Contacts without transactions (lastTxTime == null || <= 0), ordered alphabetically by name ASC.
        return withDisambiguation.sortedWith { a, b ->
            val aTime = a.lastTxTime ?: -1L
            val bTime = b.lastTxTime ?: -1L
            val aHasTx = aTime > 0L
            val bHasTx = bTime > 0L

            when {
                aHasTx && bHasTx -> {
                    // Both have transactions: newest first
                    val timeCompare = bTime.compareTo(aTime)
                    if (timeCompare != 0) timeCompare
                    else a.person.name.compareTo(b.person.name, ignoreCase = true)
                }
                aHasTx && !bHasTx -> -1 // a comes before b
                !aHasTx && bHasTx -> 1  // b comes before a
                else -> {
                    // Neither has transactions: alphabetical by name ASC
                    val nameCompare = a.person.name.compareTo(b.person.name, ignoreCase = true)
                    if (nameCompare != 0) nameCompare
                    else a.person.id.compareTo(b.person.id)
                }
            }
        }
    }

    /**
     * Filters and groups a list of persons based on search query into ContactGroups.
     * Uses searchAndDeduplicate to ensure duplicate contacts appear only once,
     * sorted with active transactions first, and no internal IDs displayed.
     */
    fun filterAndGroup(
        persons: List<Person>,
        query: String,
        lastTxTimes: Map<Long, Long> = emptyMap()
    ): List<ContactGroup> {
        val sortedList = searchAndDeduplicate(persons, query, lastTxTimes)

        // Group by normalized name preserving sorted order
        val groupedMap = LinkedHashMap<String, MutableList<PersonWithDisambiguation>>()
        for (item in sortedList) {
            val key = normalizeName(item.person.name)
            groupedMap.getOrPut(key) { mutableListOf() }.add(item)
        }

        return groupedMap.map { (normName, disambiguatedList) ->
            val displayName = disambiguatedList.firstOrNull()?.person?.name?.trim()?.replace(Regex("\\s+"), " ") ?: normName
            ContactGroup(
                normalizedName = normName,
                displayName = displayName,
                contacts = disambiguatedList
            )
        }
    }

    /**
     * Filters and groups non-deleted persons strictly matching [role].
     */
    fun filterAndGroupForRole(
        persons: List<Person>,
        role: PersonRole,
        query: String,
        lastTxTimes: Map<Long, Long> = emptyMap()
    ): List<ContactGroup> {
        val scoped = persons.filter { !it.isDeleted && it.role == role }
        return filterAndGroup(scoped, query, lastTxTimes)
    }

    /**
     * Classifies an initial search query into `(initialName, initialPhone)`.
     * If the query contains digits and no alphabetic letters (phone-like input),
     * populates the phone field instead of mistakenly putting a phone number in the name field.
     */
    fun splitInitialPersonQuery(query: String): Pair<String, String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return "" to ""
        val isPhoneLike = !trimmed.any { it.isLetter() } &&
            trimmed.any { it.isDigit() } &&
            trimmed.all { it.isDigit() || it in "+-(). /" || it.isWhitespace() }
        return if (isPhoneLike) "" to trimmed else trimmed to ""
    }

    /**
     * Finds an existing non-deleted person in the exact same [role] sharing both
     * the same normalized name and normalized phone number.
     */
    fun findExactDuplicate(
        persons: List<Person>,
        name: String,
        phone: String,
        role: PersonRole
    ): Person? {
        val normName = normalizeName(name)
        val normPhone = normalizePhone(phone)
        if (normName.isBlank()) return null
        return persons.firstOrNull { p ->
            !p.isDeleted &&
                p.role == role &&
                normalizeName(p.name) == normName &&
                normalizePhone(p.phone) == normPhone
        }
    }

    /**
     * Finds an existing non-deleted person in the same [role] sharing the same
     * valid normalized phone number (>= 7 digits).
     */
    fun findPhoneDuplicate(
        persons: List<Person>,
        phone: String,
        role: PersonRole
    ): Person? {
        val normPhone = normalizePhone(phone)
        if (normPhone.isBlank() || normPhone.count { it.isDigit() } < 7) return null
        return persons.firstOrNull { p ->
            !p.isDeleted &&
                p.role == role &&
                normalizePhone(p.phone) == normPhone
        }
    }
}
