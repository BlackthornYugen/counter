package dev.jskw.counter.data.remote

import dev.jskw.counter.data.model.Counter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class KtorCounterApiService(
    private val baseUrl: String = "https://counter.jskw.dev",
    private val httpClient: HttpClient = createDefaultHttpClient()
) : CounterApiService {

    override suspend fun publishCounter(
        counter: Counter,
        viewUuid: String,
        managementUuid: String
    ): Result<PublishCounterResponse> = runCatching {
        val request = PublishCounterRequest(
            title = counter.title,
            count = counter.count,
            viewUuid = viewUuid,
            updateUuid = managementUuid,
            deleteUuid = managementUuid
        )
        val response = httpClient.post("$baseUrl/api/counters") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.isSuccess()) {
            runCatching { response.body<PublishCounterResponse>() }.getOrElse {
                PublishCounterResponse(
                    success = true,
                    viewUuid = viewUuid,
                    updateUuid = managementUuid,
                    deleteUuid = managementUuid,
                    count = counter.count
                )
            }
        } else {
            PublishCounterResponse(
                success = true,
                viewUuid = viewUuid,
                updateUuid = managementUuid,
                deleteUuid = managementUuid,
                count = counter.count,
                message = "HTTP ${response.status.value}"
            )
        }
    }

    override suspend fun updateCounter(
        updateUuid: String,
        count: Int,
        title: String?
    ): Result<Boolean> = runCatching {
        val request = UpdateCounterRequest(count = count, title = title, updateUuid = updateUuid)
        val response = httpClient.put("$baseUrl/api/counters/$updateUuid") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        response.status.isSuccess()
    }

    override suspend fun deleteCounter(deleteUuid: String): Result<Boolean> = runCatching {
        val response = httpClient.delete("$baseUrl/api/counters/$deleteUuid")
        response.status.isSuccess()
    }

    override suspend fun fetchLiveCount(viewUuid: String): Result<RemoteCounterState> = runCatching {
        val response = httpClient.get("$baseUrl/api/counters/view/$viewUuid")
        if (response.status.isSuccess()) {
            response.body<RemoteCounterState>()
        } else {
            throw Exception("Failed to fetch count: ${response.status.value}")
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
            }
        }
    }
}
