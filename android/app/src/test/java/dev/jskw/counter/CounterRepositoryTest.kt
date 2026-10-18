package dev.jskw.counter

import dev.jskw.counter.data.local.CounterDao
import dev.jskw.counter.data.model.Counter
import dev.jskw.counter.data.model.canDelete
import dev.jskw.counter.data.model.canDeleteOnServer
import dev.jskw.counter.data.model.canIncrementDecrement
import dev.jskw.counter.data.remote.CounterApiService
import dev.jskw.counter.data.remote.CounterInfoResponse
import dev.jskw.counter.data.remote.LiveUpdate
import dev.jskw.counter.data.remote.RegisterResponse
import dev.jskw.counter.data.repository.CounterRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeCounterDao : CounterDao {
    private val countersMap = mutableMapOf<String, Counter>()
    private val flow = MutableStateFlow<List<Counter>>(emptyList())
    private val pinnedFlow = MutableStateFlow<List<Counter>>(emptyList())

    private fun updateFlow() {
        val sortedList = countersMap.values.sortedBy { it.title }
        flow.value = sortedList
        pinnedFlow.value = sortedList.filter { it.isPinned }
    }

    override fun getAllCounters(): Flow<List<Counter>> = flow

    override fun getPinnedCounters(): Flow<List<Counter>> = pinnedFlow

    override suspend fun getCounterById(id: String): Counter? = countersMap[id]

    override suspend fun insertCounter(counter: Counter) {
        countersMap[counter.id] = counter
        updateFlow()
    }

    override suspend fun updateCounter(counter: Counter) {
        countersMap[counter.id] = counter
        updateFlow()
    }

    override suspend fun unpinAll() {
        countersMap.keys.forEach { k ->
            countersMap[k] = countersMap[k]!!.copy(isPinned = false)
        }
        updateFlow()
    }

    override suspend fun updatePinnedStatus(id: String, isPinned: Boolean) {
        if (isPinned) {
            countersMap.keys.forEach { k ->
                countersMap[k] = countersMap[k]!!.copy(isPinned = false)
            }
        }
        val current = countersMap[id]
        if (current != null) {
            countersMap[id] = current.copy(isPinned = isPinned)
            updateFlow()
        }
    }

    override suspend fun deleteCounter(counter: Counter) {
        countersMap.remove(counter.id)
        updateFlow()
    }

    override suspend fun deleteCounterById(id: String) {
        countersMap.remove(id)
        updateFlow()
    }
}

class FakeCounterApiService : CounterApiService {
    var publishedViewUuid: String? = null
    var publishedManagementUuid: String? = null
    var lastUpdatedCount: Int? = null
    var deletedUuid: String? = null

    val tokenRolesMap = mutableMapOf<String, String>()

    override suspend fun publishCounter(
        initialCount: Int,
        readOnlyUuid: String?,
        readWriteUuid: String?,
        adminUuid: String?
    ): Result<RegisterResponse> {
        publishedViewUuid = readOnlyUuid ?: java.util.UUID.randomUUID().toString()
        publishedManagementUuid = readWriteUuid ?: java.util.UUID.randomUUID().toString()
        val resolvedAdmin = adminUuid ?: java.util.UUID.randomUUID().toString()

        tokenRolesMap[publishedViewUuid!!] = "read_only"
        tokenRolesMap[publishedManagementUuid!!] = "read_write"
        tokenRolesMap[resolvedAdmin] = "admin"

        return Result.success(
            RegisterResponse(
                counter_id = "test",
                value = initialCount,
                roles = mapOf("read_only" to publishedViewUuid!!, "read_write" to publishedManagementUuid!!, "admin" to resolvedAdmin),
                message = "success"
            )
        )
    }

    override suspend fun incrementCounter(token: String): Result<Boolean> {
        return Result.success(true)
    }

    override suspend fun decrementCounter(token: String): Result<Boolean> {
        return Result.success(true)
    }

    override suspend fun deleteCounter(token: String): Result<Boolean> {
        deletedUuid = token
        return Result.success(true)
    }

