package dev.jskw.counter.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class PublishCounterRequest(
    val title: String,
    val count: Int,
    val viewUuid: String,
    val updateUuid: String,
    val deleteUuid: String
)

@Serializable
data class UpdateCounterRequest(
    val count: Int,
    val title: String? = null,
    val updateUuid: String? = null
)

@Serializable
data class PublishCounterResponse(
    val success: Boolean = true,
    val viewUuid: String? = null,
    val updateUuid: String? = null,
    val deleteUuid: String? = null,
    val count: Int? = null,
    val message: String? = null
)

@Serializable
data class RemoteCounterState(
    val viewUuid: String? = null,
    val updateUuid: String? = null,
    val title: String? = null,
    val count: Int? = 0
)
