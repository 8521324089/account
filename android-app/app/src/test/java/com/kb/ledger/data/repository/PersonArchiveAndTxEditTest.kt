package com.kb.ledger.data.repository

import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.SyncOperationType
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.domain.model.PersonDomain
import com.kb.ledger.domain.model.TransactionDomain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PersonArchiveAndTxEditTest {

    private lateinit var personDao: FakePersonDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var syncDao: FakeSyncOperationDao
    private lateinit var personRepository: OfflineFirstPersonRepository
    private lateinit var transactionRepository: OfflineFirstTransactionRepository

    @Before
    fun setup() {
        personDao = FakePersonDao()
        transactionDao = FakeTransactionDao()
        personDao.transactionDaoRef = transactionDao
        transactionDao.personDaoRef = personDao
        syncDao = FakeSyncOperationDao()
        personRepository = OfflineFirstPersonRepository(personDao, syncDao, transactionDao)
        transactionRepository = OfflineFirstTransactionRepository(transactionDao, personDao, syncDao)
    }

    @Test
    fun `archive person removes from active and places in archived without deleting data`() = runTest {
        val p1 = Person(id = 1, name = "Ramesh Kumar", phone = "9876543210", isArchived = false)
        val p2 = Person(id = 2, name = "Anita Devi", phone = "9123456789", isArchived = false)
        personDao.insertPersons(listOf(p1, p2))

        // Initial active persons
        var active = personRepository.observeActivePersons().first()
        assertEquals(2, active.size)
        var archived = personRepository.observeArchivedPersons().first()
        assertEquals(0, archived.size)

        // Archive Ramesh
        val archiveResult = personRepository.archivePerson(1L)
        assertTrue(archiveResult is com.kb.ledger.util.AppResult.Success)

        // Check active and archived
        active = personRepository.observeActivePersons().first()
        assertEquals(1, active.size)
        assertEquals("Anita Devi", active[0].name)

        archived = personRepository.observeArchivedPersons().first()
        assertEquals(1, archived.size)
        assertEquals("Ramesh Kumar", archived[0].name)
        assertTrue(archived[0].isArchived)

        // Verify sync operation queued
        val pendingSync = syncDao.getAll()
        assertTrue(pendingSync.any { it.type == SyncOperationType.UPSERT })

        // Restore Ramesh
        val restoreResult = personRepository.restorePerson(1L)
        assertTrue(restoreResult is com.kb.ledger.util.AppResult.Success)

        active = personRepository.observeActivePersons().first()
        assertEquals(2, active.size)
        archived = personRepository.observeArchivedPersons().first()
        assertEquals(0, archived.size)
    }

    @Test
    fun `edit transaction updates amount and immediately recalculates balances`() = runTest {
        val person = Person(id = 1, name = "Customer A", phone = "9000000000")
        personDao.insertPerson(person)

        // Create transaction: You GAVE ₹500 (50000 cents) -> Balance = -50000
        val tx = Transaction(id = "tx-1", personId = 1, amountCents = 50000, type = TransactionType.GIVE)
        transactionDao.insertTransaction(tx)

        var balance = transactionRepository.observeAllBalances().first().first { it.personLocalId == 1L }
        assertEquals(-50000L, balance.balanceCents)
        assertEquals(50000L, transactionRepository.observeTotalYouGive().first())

        // Edit transaction: change amount to ₹800 (80000 cents) and type to RECEIVE
        val updatedDomain = TransactionDomain(
            id = "tx-1",
            personLocalId = 1,
            amountCents = 80000,
            type = com.kb.ledger.domain.model.TransactionType.RECEIVE,
            note = "Corrected payment"
        )
        val updateRes = transactionRepository.updateTransaction(updatedDomain)
        assertTrue(updateRes is com.kb.ledger.util.AppResult.Success)

        // Balance should immediately be +80000 cents ("You Get")
        balance = transactionRepository.observeAllBalances().first().first { it.personLocalId == 1L }
        assertEquals(80000L, balance.balanceCents)

        // Total You Give should now be 0, Total You Get should be 80000
        assertEquals(0L, transactionRepository.observeTotalYouGive().first())
        assertEquals(80000L, transactionRepository.observeTotalYouGet().first())

        // Verify transaction fields updated
        val storedTx = transactionDao.getTransactionById("tx-1")
        assertNotNull(storedTx)
        assertEquals(80000L, storedTx!!.amountCents)
        assertEquals(TransactionType.RECEIVE, storedTx.type)
        assertEquals("Corrected payment", storedTx.note)
        assertTrue(storedTx.syncPending)
    }

    @Test
    fun `replace bill image on transaction preserves metadata and enqueues sync`() = runTest {
        val tx = Transaction(
            id = "tx-img",
            personId = 1,
            amountCents = 25000,
            type = TransactionType.GIVE,
            imageUri = "content://media/broken/image.jpg",
            imageFileId = "stale-drive-id",
            syncPending = false
        )
        transactionDao.insertTransaction(tx)

        // Replace with new image URI
        val updatedDomain = TransactionDomain(
            id = "tx-img",
            personLocalId = 1,
            amountCents = 25000,
            type = com.kb.ledger.domain.model.TransactionType.GIVE,
            imageUri = "content://media/fresh/receipt_new.jpg"
        )
        val updateResult = transactionRepository.updateTransaction(updatedDomain)
        assertTrue(updateResult is com.kb.ledger.util.AppResult.Success)

        val updatedTx = transactionDao.getTransactionById("tx-img")!!
        assertEquals("content://media/fresh/receipt_new.jpg", updatedTx.imageUri)
        assertTrue(updatedTx.syncPending)
    }

    @Test
    fun `dashboard excludes contacts without transactions while search retains them`() = runTest {
        val pActiveWithTx = Person(id = 1, name = "Active With Tx", phone = "9111111111")
        val pActiveNoTx = Person(id = 2, name = "Active No Tx", phone = "9222222222")
        personDao.insertPersons(listOf(pActiveWithTx, pActiveNoTx))

        val tx = Transaction(id = "tx-1", personId = 1, amountCents = 10000, type = TransactionType.GIVE)
        transactionDao.insertTransaction(tx)

        // Dashboard active flow must show only Person 1
        val dashboardList = personRepository.observeDashboardActivePersons().first()
        assertEquals(1, dashboardList.size)
        assertEquals(1L, dashboardList[0].localId)

        // Search / All active flow must retain both Person 1 and Person 2
        val allActive = personRepository.observeActivePersons().first()
        assertEquals(2, allActive.size)
    }

    @Test
    fun `zero balance person deletion succeeds and soft deletes person and transactions`() = runTest {
        val p = Person(id = 10, name = "Settled Person", phone = "9333333333", cloudId = "cloud-p10")
        personDao.insertPerson(p)

        // Two transactions that cancel out to ₹0
        val tx1 = Transaction(id = "tx-give", personId = 10, amountCents = 50000, type = TransactionType.GIVE)
        val tx2 = Transaction(id = "tx-recv", personId = 10, amountCents = 50000, type = TransactionType.RECEIVE)
        transactionDao.insertTransactions(listOf(tx1, tx2))

        val delRes = personRepository.deleteZeroBalancePerson(10L)
        assertTrue(delRes is com.kb.ledger.util.AppResult.Success)

        // Person is soft-deleted
        val storedPerson = personDao.getPersonById(10L)
        assertNull("Soft deleted person should not be returned by getPersonById", storedPerson)

        // Transactions are soft-deleted
        val storedTxs = transactionDao.getTransactionsForPersonOnce(10L)
        assertTrue(storedTxs.isEmpty())

        // Sync DELETE tombstones are queued for person and each transaction
        val ops = syncDao.getAll().filter { it.type == SyncOperationType.DELETE }
        assertTrue(ops.any { it.entityId == "cloud-p10" })
        assertTrue(ops.any { it.entityId == "tx-give" })
        assertTrue(ops.any { it.entityId == "tx-recv" })
    }

    @Test
    fun `zero balance person deletion fails if balance is non-zero`() = runTest {
        val p = Person(id = 20, name = "Owing Person", phone = "9444444444")
        personDao.insertPerson(p)

        val tx = Transaction(id = "tx-non-zero", personId = 20, amountCents = 25000, type = TransactionType.GIVE)
        transactionDao.insertTransaction(tx)

        val delRes = personRepository.deleteZeroBalancePerson(20L)
        assertTrue("Deletion must fail for non-zero balance", delRes is com.kb.ledger.util.AppResult.Error)

        // Person remains active
        assertNotNull(personDao.getPersonById(20L))
    }

    @Test
    fun `zero balance person deletion fails if person has no transactions`() = runTest {
        val p = Person(id = 30, name = "Empty Person", phone = "9555555555")
        personDao.insertPerson(p)

        val delRes = personRepository.deleteZeroBalancePerson(30L)
        assertTrue("Deletion from dashboard must fail if no transactions", delRes is com.kb.ledger.util.AppResult.Error)

        assertNotNull(personDao.getPersonById(30L))
    }
}
