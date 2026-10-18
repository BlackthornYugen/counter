package dev.jskw.counter.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.jskw.counter.data.CounterRepositoryProvider
import dev.jskw.counter.notification.PinnedCounterNotifier
import dev.jskw.counter.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class PinnedCounterReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val counterId = intent.getStringExtra(EXTRA_COUNTER_ID)
        val appContext = context.applicationContext
        val repository = CounterRepositoryProvider.getRepository(appContext)
        AppLog.d("CounterReceiver", "onReceive: action=$action, counterId=$counterId")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_INCREMENT -> {
                        if (counterId != null) {
                            repository.incrementCounter(counterId)
                        }
                    }
                    ACTION_DECREMENT -> {
                        if (counterId != null) {
                            repository.decrementCounter(counterId)
                        }
                    }
                    ACTION_UNPIN -> {
                        if (counterId != null) {
                            repository.updatePinnedStatus(counterId, false)
                        }
                    }
                    ACTION_UNPIN_ALL -> {
                        val allCounters = repository.getAllCounters().firstOrNull() ?: emptyList()
                        allCounters.forEach { counter ->
                            if (counter.isPinned) {
                                repository.updatePinnedStatus(counter.id, false)
                            }
                        }
                    }
                }
                val pinnedCounters = repository.getPinnedCounters().firstOrNull() ?: emptyList()
                PinnedCounterNotifier.updateNotification(appContext, pinnedCounters)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_INCREMENT = "dev.jskw.counter.ACTION_INCREMENT"
        const val ACTION_DECREMENT = "dev.jskw.counter.ACTION_DECREMENT"
        const val ACTION_UNPIN = "dev.jskw.counter.ACTION_UNPIN"
        const val ACTION_UNPIN_ALL = "dev.jskw.counter.ACTION_UNPIN_ALL"
        const val EXTRA_COUNTER_ID = "extra_counter_id"
    }
}
