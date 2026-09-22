package dev.gridiron.core.statquery

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.StatColumn.ADOT
import dev.gridiron.core.statquery.StatColumn.AIR_YARDS_SHARE
import dev.gridiron.core.statquery.StatColumn.INTERCEPTIONS
import dev.gridiron.core.statquery.StatColumn.RACR
import dev.gridiron.core.statquery.StatColumn.RECEPTIONS
import dev.gridiron.core.statquery.StatColumn.TARGETS
import dev.gridiron.core.statquery.StatColumn.TARGET_SHARE
import dev.gridiron.core.statquery.StatColumn.WOPR
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import dev.gridiron.core.statquery.Components as C

private const val EPS = 1e-9

/** Executes generated SQL against real SQLite with hand-computed expectations. */
class StatQueryBuilderTest {
    private lateinit var db: FixtureDb

    @BeforeEach
    fun setUp() {
        db = FixtureDb()
    }

    @AfterEach
    fun tearDown() = db.close()

    private fun spec(vararg columns: StatColumn, weeks: WeekRange = WeekRange(1, 18)) =
        StatQuerySpec(season = 2025, weeks = weeks, columns = columns.toList())

    @Nested
    inner class RangeRecomputation {
        @Test
        fun `target share over a range is summed components, not a mean of weekly shares`() {
            db.player("wr1", "Alpha Receiver")
            db.week("wr1", 1, C.TARGETS to 3, C.TEAM_TARGETS to 5) // 0.60 that week
            db.week("wr1", 2, C.TARGETS to 2, C.TEAM_TARGETS to 20) // 0.10 that week

            val row = db.grid(spec(TARGET_SHARE, weeks = WeekRange(1, 2))).single()

            // 5 / 25. The mean of weekly shares would be 0.35.
            assertEquals(0.2, row.value(TARGET_SHARE)!!, EPS)
        }

        @Test
        fun `weeks outside the range and other seasons are excluded`() {
            db.player("wr1", "Alpha Receiver")
            db.week("wr1", 1, C.TARGETS to 3)
            db.week("wr1", 2, C.TARGETS to 5)
            db.week("wr1", 3, C.TARGETS to 100)
            db.week("wr1", 2, C.TARGETS to 1000, season = 2024)

            val row = db.grid(spec(TARGETS, weeks = WeekRange(1, 2))).single()

            assertEquals(8.0, row.value(TARGETS)!!, EPS)
            assertEquals(2.0, row.games, EPS)
        }

        @Test
        fun `a ratio is null when its denominator is zero or negative`() {
            db.player("rb1", "Screen Back", position = "RB")
            // Net-negative air yards, all screens: RACR is undefined, not negative.
            db.week("rb1", 1, C.TARGETS to 2, C.RECEIVING_YARDS to 15, C.AIR_YARDS to -6)
            db.player("rb2", "Pure Runner", position = "RB")
            db.week("rb2", 1, C.TARGETS to 0, C.AIR_YARDS to 0)

            val rows = db.grid(spec(RACR, ADOT)).associateBy { it.playerId }

            assertNull(rows.getValue("rb1").value(RACR))
            assertEquals(-3.0, rows.getValue("rb1").value(ADOT)!!, EPS)
            assertNull(rows.getValue("rb2").value(ADOT))
        }
    }

    @Nested
    inner class Wopr {
        @Test
        fun `negative air yards share is clamped to zero inside WOPR`() {
            db.player("rb1", "Screen Back", position = "RB")
            db.week("rb1", 1, C.TARGETS to 2, C.TEAM_TARGETS to 4, C.AIR_YARDS to -5, C.TEAM_AIR_YARDS to 15)

            val row = db.grid(spec(WOPR, AIR_YARDS_SHARE)).single()

            assertEquals(-5.0 / 15, row.value(AIR_YARDS_SHARE)!!, EPS) // raw share keeps its sign
            assertEquals(1.5 * 0.5, row.value(WOPR)!!, EPS)
        }

        @Test
        fun `air yards share above one is clamped to one inside WOPR`() {
            db.player("wr1", "Deep Threat")
            db.week("wr1", 1, C.TARGETS to 2, C.TEAM_TARGETS to 4, C.AIR_YARDS to 20, C.TEAM_AIR_YARDS to 15)

            val row = db.grid(spec(WOPR)).single()

            assertEquals(1.5 * 0.5 + 0.7 * 1.0, row.value(WOPR)!!, EPS)
        }
    }

