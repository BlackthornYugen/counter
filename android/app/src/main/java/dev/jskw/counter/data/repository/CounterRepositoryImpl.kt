package dev.jskw.counter.data.repository

import dev.jskw.counter.data.local.CounterDao
import dev.jskw.counter.data.model.Counter
import dev.jskw.counter.data.remote.CounterApiService
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class CounterRepositoryImpl(
    private val counterDao: CounterDao,
    private val apiService: CounterApiService
) : CounterRepository {

    override fun getAllCounters(): Flow<List<Counter>> {
        return counterDao.getAllCounters()
    }

    override fun getPinnedCounters(): Flow<List<Counter>> {
        return counterDao.getPinnedCounters()
    }

    override suspend fun getCounterById(id: String): Counter? {
        return counterDao.getCounterById(id)
    }

    override suspend fun createCounter(title: String, initialCount: Int): Counter {
        val newCounter = Counter(
            id = UUID.randomUUID().toString(),
            title = title,
            count = initialCount,
            isPublished = false,
            viewUuid = null,
            updateUuid = null,
            deleteUuid = null
        )
        counterDao.insertCounter(newCounter)
        return newCounter
    }

    override suspend fun incrementCounter(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        val updated = current.copy(count = current.count + 1)
        counterDao.updateCounter(updated)
        syncIfPublished(updated)
        return updated
    }

    override suspend fun decrementCounter(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        val updated = current.copy(count = current.count - 1)
        counterDao.updateCounter(updated)
        syncIfPublished(updated)
        return updated
    }

    override suspend fun updatePinnedStatus(id: String, isPinned: Boolean): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        val updated = current.copy(isPinned = isPinned)
        counterDao.updateCounter(updated)
        return updated
    }

    override suspend fun togglePin(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        return updatePinnedStatus(id, !current.isPinned)
    }

    override suspend fun deleteCounter(id: String) {
        val current = counterDao.getCounterById(id)
        if (current != null && current.isPublished && current.deleteUuid != null) {
            runCatching { apiService.deleteCounter(current.deleteUuid) }
        }
        counterDao.deleteCounterById(id)
    }

    override suspend fun updateCounter(counter: Counter) {
        counterDao.updateCounter(counter)
        syncIfPublished(counter)
    }

    override suspend fun publishCounter(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null

        val viewUuid = current.viewUuid ?: UUID.randomUUID().toString()
        val managementUuid = current.updateUuid ?: UUID.randomUUID().toString()

        val publishedCounter = current.copy(
            isPublished = true,
            viewUuid = viewUuid,
            updateUuid = managementUuid,
            deleteUuid = managementUuid
        )

        apiService.publishCounter(publishedCounter, viewUuid, managementUuid)

        counterDao.updateCounter(publishedCounter)
        return publishedCounter
    }

    override suspend fun unpublishCounter(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        if (current.deleteUuid != null) {
            runCatching { apiService.deleteCounter(current.deleteUuid) }
        }
        val unpublished = current.copy(
            isPublished = false
        )
        counterDao.updateCounter(unpublished)
        return unpublished
    }

    override suspend fun syncRemoteCount(id: String): Result<Counter> {
        val current = counterDao.getCounterById(id)
            ?: return Result.failure(IllegalArgumentException("Counter with id $id not found"))
        if (!current.isPublished || current.viewUuid == null) {
            return Result.failure(IllegalStateException("Counter is not published"))
        }
        return apiService.fetchLiveCount(current.viewUuid).mapCatching { remoteState ->
            val updated = current.copy(count = remoteState.count ?: current.count)
            counterDao.updateCounter(updated)
            updated
        }
    }

    private suspend fun syncIfPublished(counter: Counter) {
        if (counter.isPublished && counter.updateUuid != null) {
            runCatching {
                apiService.updateCounter(counter.updateUuid, counter.count, counter.title)
            }
        }
    }
}
