package com.kb.ledger.util

import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StatementSummary(
    val personName: String,
    val phone: String,
    val openingBalanceCents: Long,
    val totalGiveCents: Long,
    val totalReceiveCents: Long,
    val closingBalanceCents: Long,
    val transactionCount: Int
)

/**
 * Utility for exporting customer transaction statements to CSV and computing period summaries.
 *
 * Invariants:
 * - Net balance: RECEIVE increases balance, GIVE decreases balance.
 * - Positive closing balance = Customer owes user ("You will get").
 * - Negative closing balance = User owes customer ("You will give").
 * - Running balance is maintained chronologically with opening balance preserved across filtered dates.
 * - Safe CSV escaping handles quotes, commas, and line breaks without corruption.
 */
object StatementExportHelper {

    fun generateCsv(
        person: Person,
        transactions: List<Transaction>,
        startDateMillis: Long? = null,
        endDateMillis: Long? = null
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sortedTx = transactions.sortedBy { it.createdAt }

        // Filter transactions in range
        val inRangeTx = sortedTx.filter { tx ->
            val afterStart = startDateMillis?.let { tx.createdAt >= it } ?: true
            val beforeEnd = endDateMillis?.let { tx.createdAt <= it } ?: true
            afterStart && beforeEnd
        }

        // Transactions strictly before startDate contribute to opening balance
        val priorTx = if (startDateMillis != null) {
            sortedTx.filter { it.createdAt < startDateMillis }
        } else {
            emptyList()
        }

        var runningBalanceCents = priorTx.sumOf {
            if (it.type == TransactionType.RECEIVE) it.amountCents else -it.amountCents
        }
        val openingBalanceCents = runningBalanceCents

        var totalPeriodGiveCents = 0L
        var totalPeriodReceiveCents = 0L

        val sb = StringBuilder()
        // Header
        sb.append("\"Account Statement\"\n")
        sb.append("\"Customer Name\",${escapeCsv(person.name)}\n")
        sb.append("\"Phone Number\",${escapeCsv(person.phone)}\n")
        val periodStr = when {
            startDateMillis != null && endDateMillis != null ->
                "${dateFormat.format(Date(startDateMillis))} to ${dateFormat.format(Date(endDateMillis))}"
            startDateMillis != null -> "From ${dateFormat.format(Date(startDateMillis))}"
            endDateMillis != null -> "Up to ${dateFormat.format(Date(endDateMillis))}"
            else -> "All Time"
        }
        sb.append("\"Period\",${escapeCsv(periodStr)}\n")
        sb.append("\"Generated On\",${escapeCsv(dateFormat.format(Date()))}\n\n")

        // Table header
        sb.append("\"Date\",\"Type\",\"Note\",\"Amount (₹)\",\"Running Balance (₹)\"\n")
        sb.append("\"Opening Balance\",\"\",\"\",\"\",${formatAmount(openingBalanceCents)}\n")

        for (tx in inRangeTx) {
            if (tx.type == TransactionType.RECEIVE) {
                runningBalanceCents += tx.amountCents
                totalPeriodReceiveCents += tx.amountCents
            } else {
                runningBalanceCents -= tx.amountCents
                totalPeriodGiveCents += tx.amountCents
            }

            val dateStr = dateFormat.format(Date(tx.createdAt))
            val typeStr = if (tx.type == TransactionType.GIVE) "YOU GAVE" else "YOU GOT"
            val noteStr = tx.note ?: ""
            val amountStr = formatAmount(tx.amountCents)
            val balanceStr = formatAmount(runningBalanceCents)

            sb.append("${escapeCsv(dateStr)},${escapeCsv(typeStr)},${escapeCsv(noteStr)},${amountStr},${balanceStr}\n")
        }

        // Summary
        sb.append("\"Closing Balance\",\"\",\"\",\"\",${formatAmount(runningBalanceCents)}\n\n")
        sb.append("\"Summary\"\n")
        sb.append("\"Total You Gave\",${formatAmount(totalPeriodGiveCents)}\n")
        sb.append("\"Total You Received\",${formatAmount(totalPeriodReceiveCents)}\n")
        val netStatus = when {
            runningBalanceCents > 0 -> "You will get ₹${formatPositiveAmount(runningBalanceCents)}"
            runningBalanceCents < 0 -> "You will give ₹${formatPositiveAmount(-runningBalanceCents)}"
            else -> "Settled (₹0.00)"
        }
        sb.append("\"Net Outstanding\",${escapeCsv(netStatus)}\n")

        return sb.toString()
    }

    fun computeSummary(
        person: Person,
        transactions: List<Transaction>,
        startDateMillis: Long? = null,
        endDateMillis: Long? = null
    ): StatementSummary {
        val sortedTx = transactions.sortedBy { it.createdAt }
        val priorTx = if (startDateMillis != null) {
            sortedTx.filter { it.createdAt < startDateMillis }
        } else {
            emptyList()
        }
        val openingBalance = priorTx.sumOf {
            if (it.type == TransactionType.RECEIVE) it.amountCents else -it.amountCents
        }

        val inRangeTx = sortedTx.filter { tx ->
            val afterStart = startDateMillis?.let { tx.createdAt >= it } ?: true
            val beforeEnd = endDateMillis?.let { tx.createdAt <= it } ?: true
            afterStart && beforeEnd
        }

        var give = 0L
        var receive = 0L
        for (tx in inRangeTx) {
            if (tx.type == TransactionType.GIVE) give += tx.amountCents
            else receive += tx.amountCents
        }

        return StatementSummary(
            personName = person.name,
            phone = person.phone,
            openingBalanceCents = openingBalance,
            totalGiveCents = give,
            totalReceiveCents = receive,
            closingBalanceCents = openingBalance + receive - give,
            transactionCount = inRangeTx.size
        )
    }

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun formatAmount(amountCents: Long): String {
        val isNegative = amountCents < 0
        val absCents = kotlin.math.abs(amountCents)
        val dollars = absCents / 100
        val cents = absCents % 100
        val formatted = String.format(Locale.US, "%d.%02d", dollars, cents)
        return if (isNegative) "\"-₹$formatted\"" else "\"₹$formatted\""
    }

    private fun formatPositiveAmount(amountCents: Long): String {
        val absCents = kotlin.math.abs(amountCents)
        val dollars = absCents / 100
        val cents = absCents % 100
        return String.format(Locale.US, "%d.%02d", dollars, cents)
    }
}
