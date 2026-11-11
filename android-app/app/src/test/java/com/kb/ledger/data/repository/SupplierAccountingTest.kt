package com.kb.ledger.data.repository

import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.PersonRole
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.domain.model.PersonDomain
import com.kb.ledger.domain.model.TransactionDomain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Unit tests verifying Supplier Accounting rules and role separation.
 *
 * Rules:
 * - RECEIVE = Goods/credit received (debt increases).
 * - GIVE = Payment made to supplier (debt decreases).
 * - Supplier Net Payable = Total RECEIVE - Total GIVE
 *   - > 0: You Owe (debt)
 *   - < 0: Advance
 *   - == 0: Settled
 * - Dashboard Card 1 (Total Payable): SUM(MAX(Supplier RECEIVE - Supplier GIVE, 0))
 *   Advances of one supplier must NEVER deduct from what is owed to another supplier.
 * - Dashboard Card 2 (Paid Today): SUM(Supplier GIVE transactions today).
 */
class SupplierAccountingTest {

    private lateinit var personDao: FakePersonDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var syncOperationDao: FakeSyncOperationDao
    private lateinit var personRepo: OfflineFirstPersonRepository
    private lateinit var txRepo: OfflineFirstTransactionRepository

    private val now = System.currentTimeMillis()
    private val todayStart = now - 1000L
    private val todayEnd = now + 1000L

    @Before
    fun setUp() {
        personDao = FakePersonDao()
        transactionDao = FakeTransactionDao()
        syncOperationDao = FakeSyncOperationDao()
        personDao.transactionDaoRef = transactionDao
        transactionDao.personDaoRef = personDao

        personRepo = OfflineFirstPersonRepository(personDao, syncOperationDao, transactionDao)
        txRepo = OfflineFirstTransactionRepository(transactionDao, personDao, syncOperationDao)
    }

    @Test
    fun rahulTraders_supplierAccounting_payableAndPaidTodayCalculations() = runBlocking {
        // Rahul Traders (Supplier)
        val rahul = Person(
            id = 1L,
            name = "Rahul Traders",
            phone = "9876543210",
            role = PersonRole.SUPPLIER,
            createdAt = now - 50000L,
            updatedAt = now
        )
        personDao.insertPerson(rahul)

        // 1. Goods received: ₹10,000 (RECEIVE)
        transactionDao.insertTransaction(
            Transaction(
                id = "tx-1",
                personId = rahul.id,
                amountCents = 10_000_00L,
                type = TransactionType.RECEIVE,
                note = "Raw materials batch 1",
                createdAt = now - 40000L,
                updatedAt = now - 40000L
            )
        )

        // 2. Payment made: ₹3,000 (GIVE)
        transactionDao.insertTransaction(
            Transaction(
                id = "tx-2",
                personId = rahul.id,
                amountCents = 3_000_00L,
                type = TransactionType.GIVE,
                note = "Partial payment via NEFT",
                createdAt = now - 30000L,
                updatedAt = now - 30000L
            )
        )

        // 3. Goods received: ₹2,000 (RECEIVE)
        transactionDao.insertTransaction(
            Transaction(
                id = "tx-3",
                personId = rahul.id,
                amountCents = 2_000_00L,
                type = TransactionType.RECEIVE,
                note = "Additional supplies",
                createdAt = now - 20000L,
                updatedAt = now - 20000L
            )
        )

        // 4. Payment made: ₹1,000 (GIVE)
        transactionDao.insertTransaction(
            Transaction(
                id = "tx-4",
                personId = rahul.id,
                amountCents = 1_000_00L,
                type = TransactionType.GIVE,
                note = "Cash installment today",
                createdAt = now,
                updatedAt = now
            )
        )

        // Net Payable: (10,000 + 2,000) - (3,000 + 1,000) = 8,000 (800,000 paise)
        val rahulTxs = transactionDao.getTransactionsForPersonOnce(rahul.id)
        val netPayableCents = rahulTxs.sumOf {
            if (it.type == TransactionType.RECEIVE) it.amountCents else -it.amountCents
        }
        assertEquals(8_000_00L, netPayableCents)
        assertEquals(8000.0, netPayableCents / 100.0, 0.001)

        // Supplier Total Payable query
        val totalPayable = txRepo.observeSupplierTotalPayable().first()
        assertEquals(8_000_00L, totalPayable)

        // Supplier Paid Today: sum of GIVE transactions today (tx-4 = 1,000_00L)
        val paidToday = txRepo.observeSupplierPaidToday(todayStart, todayEnd).first()
        assertEquals(1_000_00L, paidToday)
    }

