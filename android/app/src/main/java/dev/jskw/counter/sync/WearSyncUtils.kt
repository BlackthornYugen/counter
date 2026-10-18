package dev.jskw.counter.sync

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.jskw.counter.data.model.Counter
import dev.jskw.counter.util.AppLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object WearSyncUtils {
    private const val TAG = "WearSyncUtils"
    const val COUNTERS_PATH = "/counters"
    const val COUNTER_ACTION_PATH = "/counter_action"
    const val KEY_COUNTERS_JSON = "counters_json"
    const val KEY_ACTION = "action"
    const val KEY_COUNTER_ID = "counter_id"
    const val KEY_TITLE = "title"
    const val KEY_TIMESTAMP = "timestamp"

    const val ACTION_INCREMENT = "INCREMENT"
    const val ACTION_DECREMENT = "DECREMENT"
    const val ACTION_CREATE = "CREATE"

    private val json = Json { ignoreUnknownKeys = true }

    fun pushCountersToWear(context: Context, counters: List<Counter>) {
        try {
            val wearCounters = counters.map {
                WearCounter(
                    id = it.id,
                    title = it.title,
                    count = it.count,
                    isPinned = it.isPinned,
                    isPublished = it.isPublished
                )
            }
            val jsonString = json.encodeToString(wearCounters)
            val putDataMapReq = PutDataMapRequest.create(COUNTERS_PATH).apply {
                dataMap.putString(KEY_COUNTERS_JSON, jsonString)
                dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            }
            val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context.applicationContext).putDataItem(putDataReq)
            AppLog.d(TAG, "Pushed ${counters.size} counters to wear")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to push counters to wear: ${e.message}", e)
        }
    }
}
