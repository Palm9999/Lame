package dev.gridiron.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the home-screen widget shows: the last summary of the week (from Home or My lineup) with its win chance when
 * Home wrote it, the players to watch, and the latest lineup alert, each with when it was written. Kept as lines in a
 * small file so the widget reads it without opening any database; a file from before the chance and watch lines reads
 * without them.
 */
data class WidgetState(
    val lineup: String?,
    val lineupAt: Instant?,
    val alert: String?,
    val alertAt: Instant?,
    val chance: Double? = null,
    val watch: String? = null,
) {
    fun encode(): String = listOf(
        lineup.orEmpty(), lineupAt?.toEpochMilli()?.toString().orEmpty(), alert.orEmpty(), alertAt?.toEpochMilli()?.toString().orEmpty(),
        chance?.toString().orEmpty(), watch.orEmpty(),
    ).joinToString("\n") { it.replace('\n', ' ') }

    companion object {
        val EMPTY = WidgetState(null, null, null, null)

        fun decode(text: String): WidgetState {
            val parts = text.split('\n')
            fun at(i: Int) = parts.getOrNull(i)?.toLongOrNull()?.let(Instant::ofEpochMilli)
            fun line(i: Int) = parts.getOrNull(i)?.takeIf { it.isNotBlank() }
            return WidgetState(line(0), at(1), line(2), at(3), line(4)?.toDoubleOrNull(), line(5))
        }
    }
}

/** Reads and writes [WidgetState] in `noBackupFilesDir/widget.txt`, and tells every widget to redraw after a write. */
class WidgetStore(private val context: Context, private val file: File = File(context.noBackupFilesDir, "widget.txt")) {
    @Synchronized
    fun read(): WidgetState = runCatching { if (file.isFile) WidgetState.decode(file.readText()) else WidgetState.EMPTY }.getOrDefault(WidgetState.EMPTY)

    /** My lineup's summary; it carries no chance of its own, so Home's older one is dropped rather than shown beside it. */
    @Synchronized
    fun lineup(summary: String, at: Instant = Instant.now()) = write(read().copy(lineup = summary, lineupAt = at, chance = null))

    /** Home's summary, its win chance (live while a game is on) and the players to watch ("Name Q · Name O"). */
    @Synchronized
    fun home(summary: String, chance: Double?, watch: String?, at: Instant = Instant.now()) =
        write(read().copy(lineup = summary, lineupAt = at, chance = chance, watch = watch))

    /** The latest lineup alert; null clears it (a check that found nothing). */
    @Synchronized
    fun alert(text: String?, at: Instant = Instant.now()) = write(read().copy(alert = text, alertAt = text?.let { at }))

    private fun write(state: WidgetState) {
        runCatching { file.writeText(state.encode()) }
        LineupWidget.redraw(context)
    }
}

/** The home-screen widget: My lineup's last summary and the latest lineup alert; a tap opens the app. */
class LineupWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val state = WidgetStore(context.applicationContext).read()
        for (id in ids) manager.updateAppWidget(id, views(context, state))
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

        /** The widget's two lines from [state]; "Open My lineup" until it has run once. */
        fun lines(state: WidgetState, zone: ZoneId = ZoneId.systemDefault()): Pair<String, String> {
            val lineup = state.lineup?.let { l -> l + (state.lineupAt?.let { " · ${TIME.format(it.atZone(zone))}" } ?: "") }
                ?: "Open My lineup to see your week here"
            val alert = state.alert?.let { "⚠ $it" } ?: "No lineup problems found"
            return lineup to alert
        }

        private fun views(context: Context, state: WidgetState): RemoteViews {
            val (lineup, alert) = lines(state)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return RemoteViews(context.packageName, R.layout.widget_lineup).apply {
                setTextViewText(R.id.widget_lineup, lineup)
                setTextViewText(R.id.widget_alert, alert)
                val chance = state.chance
                setViewVisibility(R.id.widget_chance, if (chance == null) android.view.View.GONE else android.view.View.VISIBLE)
                if (chance != null) setProgressBar(R.id.widget_chance, 100, (chance * 100).toInt().coerceIn(0, 100), false)
                setViewVisibility(R.id.widget_watch, if (state.watch == null) android.view.View.GONE else android.view.View.VISIBLE)
                setTextViewText(R.id.widget_watch, state.watch?.let { "Watch: $it" }.orEmpty())
                setOnClickPendingIntent(R.id.widget_root, open)
            }
        }

        fun redraw(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, LineupWidget::class.java))
            if (ids.isEmpty()) return
            val state = WidgetStore(context.applicationContext).read()
            for (id in ids) manager.updateAppWidget(id, views(context, state))
        }
    }
}
