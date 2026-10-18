package dev.jskw.counter.wear

import android.content.Context
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

object WearCounterSync {
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

    private val _countersState = MutableStateFlow<List<WearCounter>>(emptyList())
    val countersState: StateFlow<List<WearCounter>> = _countersState.asStateFlow()

    fun updateCountersFromJson(jsonString: String) {
        try {
            val list = json.decodeFromString<List<WearCounter>>(jsonString)
            _countersState.value = list
        } catch (_: Exception) {
        }
    }

    fun sendActionFromWatch(context: Context, action: String, counterId: String? = null, title: String? = null) {
        try {
            val putDataMapReq = PutDataMapRequest.create(COUNTER_ACTION_PATH).apply {
                dataMap.putString(KEY_ACTION, action)
                if (counterId != null) dataMap.putString(KEY_COUNTER_ID, counterId)
                if (title != null) dataMap.putString(KEY_TITLE, title)
                dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            }
            val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context.applicationContext).putDataItem(putDataReq)
        } catch (_: Exception) {
        }
    }

    fun registerDataClientListener(context: Context, dataClientListener: DataClient.OnDataChangedListener) {
        val dataClient = Wearable.getDataClient(context.applicationContext)
        dataClient.addListener(dataClientListener)

        dataClient.dataItems.addOnSuccessListener { dataItems ->
            for (item in dataItems) {
                if (item.uri.path == COUNTERS_PATH) {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val jsonStr = dataMap.getString(KEY_COUNTERS_JSON)
                    if (!jsonStr.isNullOrEmpty()) {
                        updateCountersFromJson(jsonStr)
                    }
                }
            }
            dataItems.release()
        }
    }

    fun unregisterDataClientListener(context: Context, dataClientListener: DataClient.OnDataChangedListener) {
        try {
            Wearable.getDataClient(context.applicationContext).removeListener(dataClientListener)
        } catch (_: Exception) {
        }
    }
}

class WearableWatchListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (i in 0 until dataEvents.count) {
            val event = dataEvents.get(i)
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WearCounterSync.COUNTERS_PATH) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val jsonStr = dataMap.getString(WearCounterSync.KEY_COUNTERS_JSON)
                if (!jsonStr.isNullOrEmpty()) {
                    WearCounterSync.updateCountersFromJson(jsonStr)
                }
            }
        }
    }
}
