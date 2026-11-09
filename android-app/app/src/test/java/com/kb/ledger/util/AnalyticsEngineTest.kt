package com.kb.ledger.util

import com.kb.ledger.data.dao.PersonBalanceEntity
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsEngineTest {

    @Test
    fun `empty inputs return zero summary`() {
        val summary = AnalyticsEngine.computeAnalytics(emptyList(), emptyList(), emptyList())
        assertEquals(0L, summary.totalYouGiveCents)
        assertEquals(0L, summary.totalYouGetCents)
        assertEquals(0L, summary.periodGiveCents)
        assertEquals(0L, summary.periodReceiveCents)
        assertEquals(0L, summary.periodNetCashFlowCents)
        assertEquals(0, summary.periodTxCount)
        assertTrue(summary.trends.isEmpty())
        assertTrue(summary.topDebtors.isEmpty())
        assertTrue(summary.topCreditors.isEmpty())
    }

    @Test
    fun `analytics correctly calculates balances and top debtors and creditors`() {
        val p1 = Person(id = 1L, name = "Aman", phone = "111")
        val p2 = Person(id = 2L, name = "Bhuvan", phone = "222")
        val p3 = Person(id = 3L, name = "Chitra", phone = "333")

        val balances = listOf(
            PersonBalanceEntity(personId = 1L, balanceCents = 100000L), // Aman owes ₹1000
            PersonBalanceEntity(personId = 2L, balanceCents = -40000L), // You owe Bhuvan ₹400
            PersonBalanceEntity(personId = 3L, balanceCents = 250000L)  // Chitra owes ₹2500
        )

        val tx1 = Transaction(id = "1", personId = 1L, amountCents = 100000L, type = TransactionType.RECEIVE, createdAt = 1000L)
        val tx2 = Transaction(id = "2", personId = 2L, amountCents = 40000L, type = TransactionType.GIVE, createdAt = 2000L)
        val tx3 = Transaction(id = "3", personId = 3L, amountCents = 250000L, type = TransactionType.RECEIVE, createdAt = 3000L)

        val summary = AnalyticsEngine.computeAnalytics(
            persons = listOf(p1, p2, p3),
            balances = balances,
            transactions = listOf(tx1, tx2, tx3)
        )

        assertEquals(40000L, summary.totalYouGiveCents)
        assertEquals(350000L, summary.totalYouGetCents)
        assertEquals(40000L, summary.periodGiveCents)
        assertEquals(350000L, summary.periodReceiveCents)
        assertEquals(310000L, summary.periodNetCashFlowCents) // 350000 - 40000

        // Top debtors should have Chitra first (250000), then Aman (100000)
        assertEquals(2, summary.topDebtors.size)
        assertEquals("Chitra", summary.topDebtors[0].personName)
        assertEquals(250000L, summary.topDebtors[0].balanceCents)
        assertEquals("Aman", summary.topDebtors[1].personName)
        assertEquals(100000L, summary.topDebtors[1].balanceCents)

        // Top creditors should have Bhuvan (40000)
        assertEquals(1, summary.topCreditors.size)
        assertEquals("Bhuvan", summary.topCreditors[0].personName)
        assertEquals(40000L, summary.topCreditors[0].balanceCents)
    }
}
