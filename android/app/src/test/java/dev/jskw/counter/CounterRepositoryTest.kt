package dev.jskw.counter

import dev.jskw.counter.data.local.CounterDao
import dev.jskw.counter.data.model.Counter
import dev.jskw.counter.data.remote.CounterApiService
import dev.jskw.counter.data.remote.PublishCounterResponse
import dev.jskw.counter.data.remote.RemoteCounterState
import dev.jskw.counter.data.repository.CounterRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    override suspend fun updatePinnedStatus(id: String, isPinned: Boolean) {
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

    override suspend fun publishCounter(
        counter: Counter,
        viewUuid: String,
        managementUuid: String
    ): Result<PublishCounterResponse> {
        publishedViewUuid = viewUuid
        publishedManagementUuid = managementUuid
        return Result.success(
            PublishCounterResponse(
                success = true,
                viewUuid = viewUuid,
                updateUuid = managementUuid,
                deleteUuid = managementUuid,
                count = counter.count
            )
        )
    }

    override suspend fun updateCounter(
        updateUuid: String,
        count: Int,
        title: String?
    ): Result<Boolean> {
        lastUpdatedCount = count
        return Result.success(true)
    }

    override suspend fun deleteCounter(deleteUuid: String): Result<Boolean> {
        deletedUuid = deleteUuid
        return Result.success(true)
    }

    override suspend fun fetchLiveCount(viewUuid: String): Result<RemoteCounterState> {
        return Result.success(RemoteCounterState(viewUuid = viewUuid, count = 42))
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
    fun testPublishGeneratesUuidsAndUpdatesRemote() = runTest {
        val counter = repository.createCounter(title = "Live Counter", initialCount = 3)
        val published = repository.publishCounter(counter.id)

        assertNotNull(published)
        assertTrue(published!!.isPublished)
        assertNotNull(published.viewUuid)
        assertNotNull(published.updateUuid)
        assertNotNull(published.deleteUuid)
        assertEquals(published.updateUuid, published.deleteUuid)

        assertEquals(published.viewUuid, fakeApi.publishedViewUuid)
        assertEquals(published.updateUuid, fakeApi.publishedManagementUuid)
    }

    @Test
    fun testDeleteCounter() = runTest {
        val counter = repository.createCounter(title = "Delete Me", initialCount = 1)
        val published = repository.publishCounter(counter.id)
        assertNotNull(published)

        repository.deleteCounter(published!!.id)

        assertEquals(published.deleteUuid, fakeApi.deletedUuid)
        val deleted = repository.getCounterById(published.id)
        assertEquals(null, deleted)
    }
}
