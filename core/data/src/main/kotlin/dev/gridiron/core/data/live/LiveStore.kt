package dev.gridiron.core.data.live

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import java.time.Duration
import java.time.Instant

public data class NewsPlayer(val name: String, val playerId: String?)

public data class NewsItem(
    val id: String,
    val published: Instant,
    val headline: String,
    val description: String?,
    val url: String,
    val players: List<NewsPlayer>,
)

public data class LiveStatus(
    val status: String,
    val abbr: String,
    val shortComment: String?,
    val longComment: String?,
    val updatedAt: Instant?,
)

public data class InjuryNote(val notedAt: Instant, val status: String, val comment: String)

public data class LiveInjury(
    val espnId: String,
    val playerId: String?,
    val name: String,
    val team: String?,
    val position: String?,
    val status: String,
    val abbr: String,
    val shortComment: String?,
    val updatedAt: Instant?,
)

private val RETENTION: Duration = Duration.ofDays(30)
private const val NEWS_LIMIT = 100L

private fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

private fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

private fun SQLiteStatement.instantOrNull(index: Int): Instant? = if (isNull(index)) null else Instant.ofEpochMilli(getLong(index))

/** Upserts [articles] and replaces their player tags. */
internal fun SQLiteConnection.saveNews(articles: List<NewsArticle>, playerIds: Map<String, String>) {
    prepare(
        """INSERT INTO news_item (id, published, headline, description, url) VALUES (?, ?, ?, ?, ?)
           ON CONFLICT (id) DO UPDATE SET published = excluded.published, headline = excluded.headline,
               description = excluded.description, url = excluded.url""",
    ).use { st ->
        for (a in articles) {
            st.reset()
            st.clearBindings()
            st.bindText(1, a.id)
            st.bindLong(2, a.published.toEpochMilli())
            st.bindText(3, a.headline)
            st.bindTextOrNull(4, a.description)
            st.bindText(5, a.url)
            st.step()
        }
    }
    prepare("DELETE FROM news_player WHERE news_id = ?").use { st ->
        for (a in articles) {
            st.reset()
            st.bindText(1, a.id)
            st.step()
        }
    }
    prepare("INSERT OR IGNORE INTO news_player (news_id, espn_id, name, player_id) VALUES (?, ?, ?, ?)").use { st ->
        for (a in articles) {
            for (p in a.athletes) {
                st.reset()
                st.clearBindings()
                st.bindText(1, a.id)
                st.bindText(2, p.espnId)
                st.bindText(3, p.name)
                st.bindTextOrNull(4, playerIds[p.espnId])
                st.step()
            }
        }
    }
}

/**
 * Replaces the status snapshot with [injuries], first appending a note for
 * each player whose short comment differs from their latest note. A note is
 * dated by ESPN's own time for it, or [now] if ESPN gave none.
 */
