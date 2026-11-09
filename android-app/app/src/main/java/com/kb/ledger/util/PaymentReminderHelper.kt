package com.kb.ledger.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Utility for generating respectful, customer-friendly payment reminder messages and
 * user-approved handoff Intents (WhatsApp, SMS, generic Share).
 *
 * Invariants:
 * - Never transmits external messages silently. All communication requires explicit user review and confirmation via system intents.
 * - Supports sending messages for positive balances (reminders), settled accounts (updates), and credit balances.
 * - Amounts are formatted cleanly in ₹ using integer cents.
 */
object PaymentReminderHelper {

    /**
     * Normalizes a phone number for the WhatsApp click-to-chat API.
     * WhatsApp requires international E.164 digits without '+' or leading trunk '0's.
     * For 10-digit Indian mobile numbers (starting with 6, 7, 8, 9), prepends '91'.
     * Strips whitespace, dashes, and brackets.
     * Returns null if the phone is blank or contains insufficient digits.
     */
    fun normalizeWhatsAppPhone(phone: String, defaultCountryCode: String = "91"): String? {
        val trimmed = phone.trim()
        if (trimmed.isEmpty()) return null

        val hasPlus = trimmed.startsWith("+")
        var digits = trimmed.replace(Regex("[^0-9]"), "")
        if (digits.isEmpty()) return null

        // Strip leading '00' international prefix if present
        if (digits.startsWith("00")) {
            digits = digits.substring(2)
        }

        return when {
            // Explicit '+' provided with valid length (>= 10)
            hasPlus && digits.length >= 10 -> digits
            // 11 digits starting with trunk '0' (e.g. 09431733886)
            digits.length == 11 && digits.startsWith("0") -> defaultCountryCode + digits.substring(1)
            // Standard 10-digit mobile number
            digits.length == 10 -> defaultCountryCode + digits
            // Already includes 12-digit Indian country code '91'
            digits.length == 12 && digits.startsWith(defaultCountryCode) -> digits
            // International phone numbers (11 to 15 digits)
            digits.length in 11..15 -> digits
            // Fallback for at least 7 digits
            digits.length >= 7 -> digits
            else -> null
        }
    }

    /**
     * Generates a courteous, pre-filled message for payment reminder or account status.
     * Works for outstanding balances, settled accounts, and credit balances.
     */
    fun buildReminderMessage(
        personName: String,
        balanceCents: Long,
        senderBusinessName: String? = "Ledger"
    ): String {
        val absBalance = kotlin.math.abs(balanceCents)
        val dollars = absBalance / 100
        val cents = absBalance % 100
        val amountStr = if (cents == 0L) "$dollars" else String.format(Locale.US, "%d.%02d", dollars, cents)
        val signature = senderBusinessName?.takeIf { it.isNotBlank() }?.let { " with $it" } ?: ""

        return when {
            balanceCents > 0 -> {
                "Hi $personName, this is a gentle reminder regarding your outstanding balance of ₹$amountStr$signature. Kindly clear it when convenient. Thank you!"
            }
            balanceCents < 0 -> {
                "Hi $personName, your account$signature currently has a balance of ₹$amountStr in your favor (You Give). Thank you!"
            }
            else -> {
                "Hi $personName, your ledger account$signature is completely settled (₹0.00 balance). Thank you for your business!"
            }
        }
    }

    /**
     * Builds official WhatsApp click-to-chat URL.
     * Uses normalized international phone number if available.
     * Properly UTF-8 encodes special characters, spaces, and line breaks.
     */
    fun buildWhatsAppUrl(phone: String, message: String): String {
        val normalized = normalizeWhatsAppPhone(phone)
        val encodedMsg = URLEncoder.encode(message, StandardCharsets.UTF_8.name())
        return if (!normalized.isNullOrEmpty()) {
            "https://api.whatsapp.com/send?phone=$normalized&text=$encodedMsg"
        } else {
            "https://api.whatsapp.com/send?text=$encodedMsg"
        }
    }

    /**
     * Builds standard smsto: URI string.
     */
    fun buildSmsUriString(phone: String): String {
        val cleanPhone = phone.replace(Regex("[^0-9+]"), "")
        return if (cleanPhone.isNotEmpty()) "smsto:$cleanPhone" else "smsto:"
    }

    /**
     * Creates an Intent to send the message via WhatsApp.
     */
    fun createWhatsAppIntent(phone: String, message: String): Intent {
        val url = buildWhatsAppUrl(phone, message)
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    /**
     * Creates an Intent to open the SMS app with pre-filled message and recipient.
     * Supplies both standard EXTRA_TEXT and vendor sms_body for compatibility.
     */
    fun createSmsIntent(phone: String, message: String): Intent {
        val uriStr = buildSmsUriString(phone)
        return Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse(uriStr)
            putExtra("sms_body", message)
            putExtra(Intent.EXTRA_TEXT, message)
        }
    }

    /**
     * Creates a generic Android share chooser Intent allowing the user to select any messaging app.
     */
    fun createShareChooserIntent(context: Context, message: String, title: String = "Share Ledger Statement"): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        return Intent.createChooser(sendIntent, title)
    }
}