    @Test
    fun supplierAdvances_areNotOffsetAgainstOtherSupplierDebts() = runBlocking {
        // Supplier 1: Rahul Traders (We owe ₹8,000)
        val supplier1 = Person(id = 1L, name = "Rahul Traders", phone = "9876543210", role = PersonRole.SUPPLIER)
        personDao.insertPerson(supplier1)
        transactionDao.insertTransaction(
            Transaction(id = "s1-tx1", personId = supplier1.id, amountCents = 10_000_00L, type = TransactionType.RECEIVE, createdAt = now)
        )
        transactionDao.insertTransaction(
            Transaction(id = "s1-tx2", personId = supplier1.id, amountCents = 2_000_00L, type = TransactionType.GIVE, createdAt = now)
        )
        // Supplier 1 net payable = 8,000_00L

        // Supplier 2: Sharma Logistics (We paid ₹5,000 advance, received ₹2,000 goods -> Payable = -₹3,000)
        val supplier2 = Person(id = 2L, name = "Sharma Logistics", phone = "9876500000", role = PersonRole.SUPPLIER)
        personDao.insertPerson(supplier2)
        transactionDao.insertTransaction(
            Transaction(id = "s2-tx1", personId = supplier2.id, amountCents = 2_000_00L, type = TransactionType.RECEIVE, createdAt = now)
        )
        transactionDao.insertTransaction(
            Transaction(id = "s2-tx2", personId = supplier2.id, amountCents = 5_000_00L, type = TransactionType.GIVE, createdAt = now)
        )
        // Supplier 2 net payable = -3,000_00L (Advance)

        // Total Payable Card MUST be ₹8,000 (NOT 8,000 - 3,000 = 5,000!)
        val totalPayable = txRepo.observeSupplierTotalPayable().first()
        assertEquals("Supplier advances must not offset debt owed to other suppliers", 8_000_00L, totalPayable)
    }

    @Test
    fun samePhone_customerAndSupplier_remainCompletelyIndependent() = runBlocking {
        val sharedPhone = "9988776655"

        // Customer with shared phone
        val customer = Person(id = 100L, name = "Anil Customer", phone = sharedPhone, role = PersonRole.CUSTOMER)
        personDao.insertPerson(customer)
        transactionDao.insertTransaction(
            Transaction(id = "c-tx1", personId = customer.id, amountCents = 500_00L, type = TransactionType.GIVE, createdAt = now)
        )

        // Supplier with shared phone
        val supplier = Person(id = 200L, name = "Anil Supplier", phone = sharedPhone, role = PersonRole.SUPPLIER)
        personDao.insertPerson(supplier)
        transactionDao.insertTransaction(
            Transaction(id = "s-tx1", personId = supplier.id, amountCents = 1200_00L, type = TransactionType.RECEIVE, createdAt = now)
        )

        // Customer query returns only customer
        val customers = personRepo.observeAllPersons(PersonRole.CUSTOMER).first()
        assertEquals(1, customers.size)
        assertEquals("Anil Customer", customers[0].name)
        assertEquals(PersonRole.CUSTOMER, customers[0].role)

        // Supplier query returns only supplier
        val suppliers = personRepo.observeAllPersons(PersonRole.SUPPLIER).first()
        assertEquals(1, suppliers.size)
        assertEquals("Anil Supplier", suppliers[0].name)
        assertEquals(PersonRole.SUPPLIER, suppliers[0].role)

        // Customer transactions
        val customerTxs = txRepo.observeTransactionsForPerson(customer.id).first()
        assertEquals(1, customerTxs.size)
        assertEquals(500_00L, customerTxs[0].amountCents)

        // Supplier transactions
        val supplierTxs = txRepo.observeTransactionsForPerson(supplier.id).first()
        assertEquals(1, supplierTxs.size)
        assertEquals(1200_00L, supplierTxs[0].amountCents)
    }
}

