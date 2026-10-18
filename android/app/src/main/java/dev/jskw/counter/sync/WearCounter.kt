package dev.jskw.counter.sync

import kotlinx.serialization.Serializable

@Serializable
data class WearCounter(
    val id: String,
    val title: String,
    val count: Int,
    val isPinned: Boolean = false,
    val isPublished: Boolean = false
)