    override suspend fun fetchLiveCount(token: String): Result<CounterInfoResponse> {
        val access = tokenRolesMap[token] ?: when {
            token.contains("ro") -> "read_only"
            token.contains("rw") -> "read_write"
            else -> "admin"
        }
        return Result.success(CounterInfoResponse(counter_id = "test", value = 42, access = access))
    }

    override fun observeLiveCount(token: String): Flow<Result<LiveUpdate>> = kotlinx.coroutines.flow.flow {
        emit(Result.success(LiveUpdate(value = 42)))
    }
}

class CounterRepositoryTest {

    private lateinit var fakeDao: FakeCounterDao
    private lateinit var fakeApi: FakeCounterApiService
    private lateinit var repository: CounterRepositoryImpl

    @Before
    fun setup() {
        fakeDao = FakeCounterDao()
        fakeApi = FakeCounterApiService()
        repository = CounterRepositoryImpl(fakeDao, fakeApi)
    }

    @Test
    fun testCreateAndGetCounter() = runTest {
        val counter = repository.createCounter(title = "Test Counter", initialCount = 10)
        assertNotNull(counter.id)
        assertEquals("Test Counter", counter.title)
        assertEquals(10, counter.count)

        val retrieved = repository.getCounterById(counter.id)
        assertEquals(counter, retrieved)
    }

    @Test
    fun testIncrementAndDecrement() = runTest {
        val counter = repository.createCounter(title = "Item Count", initialCount = 5)

        val incremented = repository.incrementCounter(counter.id)
        assertEquals(6, incremented?.count)

        val decremented = repository.decrementCounter(counter.id)
        assertEquals(5, decremented?.count)
    }

    @Test
    fun testDecrementDoesNotGoNegative() = runTest {
        val counter = repository.createCounter(title = "Zero Count", initialCount = 0)

        val decremented = repository.decrementCounter(counter.id)
        assertEquals(0, decremented?.count)
    }

    @Test
    fun testTogglePinAndPinnedCounters() = runTest {
        val counter = repository.createCounter(title = "Pinned Item", initialCount = 0)
        assertFalse(counter.isPinned)

        val pinned = repository.togglePin(counter.id)
        assertNotNull(pinned)
        assertTrue(pinned!!.isPinned)

        val pinnedList = repository.getPinnedCounters().first()
        assertEquals(1, pinnedList.size)
        assertEquals(counter.id, pinnedList[0].id)

        val unpinned = repository.togglePin(counter.id)
        assertNotNull(unpinned)
        assertFalse(unpinned!!.isPinned)

        val emptyPinnedList = repository.getPinnedCounters().first()
        assertTrue(emptyPinnedList.isEmpty())
    }

    @Test
    fun testPinningNewCounterUnpinsExisting() = runTest {
        val counter1 = repository.createCounter(title = "Counter 1", initialCount = 0)
        val counter2 = repository.createCounter(title = "Counter 2", initialCount = 0)

        repository.togglePin(counter1.id)
        val pinnedList1 = repository.getPinnedCounters().first()
        assertEquals(1, pinnedList1.size)
        assertEquals(counter1.id, pinnedList1[0].id)

        repository.togglePin(counter2.id)
        val pinnedList2 = repository.getPinnedCounters().first()
        assertEquals(1, pinnedList2.size)
        assertEquals(counter2.id, pinnedList2[0].id)

        val retrieved1 = repository.getCounterById(counter1.id)
        assertFalse(retrieved1!!.isPinned)
    }

    @Test
    fun testPublishGeneratesUuidsAndUpdatesRemote() = runTest {
        val counter = repository.createCounter(title = "Live Counter", initialCount = 3)
        val published = repository.publishCounter(counter.id).getOrNull()

        assertNotNull(published)
        assertTrue(published!!.isPublished)
        assertNotNull(published.viewUuid)
        assertNotNull(published.updateUuid)
        assertNotNull(published.deleteUuid)

        assertEquals(published.viewUuid, fakeApi.publishedViewUuid)
        assertEquals(published.updateUuid, fakeApi.publishedManagementUuid)
    }

