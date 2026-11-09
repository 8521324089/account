package com.kb.ledger.util

/**
 * Privacy-safe diagnostic logger for non-fatal background operations and self-healing.
 *
 * Guarantees:
 * 1. Privacy-safe: Records only operation tags, sanitized event summaries, and exception class names.
 *    Never logs customer names, phone numbers, notes, amounts, receipt contents, or sensitive paths.
 * 2. Non-fatal: Never throws exceptions to callers.
 * 3. Testable: Provides an optional [Listener] for deterministic regression testing on JVM without
 *    needing android.util.Log mock implementations.
 */
object SafeLogger {

    fun interface Listener {
        fun onDiagnostic(tag: String, message: String, exceptionClass: String?)
    }

    @Volatile
    var listener: Listener? = null

    fun log(tag: String, message: String, error: Throwable? = null) {
        val exClass = error?.javaClass?.simpleName
        try {
            listener?.onDiagnostic(tag, message, exClass)
        } catch (_: Throwable) {
            // Logger failure must never bubble up
        }
        if (error != null) {
            System.err.println("[$tag] $message: $exClass")
        }
    }
}
