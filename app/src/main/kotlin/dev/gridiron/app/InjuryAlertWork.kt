package dev.gridiron.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.gridiron.core.data.live.InjuryAlert
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Every two hours, with a network: ESPN's injury list against the last one seen, a notification per rostered change. */
class InjuryAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GridironApplication
        if (!app.settings.injuryAlerts.first()) return Result.success()
        notify(applicationContext, app.injuryAlerts.check())
        // A failed fetch alerts nothing and waits for the next run; retrying sooner adds nothing.
        return Result.success()
    }

    companion object {
        private const val WORK = "injury-alerts"
        private const val CHANNEL = "injuries"

        /** Starts the two-hourly check, keeping one already scheduled, or stops it. */
        fun schedule(context: Context, on: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!on) {
                work.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<InjuryAlertWorker>(2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun notify(context: Context, alerts: List<InjuryAlert>) {
            if (alerts.isEmpty()) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Injury alerts", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val compat = NotificationManagerCompat.from(context)
            for (alert in alerts) {
                val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_injury)
                    .setContentTitle(alert.title)
                    .setContentText(alert.text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                // One per player: a newer change replaces his older alert.
                compat.notify(alert.playerId.hashCode(), notification)
            }
        }
    }
}