internal fun SQLiteConnection.saveInjuries(injuries: List<EspnInjury>, playerIds: Map<String, String>, now: Instant) {
    prepare("SELECT comment FROM injury_note WHERE espn_id = ? ORDER BY noted_at DESC LIMIT 1").use { latest ->
        prepare("INSERT OR IGNORE INTO injury_note (espn_id, noted_at, player_id, status, comment) VALUES (?, ?, ?, ?, ?)").use { add ->
            for (i in injuries) {
                val comment = i.shortComment ?: continue
                latest.reset()
                latest.bindText(1, i.espnId)
                val previous = if (latest.step()) latest.getText(0) else null
                if (previous == comment) continue
                add.reset()
                add.clearBindings()
                add.bindText(1, i.espnId)
                add.bindLong(2, (i.date ?: now).toEpochMilli())
                add.bindTextOrNull(3, playerIds[i.espnId])
                add.bindText(4, i.status)
                add.bindText(5, comment)
                add.step()
            }
        }
    }
    execSQL("DELETE FROM injury_status")
    prepare(
        """INSERT OR IGNORE INTO injury_status
           (espn_id, player_id, name, team, position, status, abbr, short_comment, long_comment, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
    ).use { st ->
        for (i in injuries) {
            st.reset()
            st.clearBindings()
            st.bindText(1, i.espnId)
            st.bindTextOrNull(2, playerIds[i.espnId])
            st.bindText(3, i.name)
            st.bindTextOrNull(4, i.team)
            st.bindTextOrNull(5, i.position)
            st.bindText(6, i.status)
            st.bindText(7, i.abbr)
            st.bindTextOrNull(8, i.shortComment)
            st.bindTextOrNull(9, i.longComment)
            if (i.date == null) st.bindNull(10) else st.bindLong(10, i.date.toEpochMilli())
            st.step()
        }
    }
}

private val LINKED_TABLES = listOf("news_player", "injury_status", "injury_note")

/** ESPN ids stored without an app player id, to look up again. */
internal fun SQLiteConnection.unlinkedEspnIds(): Set<String> =
    prepare(LINKED_TABLES.joinToString(" UNION ") { "SELECT espn_id FROM $it WHERE player_id IS NULL" }).use { st ->
        buildSet { while (st.step()) add(st.getText(0)) }
    }

/** Fills in player ids that are now known, for rows stored before they were. */
internal fun SQLiteConnection.relink(playerIds: Map<String, String>) {
    for (table in LINKED_TABLES) {
        prepare("UPDATE $table SET player_id = ? WHERE espn_id = ? AND player_id IS NULL").use { st ->
            for ((espnId, playerId) in playerIds) {
                st.reset()
                st.bindText(1, playerId)
                st.bindText(2, espnId)
                st.step()
            }
        }
    }
}

/** Drops news and notes older than thirty days before [now]. */
internal fun SQLiteConnection.prune(now: Instant) {
    val cutoff = now.minus(RETENTION).toEpochMilli()
    for (sql in listOf(
        "DELETE FROM news_player WHERE news_id IN (SELECT id FROM news_item WHERE published < ?)",
        "DELETE FROM news_item WHERE published < ?",
        "DELETE FROM injury_note WHERE noted_at < ?",
    )) {
        prepare(sql).use {
            it.bindLong(1, cutoff)
            it.step()
        }
    }
}

internal fun SQLiteConnection.setMeta(key: String, value: String) {
    prepare("INSERT OR REPLACE INTO live_meta (key, value) VALUES (?, ?)").use {
        it.bindText(1, key)
        it.bindText(2, value)
        it.step()
    }
}

internal fun SQLiteConnection.meta(key: String): String? =
    prepare("SELECT value FROM live_meta WHERE key = ?").use {
        it.bindText(1, key)
        if (it.step()) it.getText(0) else null
    }

/** The newest articles, or only those tagged with [playerId]; each with its tagged players. */
internal fun SQLiteConnection.news(playerId: String?): List<NewsItem> {
    val sql = if (playerId == null) {
        "SELECT id, published, headline, description, url FROM news_item ORDER BY published DESC, id LIMIT ?"
    } else {
        """SELECT n.id, n.published, n.headline, n.description, n.url
           FROM news_item n JOIN news_player p ON p.news_id = n.id
           WHERE p.player_id = ? ORDER BY n.published DESC, n.id LIMIT ?"""
    }
    val items = prepare(sql).use { st ->
        if (playerId == null) {
            st.bindLong(1, NEWS_LIMIT)
        } else {
            st.bindText(1, playerId)
            st.bindLong(2, NEWS_LIMIT)
        }
        buildList {
            while (st.step()) {
                add(NewsItem(st.getText(0), Instant.ofEpochMilli(st.getLong(1)), st.getText(2), st.textOrNull(3), st.getText(4), emptyList()))
            }
        }
    }
    if (items.isEmpty()) return items
    // At most a few thousand tags in thirty days: one read, grouped here.
    val tags = prepare("SELECT news_id, name, player_id FROM news_player ORDER BY news_id, name").use { st ->
        buildList { while (st.step()) add(st.getText(0) to NewsPlayer(st.getText(1), st.textOrNull(2))) }
    }.groupBy({ it.first }, { it.second })
    return items.map { it.copy(players = tags[it.id].orEmpty()) }
}

internal fun SQLiteConnection.status(playerId: String): LiveStatus? =
    prepare(
        """SELECT status, abbr, short_comment, long_comment, updated_at FROM injury_status
           WHERE player_id = ? ORDER BY updated_at DESC LIMIT 1""",
    ).use { st ->
        st.bindText(1, playerId)
        if (st.step()) LiveStatus(st.getText(0), st.getText(1), st.textOrNull(2), st.textOrNull(3), st.instantOrNull(4)) else null
    }

internal fun SQLiteConnection.notes(playerId: String): List<InjuryNote> =
    prepare("SELECT noted_at, status, comment FROM injury_note WHERE player_id = ? ORDER BY noted_at DESC").use { st ->
        st.bindText(1, playerId)
        buildList { while (st.step()) add(InjuryNote(Instant.ofEpochMilli(st.getLong(0)), st.getText(1), st.getText(2))) }
    }

/** The Grid's badge letters by player id: every linked player whose status isn't Active. */
internal fun SQLiteConnection.badges(): Map<String, String> =
    prepare("SELECT player_id, abbr FROM injury_status WHERE player_id IS NOT NULL AND abbr <> 'A'").use { st ->
        buildMap { while (st.step()) put(st.getText(0), st.getText(1)) }
    }

/** Everyone on the report but Active players, by team, most serious first. */
internal fun SQLiteConnection.injuries(): List<LiveInjury> =
    prepare(
        """SELECT espn_id, player_id, name, team, position, status, abbr, short_comment, updated_at
           FROM injury_status WHERE abbr <> 'A'
           ORDER BY team, CASE abbr WHEN 'O' THEN 0 WHEN 'IR' THEN 1 WHEN 'D' THEN 2 WHEN 'Q' THEN 3 ELSE 4 END, name""",
    ).use { st ->
        buildList {
            while (st.step()) {
                add(
                    LiveInjury(
                        espnId = st.getText(0),
                        playerId = st.textOrNull(1),
                        name = st.getText(2),
                        team = st.textOrNull(3),
                        position = st.textOrNull(4),
                        status = st.getText(5),
                        abbr = st.getText(6),
                        shortComment = st.textOrNull(7),
                        updatedAt = st.instantOrNull(8),
                    ),
                )
            }
        }
    }
