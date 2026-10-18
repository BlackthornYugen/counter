package dev.jskw.counter.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.jskw.counter.MainActivity
import dev.jskw.counter.data.model.Counter
import dev.jskw.counter.receiver.PinnedCounterReceiver

object PinnedCounterNotifier {
    const val CHANNEL_ID = "pinned_counters_channel"
    const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

    fun updateNotification(context: Context, pinnedCounters: List<Counter>) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(context)

        if (pinnedCounters.isEmpty()) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }

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

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decrement (-) action
        val decIntent = Intent(context, PinnedCounterReceiver::class.java).apply {
            action = PinnedCounterReceiver.ACTION_DECREMENT
            putExtra(PinnedCounterReceiver.EXTRA_COUNTER_ID, primaryCounter.id)
        }
        val decPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            decIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Increment (+) action
        val incIntent = Intent(context, PinnedCounterReceiver::class.java).apply {
            action = PinnedCounterReceiver.ACTION_INCREMENT
            putExtra(PinnedCounterReceiver.EXTRA_COUNTER_ID, primaryCounter.id)
        }
        val incPendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            incIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // DeleteIntent (Dismissal action)
        val deleteIntent = Intent(context, PinnedCounterReceiver::class.java).apply {
            action = PinnedCounterReceiver.ACTION_UNPIN_ALL
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            context,
            3,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
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

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