    @Test
    fun testDeleteCounter() = runTest {
        val counter = repository.createCounter(title = "Delete Me", initialCount = 1)
        val published = repository.publishCounter(counter.id).getOrNull()
        assertNotNull(published)

        repository.deleteCounter(published!!.id)

        assertEquals(published.deleteUuid, fakeApi.deletedUuid)
        val deleted = repository.getCounterById(published.id)
        assertEquals(null, deleted)
    }

    @Test
    fun testRepublishPreservesUuids() = runTest {
        val counter = repository.createCounter(title = "Republish Counter", initialCount = 7)
        val published = repository.publishCounter(counter.id).getOrNull()
        assertNotNull(published)
        assertTrue(published!!.isPublished)

        val originalViewUuid = published.viewUuid
        val originalUpdateUuid = published.updateUuid
        val originalDeleteUuid = published.deleteUuid
        assertNotNull(originalViewUuid)
        assertNotNull(originalUpdateUuid)
        assertNotNull(originalDeleteUuid)

        val unpublished = repository.unpublishCounter(counter.id).getOrNull()
        assertNotNull(unpublished)
        assertFalse(unpublished!!.isPublished)
        assertEquals(originalViewUuid, unpublished.viewUuid)
        assertEquals(originalUpdateUuid, unpublished.updateUuid)
        assertEquals(originalDeleteUuid, unpublished.deleteUuid)

        val republished = repository.publishCounter(counter.id).getOrNull()
        assertNotNull(republished)
        assertTrue(republished!!.isPublished)
        assertEquals(originalViewUuid, republished.viewUuid)
        assertEquals(originalUpdateUuid, republished.updateUuid)
        assertEquals(originalDeleteUuid, republished.deleteUuid)
    }

    @Test
    fun testImportCounterFromReadWriteLink() = runTest {
        val roUuid = "c768f56e-8ff4-4363-8822-79ee8ca6eef4"
        val rwUuid = "e2244bb4-0997-4ef8-8280-9dfbf2ba1022"
        fakeApi.tokenRolesMap[roUuid] = "read_only"
        fakeApi.tokenRolesMap[rwUuid] = "read_write"

        val link = "#Shared RW Counter#read#$roUuid#write#$rwUuid"
        val result = repository.importCounterFromLink(link)

        assertTrue(result.isSuccess)
        val counter = result.getOrNull()!!
        assertEquals("Shared RW Counter", counter.title)
        assertEquals(42, counter.count)
        assertTrue(counter.isPublished)
        assertEquals(roUuid, counter.viewUuid)
        assertEquals(rwUuid, counter.updateUuid)
        assertNull(counter.deleteUuid)

        // Read-write permission check
        assertTrue(counter.canIncrementDecrement)
        assertFalse(counter.canDeleteOnServer)
    }

    @Test
    fun testImportCounterFromReadOnlyLink() = runTest {
        val roUuid = "c768f56e-8ff4-4363-8822-79ee8ca6eef4"
        fakeApi.tokenRolesMap[roUuid] = "read_only"

        val link = "#Shared RO Counter#read#$roUuid"
        val result = repository.importCounterFromLink(link)

        assertTrue(result.isSuccess)
        val counter = result.getOrNull()!!
        assertEquals("Shared RO Counter", counter.title)
        assertEquals(42, counter.count)
        assertTrue(counter.isPublished)
        assertEquals(roUuid, counter.viewUuid)
        assertNull(counter.updateUuid)
        assertNull(counter.deleteUuid)

        // Read-only permission check: cannot increment/decrement, cannot delete on server
        assertFalse(counter.canIncrementDecrement)
        assertFalse(counter.canDeleteOnServer)

        // Attempting increment should not change local count
        val attemptIncrement = repository.incrementCounter(counter.id)
        assertEquals(42, attemptIncrement?.count)
    }

