package com.kb.ledger.data.repository

import com.kb.ledger.data.model.SyncEntityType
import com.kb.ledger.data.model.SyncOperationType
import com.kb.ledger.domain.model.PersonDomain
import com.kb.ledger.domain.model.TransactionDomain
import com.kb.ledger.domain.model.TransactionType
import com.kb.ledger.util.AppResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class OfflineFirstTransactionRepositoryTest {

    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var personDao: FakePersonDao
    private lateinit var syncOpDao: FakeSyncOperationDao
    private lateinit var repository: OfflineFirstTransactionRepository

    @Before
    fun setUp() {
        transactionDao = FakeTransactionDao()
        personDao = FakePersonDao()
        syncOpDao = FakeSyncOperationDao()
        repository = OfflineFirstTransactionRepository(transactionDao, personDao, syncOpDao)
    }

    @Test
    fun testSaveTransactionQueuesUpsertSyncOperation() = runTest {
        val tx = TransactionDomain(
            id = UUID.randomUUID().toString(),
            personLocalId = 1L,
            amountCents = 50075L,
            type = TransactionType.GIVE,
            note = "Loan for groceries"
        )

        val result = repository.saveTransaction(tx)
        assertTrue(result is AppResult.Success)

        val saved = (result as AppResult.Success).data
        assertEquals(tx.id, saved.id)
        assertEquals(50075L, saved.amountCents)
        assertEquals(500.75, saved.amount, 0.001)

        val ops = syncOpDao.getAll()
        assertEquals(1, ops.size)
        assertEquals(SyncOperationType.UPSERT, ops[0].type)
        assertEquals(SyncEntityType.TRANSACTION, ops[0].entityType)
        assertEquals(tx.id, ops[0].entityId)
    }

    @Test
    fun testObserveTransactionsForPerson() = runTest {
        val t1 = TransactionDomain(id = "tx1", personLocalId = 1L, amountCents = 1000L, type = TransactionType.GIVE, createdAt = 100)
        val t2 = TransactionDomain(id = "tx2", personLocalId = 1L, amountCents = 2000L, type = TransactionType.RECEIVE, createdAt = 200)
        val t3 = TransactionDomain(id = "tx3", personLocalId = 2L, amountCents = 5000L, type = TransactionType.GIVE, createdAt = 300)

        repository.saveTransaction(t1)
        repository.saveTransaction(t2)
        repository.saveTransaction(t3)

        val person1Txs = repository.observeTransactionsForPerson(1L).first()
        assertEquals(2, person1Txs.size)
        assertEquals("tx2", person1Txs[0].id) // newest first
        assertEquals("tx1", person1Txs[1].id)

        val person2Txs = repository.observeTransactionsForPerson(2L).first()
        assertEquals(1, person2Txs.size)
        assertEquals("tx3", person2Txs[0].id)
    }

    @Test
    fun testObserveAllBalancesCalculations() = runTest {
        // Person 1: GIVE 1000, RECEIVE 300 -> net balance = 300 - 1000 = -700 ("You Give" 7.00)
        repository.saveTransaction(TransactionDomain(id = "t1", personLocalId = 1L, amountCents = 1000L, type = TransactionType.GIVE))
        repository.saveTransaction(TransactionDomain(id = "t2", personLocalId = 1L, amountCents = 300L, type = TransactionType.RECEIVE))

        // Person 2: RECEIVE 5000 -> net balance = +5000 ("You Get" 50.00)
        repository.saveTransaction(TransactionDomain(id = "t3", personLocalId = 2L, amountCents = 5000L, type = TransactionType.RECEIVE))

        val balances = repository.observeAllBalances().first()
        assertEquals(2, balances.size)

        val b1 = balances.first { it.personLocalId == 1L }
        assertEquals(-700L, b1.balanceCents)
        assertEquals(-7.0, b1.balance, 0.001)

        val b2 = balances.first { it.personLocalId == 2L }
        assertEquals(5000L, b2.balanceCents)
        assertEquals(50.0, b2.balance, 0.001)
    }

    @Test
    fun testObserveTotalGiveAndReceiveToday() = runTest {
        val today = 1700000000000L
        repository.saveTransaction(TransactionDomain(id = "t1", personLocalId = 1L, amountCents = 15000L, type = TransactionType.GIVE, createdAt = today + 1000))
        repository.saveTransaction(TransactionDomain(id = "t2", personLocalId = 2L, amountCents = 5000L, type = TransactionType.GIVE, createdAt = today + 2000))
        repository.saveTransaction(TransactionDomain(id = "t3", personLocalId = 1L, amountCents = 8000L, type = TransactionType.RECEIVE, createdAt = today + 3000))
        repository.saveTransaction(TransactionDomain(id = "t4", personLocalId = 1L, amountCents = 9999L, type = TransactionType.RECEIVE, createdAt = today - 10000)) // yesterday

        val totalGive = repository.observeTotalGive().first()
        assertEquals(20000L, totalGive) // 15000 + 5000

        val receiveToday = repository.observeTotalReceiveToday(today).first()
        assertEquals(8000L, receiveToday) // only t3, not yesterday's t4
    }

    @Test
    fun testDeleteTransactionQueuesDeleteSyncOperation() = runTest {
        val tx = (repository.saveTransaction(TransactionDomain(id = "tx-del", personLocalId = 1L, amountCents = 500L, type = TransactionType.GIVE)) as AppResult.Success).data

        repository.deleteTransaction(tx.id)

        val ops = syncOpDao.getAll()
        val deleteOp = ops.firstOrNull { it.type == SyncOperationType.DELETE && it.entityId == tx.id }
        assertNotNull(deleteOp)
        assertEquals(SyncEntityType.TRANSACTION, deleteOp!!.entityType)
    }

    @Test
    fun testUpdateImageFileId() = runTest {
        val tx = (repository.saveTransaction(TransactionDomain(id = "tx-img", personLocalId = 1L, amountCents = 500L, type = TransactionType.GIVE)) as AppResult.Success).data

        val updateResult = repository.updateImageFileId(tx.id, "drive-file-123")
        assertTrue(updateResult is AppResult.Success)

        val updatedTx = transactionDao.getTransactionById(tx.id)
        assertNotNull(updatedTx)
        assertEquals("drive-file-123", updatedTx!!.imageFileId)
        assertTrue(updatedTx.syncPending)
    }

    @Test
    fun testRepositoryAutomaticallyUpdatesLocalPartitionedBackup() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("repo_backup_test").toFile()
        try {
            val backupManager = com.kb.ledger.backup.partition.LocalPartitionedBackupManager(File(tempDir, "backup"))
            val repoWithBackup = OfflineFirstTransactionRepository(
                transactionDao = transactionDao,
                personDao = personDao,
                syncOperationDao = syncOpDao,
                localBackupManager = backupManager
            )

            val tx = TransactionDomain(
                id = "auto-backup-tx",
                personLocalId = 1L,
                amountCents = 12000L,
                type = TransactionType.GIVE,
                note = "Backup check",
                createdAt = 1792886400000L // 2026-10-25
            )

            repoWithBackup.saveTransaction(tx)

            // Verify backup chunk exists and contains the transaction
            val partitionDir = File(tempDir, "backup/transactions/2026/10/25")
            assertTrue("Partition directory should exist after repository save", partitionDir.exists())

            val txsInBackup = backupManager.readAllTransactions()
            assertEquals(1, txsInBackup.size)
            assertEquals("auto-backup-tx", txsInBackup[0].id)
            assertFalse(txsInBackup[0].isDeleted)

            // Delete transaction through repository
            repoWithBackup.deleteTransaction("auto-backup-tx")

            val txsAfterDelete = backupManager.readAllTransactions()
            assertEquals(1, txsAfterDelete.size)
            assertTrue("Transaction in backup should be marked isDeleted = true", txsAfterDelete[0].isDeleted)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
