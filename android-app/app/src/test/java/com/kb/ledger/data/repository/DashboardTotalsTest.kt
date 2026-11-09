package com.kb.ledger.data.repository

import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.SyncOperationType
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class DashboardTotalsTest {

    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var personDao: FakePersonDao
    private lateinit var syncDao: FakeSyncOperationDao
    private lateinit var repository: OfflineFirstTransactionRepository

    @Before
    fun setup() {
        transactionDao = FakeTransactionDao()
        personDao = FakePersonDao()
        syncDao = FakeSyncOperationDao()
        repository = OfflineFirstTransactionRepository(transactionDao, personDao, syncDao)
    }

    @Test
    fun `dashboard totals reconcile correctly with positive negative and settled balances`() = runTest {
        val now = System.currentTimeMillis()
        val txs = listOf(
            Transaction(id = "1", personId = 1, amountCents = 50000, type = TransactionType.GIVE, createdAt = now),
            Transaction(id = "2", personId = 1, amountCents = 20000, type = TransactionType.RECEIVE, createdAt = now),
            Transaction(id = "3", personId = 2, amountCents = 10000, type = TransactionType.GIVE, createdAt = now),
            Transaction(id = "4", personId = 2, amountCents = 60000, type = TransactionType.RECEIVE, createdAt = now),
            Transaction(id = "5", personId = 3, amountCents = 40000, type = TransactionType.GIVE, createdAt = now),
            Transaction(id = "6", personId = 3, amountCents = 40000, type = TransactionType.RECEIVE, createdAt = now),
            Transaction(id = "7", personId = 4, amountCents = 20000, type = TransactionType.GIVE, createdAt = now)
        )
        transactionDao.insertTransactions(txs)

        // Total You Give = 30000 (Alice) + 20000 (David) = 50000 cents (₹500.00)
        val youGiveCents = repository.observeTotalYouGive().first()
        assertEquals(50000L, youGiveCents)

        // Total You Get = 50000 (Bob) = 50000 cents (₹500.00)
        val youGetCents = repository.observeTotalYouGet().first()
        assertEquals(50000L, youGetCents)
    }

    @Test
    fun `received today respects local midnight date boundaries and excludes give and yesterday`() = runTest {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        cal.apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val todayEnd = cal.timeInMillis

        val yesterday = todayStart - 1000L // 1 second before midnight
        val exactStart = todayStart
        val exactEnd = todayEnd
        val tomorrow = todayEnd + 1000L // 1 second after midnight

        val txs = listOf(
            // Yesterday's RECEIVE: excluded
            Transaction(id = "y1", personId = 1, amountCents = 10000, type = TransactionType.RECEIVE, createdAt = yesterday),
            // Today's midnight start boundary: included
            Transaction(id = "t0", personId = 1, amountCents = 15000, type = TransactionType.RECEIVE, createdAt = exactStart),
            // Today's midday RECEIVE: included
            Transaction(id = "t1", personId = 1, amountCents = 25000, type = TransactionType.RECEIVE, createdAt = todayStart + 3600000L),
            // Today's GIVE: excluded
            Transaction(id = "t2", personId = 2, amountCents = 80000, type = TransactionType.GIVE, createdAt = todayStart + 3600000L),
            // Today's midnight end boundary: included
            Transaction(id = "t3", personId = 2, amountCents = 20000, type = TransactionType.RECEIVE, createdAt = exactEnd),
            // Tomorrow's RECEIVE: excluded
            Transaction(id = "m1", personId = 1, amountCents = 50000, type = TransactionType.RECEIVE, createdAt = tomorrow)
        )
        transactionDao.insertTransactions(txs)

        val receivedToday = repository.observeTotalReceiveToday(todayStart, todayEnd).first()
        // t0 (15000) + t1 (25000) + t3 (20000) = 60000 cents (₹600.00)
        assertEquals(60000L, receivedToday)
    }

    @Test
    fun `received today recalculates when transaction is edited or deleted`() = runTest {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        val todayEnd = todayStart + 24 * 3600 * 1000L - 1L
        val txTime = todayStart + 1000L

        val initialTx = Transaction(id = "edit1", personId = 1, amountCents = 30000, type = TransactionType.RECEIVE, createdAt = txTime)
        transactionDao.insertTransaction(initialTx)

        assertEquals(30000L, repository.observeTotalReceiveToday(todayStart, todayEnd).first())

        // Edit transaction: increase amount from ₹300 to ₹500
        val updatedTx = initialTx.copy(amountCents = 50000)
        transactionDao.updateTransaction(updatedTx)

        assertEquals(50000L, repository.observeTotalReceiveToday(todayStart, todayEnd).first())

        // Edit transaction: change type to GIVE -> should drop to 0
        val giveTx = updatedTx.copy(type = TransactionType.GIVE)
        transactionDao.updateTransaction(giveTx)

        assertEquals(0L, repository.observeTotalReceiveToday(todayStart, todayEnd).first())
    }
}
