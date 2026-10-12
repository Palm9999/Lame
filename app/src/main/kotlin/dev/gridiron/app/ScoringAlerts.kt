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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.gridiron.core.data.ScoresWeek
import dev.gridiron.core.data.live.MatchupSide
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** A starter's points jumped by [gained] since the last check, to [total]: a touchdown or a big play. */
data class ScoringPlay(val espnId: String, val name: String, val gained: Double, val total: Double)

/**
 * Each starter's ESPN points at the last check, kept in [seen] (the week's key on the first line, then `espnId=points`
 * lines), so a jump of [BIG_PLAY] or more between two checks alerts once. The first check of a week only remembers.
 */
class ScoringPlayChecker(private val seen: File) {
    /** The big plays in [side]'s starting lineup (any slot but the bench and IR) since the last check of week [key]. */
    @Synchronized
    fun check(key: String, side: MatchupSide): List<ScoringPlay> {
        val starters = side.lineup.filter { it.slot != "BE" && it.slot != "IR" && it.espnPoints != null }
        val lines = runCatching { seen.readLines() }.getOrNull()
        val before = lines?.takeIf { it.firstOrNull() == key }?.drop(1)?.mapNotNull { line ->
            val (id, points) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            points.toDoubleOrNull()?.let { id to it }
        }?.toMap()
        runCatching { seen.writeText((listOf(key) + starters.map { "${it.espnId}=${it.espnPoints}" }).joinToString("\n")) }
        if (before == null) return emptyList()
        // A player new to the lineup since the last check has no baseline: his points so far aren't a play.
        return starters.mapNotNull { p ->
            val was = before[p.espnId] ?: return@mapNotNull null
            val now = p.espnPoints!!
            (now - was).takeIf { it >= BIG_PLAY }?.let { ScoringPlay(p.espnId, p.name, it, now) }
        }
    }

    private companion object {
        /** A touchdown scores 6 (a passing one 4, plus the drive's yards); a few short catches rarely add up to 5. */
        const val BIG_PLAY = 5.0
    }
}

/**
 * While any game of the week is live: the user's ESPN lineup against the last check ([ScoringPlayChecker]), a
 * notification per big play with the live score, then again in [EVERY]. It stops when no game is live; each kickoff
 * window starts it again ([schedule], from the two-hourly job).
 */
class ScoringAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GridironApplication
        if (!app.settings.alerts.first().scoring) return Result.success()
        val check = try {
            app.scoringPlays() ?: return Result.success() // no league or week: nothing to watch
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.retry() // ESPN failed while a game is on: WorkManager's backoff tries again
        }
        notify(applicationContext, check)
        if (check.live) WorkManager.getInstance(applicationContext).enqueueUniqueWork(LIVE, ExistingWorkPolicy.REPLACE, request(EVERY))
        return Result.success()
    }

    /** [plays] since the last check, the live [score] ("84.2–71.0 vs Rival"), and whether any game is still [live]. */
    data class Check(val plays: List<ScoringPlay>, val score: String?, val live: Boolean)

    companion object {
        private const val CHANNEL = "scoring"
        private const val LIVE = "scoring-live"

        /** How often a live week is checked: one ESPN request each time. */
        val EVERY: Duration = Duration.ofMinutes(5)

        private fun request(delay: Duration) = OneTimeWorkRequestBuilder<ScoringAlertWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, EVERY.toMillis(), TimeUnit.MILLISECONDS)
            .build()

        /** A start shortly after each kickoff window still ahead in [week]; one already scheduled is kept. */
        fun schedule(context: Context, week: ScoresWeek, now: Instant = Instant.now()) {
            val work = WorkManager.getInstance(context)
            for (kickoff in week.games.mapNotNull { it.kickoff }.distinct().filter { it.isAfter(now) }) {
                val delay = Duration.between(now, kickoff).plus(EVERY)
                work.enqueueUniqueWork("scoring-${kickoff.toEpochMilli()}", ExistingWorkPolicy.KEEP, request(delay))
            }
        }

        private fun notify(context: Context, check: Check) {
            if (check.plays.isEmpty()) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Scoring plays", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val compat = NotificationManagerCompat.from(context)
            for (play in check.plays) {
                val text = "Now ${one(play.total)} pts." + (check.score?.let { " $it" } ?: "")
                val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_injury)
                    .setContentTitle("${play.name} +${one(play.gained)} pts")
                    .setContentText(text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                // One per player: his next play replaces this one.
                compat.notify(("scoring:" + play.espnId).hashCode(), notification)
            }
        }

        private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
    }
}
