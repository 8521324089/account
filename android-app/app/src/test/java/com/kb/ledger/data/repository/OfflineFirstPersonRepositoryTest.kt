package com.kb.ledger.data.repository

import com.kb.ledger.data.json.JsonSchemaValidator
import com.kb.ledger.data.model.SyncEntityType
import com.kb.ledger.data.model.SyncOperationType
import com.kb.ledger.domain.model.PersonDomain
import com.kb.ledger.domain.model.SyncStatus
import com.kb.ledger.util.AppResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OfflineFirstPersonRepositoryTest {

    private lateinit var personDao: FakePersonDao
    private lateinit var syncOpDao: FakeSyncOperationDao
    private lateinit var repository: OfflineFirstPersonRepository

    @Before
    fun setUp() {
        personDao = FakePersonDao()
        syncOpDao = FakeSyncOperationDao()
        repository = OfflineFirstPersonRepository(personDao, syncOpDao)
    }

    @Test
    fun testSavePersonGeneratesCloudIdAndQueuesSyncOperation() = runTest {
        val input = PersonDomain(name = "John Doe", phone = "+1234567890")

        val result = repository.savePerson(input)

        assertTrue(result is AppResult.Success)
        val saved = (result as AppResult.Success).data
        assertTrue(saved.localId > 0)
        assertNotNull(saved.cloudId)
        assertTrue(JsonSchemaValidator.isValidUuid(saved.cloudId!!))
        assertEquals(SyncStatus.PENDING_UPLOAD, saved.syncStatus)

        // Verify sync operation queued
        val ops = syncOpDao.getAll()
        assertEquals(1, ops.size)
        assertEquals(SyncOperationType.UPSERT, ops[0].type)
        assertEquals(SyncEntityType.PERSON, ops[0].entityType)
        assertEquals(saved.cloudId, ops[0].entityId)
    }

    @Test
    fun testObserveAllPersonsExcludesDeleted() = runTest {
        repository.savePerson(PersonDomain(name = "Alice", phone = "111"))
        val p2 = (repository.savePerson(PersonDomain(name = "Bob", phone = "222")) as AppResult.Success).data

        var observed = repository.observeAllPersons().first()
        assertEquals(2, observed.size)

        repository.deletePerson(p2.localId)

        observed = repository.observeAllPersons().first()
        assertEquals(1, observed.size)
        assertEquals("Alice", observed[0].name)
    }

    @Test
    fun testDeletePersonQueuesDeleteSyncOperation() = runTest {
        val person = (repository.savePerson(PersonDomain(name = "Charlie", phone = "333")) as AppResult.Success).data

        val deleteResult = repository.deletePerson(person.localId)
        assertTrue(deleteResult is AppResult.Success)

        val ops = syncOpDao.getAll()
        val deleteOp = ops.firstOrNull { it.type == SyncOperationType.DELETE }
        assertNotNull(deleteOp)
        assertEquals(SyncEntityType.PERSON, deleteOp!!.entityType)
        assertEquals(person.cloudId, deleteOp.entityId)
    }

    @Test
    fun testApplyRemotePersonsLww() = runTest {
        val cloudId = "550e8400-e29b-41d4-a716-446655440000"
        val local = (repository.savePerson(PersonDomain(name = "Local Name", phone = "111", cloudId = cloudId)) as AppResult.Success).data

        // 1. Remote with older timestamp should be ignored
        val olderRemote = PersonDomain(
            cloudId = cloudId,
            name = "Older Remote",
            phone = "111",
            updatedAt = local.updatedAt - 1000
        )
        repository.applyRemotePersons(listOf(olderRemote))
        assertEquals("Local Name", repository.getPersonByLocalId(local.localId)?.name)

        // 2. Remote with newer timestamp should overwrite
        val newerRemote = PersonDomain(
            cloudId = cloudId,
            name = "Newer Remote",
            phone = "999",
            updatedAt = local.updatedAt + 1000
        )
        repository.applyRemotePersons(listOf(newerRemote))
        val updated = repository.getPersonByLocalId(local.localId)
        assertEquals("Newer Remote", updated?.name)
        assertEquals("999", updated?.phone)
    }
}