    @Nested
    inner class PerGame {
        @Test
        fun `per-game mode divides counting stats but leaves rates alone`() {
            db.player("wr1", "Alpha Receiver")
            db.week("wr1", 1, C.TARGETS to 3, C.TEAM_TARGETS to 10)
            db.week("wr1", 2, C.TARGETS to 5, C.TEAM_TARGETS to 10)

            val row = db.grid(spec(TARGETS, TARGET_SHARE).copy(mode = ValueMode.PER_GAME)).single()

            assertEquals(4.0, row.value(TARGETS)!!, EPS)
            assertEquals(0.4, row.value(TARGET_SHARE)!!, EPS)
        }

        @Test
        fun `filters apply to the values as displayed`() {
            db.player("wr1", "Two Games")
            db.week("wr1", 1, C.TARGETS to 5)
            db.week("wr1", 2, C.TARGETS to 5) // 10 total, 5 per game
            db.player("wr2", "One Game")
            db.week("wr2", 1, C.TARGETS to 6) // 6 total, 6 per game
            val atLeastSix = listOf(Filter(TARGETS, Condition.AtLeast(6.0)))

            val total = db.grid(spec(TARGETS).copy(filters = atLeastSix))
            val perGame = db.grid(spec(TARGETS).copy(filters = atLeastSix, mode = ValueMode.PER_GAME))

            assertEquals(setOf("wr1", "wr2"), total.map { it.playerId }.toSet())
            assertEquals(listOf("wr2"), perGame.map { it.playerId })
        }
    }

    @Nested
    inner class Sorting {
        @Test
        fun `nulls sort last in both directions`() {
            db.player("wr1", "Deep")
            db.week("wr1", 1, C.TARGETS to 2, C.AIR_YARDS to 30)
            db.player("wr2", "Short")
            db.week("wr2", 1, C.TARGETS to 2, C.AIR_YARDS to 10)
            db.player("rb1", "No Targets", position = "RB")
            db.week("rb1", 1, C.TARGETS to 0, C.AIR_YARDS to 0)

            val desc = db.grid(spec(ADOT).copy(sort = listOf(Sort(ADOT, Direction.DESCENDING))))
            val asc = db.grid(spec(ADOT).copy(sort = listOf(Sort(ADOT, Direction.ASCENDING))))

            assertEquals(listOf("wr1", "wr2", "rb1"), desc.map { it.playerId })
            assertEquals(listOf("wr2", "wr1", "rb1"), asc.map { it.playerId })
        }

        @Test
        fun `secondary sort keys break ties`() {
            listOf("a" to 5, "b" to 9, "c" to 5).forEach { (id, rec) ->
                db.player(id, "Player $id")
                db.week(id, 1, C.TARGETS to 10, C.RECEPTIONS to rec)
            }
            val sort = listOf(Sort(TARGETS), Sort(RECEPTIONS, Direction.ASCENDING))

            val rows = db.grid(spec(TARGETS, RECEPTIONS).copy(sort = sort))

            assertEquals(listOf("a", "c", "b"), rows.map { it.playerId })
        }

        @Test
        fun `ties fall back to name then id, so pages never overlap or skip`() {
            val ids = listOf("p5", "p3", "p1", "p4", "p2")
            ids.forEach { db.player(it, "Same Name"); db.week(it, 1, C.TARGETS to 7) }
            val base = spec(TARGETS).copy(limit = 2)

            val paged = (0..2).flatMap { page -> db.grid(base.copy(offset = page * 2)).map { it.playerId } }

            assertEquals(ids.sorted(), paged)
        }

        @Test
        fun `default sort is the first column descending`() {
            listOf("low" to 1, "high" to 9, "mid" to 5).forEach { (id, t) ->
                db.player(id, id)
                db.week(id, 1, C.TARGETS to t)
            }

            assertEquals(listOf("high", "mid", "low"), db.grid(spec(TARGETS)).map { it.playerId })
        }
    }

