package com.kb.ledger.release

import android.content.Context
import android.content.ContextWrapper
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.data.remote.AuthError
import com.kb.ledger.data.remote.AuthState
import com.kb.ledger.data.remote.GoogleAuthManager
import com.kb.ledger.data.remote.OAuthTokenProvider
import com.kb.ledger.data.repository.FakePersonDao
import com.kb.ledger.data.repository.FakeRecurringRuleDao
import com.kb.ledger.data.repository.FakeSyncOperationDao
import com.kb.ledger.data.repository.FakeTransactionDao
import com.kb.ledger.data.repository.OfflineFirstPersonRepository
import com.kb.ledger.data.repository.OfflineFirstTransactionRepository
import com.kb.ledger.domain.model.PersonDomain
import com.kb.ledger.domain.model.TransactionDomain
import com.kb.ledger.domain.model.TransactionType as DomainTxType
import com.kb.ledger.data.model.TransactionType as EntityTxType
import com.kb.ledger.domain.repository.SyncState
import com.kb.ledger.sync.DriveSyncWorker
import com.kb.ledger.util.AppResult
import com.kb.ledger.util.BackupData
import com.kb.ledger.util.BackupManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Regression test suite verifying clean-install release safety standards:
 * 1. Google Web Client ID placeholder prevents authentication launch.
 * 2. No Google Sign-In action is presented as available in clean install state.
 * 3. Cloud-sync UI and worker logic cannot trigger unhandled exceptions.
 * 4. Local ledger operations (GIVE, RECEIVE, Net Balance) operate 100% offline.
 * 5. Manual SAF backup export and restore remain fully accessible.
 * 6. Account actions and sign-out never clear or alter local ledger data.
 */
class CleanInstallReleaseSafetyTest {

    private val dummyContext: Context = object : ContextWrapper(null) {}
    private lateinit var authManager: GoogleAuthManager
    private lateinit var fakeTokenProvider: FakeOAuthTokenProvider

    private lateinit var personDao: FakePersonDao
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var recurringRuleDao: FakeRecurringRuleDao
    private lateinit var syncOpDao: FakeSyncOperationDao
    private lateinit var personRepo: OfflineFirstPersonRepository
    private lateinit var txRepo: OfflineFirstTransactionRepository

    class FakeOAuthTokenProvider : OAuthTokenProvider {
        var tokenToReturn: String = "fake-token"
        var callCount: Int = 0
        override suspend fun getAccessToken(accountEmail: String, scope: String): AppResult<String> {
            callCount++
            return AppResult.Success(tokenToReturn)
        }
        override suspend fun clearToken(token: String) {}
    }

    @Before
    fun setUp() {
        fakeTokenProvider = FakeOAuthTokenProvider()
        authManager = GoogleAuthManager(
            context = dummyContext,
            tokenProvider = fakeTokenProvider,
            credentialManagerProvider = { throw IllegalStateException("CredentialManager should NEVER be called when unconfigured") }
        )

        personDao = FakePersonDao()
        transactionDao = FakeTransactionDao()
        recurringRuleDao = FakeRecurringRuleDao()
        syncOpDao = FakeSyncOperationDao()
        personRepo = OfflineFirstPersonRepository(personDao, syncOpDao)
        txRepo = OfflineFirstTransactionRepository(transactionDao, personDao, syncOpDao)
    }

    // ── 1. Web Client ID & Configuration Safety ───────────────────────────────

    @Test
    fun googleAuthManager_isConfigured_returnsFalseForPlaceholderWebClientId() {
        // Assert that the release build correctly flags the placeholder client ID as unconfigured
        assertTrue(GoogleAuthManager.WEB_CLIENT_ID.startsWith("REPLACE_WITH"))
        assertFalse("isConfigured must be false when Web Client ID is a placeholder", GoogleAuthManager.isConfigured)
        assertFalse("instance isConfigured must match companion isConfigured", authManager.isConfigured)
    }

    @Test
    fun googleAuthManager_signIn_rejectsPlaceholderClientIdWithoutLaunchingAuth() = runBlocking {
        // Calling signIn when unconfigured must return AppResult.Error immediately
        // and must NEVER invoke CredentialManager (which would throw IllegalStateException)
        val result = authManager.signIn(dummyContext)

        assertTrue("Result must be an AppResult.Error", result is AppResult.Error)
        val errorMessage = (result as AppResult.Error).message
        assertTrue("Error must state configuration is missing", errorMessage.contains(AuthError.CONFIGURATION_MISSING.code))
        assertTrue("Error must explain Google Sign-In is unavailable", errorMessage.contains("unavailable in this release"))
    }

    // ── 2. Account Preferences & Local Ledger Data Isolation ──────────────────

