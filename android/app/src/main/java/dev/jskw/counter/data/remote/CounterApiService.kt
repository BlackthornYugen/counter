package dev.jskw.counter.data.remote

import dev.jskw.counter.data.model.Counter

interface CounterApiService {
    suspend fun publishCounter(
        counter: Counter,
        viewUuid: String,
        managementUuid: String
    ): Result<PublishCounterResponse>

    suspend fun updateCounter(
        updateUuid: String,
        count: Int,
        title: String? = null
    ): Result<Boolean>

    suspend fun deleteCounter(deleteUuid: String): Result<Boolean>

    suspend fun fetchLiveCount(viewUuid: String): Result<RemoteCounterState>
}
