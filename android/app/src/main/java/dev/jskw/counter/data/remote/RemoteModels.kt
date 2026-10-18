package dev.jskw.counter.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val initial_value: Int = 0,
    val read_only_uuid: String? = null,
    val read_write_uuid: String? = null,
    val admin_uuid: String? = null
)

@Serializable
data class RegisterResponse(
    val counter_id: String,
    val value: Int,
    val roles: Map<String, String>,
    val message: String
)

@Serializable
data class ActionRequest(
    val delta: Int = 1
)

@Serializable
data class CounterInfoResponse(
    val counter_id: String,
    val value: Int,
    val access: String
)

@Serializable
data class LiveUpdate(
    val value: Int
)

@Serializable
data class WebSocketUpdate(
    val type: String? = null,
    val value: Int? = null,
    val action: String? = null,
    val counter_id: String? = null
)
