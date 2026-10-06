package dev.gridiron.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import dev.gridiron.core.data.live.NewsAlert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Every two hours, with a network: ESPN's injury list against the last one seen, a notification per rostered change;
 * then a [LineupAlertWorker] for each of the week's kickoff windows still ahead.
 */
class InjuryAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GridironApplication
        val alerts = app.settings.alerts.first()
        if (!alerts.any) return Result.success()
        // The injury check refreshes ESPN's feeds, which the news check reads, so it runs whenever either is on.
        if (alerts.injury || alerts.news) {
            val injuries = app.injuryAlerts.check()
            if (alerts.injury) notify(applicationContext, injuries)
        }
        if (alerts.news) notifyNews(applicationContext, app.newsAlerts.check())
        // Tuesday from 9: last week, the report card's place and this week's win chance, once.
        try {
            if (alerts.summary) app.weeklySummaryIfDue()?.let { notifySummary(applicationContext, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not sent, so the next run tries again.
        }
        // The week's kickoff windows each get a lineup check shortly before; a missing scoreboard just skips them.
        try {
            if (alerts.lineup) app.upcomingWeek()?.let { LineupAlertWorker.schedule(applicationContext, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Next run tries again.
        }
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

        private const val NEWS_CHANNEL = "news"
        private const val SUMMARY_CHANNEL = "summary"

        private fun notifySummary(context: Context, text: String) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(SUMMARY_CHANNEL, "Weekly summary", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, SUMMARY_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_injury)
                .setContentTitle("Your week")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify("summary".hashCode(), notification)
        }

        /** One notification per story; a tap opens it in the browser. */
        private fun notifyNews(context: Context, alerts: List<NewsAlert>) {
            if (alerts.isEmpty()) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(NEWS_CHANNEL, "Roster news", NotificationManager.IMPORTANCE_LOW))
            val compat = NotificationManagerCompat.from(context)
            for (alert in alerts) {
                val open = PendingIntent.getActivity(
                    context, alert.item.id.hashCode(),
                    Intent(Intent.ACTION_VIEW, Uri.parse(alert.item.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(context, NEWS_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_injury)
                    .setContentTitle(alert.title)
                    .setContentText(alert.text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                compat.notify(("news:" + alert.item.id).hashCode(), notification)
            }
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
