package dev.jskw.counter.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

@Entity(tableName = "counters")
@Serializable
data class Counter(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val count: Int = 0,
    val isPinned: Boolean = false,
    val isPublished: Boolean = false,
    val viewUuid: String? = null,
    val updateUuid: String? = null,
    val deleteUuid: String? = null
)
