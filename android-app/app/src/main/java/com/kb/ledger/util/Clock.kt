package com.kb.ledger.util

/**
 * Testable clock abstraction.
 * Production code uses SystemClock; tests inject a fake.
 * Returns UTC epoch milliseconds.
 */
interface Clock {
    fun nowMillis(): Long
    fun nowIso8601(): String
}

class SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun nowIso8601(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(nowMillis()))
    }
}
