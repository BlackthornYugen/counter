package dev.jskw.counter.sync

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import dev.jskw.counter.data.CounterRepositoryProvider
import dev.jskw.counter.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class PhoneWearableListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        AppLog.d(TAG, "onDataChanged: received events")
        for (i in 0 until dataEvents.count) {
            val event = dataEvents.get(i)
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WearSyncUtils.COUNTER_ACTION_PATH) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val action = dataMap.getString(WearSyncUtils.KEY_ACTION)
                val counterId = dataMap.getString(WearSyncUtils.KEY_COUNTER_ID)
                val title = dataMap.getString(WearSyncUtils.KEY_TITLE, "default watch counter")

                val appContext = applicationContext
                scope.launch {
                    val repository = CounterRepositoryProvider.getRepository(appContext)
                    when (action) {
                        WearSyncUtils.ACTION_INCREMENT -> {
                            if (!counterId.isNullOrEmpty()) {
                                AppLog.i(TAG, "Action INCREMENT for counterId=$counterId")
                                repository.incrementCounter(counterId)
                            }
                        }
                        WearSyncUtils.ACTION_DECREMENT -> {
                            if (!counterId.isNullOrEmpty()) {
                                AppLog.i(TAG, "Action DECREMENT for counterId=$counterId")
                                repository.decrementCounter(counterId)
                            }
                        }
                        WearSyncUtils.ACTION_CREATE -> {
                            AppLog.i(TAG, "Action CREATE counter title='$title'")
                            repository.createCounter(title, 0)
                        }
                    }

                    val allCounters = repository.getAllCounters().firstOrNull() ?: emptyList()
                    WearSyncUtils.pushCountersToWear(appContext, allCounters)
                }
            }
        }
    }

    companion object {
        private const val TAG = "PhoneWearableService"
    }
}
