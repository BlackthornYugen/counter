package dev.jskw.counter.data.repository

import android.content.Context
import dev.jskw.counter.data.local.CounterDao
import dev.jskw.counter.data.model.Counter
import dev.jskw.counter.data.model.canIncrementDecrement
import dev.jskw.counter.data.model.effectiveAdminToken
import dev.jskw.counter.data.model.effectiveReadToken
import dev.jskw.counter.data.model.effectiveWriteToken
import dev.jskw.counter.data.remote.CounterApiService
import dev.jskw.counter.sync.WearSyncUtils
import dev.jskw.counter.util.AppLog
import dev.jskw.counter.util.CounterLinkParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID

class CounterRepositoryImpl(
    private val counterDao: CounterDao,
    private val apiService: CounterApiService,
    private val context: Context? = null
) : CounterRepository {

    private suspend fun pushAllToWear() {
        if (context != null) {
            val all = counterDao.getAllCounters().firstOrNull() ?: emptyList()
            WearSyncUtils.pushCountersToWear(context, all)
        }
    }

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
        pushAllToWear()
        return newCounter
    }

    override suspend fun incrementCounter(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        if (!current.canIncrementDecrement) {
            AppLog.w(TAG, "incrementCounter: counter id=$id is read-only")
            return current
        }
        val updated = current.copy(count = current.count + 1)
        counterDao.updateCounter(updated)
        AppLog.d(TAG, "incrementCounter: id=$id local count updated to ${updated.count}")
        pushAllToWear()
        val writeToken = current.effectiveWriteToken
        if (current.isPublished && writeToken != null) {
            AppLog.d(TAG, "incrementCounter: dispatching remote increment for token=$writeToken")
            val remoteResult = apiService.incrementCounter(writeToken)
            remoteResult.onFailure { e ->
                AppLog.e(TAG, "incrementCounter remote sync failed: ${e.message}", e)
            }
        }
        return updated
    }

    override suspend fun decrementCounter(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        if (!current.canIncrementDecrement) {
            AppLog.w(TAG, "decrementCounter: counter id=$id is read-only")
            return current
        }
        if (current.count <= 0) return current
        val updated = current.copy(count = current.count - 1)
        counterDao.updateCounter(updated)
        AppLog.d(TAG, "decrementCounter: id=$id local count updated to ${updated.count}")
        pushAllToWear()
        val writeToken = current.effectiveWriteToken
        if (current.isPublished && writeToken != null) {
            AppLog.d(TAG, "decrementCounter: dispatching remote decrement for token=$writeToken")
            val remoteResult = apiService.decrementCounter(writeToken)
            remoteResult.onFailure { e ->
                AppLog.e(TAG, "decrementCounter remote sync failed: ${e.message}", e)
            }
        }
        return updated
    }

    override suspend fun updatePinnedStatus(id: String, isPinned: Boolean): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        if (isPinned) {
            counterDao.unpinAll()
        }
        val updated = current.copy(isPinned = isPinned)
        counterDao.updateCounter(updated)
        AppLog.d(TAG, "updatePinnedStatus: id=$id isPinned=$isPinned")
        pushAllToWear()
        return updated
    }

    override suspend fun togglePin(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        return updatePinnedStatus(id, !current.isPinned)
    }

    override suspend fun deleteCounter(id: String, deleteOnServer: Boolean) {
        val current = counterDao.getCounterById(id)
        val adminToken = current?.effectiveAdminToken
        AppLog.d(TAG, "deleteCounter: deleting id=$id, deleteOnServer=$deleteOnServer, isPublished=${current?.isPublished}, adminToken=$adminToken")
        if (deleteOnServer && current != null && current.isPublished && adminToken != null) {
            val remoteResult = apiService.deleteCounter(adminToken)
            remoteResult.onFailure { e ->
                AppLog.w(TAG, "deleteCounter: remote delete failed: ${e.message}", e)
            }
        }
        counterDao.deleteCounterById(id)
        pushAllToWear()
    }

    override suspend fun forgetAdminKey(id: String): Counter? {
        val current = counterDao.getCounterById(id) ?: return null
        if (current.deleteUuid == null) return current
        val updated = current.copy(deleteUuid = null)
        counterDao.updateCounter(updated)
        pushAllToWear()
        AppLog.i(TAG, "forgetAdminKey: cleared deleteUuid for counter id=$id")
        return updated
    }

    override suspend fun updateCounter(counter: Counter) {
        counterDao.updateCounter(counter)
        pushAllToWear()
    }

    override suspend fun publishCounter(id: String): Result<Counter> {
        val current = counterDao.getCounterById(id)
            ?: return Result.failure(IllegalArgumentException("Counter with id $id not found"))

        AppLog.d(TAG, "publishCounter: publishing counter id=$id title='${current.title}' count=${current.count}")

        val apiResult = apiService.publishCounter(
            initialCount = current.count,
            readOnlyUuid = current.viewUuid,
            readWriteUuid = current.updateUuid,
            adminUuid = current.deleteUuid
        )

        val response = apiResult.getOrElse { e ->
            val readToken = current.effectiveReadToken
            if (readToken != null && e.message?.contains("409") == true) {
                AppLog.w(TAG, "publishCounter: room already registered (409). Checking if accessible via token=$readToken")
                val liveCheck = apiService.fetchLiveCount(readToken)
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

        val adminToken = current.effectiveAdminToken
        if (adminToken == null) {
            return Result.failure(IllegalStateException("Admin permission is required to unpublish counter"))
        }
        AppLog.d(TAG, "unpublishCounter: unpublishing id=$id, adminToken=$adminToken")
        val delResult = apiService.deleteCounter(adminToken)
        delResult.onFailure { e ->
            AppLog.w(TAG, "unpublishCounter: remote delete returned error: ${e.message}", e)
            return Result.failure(e)
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
        val readToken = current.effectiveReadToken
        if (!current.isPublished || readToken == null) {
            return Result.failure(IllegalStateException("Counter is not published or has no valid access token"))
        }
        AppLog.d(TAG, "syncRemoteCount: fetching live count for id=$id, token=$readToken")
        return apiService.fetchLiveCount(readToken).mapCatching { remoteState ->
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
        val readToken = current.effectiveReadToken
        if (!current.isPublished || readToken == null) {
            throw IllegalStateException("Counter is not published or has no valid access token")
        }

        AppLog.d(TAG, "observeRemoteCount: listening on token=$readToken for counter id=$id")
        apiService.observeLiveCount(readToken).collect { result ->
            result.onSuccess { remoteState ->
                val updated = counterDao.getCounterById(id) ?: return@onSuccess
                val nextCounter = updated.copy(count = remoteState.value)
                counterDao.updateCounter(nextCounter)
                AppLog.d(TAG, "observeRemoteCount: received remote value ${remoteState.value} for id=$id")
                emit(Result.success(nextCounter))
            }.onFailure { err ->
                if (err is CancellationException) throw err
                AppLog.e(TAG, "observeRemoteCount: stream error for id=$id: ${err.message}", err)
                emit(Result.failure(err))
            }
        }
    }

    override suspend fun importCounterFromLink(link: String): Result<Counter> {
        val parsed = CounterLinkParser.parse(link)
        if (parsed.uuids.isEmpty()) {
            return Result.failure(IllegalArgumentException("No valid UUID tokens found in link"))
        }

        var resolvedReadOnlyUuid: String? = null
        var resolvedReadWriteUuid: String? = null
        var resolvedAdminUuid: String? = null
        var resolvedCounterId: String? = null
        var latestValue = 0

        for (uuid in parsed.uuids) {
            val result = apiService.fetchLiveCount(uuid)
            result.onSuccess { info ->
                resolvedCounterId = info.counter_id
                latestValue = info.value
                when (info.access) {
                    "read_only" -> resolvedReadOnlyUuid = uuid
                    "read_write" -> resolvedReadWriteUuid = uuid
                    "admin" -> resolvedAdminUuid = uuid
                }
            }.onFailure { e ->
                AppLog.w(TAG, "importCounterFromLink: failed to query token $uuid: ${e.message}")
            }
        }

        if (resolvedCounterId == null) {
            return Result.failure(IllegalStateException("Could not resolve any token on server"))
        }

        val roUuid = resolvedReadOnlyUuid
        if (roUuid == null) {
            return Result.failure(IllegalArgumentException("Link does not contain a valid read-only token"))
        }

        val title = parsed.label?.takeIf { it.isNotBlank() }
            ?: "Counter ${resolvedCounterId.take(8)}"

        val existingCounters = counterDao.getAllCounters().firstOrNull() ?: emptyList()
        val existing = existingCounters.find { it.viewUuid == roUuid }

        if (existing != null) {
            val updated = existing.copy(
                title = parsed.label?.takeIf { it.isNotBlank() } ?: existing.title,
                count = latestValue,
                isPublished = true,
                viewUuid = roUuid,
                updateUuid = resolvedReadWriteUuid ?: existing.updateUuid,
                deleteUuid = resolvedAdminUuid ?: existing.deleteUuid
            )
            counterDao.updateCounter(updated)
            pushAllToWear()
            AppLog.i(TAG, "importCounterFromLink: updated existing counter id=${updated.id}")
            return Result.success(updated)
        } else {
            val newCounter = Counter(
                id = UUID.randomUUID().toString(),
                title = title,
                count = latestValue,
                isPublished = true,
                viewUuid = roUuid,
                updateUuid = resolvedReadWriteUuid,
                deleteUuid = resolvedAdminUuid
            )
            counterDao.insertCounter(newCounter)
            pushAllToWear()
            AppLog.i(TAG, "importCounterFromLink: created new counter id=${newCounter.id}, title='$title', count=$latestValue")
            return Result.success(newCounter)
        }
    }

    companion object {
        private const val TAG = "CounterRepo"
    }
}
