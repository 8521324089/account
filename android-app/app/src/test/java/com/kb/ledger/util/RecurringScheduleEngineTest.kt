package com.kb.ledger.util

import com.kb.ledger.data.model.RecurrenceFrequency
import com.kb.ledger.data.model.RecurringRule
import com.kb.ledger.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RecurringScheduleEngineTest {

    @Test
    fun `daily frequency advances exactly one calendar day`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 15, 10, 0, 0)
        }
        val next = RecurringScheduleEngine.calculateNextOccurrence(cal.timeInMillis, RecurrenceFrequency.DAILY)
        val nextCal = Calendar.getInstance().apply { timeInMillis = next }

        assertEquals(2026, nextCal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, nextCal.get(Calendar.MONTH))
        assertEquals(16, nextCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `weekly frequency advances exactly seven days`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 1, 10, 0, 0)
        }
        val next = RecurringScheduleEngine.calculateNextOccurrence(cal.timeInMillis, RecurrenceFrequency.WEEKLY)
        val nextCal = Calendar.getInstance().apply { timeInMillis = next }

        assertEquals(8, nextCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `monthly frequency handles month-end clamping`() {
        // Jan 31 -> Feb 28 in a non-leap year
        val cal = Calendar.getInstance().apply {
            set(2025, Calendar.JANUARY, 31, 12, 0, 0)
        }
        val next = RecurringScheduleEngine.calculateNextOccurrence(cal.timeInMillis, RecurrenceFrequency.MONTHLY)
        val nextCal = Calendar.getInstance().apply { timeInMillis = next }

        assertEquals(Calendar.FEBRUARY, nextCal.get(Calendar.MONTH))
        assertEquals(28, nextCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `processRule generates due transactions with deterministic IDs and updates nextExecutionDate`() {
        val startCal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 1, 10, 0, 0)
        }
        val rule = RecurringRule(
            id = "rule-rent",
            personId = 50L,
            amountCents = 1500000L, // ₹15,000
            type = TransactionType.RECEIVE,
            note = "Shop rent",
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = startCal.timeInMillis,
            nextExecutionDate = startCal.timeInMillis,
            endDate = null
        )

        // Process up to March 15 (should generate Jan 1, Feb 1, Mar 1)
        val targetCal = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 15, 10, 0, 0)
        }
        val (txs, updatedRule) = RecurringScheduleEngine.processRule(rule, targetCal.timeInMillis)

        assertEquals(3, txs.size)
        assertEquals("rec_rule-rent_${startCal.timeInMillis}", txs[0].id)
        assertEquals(1500000L, txs[0].amountCents)
        assertEquals(TransactionType.RECEIVE, txs[0].type)
        assertEquals("Shop rent", txs[0].note)

        // The updated rule's nextExecutionDate should now be April 1, 2026
        val nextCal = Calendar.getInstance().apply { timeInMillis = updatedRule.nextExecutionDate }
        assertEquals(Calendar.APRIL, nextCal.get(Calendar.MONTH))
        assertEquals(1, nextCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `paused rule generates zero transactions`() {
        val rule = RecurringRule(
            id = "rule-paused",
            personId = 50L,
            amountCents = 5000L,
            type = TransactionType.GIVE,
            frequency = RecurrenceFrequency.DAILY,
            startDate = 1000L,
            nextExecutionDate = 1000L,
            isPaused = true
        )
        val (txs, updatedRule) = RecurringScheduleEngine.processRule(rule, 5000000L)
        assertTrue(txs.isEmpty())
        assertEquals(rule, updatedRule)
    }

    @Test
    fun `rule with endDate stops generating and pauses once reached`() {
        val cal1 = Calendar.getInstance().apply { set(2026, Calendar.JUNE, 1, 0, 0, 0) }
        val calEnd = Calendar.getInstance().apply { set(2026, Calendar.JUNE, 3, 0, 0, 0) }
        val target = Calendar.getInstance().apply { set(2026, Calendar.JUNE, 10, 0, 0, 0) }

        val rule = RecurringRule(
            id = "rule-ended",
            personId = 50L,
            amountCents = 1000L,
            type = TransactionType.GIVE,
            frequency = RecurrenceFrequency.DAILY,
            startDate = cal1.timeInMillis,
            nextExecutionDate = cal1.timeInMillis,
            endDate = calEnd.timeInMillis
        )

        val (txs, updatedRule) = RecurringScheduleEngine.processRule(rule, target.timeInMillis)
        // Should generate June 1, June 2, June 3 (3 days)
        assertEquals(3, txs.size)
        assertTrue(updatedRule.isPaused)
    }
}
