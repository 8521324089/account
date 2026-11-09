package com.kb.ledger.util

import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementExportHelperTest {

    private val testPerson = Person(
        id = 101L,
        name = "Ramesh Kumar",
        phone = "9876543210"
    )

    @Test
    fun `empty transactions generates valid statement with zero balance`() {
        val csv = StatementExportHelper.generateCsv(testPerson, emptyList())
        val summary = StatementExportHelper.computeSummary(testPerson, emptyList())

        assertTrue(csv.contains("\"Customer Name\",\"Ramesh Kumar\""))
        assertTrue(csv.contains("\"Phone Number\",\"9876543210\""))
        assertTrue(csv.contains("\"Opening Balance\",\"\",\"\",\"\",\"₹0.00\""))
        assertTrue(csv.contains("\"Closing Balance\",\"\",\"\",\"\",\"₹0.00\""))
        assertTrue(csv.contains("\"Net Outstanding\",\"Settled (₹0.00)\""))

        assertEquals(0L, summary.openingBalanceCents)
        assertEquals(0L, summary.closingBalanceCents)
        assertEquals(0, summary.transactionCount)
    }

    @Test
    fun `transactions calculate running and closing balances accurately`() {
        val tx1 = Transaction(
            id = "tx-1",
            personId = 101L,
            amountCents = 50000L, // ₹500 GIVE
            type = TransactionType.GIVE,
            note = "Groceries",
            createdAt = 1000L
        )
        val tx2 = Transaction(
            id = "tx-2",
            personId = 101L,
            amountCents = 20000L, // ₹200 RECEIVE
            type = TransactionType.RECEIVE,
            note = "UPI payment",
            createdAt = 2000L
        )
        val tx3 = Transaction(
            id = "tx-3",
            personId = 101L,
            amountCents = 100000L, // ₹1000 RECEIVE
            type = TransactionType.RECEIVE,
            note = "Cash return",
            createdAt = 3000L
        )

        val transactions = listOf(tx1, tx2, tx3)
        val csv = StatementExportHelper.generateCsv(testPerson, transactions)
        val summary = StatementExportHelper.computeSummary(testPerson, transactions)

        // After tx1 (GIVE 500): running balance = -500.00
        // After tx2 (RECEIVE 200): running balance = -300.00
        // After tx3 (RECEIVE 1000): running balance = +700.00
        assertEquals(50000L, summary.totalGiveCents)
        assertEquals(120000L, summary.totalReceiveCents)
        assertEquals(70000L, summary.closingBalanceCents) // ₹700 net to get
        assertEquals(3, summary.transactionCount)

        assertTrue(csv.contains("\"YOU GAVE\",\"Groceries\",\"₹500.00\",\"-₹500.00\""))
        assertTrue(csv.contains("\"YOU GOT\",\"UPI payment\",\"₹200.00\",\"-₹300.00\""))
        assertTrue(csv.contains("\"YOU GOT\",\"Cash return\",\"₹1000.00\",\"₹700.00\""))
        assertTrue(csv.contains("\"Closing Balance\",\"\",\"\",\"\",\"₹700.00\""))
        assertTrue(csv.contains("\"Net Outstanding\",\"You will get ₹700.00\""))
    }

    @Test
    fun `date filter accounts for prior transactions in opening balance`() {
        val txPrior = Transaction(
            id = "tx-prior",
            personId = 101L,
            amountCents = 30000L, // ₹300 GIVE
            type = TransactionType.GIVE,
            note = "Old credit",
            createdAt = 1000L
        )
        val txInRange = Transaction(
            id = "tx-in-range",
            personId = 101L,
            amountCents = 10000L, // ₹100 RECEIVE
            type = TransactionType.RECEIVE,
            note = "Recent payment",
            createdAt = 5000L
        )
        val txFuture = Transaction(
            id = "tx-future",
            personId = 101L,
            amountCents = 5000L,
            type = TransactionType.GIVE,
            createdAt = 10000L
        )

        val allTx = listOf(txPrior, txInRange, txFuture)
        val csv = StatementExportHelper.generateCsv(testPerson, allTx, startDateMillis = 3000L, endDateMillis = 8000L)
        val summary = StatementExportHelper.computeSummary(testPerson, allTx, startDateMillis = 3000L, endDateMillis = 8000L)

        // Opening balance from txPrior: -₹300.00
        assertEquals(-30000L, summary.openingBalanceCents)
        assertEquals(10000L, summary.totalReceiveCents)
        assertEquals(0L, summary.totalGiveCents)
        assertEquals(-20000L, summary.closingBalanceCents)
        assertEquals(1, summary.transactionCount)

        assertTrue(csv.contains("\"Opening Balance\",\"\",\"\",\"\",\"-₹300.00\""))
        assertTrue(csv.contains("\"Recent payment\""))
        // Prior and future transactions should not appear as table rows
        assertTrue(!csv.contains("\"Old credit\""))
        assertTrue(csv.contains("\"Closing Balance\",\"\",\"\",\"\",\"-₹200.00\""))
        assertTrue(csv.contains("\"Net Outstanding\",\"You will give ₹200.00\""))
    }

    @Test
    fun `csv properly escapes quotes and commas in person names and notes`() {
        val specialPerson = Person(
            id = 202L,
            name = "Ramesh \"Lala\", Vendor",
            phone = "+91 98765-43210"
        )
        val tx = Transaction(
            id = "tx-spec",
            personId = 202L,
            amountCents = 15050L,
            type = TransactionType.GIVE,
            note = "Items: milk, bread, \"butter\"",
            createdAt = 1000L
        )

        val csv = StatementExportHelper.generateCsv(specialPerson, listOf(tx))
        assertTrue(csv.contains("\"Customer Name\",\"Ramesh \"\"Lala\"\", Vendor\""))
        assertTrue(csv.contains("\"Items: milk, bread, \"\"butter\"\"\""))
    }
}
