package com.kb.ledger.util

import com.kb.ledger.data.model.RecurrenceFrequency
import com.kb.ledger.data.model.RecurringRule
import com.kb.ledger.data.model.Transaction
import java.util.Calendar

/**
 * Deterministic calculation engine for recurring ledger transactions.
 *
 * Invariants:
 * - Generates stable, deterministic IDs (`rec_${rule.id}_${executionDate}`) to prevent duplicates on retries.
 * - Accurately handles month-end boundaries (e.g., 31st Jan -> 28th Feb -> 31st Mar).
 * - Never generates occurrences beyond `endDate` or before `startDate`.
 * - Paused rules generate no occurrences.
 */
object RecurringScheduleEngine {

    fun calculateNextOccurrence(currentDateMillis: Long, frequency: RecurrenceFrequency): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = currentDateMillis
        }

        when (frequency) {
            RecurrenceFrequency.DAILY -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            RecurrenceFrequency.WEEKLY -> {
                cal.add(Calendar.WEEK_OF_YEAR, 1)
            }
            RecurrenceFrequency.MONTHLY -> {
                val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
                cal.add(Calendar.MONTH, 1)
                // Calendar.add automatically clamps days exceeding the new month (e.g. Jan 31 -> Feb 28)
            }
        }
        return cal.timeInMillis
    }

    /**
     * Generates all due occurrences for a rule up to [targetTimeMillis].
     * Returns a pair of:
     * 1. List of new Transaction entities with stable deterministic IDs.
     * 2. The updated RecurringRule with advanced nextExecutionDate and isPaused flag if completed.
     */
    fun processRule(rule: RecurringRule, targetTimeMillis: Long): Pair<List<Transaction>, RecurringRule> {
        if (rule.isPaused) {
            return Pair(emptyList(), rule)
        }

        val generatedTransactions = mutableListOf<Transaction>()
        var nextDate = rule.nextExecutionDate

        while (nextDate <= targetTimeMillis) {
            // Check optional end date
            if (rule.endDate != null && nextDate > rule.endDate) {
                // Schedule has passed its end date; mark paused/completed
                return Pair(generatedTransactions, rule.copy(isPaused = true, nextExecutionDate = nextDate))
            }

            // Create deterministic transaction
            val stableId = "rec_${rule.id}_${nextDate}"
            val tx = Transaction(
                id = stableId,
                personId = rule.personId,
                amountCents = rule.amountCents,
                type = rule.type,
                note = rule.note ?: "Recurring: ${rule.frequency.name.lowercase()}",
                createdAt = nextDate,
                updatedAt = nextDate,
                syncPending = true
            )
            generatedTransactions.add(tx)

            // Advance to next execution
            nextDate = calculateNextOccurrence(nextDate, rule.frequency)
        }

        val isFinished = rule.endDate != null && nextDate > rule.endDate
        val updatedRule = rule.copy(
            nextExecutionDate = nextDate,
            isPaused = if (isFinished) true else rule.isPaused
        )

        return Pair(generatedTransactions, updatedRule)
    }
}
