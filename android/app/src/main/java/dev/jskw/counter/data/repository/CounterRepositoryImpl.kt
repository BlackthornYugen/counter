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
        if (current.isPublished && current.updateUuid != null) {
            runCatching { apiService.incrementCounter(current.updateUuid) }
        }
        return updated
    }

    override suspend fun decrementCounter(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        val updated = current.copy(count = current.count - 1)
        counterDao.updateCounter(updated)
        if (current.isPublished && current.updateUuid != null) {
            runCatching { apiService.decrementCounter(current.updateUuid) }
        }
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
    }

    override suspend fun publishCounter(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null

        val response = apiService.publishCounter(
            initialCount = current.count,
            readOnlyUuid = current.viewUuid,
            readWriteUuid = current.updateUuid,
            adminUuid = current.deleteUuid
        ).getOrNull() ?: return null
        
        val viewUuid = response.roles["read_only"] ?: return null
        // fallback to admin if read_write is absent
        val managementUuid = response.roles["read_write"] ?: response.roles["admin"] ?: return null

        val publishedCounter = current.copy(
            isPublished = true,
            viewUuid = viewUuid,
            updateUuid = managementUuid,
            deleteUuid = response.roles["admin"]
        )

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
            val updated = current.copy(count = remoteState.value)
            counterDao.updateCounter(updated)
            updated
        }
    }

    override fun observeRemoteCount(id: String): Flow<Result<Counter>> = kotlinx.coroutines.flow.flow {
        val current = counterDao.getCounterById(id)
            ?: throw IllegalArgumentException("Counter with id $id not found")
        if (!current.isPublished || current.viewUuid == null) {
            throw IllegalStateException("Counter is not published")
        }

        apiService.observeLiveCount(current.viewUuid).collect { result ->
            result.onSuccess { remoteState ->
                val updated = counterDao.getCounterById(id) ?: return@onSuccess
                val nextCounter = updated.copy(count = remoteState.value)
                counterDao.updateCounter(nextCounter)
                emit(Result.success(nextCounter))
            }.onFailure {
                emit(Result.failure(it))
            }
        }
    }
}
