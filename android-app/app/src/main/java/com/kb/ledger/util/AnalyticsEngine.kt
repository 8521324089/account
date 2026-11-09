package com.kb.ledger.util

import com.kb.ledger.data.dao.PersonBalanceEntity
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class PeriodTrend(
    val periodLabel: String,
    val giveCents: Long,
    val receiveCents: Long
)

data class TopContactItem(
    val personId: Long,
    val personName: String,
    val phone: String,
    val balanceCents: Long
)

data class AnalyticsSummary(
    val totalYouGiveCents: Long,
    val totalYouGetCents: Long,
    val periodGiveCents: Long,
    val periodReceiveCents: Long,
    val periodNetCashFlowCents: Long,
    val periodTxCount: Int,
    val trends: List<PeriodTrend>,
    val topDebtors: List<TopContactItem>, // Customers who owe you money (balance > 0)
    val topCreditors: List<TopContactItem> // Customers you owe money to (balance < 0)
)

/**
 * Calculation engine for Visual Analytics.
 * Computes period totals, monthly/weekly trends, top debtors/creditors from ledger data.
 */
object AnalyticsEngine {

    fun computeAnalytics(
        persons: List<Person>,
        balances: List<PersonBalanceEntity>,
        transactions: List<Transaction>,
        startTimeMillis: Long? = null,
        endTimeMillis: Long? = null
    ): AnalyticsSummary {
        val personMap = persons.associateBy { it.id }

        // Lifetime Outstanding totals
        var totalYouGive = 0L
        var totalYouGet = 0L
        for (b in balances) {
            if (b.balanceCents > 0) {
                totalYouGet += b.balanceCents
            } else if (b.balanceCents < 0) {
                totalYouGive += -b.balanceCents
            }
        }

        // Filter transactions for period
        val inRangeTx = transactions.filter { tx ->
            val after = startTimeMillis?.let { tx.createdAt >= it } ?: true
            val before = endTimeMillis?.let { tx.createdAt <= it } ?: true
            after && before
        }

        var periodGive = 0L
        var periodReceive = 0L
        for (tx in inRangeTx) {
            if (tx.type == TransactionType.GIVE) {
                periodGive += tx.amountCents
            } else {
                periodReceive += tx.amountCents
            }
        }
        val netCashFlow = periodReceive - periodGive

        // Monthly trends from in-range transactions (or all if unbounded)
        val monthFormat = SimpleDateFormat("MMM yyyy", Locale.US)
        val groupedByMonth = inRangeTx.sortedBy { it.createdAt }.groupBy {
            monthFormat.format(Date(it.createdAt))
        }

        val trends = groupedByMonth.map { (monthLabel, txList) ->
            var give = 0L
            var receive = 0L
            for (tx in txList) {
                if (tx.type == TransactionType.GIVE) give += tx.amountCents
                else receive += tx.amountCents
            }
            PeriodTrend(
                periodLabel = monthLabel,
                giveCents = give,
                receiveCents = receive
            )
        }

        // Top Debtors (people who owe you the most, positive balance)
        val topDebtors = balances
            .filter { it.balanceCents > 0 }
            .sortedByDescending { it.balanceCents }
            .take(5)
            .map { b ->
                val p = personMap[b.personId]
                val fallbackName = if (!p?.phone.isNullOrBlank()) p!!.phone else "Customer"
                TopContactItem(
                    personId = b.personId,
                    personName = p?.name ?: fallbackName,
                    phone = p?.phone ?: "",
                    balanceCents = b.balanceCents
                )
            }

        // Top Creditors (people you owe the most to, negative balance)
        val topCreditors = balances
            .filter { it.balanceCents < 0 }
            .sortedBy { it.balanceCents } // Most negative first
            .take(5)
            .map { b ->
                val p = personMap[b.personId]
                val fallbackName = if (!p?.phone.isNullOrBlank()) p!!.phone else "Customer"
                TopContactItem(
                    personId = b.personId,
                    personName = p?.name ?: fallbackName,
                    phone = p?.phone ?: "",
                    balanceCents = -b.balanceCents // absolute amount you owe
                )
            }

        return AnalyticsSummary(
            totalYouGiveCents = totalYouGive,
            totalYouGetCents = totalYouGet,
            periodGiveCents = periodGive,
            periodReceiveCents = periodReceive,
            periodNetCashFlowCents = netCashFlow,
            periodTxCount = inRangeTx.size,
            trends = trends,
            topDebtors = topDebtors,
            topCreditors = topCreditors
        )
    }
}
