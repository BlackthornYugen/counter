package dev.jskw.counter.data.remote

import dev.jskw.counter.BuildConfig
import dev.jskw.counter.util.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import okhttp3.logging.HttpLoggingInterceptor

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
        AppLog.d(TAG, "publishCounter: initialCount=$initialCount, ro=$readOnlyUuid, rw=$readWriteUuid, admin=$adminUuid (endpoint: $baseUrl/api/counters/register)")
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
            val body = response.body<RegisterResponse>()
            AppLog.i(TAG, "publishCounter success: id=${body.counter_id}, value=${body.value}, roles=${body.roles}")
            body
        } else {
            val errorText = runCatching { response.bodyAsText() }.getOrDefault("")
            val msg = "HTTP ${response.status.value}: $errorText"
            AppLog.e(TAG, "publishCounter failed: $msg")
            throw Exception(msg)
        }
    }.onFailure { e ->
        AppLog.e(TAG, "publishCounter unexpected error: ${e.message}", e)
    }

    override suspend fun incrementCounter(token: String): Result<Boolean> = runCatching {
        AppLog.d(TAG, "incrementCounter: token=$token")
        val request = ActionRequest(delta = 1)
        val response = httpClient.post("$baseUrl/api/counters/$token/increment") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.isSuccess()) {
            AppLog.d(TAG, "incrementCounter success: token=$token")
            true
        } else {
            val errorText = runCatching { response.bodyAsText() }.getOrDefault("")
            val msg = "incrementCounter failed: HTTP ${response.status.value}: $errorText"
            AppLog.e(TAG, msg)
            throw Exception(msg)
        }
    }.onFailure { e ->
        AppLog.e(TAG, "incrementCounter unexpected error: ${e.message}", e)
    }

    override suspend fun decrementCounter(token: String): Result<Boolean> = runCatching {
        AppLog.d(TAG, "decrementCounter: token=$token")
        val request = ActionRequest(delta = 1)
        val response = httpClient.post("$baseUrl/api/counters/$token/decrement") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.isSuccess()) {
            AppLog.d(TAG, "decrementCounter success: token=$token")
            true
        } else {
            val errorText = runCatching { response.bodyAsText() }.getOrDefault("")
            val msg = "decrementCounter failed: HTTP ${response.status.value}: $errorText"
            AppLog.e(TAG, msg)
            throw Exception(msg)
        }
    }.onFailure { e ->
        AppLog.e(TAG, "decrementCounter unexpected error: ${e.message}", e)
    }

    override suspend fun deleteCounter(token: String): Result<Boolean> = runCatching {
        AppLog.d(TAG, "deleteCounter: token=$token")
        val response = httpClient.delete("$baseUrl/api/counters/$token")
        if (response.status.isSuccess()) {
            AppLog.d(TAG, "deleteCounter success: token=$token")
            true
        } else {
            val errorText = runCatching { response.bodyAsText() }.getOrDefault("")
            val msg = "deleteCounter failed: HTTP ${response.status.value}: $errorText"
            AppLog.e(TAG, msg)
            throw Exception(msg)
        }
    }.onFailure { e ->
        AppLog.e(TAG, "deleteCounter unexpected error: ${e.message}", e)
    }

    override suspend fun fetchLiveCount(token: String): Result<CounterInfoResponse> = runCatching {
        AppLog.d(TAG, "fetchLiveCount: token=$token")
        val response = httpClient.get("$baseUrl/api/counters/$token")
        if (response.status.isSuccess()) {
            val body = response.body<CounterInfoResponse>()
            AppLog.d(TAG, "fetchLiveCount success: token=$token, value=${body.value}, access=${body.access}")
            body
        } else {
            val errorText = runCatching { response.bodyAsText() }.getOrDefault("")
            val msg = "fetchLiveCount failed: HTTP ${response.status.value}: $errorText"
            AppLog.e(TAG, msg)
            throw Exception(msg)
        }
    }.onFailure { e ->
        AppLog.e(TAG, "fetchLiveCount unexpected error: ${e.message}", e)
    }

    override fun observeLiveCount(token: String): Flow<Result<LiveUpdate>> = flow {
        val wsUrl = baseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://") + "/ws/$token"

        AppLog.i(TAG_WS, "observeLiveCount connecting via WebSocket for token=$token at $wsUrl")
        try {
            val jsonParser = Json { ignoreUnknownKeys = true }
            httpClient.webSocket(wsUrl) {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        AppLog.d(TAG_WS, "Received WS message for token=$token: $text")
                        try {
                            val parsed = jsonParser.decodeFromString<WebSocketUpdate>(text)
                            if (parsed.value != null) {
                                emit(Result.success(LiveUpdate(value = parsed.value)))
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            AppLog.e(TAG_WS, "Failed to decode WS JSON for token=$token: $text", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLog.e(TAG_WS, "observeLiveCount WebSocket error for token=$token: ${e.message}", e)
            emit(Result.failure(e))
        }
    }

    companion object {
        private const val TAG = "CounterApi"
        private const val TAG_WS = "CounterWS"
        private const val TAG_HTTP = "CounterHttp"

        fun createDefaultHttpClient(): HttpClient {
            return HttpClient(OkHttp) {
                engine {
                    addInterceptor(HttpLoggingInterceptor { message ->
                        AppLog.d(TAG_HTTP, message)
                    }.apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    })
                }
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                        isLenient = true
                    })
                }
                install(WebSockets)
            }
        }
    }
}