    @Nested
    inner class Filtering {
        @Test
        fun `minimum games applies before anything else`() {
            db.player("wr1", "Regular")
            db.week("wr1", 1, C.TARGETS to 1)
            db.week("wr1", 2, C.TARGETS to 1)
            db.player("wr2", "Cameo")
            db.week("wr2", 1, C.TARGETS to 30)

            val rows = db.grid(spec(TARGETS).copy(minGames = 2))

            assertEquals(listOf("wr1"), rows.map { it.playerId })
        }

        @Test
        fun `positions and teams narrow the result`() {
            db.player("wr1", "Wideout", position = "WR", team = "KC")
            db.player("te1", "Tight End", position = "TE", team = "KC")
            db.player("rb1", "Back", position = "RB", team = "BUF")
            listOf("wr1", "te1", "rb1").forEach { db.week(it, 1, C.TARGETS to 3) }

            val flexKc = db.grid(spec(TARGETS).copy(positions = Position.FLEX, teams = setOf("KC")))

            assertEquals(setOf("wr1", "te1"), flexKc.map { it.playerId }.toSet())
        }

        @Test
        fun `between is inclusive at both ends`() {
            listOf("a" to 4, "b" to 5, "c" to 7, "d" to 8).forEach { (id, t) ->
                db.player(id, id)
                db.week(id, 1, C.TARGETS to t)
            }
            val filter = Filter(TARGETS, Condition.Between(5.0, 7.0))

            val rows = db.grid(spec(TARGETS).copy(filters = listOf(filter)))

            assertEquals(setOf("b", "c"), rows.map { it.playerId }.toSet())
        }

        @Test
        fun `count agrees with the rows the grid returns`() {
            (1..12).forEach { i ->
                db.player("p$i", "Player $i", position = if (i % 3 == 0) "TE" else "WR")
                db.week("p$i", 1, C.TARGETS to i)
            }
            val s = spec(TARGETS).copy(
                positions = setOf(Position.WR),
                filters = listOf(Filter(TARGETS, Condition.GreaterThan(3.0))),
            )

            assertEquals(db.grid(s).size, db.count(s))
            assertEquals(6, db.count(s)) // WRs 4,5,7,8,10,11
        }

        @Test
        fun `name filter matches a full-name prefix or the start of any word`() {
            db.player("c1", "Ja'Marr Chase")
            db.player("c2", "Chase Brown", position = "RB")
            db.player("h1", "Jalen Hurts", position = "QB")
            listOf("c1", "c2", "h1").forEach { db.week(it, 1, C.TARGETS to 1) }

            val rows = db.grid(spec(TARGETS).copy(name = "chase"))

            assertEquals(setOf("c1", "c2"), rows.map { it.playerId }.toSet())
        }
    }

    @Nested
    inner class Percentiles {
        private fun seed() {
            listOf("w1" to 10, "w2" to 20, "w3" to 30).forEach { (id, t) ->
                db.player(id, id, position = "WR", team = if (id == "w3") "KC" else "BUF")
                db.week(id, 1, C.TARGETS to t, C.AIR_YARDS to t * 10)
            }
            // RBs are ranked among RBs only.
            listOf("r1" to 2, "r2" to 4).forEach { (id, t) ->
                db.player(id, id, position = "RB")
                db.week(id, 1, C.TARGETS to t, C.AIR_YARDS to 0)
            }
        }

        @Test
        fun `best is one and each position is ranked separately`() {
            seed()
            val rows = db.grid(spec(TARGETS).copy(percentiles = true)).associateBy { it.playerId }

            assertEquals(0.0, rows.getValue("w1").percentile(TARGETS)!!, EPS)
            assertEquals(0.5, rows.getValue("w2").percentile(TARGETS)!!, EPS)
            assertEquals(1.0, rows.getValue("w3").percentile(TARGETS)!!, EPS)
            assertEquals(1.0, rows.getValue("r2").percentile(TARGETS)!!, EPS)
        }

        @Test
        fun `lower-is-better columns rank the lowest value best`() {
            listOf("q1" to 1, "q2" to 9).forEach { (id, ints) ->
                db.player(id, id, position = "QB")
                db.week(id, 1, C.INTERCEPTIONS to ints)
            }

            val rows = db.grid(spec(INTERCEPTIONS).copy(percentiles = true)).associateBy { it.playerId }

            assertEquals(1.0, rows.getValue("q1").percentile(INTERCEPTIONS)!!, EPS)
            assertEquals(0.0, rows.getValue("q2").percentile(INTERCEPTIONS)!!, EPS)
        }

        @Test
        fun `players without a value get no percentile and don't dilute others`() {
            // aDOT 5, 10, 15, plus one WR with no targets and so no aDOT.
            listOf("w1" to 5, "w2" to 10, "w3" to 15).forEach { (id, adot) ->
                db.player(id, id)
                db.week(id, 1, C.TARGETS to 2, C.AIR_YARDS to adot * 2)
            }
            db.player("w4", "No Targets")
            db.week("w4", 1, C.TARGETS to 0, C.AIR_YARDS to 0)

            val rows = db.grid(spec(ADOT).copy(percentiles = true)).associateBy { it.playerId }

            assertNull(rows.getValue("w4").percentile(ADOT))
            // Had the null row joined the ranking, these would be 1/3, 2/3 and 1.
            assertEquals(0.0, rows.getValue("w1").percentile(ADOT)!!, EPS)
            assertEquals(0.5, rows.getValue("w2").percentile(ADOT)!!, EPS)
            assertEquals(1.0, rows.getValue("w3").percentile(ADOT)!!, EPS)
        }

        @Test
        fun `tied values share a percentile`() {
            listOf("a", "b", "c").forEach { db.player(it, it); db.week(it, 1, C.TARGETS to 6) }

            val pcts = db.grid(spec(TARGETS).copy(percentiles = true)).map { it.percentile(TARGETS) }

            assertEquals(listOf(0.0, 0.0, 0.0), pcts)
        }

        @Test
        fun `filters narrow the view without changing anyone's percentile`() {
            seed()
            val narrowed = db.grid(
                spec(TARGETS).copy(
                    percentiles = true,
                    teams = setOf("KC"),
                    filters = listOf(Filter(TARGETS, Condition.AtLeast(25.0))),
                ),
            )

            assertEquals(1.0, narrowed.single().percentile(TARGETS)!!, EPS)
        }
    }

