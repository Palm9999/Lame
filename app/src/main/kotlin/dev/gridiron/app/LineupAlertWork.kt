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
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.gridiron.core.data.ScoresWeek
import dev.gridiron.core.data.live.LineupAlert
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * About [LEAD] before a kickoff window: re-syncs the league and notifies about any starter in the user's ESPN lineup
 * who is Out, Doubtful, on IR or suspended in that window (or on bye, in the week's first window), with the bench
 * player to start instead.
 */
class LineupAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GridironApplication
        if (!app.settings.alerts.first().lineup) return Result.success()
        val window = Instant.ofEpochMilli(inputData.getLong(KICKOFF, 0L))
        val alerts = app.lineupAlerts(window)
        notify(applicationContext, alerts)
        // The widget shows the latest check's first problem, or that it found none.
        app.widget.alert(alerts.firstOrNull()?.let { "${it.title.removePrefix("Lineup: ")}. ${it.text}" })
        return Result.success()
    }

    companion object {
        private const val KICKOFF = "kickoff"
        private const val CHANNEL = "lineup"

        /** How long before kickoff the check runs: after most inactives are known, with time to swap. */
        val LEAD: Duration = Duration.ofMinutes(90)

        /**
         * One check per kickoff window still ahead in [week]. A window already scheduled keeps its check, and one
         * already checked isn't checked again (the 90 minutes between its check and kickoff); WorkManager keeps
         * finished work at least a day, which outlasts that.
         */
        suspend fun schedule(context: Context, week: ScoresWeek, now: Instant = Instant.now()) {
            val work = WorkManager.getInstance(context)
            for (kickoff in week.games.mapNotNull { it.kickoff }.distinct()) {
                val known = work.getWorkInfosForUniqueWorkFlow(workName(kickoff)).first().isNotEmpty()
                if (!needsCheck(kickoff, now, known)) continue
                val delay = Duration.between(now, kickoff.minus(LEAD)).toMillis().coerceAtLeast(0L)
                val request = OneTimeWorkRequestBuilder<LineupAlertWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KICKOFF to kickoff.toEpochMilli()))
                    .build()
                work.enqueueUniqueWork(workName(kickoff), ExistingWorkPolicy.KEEP, request)
            }
        }

        private fun workName(kickoff: Instant) = "lineup-${kickoff.toEpochMilli()}"

        /** Whether a window kicking off at [kickoff] needs a check: still ahead, and none scheduled, run or finished ([known]). */
        internal fun needsCheck(kickoff: Instant, now: Instant, known: Boolean): Boolean = kickoff.isAfter(now) && !known

        private fun notify(context: Context, alerts: List<LineupAlert>) {
            if (alerts.isEmpty()) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Lineup alerts", NotificationManager.IMPORTANCE_HIGH))
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
                compat.notify(("lineup:" + alert.starter.playerId).hashCode(), notification)
            }
        }
    }
}
