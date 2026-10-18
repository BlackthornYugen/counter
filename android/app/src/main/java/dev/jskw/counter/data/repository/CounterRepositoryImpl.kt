package dev.jskw.counter.data.repository

import dev.jskw.counter.data.local.CounterDao
import dev.jskw.counter.data.model.Counter
import dev.jskw.counter.data.remote.CounterApiService
import dev.jskw.counter.util.AppLog
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
        AppLog.d(TAG, "createCounter: created local counter id=${newCounter.id}, title='$title', count=$initialCount")
        return newCounter
    }

    override suspend fun incrementCounter(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        val updated = current.copy(count = current.count + 1)
        counterDao.updateCounter(updated)
        AppLog.d(TAG, "incrementCounter: id=$id local count updated to ${updated.count}")
        if (current.isPublished && current.updateUuid != null) {
            AppLog.d(TAG, "incrementCounter: dispatching remote increment for token=${current.updateUuid}")
            val remoteResult = apiService.incrementCounter(current.updateUuid)
            remoteResult.onFailure { e ->
                AppLog.e(TAG, "incrementCounter remote sync failed: ${e.message}", e)
            }
        }
        return updated
    }

    override suspend fun decrementCounter(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        val updated = current.copy(count = current.count - 1)
        counterDao.updateCounter(updated)
        AppLog.d(TAG, "decrementCounter: id=$id local count updated to ${updated.count}")
        if (current.isPublished && current.updateUuid != null) {
            AppLog.d(TAG, "decrementCounter: dispatching remote decrement for token=${current.updateUuid}")
            val remoteResult = apiService.decrementCounter(current.updateUuid)
            remoteResult.onFailure { e ->
                AppLog.e(TAG, "decrementCounter remote sync failed: ${e.message}", e)
            }
        }
        return updated
    }

    override suspend fun updatePinnedStatus(id: String, isPinned: Boolean): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        val updated = current.copy(isPinned = isPinned)
        counterDao.updateCounter(updated)
        AppLog.d(TAG, "updatePinnedStatus: id=$id isPinned=$isPinned")
        return updated
    }

    override suspend fun togglePin(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        return updatePinnedStatus(id, !current.isPinned)
    }

    override suspend fun deleteCounter(id: String) {
        val current = counterDao.getCounterById(id)
        AppLog.d(TAG, "deleteCounter: deleting id=$id, isPublished=${current?.isPublished}, deleteUuid=${current?.deleteUuid}")
        if (current != null && current.isPublished && current.deleteUuid != null) {
            val remoteResult = apiService.deleteCounter(current.deleteUuid)
            remoteResult.onFailure { e ->
                AppLog.w(TAG, "deleteCounter: remote delete failed: ${e.message}", e)
            }
        }
        counterDao.deleteCounterById(id)
    }

    override suspend fun updateCounter(counter: Counter) {
        counterDao.updateCounter(counter)
    }

    override suspend fun publishCounter(id: String): Result<Counter> {
        val current = counterDao.getCounterById(id)
            ?: return Result.failure(IllegalArgumentException("Counter with id $id not found"))

        AppLog.d(TAG, "publishCounter: publishing counter id=$id title='${current.title}' count=${current.count}")

        // For new publishing, null UUIDs cause the server to generate fresh tokens.
        // For republishing, pass the existing UUIDs previously obtained from the server so the share link is preserved.
        val apiResult = apiService.publishCounter(
            initialCount = current.count,
            readOnlyUuid = current.viewUuid,
            readWriteUuid = current.updateUuid,
            adminUuid = current.deleteUuid
        )

        val response = apiResult.getOrElse { e ->
            // If the room was already registered on the server (e.g. remote delete was skipped or failed on unpublish)
            if (current.viewUuid != null && e.message?.contains("409") == true) {
                AppLog.w(TAG, "publishCounter: room already registered (409). Checking if accessible via viewUuid=${current.viewUuid}")
                val liveCheck = apiService.fetchLiveCount(current.viewUuid)
                if (liveCheck.isSuccess) {
                    AppLog.i(TAG, "publishCounter: existing counter room is valid and accessible, restoring published state")
                    val republishedCounter = current.copy(isPublished = true)
                    counterDao.updateCounter(republishedCounter)
                    return Result.success(republishedCounter)
                }
            }
            AppLog.e(TAG, "publishCounter: API call failed for id=$id: ${e.message}", e)
            return Result.failure(e)
        }

        val viewUuid = response.roles["read_only"]
        val managementUuid = response.roles["read_write"] ?: response.roles["admin"]
        val adminUuid = response.roles["admin"]

        if (viewUuid == null || managementUuid == null) {
            val err = IllegalStateException("Server response missing required roles: ${response.roles}")
            AppLog.e(TAG, "publishCounter: $err")
            return Result.failure(err)
        }

        val publishedCounter = current.copy(
            isPublished = true,
            viewUuid = viewUuid,
            updateUuid = managementUuid,
            deleteUuid = adminUuid
        )

        counterDao.updateCounter(publishedCounter)
        AppLog.i(TAG, "publishCounter: counter id=$id published successfully! viewUuid=$viewUuid, updateUuid=$managementUuid, adminUuid=$adminUuid")
        return Result.success(publishedCounter)
    }

    override suspend fun unpublishCounter(id: String): Result<Counter> {
        val current = counterDao.getCounterById(id)
            ?: return Result.failure(IllegalArgumentException("Counter with id $id not found"))

        AppLog.d(TAG, "unpublishCounter: unpublishing id=$id, deleteUuid=${current.deleteUuid}")
        if (current.deleteUuid != null) {
            val delResult = apiService.deleteCounter(current.deleteUuid)
            delResult.onFailure { e ->
                AppLog.w(TAG, "unpublishCounter: remote delete returned error: ${e.message}", e)
            }
        }
        val unpublished = current.copy(
            isPublished = false
        )
        counterDao.updateCounter(unpublished)
        AppLog.i(TAG, "unpublishCounter: counter id=$id unpublished, retaining UUIDs for republishing")
        return Result.success(unpublished)
    }

    override suspend fun syncRemoteCount(id: String): Result<Counter> {
        val current = counterDao.getCounterById(id)
            ?: return Result.failure(IllegalArgumentException("Counter with id $id not found"))
        if (!current.isPublished || current.viewUuid == null) {
            return Result.failure(IllegalStateException("Counter is not published"))
        }
        AppLog.d(TAG, "syncRemoteCount: fetching live count for id=$id, viewUuid=${current.viewUuid}")
        return apiService.fetchLiveCount(current.viewUuid).mapCatching { remoteState ->
            val updated = current.copy(count = remoteState.value)
            counterDao.updateCounter(updated)
            AppLog.d(TAG, "syncRemoteCount: updated id=$id count to ${remoteState.value}")
            updated
        }.onFailure { e ->
            AppLog.e(TAG, "syncRemoteCount: failed for id=$id: ${e.message}", e)
        }
    }

    override fun observeRemoteCount(id: String): Flow<Result<Counter>> = kotlinx.coroutines.flow.flow {
        val current = counterDao.getCounterById(id)
            ?: throw IllegalArgumentException("Counter with id $id not found")
        if (!current.isPublished || current.viewUuid == null) {
            throw IllegalStateException("Counter is not published")
        }

        AppLog.d(TAG, "observeRemoteCount: listening on viewUuid=${current.viewUuid} for counter id=$id")
        apiService.observeLiveCount(current.viewUuid).collect { result ->
            result.onSuccess { remoteState ->
                val updated = counterDao.getCounterById(id) ?: return@onSuccess
                val nextCounter = updated.copy(count = remoteState.value)
                counterDao.updateCounter(nextCounter)
                AppLog.d(TAG, "observeRemoteCount: received remote value ${remoteState.value} for id=$id")
                emit(Result.success(nextCounter))
            }.onFailure {
                AppLog.e(TAG, "observeRemoteCount: stream error for id=$id: ${it.message}", it)
                emit(Result.failure(it))
            }
        }
    }

    companion object {
        private const val TAG = "CounterRepo"
    }
}
