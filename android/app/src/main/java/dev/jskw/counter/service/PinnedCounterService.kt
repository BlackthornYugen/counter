package dev.jskw.counter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.jskw.counter.MainActivity
import dev.jskw.counter.data.CounterRepositoryProvider
import dev.jskw.counter.data.model.Counter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class PinnedCounterService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private var isForegroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (!isForegroundStarted) {
            startForegroundWithInitialNotification()
        }
        observePinnedCounters()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isForegroundStarted) {
            startForegroundWithInitialNotification()
        }

        val action = intent?.action
        val counterId = intent?.getStringExtra(EXTRA_COUNTER_ID)
        val repository = CounterRepositoryProvider.getRepository(applicationContext)

        when (action) {
            ACTION_INCREMENT -> {
                if (counterId != null) {
                    serviceScope.launch { repository.incrementCounter(counterId) }
                }
            }
            ACTION_DECREMENT -> {
                if (counterId != null) {
                    serviceScope.launch { repository.decrementCounter(counterId) }
                }
            }
            ACTION_UNPIN -> {
                if (counterId != null) {
                    serviceScope.launch { repository.updatePinnedStatus(counterId, false) }
                }
            }
            ACTION_UNPIN_ALL -> {
                serviceScope.launch {
                    val allCounters = repository.getAllCounters().firstOrNull() ?: emptyList()
                    allCounters.forEach { counter ->
                        if (counter.isPinned) {
                            repository.updatePinnedStatus(counter.id, false)
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithInitialNotification() {
        val initialNotification = buildInitialNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
        isForegroundStarted = true
    }

    private fun buildInitialNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pinned Counters")
            .setContentText("Loading...")
            .setSmallIcon(iconRes)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun observePinnedCounters() {
        val repository = CounterRepositoryProvider.getRepository(applicationContext)
        serviceScope.launch {
            repository.getPinnedCounters().collect { pinnedCounters ->
                if (pinnedCounters.isEmpty()) {
                    stopForegroundService()
                } else {
                    val updatedNotification = buildUpdatedNotification(pinnedCounters)
                    notificationManager.notify(NOTIFICATION_ID, updatedNotification)
                }
            }
        }
    }

    private fun buildUpdatedNotification(pinnedCounters: List<Counter>): Notification {
        val primaryCounter = pinnedCounters.first()

        val title = if (pinnedCounters.size == 1) {
            primaryCounter.title
        } else {
            "${pinnedCounters.size} Pinned Counters"
        }

        val contentText = if (pinnedCounters.size == 1) {
            "Count: ${primaryCounter.count}"
        } else {
            pinnedCounters.joinToString(separator = " | ") { "${it.title}: ${it.count}" }
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decrement (-) action
        val decIntent = Intent(this, PinnedCounterService::class.java).apply {
            action = ACTION_DECREMENT
            putExtra(EXTRA_COUNTER_ID, primaryCounter.id)
        }
        val decPendingIntent = PendingIntent.getService(
            this,
            1,
            decIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Increment (+) action
        val incIntent = Intent(this, PinnedCounterService::class.java).apply {
            action = ACTION_INCREMENT
            putExtra(EXTRA_COUNTER_ID, primaryCounter.id)
        }
        val incPendingIntent = PendingIntent.getService(
            this,
            2,
            incIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // DeleteIntent (Dismissal action)
        val deleteIntent = Intent(this, PinnedCounterService::class.java).apply {
            action = ACTION_UNPIN_ALL
        }
        val deletePendingIntent = PendingIntent.getService(
            this,
            3,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(iconRes)
            .setContentIntent(openAppPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_previous, "-", decPendingIntent)
            .addAction(android.R.drawable.ic_input_add, "+", incPendingIntent)
            .build()
    }

    private fun stopForegroundService() {
        isForegroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pinned Counters",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent controls for pinned counters"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "pinned_counters_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_INCREMENT = "dev.jskw.counter.ACTION_INCREMENT"
        const val ACTION_DECREMENT = "dev.jskw.counter.ACTION_DECREMENT"
        const val ACTION_UNPIN = "dev.jskw.counter.ACTION_UNPIN"
        const val ACTION_UNPIN_ALL = "dev.jskw.counter.ACTION_UNPIN_ALL"
        const val EXTRA_COUNTER_ID = "extra_counter_id"
    }
}
