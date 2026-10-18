package dev.jskw.counter.data

import android.content.Context
import dev.jskw.counter.data.local.CounterDatabase
import dev.jskw.counter.data.remote.KtorCounterApiService
import dev.jskw.counter.data.repository.CounterRepository
import dev.jskw.counter.data.repository.CounterRepositoryImpl

object CounterRepositoryProvider {
    @Volatile
    private var instance: CounterRepository? = null

    fun getRepository(context: Context): CounterRepository {
        return instance ?: synchronized(this) {
            instance ?: createRepository(context).also { instance = it }
        }
    }

    private fun createRepository(context: Context): CounterRepository {
        val database = CounterDatabase.getInstance(context.applicationContext)
        val apiService = KtorCounterApiService()
        return CounterRepositoryImpl(
            counterDao = database.counterDao(),
            apiService = apiService,
            context = context.applicationContext
        )
    }
}
