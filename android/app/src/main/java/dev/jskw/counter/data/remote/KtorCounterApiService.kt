package dev.jskw.counter.data.remote

import dev.jskw.counter.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class KtorCounterApiService(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    private val httpClient: HttpClient = createDefaultHttpClient()
) : CounterApiService {

    override suspend fun publishCounter(
        initialCount: Int,
        readOnlyUuid: String?,
        readWriteUuid: String?,
        adminUuid: String?
    ): Result<RegisterResponse> = runCatching {
        val request = RegisterRequest(
            initial_value = initialCount,
            read_only_uuid = readOnlyUuid,
            read_write_uuid = readWriteUuid,
            admin_uuid = adminUuid
        )
        val response = httpClient.post("$baseUrl/api/counters/register") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.isSuccess()) {
            response.body<RegisterResponse>()
        } else {
            throw Exception("HTTP ${response.status.value}")
        }
    }

    override suspend fun incrementCounter(token: String): Result<Boolean> = runCatching {
        val request = ActionRequest(delta = 1)
        val response = httpClient.post("$baseUrl/api/counters/$token/increment") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        response.status.isSuccess()
    }

    override suspend fun decrementCounter(token: String): Result<Boolean> = runCatching {
        val request = ActionRequest(delta = 1)
        val response = httpClient.post("$baseUrl/api/counters/$token/decrement") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        response.status.isSuccess()
    }

    override suspend fun deleteCounter(token: String): Result<Boolean> = runCatching {
        val response = httpClient.delete("$baseUrl/api/counters/$token")
        response.status.isSuccess()
    }

    override suspend fun fetchLiveCount(token: String): Result<CounterInfoResponse> = runCatching {
        val response = httpClient.get("$baseUrl/api/counters/$token")
        if (response.status.isSuccess()) {
            response.body<CounterInfoResponse>()
        } else {
            throw Exception("Failed to fetch count: ${response.status.value}")
        }
    }

    override fun observeLiveCount(token: String): Flow<Result<SseUpdate>> = flow {
        try {
            val jsonParser = Json { ignoreUnknownKeys = true }
            httpClient.sse("$baseUrl/sse/$token") {
                incoming.collect { event ->
                    event.data?.let { jsonString ->
                        try {
                            val state = jsonParser.decodeFromString<SseUpdate>(jsonString)
                            emit(Result.success(state))
                        } catch (e: Exception) {
                            emit(Result.failure(e))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }

    companion object {
        fun createDefaultHttpClient(): HttpClient {
            return HttpClient(OkHttp) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                        isLenient = true
                    })
                }
                install(SSE)
            }
        }
    }
}