    @Nested
    inner class Qualifiers {
        private val qualified = listOf(Filter(TARGETS, Condition.AtLeast(20.0)))

        private fun seed() {
            // Three starters, plus two backups who'd otherwise top a rate stat.
            listOf("s1" to 20, "s2" to 40, "s3" to 60).forEach { (id, t) ->
                db.player(id, "Starter $id")
                db.week(id, 1, C.TARGETS to t, C.RECEPTIONS to t / 2)
            }
            listOf("b1" to 1, "b2" to 2).forEach { (id, t) ->
                db.player(id, "Backup $id")
                db.week(id, 1, C.TARGETS to t, C.RECEPTIONS to t)
            }
        }

        @Test
        fun `unqualified players are left out`() {
            seed()
            val rows = db.grid(spec(TARGETS).copy(qualifiers = qualified))
            assertEquals(listOf("s3", "s2", "s1"), rows.map { it.playerId })
        }

        @Test
        fun `percentiles rank only qualified players`() {
            seed()
            val rows = db.grid(spec(TARGETS).copy(qualifiers = qualified, percentiles = true)).associateBy { it.playerId }

            // Among the three starters: 0, 0.5, 1. Had the backups been ranked
            // too, the weakest starter would sit at 0.5 rather than 0.
            assertEquals(0.0, rows.getValue("s1").percentile(TARGETS)!!, EPS)
            assertEquals(0.5, rows.getValue("s2").percentile(TARGETS)!!, EPS)
            assertEquals(1.0, rows.getValue("s3").percentile(TARGETS)!!, EPS)
        }

        @Test
        fun `a small sample can't top a rate stat's percentiles`() {
            seed()
            // Backups caught every target; starters caught half.
            val rows = db.grid(spec(StatColumn.CATCH_RATE).copy(qualifiers = qualified, percentiles = true))
            assertTrue(rows.none { it.playerId.startsWith("b") })
            assertEquals(0.0, rows.first().percentile(StatColumn.CATCH_RATE)!!, EPS) // all tied at 0.5
        }

        @Test
        fun `unqualified players can be included, unranked`() {
            seed()
            val rows = db.grid(
                spec(TARGETS).copy(qualifiers = qualified, includeUnqualified = true, percentiles = true),
            ).associateBy { it.playerId }

            assertEquals(5, rows.size)
            assertNull(rows.getValue("b1").percentile(TARGETS))
            assertEquals(0.0, rows.getValue("s1").percentile(TARGETS)!!, EPS)
        }

        @Test
        fun `count respects qualifiers`() {
            seed()
            assertEquals(3, db.count(spec(TARGETS).copy(qualifiers = qualified)))
            assertEquals(5, db.count(spec(TARGETS).copy(qualifiers = qualified, includeUnqualified = true)))
        }
    }

    @Nested
    inner class Search {
        @Test
        fun `full-name prefix matches rank ahead of word matches`() {
            db.player("c1", "Ja'Marr Chase")
            db.player("c2", "Chase Brown", position = "RB")
            db.player("c3", "Chase Claypool")

            val ids = db.rows(StatQueryBuilder.search("chase")!!).map { it[0] as String }

            assertEquals(listOf("c2", "c3", "c1"), ids)
        }

        @Test
        fun `punctuation and accents fold the same way the ETL folds them`() {
            db.player("t1", "Tomás Ávila")

            assertEquals(1, db.rows(StatQueryBuilder.search("TOMAS avi")!!).size)
            assertEquals(1, db.rows(StatQueryBuilder.search("tomás")!!).size)
        }

        @Test
        fun `text with nothing searchable yields no query`() {
            assertNull(StatQueryBuilder.search("   "))
            assertNull(StatQueryBuilder.search("'.-"))
        }
    }

    @Nested
    inner class Injection {
        @Test
        fun `hostile strings are inert values`() {
            db.player("wr1", "Alpha Receiver")
            db.week("wr1", 1, C.TARGETS to 3)
            val hostile = "AAA') OR 1=1; DROP TABLE player; --"

            val byTeam = db.grid(spec(TARGETS).copy(teams = setOf(hostile)))
            val byName = db.grid(spec(TARGETS).copy(name = "x'; DROP TABLE player_week_stat; --"))

            assertTrue(byTeam.isEmpty())
            assertTrue(byName.isEmpty())
            assertEquals(1, (db.scalar("SELECT COUNT(*) FROM player") as Number).toInt())
        }
    }
}
