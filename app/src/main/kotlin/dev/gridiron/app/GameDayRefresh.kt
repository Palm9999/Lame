package dev.gridiron.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/** The next game-day refresh after [now]: Saturday 10 pm or Sunday 9 am, in [now]'s zone. */
internal fun nextGameDayRefresh(now: ZonedDateTime): ZonedDateTime =
    listOf(DayOfWeek.SATURDAY to LocalTime.of(22, 0), DayOfWeek.SUNDAY to LocalTime.of(9, 0))
        .map { (day, time) ->
            val at = now.with(TemporalAdjusters.nextOrSame(day)).with(time)
            if (at.isAfter(now)) at else at.plusWeeks(1)
        }
        .min()

/**
 * Rebuilds the stats at [nextGameDayRefresh], with a network, then books the next one. Running inside WorkManager,
 * a build Android interrupts is retried rather than lost.
 */
class GameDayRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GridironApplication
        app.refresher.refreshAndWait()
        // Appended, so it starts once this run has finished rather than cancelling it.
        enqueue(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val WORK = "game-day-refresh"

        /** Books the next refresh, keeping one already booked, or cancels it. */
        fun schedule(context: Context, on: Boolean) {
            if (on) enqueue(context, ExistingWorkPolicy.KEEP) else WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val now = ZonedDateTime.now()
            val request = OneTimeWorkRequestBuilder<GameDayRefreshWorker>()
                .setInitialDelay(Duration.between(now, nextGameDayRefresh(now)).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
        }
    }
}
