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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.gridiron.core.datastore.AlertSwitches
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * The next game-day refresh after [now], in [now]'s zone: the first of [times] (by default Thursday 3 pm, Saturday
 * 10 pm, Sunday 9 am, Monday 3 pm) that falls in the season. From March through August there are no games, so the
 * next one is the first after September 1.
 */
internal fun nextGameDayRefresh(now: ZonedDateTime, times: Map<DayOfWeek, LocalTime> = AlertSwitches.DEFAULT_REFRESH_TIMES): ZonedDateTime {
    // ponytail: calendar months, not the schedule; the Super Bowl ends by mid-February and week 1 starts after Labor Day.
    val from = if (now.monthValue in 3..8) now.withMonth(9).withDayOfMonth(1).with(LocalTime.MIDNIGHT) else now
    return times.map { (day, time) ->
        val at = from.with(TemporalAdjusters.nextOrSame(day)).with(time)
        if (at.isAfter(from)) at else at.plusWeeks(1)
    }.min()
}

/**
 * Rebuilds the stats at [nextGameDayRefresh], with a network, then books the next one. Running inside WorkManager,
 * a build Android interrupts is retried rather than lost.
 */
class GameDayRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GridironApplication
        notifyRefreshed(applicationContext, app.refresher.refreshAndWait())
        // Appended, so it starts once this run has finished rather than cancelling it.
        enqueue(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val WORK = "game-day-refresh"
        private const val CHANNEL = "refresh"

        /** Says the refresh ran and what it said, so a refresh done while the app was closed doesn't go unseen. */
        private fun notifyRefreshed(context: Context, finished: RefreshState.Finished?) {
            if (finished == null) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Refresh finished", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_injury)
                .setContentTitle(if (finished.ok) "Stats refreshed" else "Refresh failed")
                .setContentText(finished.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(finished.message))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(CHANNEL.hashCode(), notification)
        }

        /** Books the next refresh, keeping one already booked unless [rebook] (the times changed), or cancels it. */
        suspend fun schedule(context: Context, on: Boolean, rebook: Boolean = false) {
            when {
                !on -> WorkManager.getInstance(context).cancelUniqueWork(WORK)
                rebook -> enqueue(context, ExistingWorkPolicy.REPLACE)
                else -> enqueue(context, ExistingWorkPolicy.KEEP)
            }
        }

        private suspend fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val times = (context.applicationContext as GridironApplication).settings.alerts.first().refreshTimes
            val now = ZonedDateTime.now()
            val request = OneTimeWorkRequestBuilder<GameDayRefreshWorker>()
                .setInitialDelay(Duration.between(now, nextGameDayRefresh(now, times)).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
        }
    }
}

/**
 * Booked as every refresh starts. It joins the refresh running in the app, so the build runs under WorkManager; if
 * Android kills the process mid-build, WorkManager runs this again and [RefreshCoordinator.pending] restarts the build.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val refresher = (applicationContext as GridironApplication).refresher
        if (refresher.pending) refresher.refreshAndWait()
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("refresh", ExistingWorkPolicy.KEEP, request)
        }
    }
}
