package com.kb.ledger.data.repository

import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.PersonRole
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Feature 2: Transaction Deletion.
 *
 * Verifies:
 * - Soft deletion sets isDeleted = true and creates a tombstone.
 * - Deleted transaction is immediately excluded from active person transactions.
 * - Net balance updates immediately after transaction deletion.
 * - Transaction record remains in database for cloud sync and backup reconciliation.
 * - Associated bill image references are preserved without crashing or dangling.
 */
class TransactionDeletionTest {

    private lateinit var personDao: FakePersonDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var syncOperationDao: FakeSyncOperationDao
    private lateinit var txRepo: OfflineFirstTransactionRepository

    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        personDao = FakePersonDao()
        transactionDao = FakeTransactionDao()
        syncOperationDao = FakeSyncOperationDao()
        personDao.transactionDaoRef = transactionDao
        transactionDao.personDaoRef = personDao

        txRepo = OfflineFirstTransactionRepository(transactionDao, personDao, syncOperationDao)
    }

    @Test
    fun deleteTransaction_softDeletesRecord_andUpdatesBalanceImmediately() = runBlocking {
        val person = Person(id = 1L, name = "Karan Johar", phone = "9876543210", role = PersonRole.CUSTOMER)
        personDao.insertPerson(person)

        // Add 2 transactions
        val tx1 = Transaction(id = "tx-1", personId = person.id, amountCents = 500_00L, type = TransactionType.GIVE, createdAt = now - 1000L)
        val tx2 = Transaction(id = "tx-2", personId = person.id, amountCents = 200_00L, type = TransactionType.GIVE, createdAt = now)
        transactionDao.insertTransaction(tx1)
        transactionDao.insertTransaction(tx2)

        // Initial balance: You Gave 700.00 (-70000 cents)
        val activeTxsBefore = txRepo.observeTransactionsForPerson(person.id).first()
        assertEquals(2, activeTxsBefore.size)
        val balanceBefore = activeTxsBefore.sumOf { if (it.type == com.kb.ledger.domain.model.TransactionType.GIVE) -it.amountCents else it.amountCents }
        assertEquals(-700_00L, balanceBefore)

        // Delete tx2
        val deleteResult = txRepo.deleteTransaction("tx-2")
        assertTrue("deleteTransaction should succeed", deleteResult is com.kb.ledger.util.AppResult.Success)

        // Active transactions should now only include tx1
        val activeTxsAfter = txRepo.observeTransactionsForPerson(person.id).first()
        assertEquals(1, activeTxsAfter.size)
        assertEquals("tx-1", activeTxsAfter[0].id)

        // Net balance should update immediately to -500_00L
        val balanceAfter = activeTxsAfter.sumOf { if (it.type == com.kb.ledger.domain.model.TransactionType.GIVE) -it.amountCents else it.amountCents }
        assertEquals(-500_00L, balanceAfter)

        // Tombstone preservation: record must still exist in DB with isDeleted = 1
        val allTxsInDb = transactionDao.getAllTransactionsIncludingDeleted()
        assertEquals(2, allTxsInDb.size)
        val deletedTx = allTxsInDb.first { it.id == "tx-2" }
        assertTrue("Soft-deleted transaction must have isDeleted = true", deletedTx.isDeleted)

        // A DELETE sync operation should be queued
        val pendingOps = syncOperationDao.getAll()
        assertTrue("Sync operation must be queued for transaction deletion", pendingOps.any { it.entityId == "tx-2" })
    }

    @Test
    fun deleteTransaction_preservesReceiptMetadataOnTombstone() = runBlocking {
        val person = Person(id = 2L, name = "Sunil Shetty", phone = "9123456789", role = PersonRole.SUPPLIER)
        personDao.insertPerson(person)

        val txWithReceipt = Transaction(
            id = "tx-receipt-1",
            personId = person.id,
            amountCents = 1500_00L,
            type = TransactionType.RECEIVE,
            note = "Invoice with receipt",
            imageUri = "/data/user/0/com.kb.ledger/files/receipts/tx-receipt-1.jpg",
            createdAt = now
        )
        transactionDao.insertTransaction(txWithReceipt)

        // Soft delete transaction
        txRepo.deleteTransaction(txWithReceipt.id)

        // Verify tombstone retains imageUri so physical receipt file can be reconciled/restored
        val tombstone = transactionDao.getAllTransactionsIncludingDeleted().first { it.id == txWithReceipt.id }
        assertTrue(tombstone.isDeleted)
        assertEquals("/data/user/0/com.kb.ledger/files/receipts/tx-receipt-1.jpg", tombstone.imageUri)
    }
}
