package dev.jskw.counter.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.jskw.counter.util.ShareRole
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

val Counter.canIncrementDecrement: Boolean
    get() = !isPublished || updateUuid != null || deleteUuid != null

val Counter.canDelete: Boolean
    get() = !isPublished || deleteUuid != null

val Counter.effectiveReadToken: String?
    get() = viewUuid ?: updateUuid ?: deleteUuid

val Counter.effectiveWriteToken: String?
    get() = updateUuid ?: deleteUuid

val Counter.effectiveAdminToken: String?
    get() = deleteUuid

val Counter.availableShareRoles: List<ShareRole>
    get() {
        val roles = mutableListOf<ShareRole>()
        if (deleteUuid != null) {
            roles.add(ShareRole.ADMIN)
        }
        if (updateUuid != null || deleteUuid != null) {
            roles.add(ShareRole.READ_WRITE)
        }
        if (viewUuid != null || updateUuid != null || deleteUuid != null) {
            roles.add(ShareRole.READ_ONLY)
        }
        return roles.ifEmpty { listOf(ShareRole.READ_ONLY) }
    }