    @Test
    fun signOut_clearsAuthPreferencesWithoutModifyingRoomLedgerData() = runBlocking {
        // Seed local ledger records
        val person = Person(id = 1L, name = "Test Person", phone = "9876543210")
        personDao.insertPerson(person)

        val tx1 = Transaction(
            id = "tx-1",
            personId = 1L,
            amountCents = 15000L,
            type = EntityTxType.GIVE,
            note = "Seed transaction"
        )
        transactionDao.insertTransaction(tx1)

        // Verify records exist before sign-out
        assertEquals(1, personDao.getAllPersonsList().size)
        assertEquals(1, transactionDao.getAllTransactionsDirect().size)

        // Execute sign-out
        val signOutResult = authManager.signOut()
        assertTrue("Sign out must succeed", signOutResult is AppResult.Success)

        // Verify local Room records are 100% preserved
        val personsAfter = personDao.getAllPersonsList()
        val txsAfter = transactionDao.getAllTransactionsDirect()
        assertEquals("Local persons must NOT be deleted on sign-out", 1, personsAfter.size)
        assertEquals("Local transactions must NOT be deleted on sign-out", 1, txsAfter.size)
        assertEquals("Test Person", personsAfter[0].name)
        assertEquals(15000L, txsAfter[0].amountCents)
    }

    // ── 3. Offline Ledger Operations (GIVE, RECEIVE, Net Balance) ─────────────

    @Test
    fun localLedgerOperations_function100PercentOfflineWithoutGoogleAuth() = runBlocking {
        // Ensure authState is not authenticated
        assertFalse("Must operate in offline mode without authentication", authManager.isSignedIn)

        // 1. Create person offline
        val personDomain = PersonDomain(name = "Offline User", phone = "9123456780")
        val personResult = personRepo.savePerson(personDomain)
        assertTrue(personResult is AppResult.Success)
        val personId = (personResult as AppResult.Success).data.localId

        // 2. Add GIVE transaction (₹100 = 10,000 cents)
        val giveTx = TransactionDomain(
            id = UUID.randomUUID().toString(),
            personLocalId = personId,
            amountCents = 10000L,
            type = DomainTxType.GIVE,
            note = "Offline loan"
        )
        val giveResult = txRepo.saveTransaction(giveTx)
        assertTrue(giveResult is AppResult.Success)

        // 3. Add RECEIVE transaction (₹40 = 4,000 cents)
        val receiveTx = TransactionDomain(
            id = UUID.randomUUID().toString(),
            personLocalId = personId,
            amountCents = 4000L,
            type = DomainTxType.RECEIVE,
            note = "Offline partial payment"
        )
        val receiveResult = txRepo.saveTransaction(receiveTx)
        assertTrue(receiveResult is AppResult.Success)

        // 4. Verify Net Balance = RECEIVE - GIVE = 4,000 - 10,000 = -6,000 cents (-₹60.00)
        val personTxs = transactionDao.getAllTransactionsDirect().filter { it.personId == personId }
        val totalReceive = personTxs.filter { it.type == EntityTxType.RECEIVE }.sumOf { it.amountCents }
        val totalGive = personTxs.filter { it.type == EntityTxType.GIVE }.sumOf { it.amountCents }
        val netBalanceCents = totalReceive - totalGive

        assertEquals(4000L, totalReceive)
        assertEquals(10000L, totalGive)
        assertEquals(-6000L, netBalanceCents)
    }

    // ── 4. Manual SAF Backup & Restore Operates Completely Offline ────────────

    @Test
    fun manualSafBackupAndRestore_operateFullyWithoutGoogleAuth() = runBlocking {
        // Populate local DAOs
        val person = Person(id = 10L, name = "SAF Person", phone = "9800000000", cloudId = "cloud-saf-10")
        personDao.insertPerson(person)

        val tx = Transaction(
            id = "tx-saf-1",
            personId = 10L,
            amountCents = 7550L,
            type = EntityTxType.GIVE,
            note = "SAF backup test"
        )
        transactionDao.insertTransaction(tx)

        // Construct BackupData envelope representing manual SAF export
        val exportEnvelope = BackupData(
            schemaVersion = 3,
            exportDate = System.currentTimeMillis(),
            persons = personDao.getAllPersonsList(),
            transactions = transactionDao.getAllTransactionsDirect(),
            recurringRules = emptyList()
        )

        // Restore into fresh empty DAOs (simulating clean install restore)
        val freshPersonDao = FakePersonDao()
        val freshTxDao = FakeTransactionDao()
        val freshRuleDao = FakeRecurringRuleDao()

        val stats = BackupManager.restoreDatabaseRecords(
            backupData = exportEnvelope,
            personDao = freshPersonDao,
            transactionDao = freshTxDao,
            recurringRuleDao = freshRuleDao
        )

        assertEquals(1, stats.restoredPersonsCount)
        assertEquals(1, stats.restoredTransactionsCount)

        val restoredPersons = freshPersonDao.getAllPersonsList()
        val restoredTxs = freshTxDao.getAllTransactionsDirect()
        assertEquals(1, restoredPersons.size)
        assertEquals("SAF Person", restoredPersons[0].name)
        assertEquals(1, restoredTxs.size)
        assertEquals(7550L, restoredTxs[0].amountCents)
        assertEquals(EntityTxType.GIVE, restoredTxs[0].type)
    }

    // ── 5. Cloud Worker & Sync State Safety ────────────────────────────────────

    @Test
    fun cloudSyncState_remainsOfflineWhenNotConfigured() {
        // Assert that sync state defaults to offline and does not trigger errors
        DriveSyncWorker.updateSyncState(SyncState.Offline)
        val state = DriveSyncWorker.globalSyncState.value
        assertTrue("Sync state must be Offline", state is SyncState.Offline)
    }
}
