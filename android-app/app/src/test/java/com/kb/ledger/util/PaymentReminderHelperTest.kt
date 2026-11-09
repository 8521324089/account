package com.kb.ledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentReminderHelperTest {

    @Test
    fun `settled or negative balances return appropriate courteous message`() {
        val settledMsg = PaymentReminderHelper.buildReminderMessage("Alice", 0L)
        assertNotNull(settledMsg)
        assertTrue(settledMsg.contains("Alice"))
        assertTrue(settledMsg.contains("completely settled"))

        val creditMsg = PaymentReminderHelper.buildReminderMessage("Bob", -5000L)
        assertNotNull(creditMsg)
        assertTrue(creditMsg.contains("Bob"))
        assertTrue(creditMsg.contains("₹50"))
        assertTrue(creditMsg.contains("You Give"))
    }

    @Test
    fun `positive balances format clean reminder message`() {
        val msgWhole = PaymentReminderHelper.buildReminderMessage("Ramesh", 150000L, "Kiran Store")
        assertNotNull(msgWhole)
        assertTrue(msgWhole.contains("Hi Ramesh"))
        assertTrue(msgWhole.contains("₹1500"))
        assertTrue(msgWhole.contains("with Kiran Store"))

        val msgDecimal = PaymentReminderHelper.buildReminderMessage("Suresh", 45075L, null)
        assertNotNull(msgDecimal)
        assertTrue(msgDecimal.contains("₹450.75"))
    }

    @Test
    fun `normalizeWhatsAppPhone formats Indian and international numbers correctly`() {
        // Standard 10-digit Indian mobile
        assertEquals("919431733886", PaymentReminderHelper.normalizeWhatsAppPhone("9431733886"))

        // 11-digit with leading trunk 0
        assertEquals("919431733886", PaymentReminderHelper.normalizeWhatsAppPhone("09431733886"))

        // Formatted with spaces and dashes
        assertEquals("919876543210", PaymentReminderHelper.normalizeWhatsAppPhone("+91 98765-43210"))

        // Already 12-digit without plus
        assertEquals("919876543210", PaymentReminderHelper.normalizeWhatsAppPhone("919876543210"))

        // US number with plus
        assertEquals("15551234567", PaymentReminderHelper.normalizeWhatsAppPhone("+1 (555) 123-4567"))

        // Empty or invalid
        assertNull(PaymentReminderHelper.normalizeWhatsAppPhone(""))
        assertNull(PaymentReminderHelper.normalizeWhatsAppPhone("abc"))
        assertNull(PaymentReminderHelper.normalizeWhatsAppPhone("123"))
    }

    @Test
    fun `buildWhatsAppUrl formats url and sanitizes phone`() {
        val url = PaymentReminderHelper.buildWhatsAppUrl("9431733886", "Payment reminder for ₹500")
        assertTrue(url.startsWith("https://api.whatsapp.com/send?phone=919431733886"))
        assertTrue(url.contains("text=Payment+reminder+for+%E2%82%B9500"))
    }

    @Test
    fun `buildSmsUriString creates valid smsto Uri string`() {
        val uriStr = PaymentReminderHelper.buildSmsUriString("+91 98765-43210")
        assertEquals("smsto:+919876543210", uriStr)
    }
}
