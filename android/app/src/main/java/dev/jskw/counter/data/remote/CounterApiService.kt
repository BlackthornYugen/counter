package dev.jskw.counter.data.remote

import kotlinx.coroutines.flow.Flow

interface CounterApiService {
    suspend fun publishCounter(
        initialCount: Int,
        readOnlyUuid: String? = null,
        readWriteUuid: String? = null,
        adminUuid: String? = null
    ): Result<RegisterResponse>

    suspend fun incrementCounter(token: String): Result<Boolean>

    suspend fun decrementCounter(token: String): Result<Boolean>

    suspend fun deleteCounter(token: String): Result<Boolean>

    suspend fun fetchLiveCount(token: String): Result<CounterInfoResponse>

    fun observeLiveCount(token: String): Flow<Result<LiveUpdate>>
}