    @Test
    fun testDeleteCounterWithoutDeletingOnServer() = runTest {
        fakeApi.deletedUuid = null
        val counter = repository.createCounter(title = "Keep On Server", initialCount = 1)
        val published = repository.publishCounter(counter.id).getOrNull()
        assertNotNull(published)

        repository.deleteCounter(published!!.id, deleteOnServer = false)

        // Should NOT call server delete
        assertNull(fakeApi.deletedUuid)
        // Should be removed locally
        val deleted = repository.getCounterById(published.id)
        assertNull(deleted)
    }

    @Test
    fun testDeleteReadOnlyCounterWithoutUnpublishing() = runTest {
        fakeApi.deletedUuid = null
        val roUuid = "c768f56e-8ff4-4363-8822-79ee8ca6eef4"
        fakeApi.tokenRolesMap[roUuid] = "read_only"

        val link = "#Shared RO Counter#read#$roUuid"
        val counter = repository.importCounterFromLink(link).getOrThrow()

        // Read-only counter can be removed locally
        repository.deleteCounter(counter.id, deleteOnServer = false)

        assertNull(fakeApi.deletedUuid)
        val deleted = repository.getCounterById(counter.id)
        assertNull(deleted)
    }

    @Test
    fun testUnpublishCounterRequiresAdminToken() = runTest {
        val roUuid = "c768f56e-8ff4-4363-8822-79ee8ca6eef4"
        fakeApi.tokenRolesMap[roUuid] = "read_only"

        val link = "#Shared RO Counter#read#$roUuid"
        val counter = repository.importCounterFromLink(link).getOrThrow()

        val unpublishResult = repository.unpublishCounter(counter.id)
        assertTrue(unpublishResult.isFailure)
    }

    @Test
    fun testForgetAdminKey() = runTest {
        val counter = repository.createCounter(title = "Admin Counter", initialCount = 5)
        val published = repository.publishCounter(counter.id).getOrNull()!!
        assertNotNull(published.deleteUuid)

        val updated = repository.forgetAdminKey(published.id)
        assertNotNull(updated)
        assertNull(updated!!.deleteUuid)
        assertNotNull(updated.viewUuid)
        assertNotNull(updated.updateUuid)
        assertFalse(updated.canDeleteOnServer)

        val retrieved = repository.getCounterById(published.id)
        assertNull(retrieved!!.deleteUuid)
    }

    @Test
    fun testImportLinkDuplicatePreventionAndUpsert() = runTest {
        val roUuid = "c768f56e-8ff4-4363-8822-79ee8ca6eef4"
        val rwUuid = "e2244bb4-0997-4ef8-8280-9dfbf2ba1022"
        fakeApi.tokenRolesMap[roUuid] = "read_only"
        fakeApi.tokenRolesMap[rwUuid] = "read_write"

        // 1st import: read-only link
        val link1 = "#Initial Title#read#$roUuid"
        val res1 = repository.importCounterFromLink(link1)
        assertTrue(res1.isSuccess)
        val c1 = res1.getOrNull()!!
        assertEquals("Initial Title", c1.title)
        assertNull(c1.updateUuid)

        // 2nd import: open link for same counter with new title and read-write key
        val link2 = "#Updated Title#read#$roUuid#write#$rwUuid"
        val res2 = repository.importCounterFromLink(link2)
        assertTrue(res2.isSuccess)
        val c2 = res2.getOrNull()!!

        // Ensure same counter ID (upsert, not duplicate)
        assertEquals(c1.id, c2.id)
        assertEquals("Updated Title", c2.title)
        assertEquals(roUuid, c2.viewUuid)
        assertEquals(rwUuid, c2.updateUuid)

        // Check total count in DB is still 1
        val all = repository.getAllCounters().first()
        assertEquals(1, all.size)
    }

    @Test
    fun testImportLinkRefusesLinkWithoutReadOnlyToken() = runTest {
        val rwUuid = "e2244bb4-0997-4ef8-8280-9dfbf2ba1022"
        fakeApi.tokenRolesMap[rwUuid] = "read_write"

        // Link with only read-write token, no read-only token
        val link = "#RW Only#write#$rwUuid"
        val res = repository.importCounterFromLink(link)

        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is IllegalArgumentException)
    }
}
