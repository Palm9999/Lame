package dev.gridiron.core.data.live

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import dev.gridiron.core.forecast.PropEvent
import dev.gridiron.core.forecast.PropQuote
import dev.gridiron.core.forecast.PropsSnapshot
import java.time.Instant

/** A game of the upcoming week, with its teams as nflverse abbreviations. */
internal data class StoredEvent(val id: String, val commence: Instant, val home: String, val away: String)

/** An anytime TD has no line, and a key column can't be null: lines are never negative, so this stands for none. */
private const val NO_POINT = -1.0

private fun SQLiteStatement.bindNullableDouble(index: Int, value: Double?) {
    if (value == null) bindNull(index) else bindDouble(index, value)
}

private fun SQLiteStatement.nullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)

/** Adds or updates [events], keeping each one's fetch time. */
internal fun SQLiteConnection.saveEvents(events: List<StoredEvent>) {
    prepare(
        """INSERT INTO prop_event (id, commence, home, away) VALUES (?, ?, ?, ?)
           ON CONFLICT (id) DO UPDATE SET commence = excluded.commence, home = excluded.home, away = excluded.away""",
    ).use { st ->
        for (e in events) {
            st.reset()
            st.bindText(1, e.id)
            st.bindLong(2, e.commence.toEpochMilli())
            st.bindText(3, e.home)
            st.bindText(4, e.away)
            st.step()
        }
    }
}

/** When each stored game's props last arrived; null for never. */
internal fun SQLiteConnection.propFetchTimes(): Map<String, Instant?> =
    prepare("SELECT id, fetched_at FROM prop_event").use { st ->
        buildMap { while (st.step()) put(st.getText(0), if (st.isNull(1)) null else Instant.ofEpochMilli(st.getLong(1))) }
    }

/** Replaces one game's lines with [quotes] and marks it fetched at [at]. */
internal fun SQLiteConnection.saveLines(eventId: String, quotes: List<PropQuote>, at: Instant) {
    prepare("DELETE FROM prop_line WHERE event_id = ?").use {
        it.bindText(1, eventId)
        it.step()
    }
    prepare("INSERT OR REPLACE INTO prop_line (event_id, book, market, player, point, over, under) VALUES (?, ?, ?, ?, ?, ?, ?)").use { st ->
        for (q in quotes) {
            st.reset()
            st.clearBindings()
            st.bindText(1, eventId)
            st.bindText(2, q.book)
            st.bindText(3, q.market)
            st.bindText(4, q.player)
            st.bindDouble(5, q.point ?: NO_POINT)
            st.bindNullableDouble(6, q.over)
            st.bindNullableDouble(7, q.under)
            st.step()
        }
    }
    prepare("UPDATE prop_event SET fetched_at = ? WHERE id = ?").use {
        it.bindLong(1, at.toEpochMilli())
        it.bindText(2, eventId)
        it.step()
    }
}

/** Drops games that kicked off before [before], with their lines. */
internal fun SQLiteConnection.pruneProps(before: Instant) {
    for (sql in listOf(
        "DELETE FROM prop_line WHERE event_id IN (SELECT id FROM prop_event WHERE commence < ?)",
        "DELETE FROM prop_event WHERE commence < ?",
    )) {
        prepare(sql).use {
            it.bindLong(1, before.toEpochMilli())
            it.step()
        }
    }
}

/** Every stored game that has lines, in kickoff order, each with its lines by book, market, player and line. */
internal fun SQLiteConnection.propsSnapshot(): PropsSnapshot {
    val lines = prepare(
        "SELECT event_id, book, market, player, point, over, under FROM prop_line ORDER BY event_id, book, market, player, point",
    ).use { st ->
        buildList {
            while (st.step()) {
                val point = st.getDouble(4).takeIf { it >= 0.0 }
                add(st.getText(0) to PropQuote(st.getText(1), st.getText(2), st.getText(3), point, st.nullableDouble(5), st.nullableDouble(6)))
            }
        }
    }.groupBy({ it.first }, { it.second })
    val events = prepare("SELECT id, home, away FROM prop_event ORDER BY commence, id").use { st ->
        buildList { while (st.step()) add(Triple(st.getText(0), st.getText(1), st.getText(2))) }
    }
    return PropsSnapshot(events.mapNotNull { (id, home, away) -> lines[id]?.let { PropEvent(home, away, it) } })
}
