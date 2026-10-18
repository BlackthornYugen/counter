package dev.jskw.counter.data.repository

import dev.jskw.counter.data.model.Counter
import kotlinx.coroutines.flow.Flow

interface CounterRepository {
    fun getAllCounters(): Flow<List<Counter>>
    fun getPinnedCounters(): Flow<List<Counter>>
    suspend fun getCounterById(id: String): Counter?
    suspend fun createCounter(title: String, initialCount: Int = 0): Counter
    suspend fun incrementCounter(id: String): Counter?
    suspend fun decrementCounter(id: String): Counter?
    suspend fun deleteCounter(id: String)
    suspend fun updateCounter(counter: Counter)
    suspend fun updatePinnedStatus(id: String, isPinned: Boolean): Counter?
    suspend fun togglePin(id: String): Counter?
    suspend fun publishCounter(id: String): Counter?
    suspend fun unpublishCounter(id: String): Counter?
    suspend fun syncRemoteCount(id: String): Result<Counter>
    fun observeRemoteCount(id: String): Flow<Result<Counter>>
}
