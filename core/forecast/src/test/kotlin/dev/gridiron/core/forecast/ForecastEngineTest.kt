package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class ForecastEngineTest {
    @TempDir
    lateinit var dir: File

    private val builtAt = Instant.parse("2025-09-20T12:00:00Z")
    private val teams = listOf("AAA", "BBB", "CCC", "DDD")

    /**
     * Four teams with a QB, RB and WR each. 2024 weeks 1-3 and 2025 weeks 1-2
     * are played. 2025 week 3 is next: AAA-DDD has a line, BBB-CCC doesn't.
     * In 2025 week 4 only AAA-CCC play: BBB and DDD are on bye.
     */
    private fun league(name: String, allPlayed: Boolean = false, wrA2025Week2Targets: Double = 9.0, playedThrough: Int = 2): TestDb {
        val db = TestDb(File(dir, name))
        for (team in teams) {
            val letter = team.first()
            db.player("QB_$letter", "QB", team)
            db.player("RB_$letter", "RB", team)
            db.player("WR_$letter", "WR", team)
        }
        val schedule = listOf(
            listOf("AAA" to "BBB", "CCC" to "DDD"),
            listOf("AAA" to "CCC", "BBB" to "DDD"),
            listOf("AAA" to "DDD", "BBB" to "CCC"),
        )
        for (season in listOf(2024, 2025)) {
            for ((w, pairs) in schedule.withIndex()) {
                val week = w + 1
                val played = season == 2024 || week <= playedThrough || allPlayed
                for ((home, away) in pairs) {
                    val line = season == 2025 && week == 3 && home == "AAA"
                    db.game(
                        season, week, home, away, played = played,
                        spread = if (line) 3.0 else null, total = if (line) 44.0 else null,
                        homeCoach = "Coach $home", awayCoach = "Coach $away",
                    )
                    if (played) {
                        playWeek(db, home, season, week, wrA2025Week2Targets)
                        playWeek(db, away, season, week, wrA2025Week2Targets)
                    }
                }
            }
        }
        db.game(2025, 4, "AAA", "CCC", played = allPlayed, spread = -1.0, total = 41.0, homeCoach = "Coach AAA", awayCoach = "Coach CCC")
        if (allPlayed) {
            playWeek(db, "AAA", 2025, 4, wrA2025Week2Targets)
            playWeek(db, "CCC", 2025, 4, wrA2025Week2Targets)
        }
        db.meta("expected_through_week:2024", "3")
        db.meta("expected_through_week:2025", if (allPlayed) "4" else "$playedThrough")
        return db
    }

    private fun playWeek(db: TestDb, team: String, season: Int, week: Int, wrA2025Week2Targets: Double) {
        val k = 1.0 + 0.1 * teams.indexOf(team) + 0.05 * week // teams and weeks differ a little
        val letter = team.first()
        db.week(
            "QB_$letter", season, week, team,
            "attempts" to 32 * k, "completions" to 21 * k, "passing_yards" to 240 * k, "passing_tds" to 1.5,
            "x_passing_tds" to 1.4, "interceptions" to 1.0, "carries" to 3.0, "rushing_yards" to 12.0,
        )
        db.week(
            "RB_$letter", season, week, team,
            "carries" to 18 * k, "rushing_yards" to 80 * k, "rushing_tds" to 0.6, "x_rushing_tds" to 0.55,
            "targets" to 4.0, "receptions" to 3.0, "receiving_yards" to 22.0,
        )
        val wrTargets = if (team == "AAA" && season == 2025 && week == 2) wrA2025Week2Targets else 9 * k
        db.week(
            "WR_$letter", season, week, team,
            "targets" to wrTargets, "receptions" to 6 * k, "receiving_yards" to 80 * k, "receiving_tds" to 0.5, "x_receiving_tds" to 0.45,
        )
    }

    private fun run(db: TestDb, copy: SeasonCopy? = null, props: PropsSnapshot? = null, onWeek: (Int, Int) -> Unit = { _, _ -> }): ForecastReport =
        Forecast.run(db.conn, builtAt, copy, props, onWeek)

    private fun projections(db: TestDb) =
        db.query("SELECT player_id, season, week, metric_id, stage, mean, variance FROM player_week_projection ORDER BY 1, 2, 3, 4, 5")

    private fun targets(db: TestDb, player: String, week: Int): Double = db.query(
        "SELECT mean FROM player_week_projection WHERE player_id = '$player' AND season = 2025 AND week = $week AND stage = 'final' AND metric_id = 'targets'",
    ).single()[0]!!.toDouble()

    private fun rosTargets(db: TestDb, player: String): Double = db.query(
        "SELECT mean FROM player_ros_projection WHERE player_id = '$player' AND season = 2025 AND as_of_week = 2 AND metric_id = 'targets'",
    ).single()[0]!!.toDouble()

    private fun factors(db: TestDb, player: String) = db.query(
        "SELECT factor FROM player_week_projection_factor WHERE player_id = '$player' AND season = 2025 AND week = 3 ORDER BY factor",
    ).map { it[0] }

    private fun finalMean(db: TestDb, player: String, week: Int, metric: String): Double = db.query(
        "SELECT mean FROM player_week_projection WHERE player_id = '$player' AND season = 2025 AND week = $week AND stage = 'final' AND metric_id = '$metric'",
    ).single()[0]!!.toDouble()

    private fun rosMean(db: TestDb, player: String, metric: String): Double = db.query(
        "SELECT mean FROM player_ros_projection WHERE player_id = '$player' AND season = 2025 AND as_of_week = 2 AND metric_id = '$metric'",
    ).single()[0]!!.toDouble()

    private val wrAProps = PropsSnapshot(
        listOf(
            PropEvent(
                "AAA", "DDD",
                listOf(
                    PropQuote("dk", "player_reception_yds", "Player WR_A", 120.5, 1.9, 1.9),
                    PropQuote("dk", "player_anytime_td", "Player WR_A", null, 1.5, null),
                ),
            ),
        ),
    )

    @Test
    fun `the upcoming week gets both stages and the waterfall's factors`() {
        league("a.db").use { db ->
            val seen = mutableListOf<Pair<Int, Int>>()
            val report = run(db) { season, week -> seen += season to week }

            assertEquals(FORECAST_OK, report.status)
            assertEquals(mapOf(2025 to 3), report.upcoming)
            assertEquals(listOf(2024 to 2, 2024 to 3, 2025 to 1, 2025 to 2, 2025 to 3, 2025 to 4), seen)
            assertEquals(listOf(listOf("3")), db.query("SELECT value FROM schema_meta WHERE key = 'forecast_week:2025'"))
            assertEquals(listOf(listOf("ok")), db.query("SELECT value FROM schema_meta WHERE key = 'forecast_status'"))
            assertEquals(listOf(listOf(FORECAST_VERSION.toString())), db.query("SELECT value FROM schema_meta WHERE key = 'forecast_version'"))
            assertEquals(
                listOf(listOf("baseline"), listOf("final")),
                db.query("SELECT DISTINCT stage FROM player_week_projection WHERE player_id = 'WR_A' AND season = 2025 AND week = 3 ORDER BY stage"),
            )
            // AAA's game has a line; CCC's doesn't.
            assertEquals(listOf("game_script", "matchup"), factors(db, "WR_A"))
            assertEquals(listOf("matchup"), factors(db, "WR_C"))
            assertEquals(
                "vs DDD: too early to rate defenses",
                db.query("SELECT note FROM player_week_projection_factor WHERE player_id = 'WR_A' AND season = 2025 AND week = 3 AND factor = 'matchup'").single()[0],
            )
            // No defense ratings yet and no line: CCC's final is its baseline.
            val wrC = db.query(
                "SELECT mean FROM player_week_projection WHERE player_id = 'WR_C' AND season = 2025 AND week = 3 AND metric_id = 'targets' ORDER BY stage",
            )
            assertEquals(wrC[0][0], wrC[1][0])
        }
    }

    @Test
    fun `past weeks keep only the final stage, and the first week has nothing before it`() {
        league("a.db").use { db ->
            run(db)
            assertEquals(
                listOf(
                    listOf("2024", "2", "final"), listOf("2024", "3", "final"),
                    listOf("2025", "1", "final"), listOf("2025", "2", "final"),
                    listOf("2025", "3", "baseline"), listOf("2025", "3", "final"),
                ),
                db.query("SELECT DISTINCT season, week, stage FROM player_week_projection ORDER BY season, week, stage"),
            )
        }
    }

    @Test
    fun `rest of season sums the remaining games and skips byes`() {
        league("a.db").use { db ->
            run(db)
            // BBB is on bye in week 4: its rest of season is week 3 alone, not week 3 plus a zero.
            assertEquals(targets(db, "WR_B", 3), rosTargets(db, "WR_B"), 1e-9)
            assertTrue(rosTargets(db, "WR_A") > 1.5 * targets(db, "WR_A", 3))
            assertEquals(
                listOf(listOf("2")),
                db.query("SELECT DISTINCT as_of_week FROM player_ros_projection"),
            )
        }
    }

    @Test
    fun `a team on bye in the upcoming week keeps its rest of season`() {
        league("a.db", playedThrough = 3).use { db ->
            db.game(2025, 5, "BBB", "DDD", played = false, spread = null, total = null, homeCoach = "Coach BBB", awayCoach = "Coach DDD")
            run(db)
            // Week 4 is next and BBB is on bye: no week-4 rows, but week 5 still counts.
            assertEquals(
                emptyList<List<String?>>(),
                db.query("SELECT metric_id FROM player_week_projection WHERE player_id = 'WR_B' AND season = 2025 AND week = 4"),
            )
            val ros = db.query(
                "SELECT mean FROM player_ros_projection WHERE player_id = 'WR_B' AND season = 2025 AND as_of_week = 3 AND metric_id = 'targets'",
            )
            assertEquals(1, ros.size)
            assertTrue(ros.single()[0]!!.toDouble() > 0.0)
        }
    }

    @Test
    fun `a later week's stats never change an earlier week's projection`() {
        val before = league("a.db").use { db -> run(db); projections(db) }
        val after = league("b.db", wrA2025Week2Targets = 20.0).use { db -> run(db); projections(db) }
        val throughWeek2 = { row: List<String?> -> order(row[1]!!.toInt(), row[2]!!.toInt()) <= order(2025, 2) }

        assertEquals(before.filter(throughWeek2), after.filter(throughWeek2))
        val wrAWeek3 = { row: List<String?> -> row[0] == "WR_A" && row[1] == "2025" && row[2] == "3" }
        assertNotEquals(before.filter(wrAWeek3), after.filter(wrAWeek3))
    }

    @Test
    fun `every stored value is finite and every mean positive`() {
        league("a.db").use { db ->
            run(db)
            val values = db.query("SELECT mean, variance FROM player_week_projection") +
                db.query("SELECT mean, variance FROM player_ros_projection") +
                db.query("SELECT log_multiplier, 0 FROM player_week_projection_factor")
            assertTrue(values.isNotEmpty())
            for (row in values) for (v in row) assertTrue(v!!.toDouble().isFinite(), "$row")
            assertTrue(db.query("SELECT mean FROM player_week_projection").all { it[0]!!.toDouble() > 0.0 })
        }
    }

    @Test
    fun `off-season there is no upcoming week and no rest of season`() {
        league("a.db", allPlayed = true).use { db ->
            val report = run(db)
            assertEquals(FORECAST_OK, report.status)
            assertEquals(emptyMap<Int, Int>(), report.upcoming)
            assertEquals(emptyList<List<String?>>(), db.query("SELECT key FROM schema_meta WHERE key LIKE 'forecast_week:%'"))
            assertEquals(listOf(listOf("0")), db.query("SELECT COUNT(*) FROM player_ros_projection"))
            assertEquals(listOf(listOf("final")), db.query("SELECT DISTINCT stage FROM player_week_projection WHERE season = 2025 AND week = 4"))
        }
    }

    @Test
    fun `with no game played yet there is nothing to project from`() {
        TestDb(File(dir, "a.db")).use { db ->
            db.player("WR_A", "WR", "AAA")
            db.game(2025, 1, "AAA", "BBB", played = false, spread = 3.0, total = 44.0)

            val report = run(db)

            assertEquals("no games to project from yet", report.status)
            assertEquals(0L, report.rows)
            assertEquals(listOf(listOf("no games to project from yet")), db.query("SELECT value FROM schema_meta WHERE key = 'forecast_status'"))
        }
    }

    @Test
    fun `without a schedule there are no projections`() {
        TestDb(File(dir, "a.db")).use { db ->
            db.player("WR_A", "WR", "AAA")
            db.week("WR_A", 2025, 1, "AAA", "targets" to 5.0)
            assertEquals("no schedule", run(db).status)
            assertEquals(listOf(listOf("0")), db.query("SELECT COUNT(*) FROM player_week_projection"))
        }
    }

    @Test
    fun `a copied season comes from the previous database, and later seasons don't change`() {
        val previous = TestDb(File(dir, "previous.db")).apply {
            exec("INSERT INTO player_week_projection VALUES ('WR_A', 2024, 2, 'targets', 'final', 99.0, 1.0)")
            close()
        }
        val normal = league("normal.db").use { db -> run(db); projections(db) }

        league("copy.db").use { db ->
            val report = run(db, SeasonCopy(previous.file, setOf(2024)))
            val rows = projections(db)

            assertEquals(listOf(listOf("WR_A", "2024", "2", "targets", "final", "99.0", "1.0")), rows.filter { it[1] == "2024" })
            assertEquals(normal.filter { it[1] == "2025" }, rows.filter { it[1] == "2025" })
            assertEquals(3, report.weeks) // 2025 weeks 1-3; 2024 was copied and week 4 is rest of season
        }
    }

    @Test
    fun `a player who changed teams is projected with his new team`() {
        league("a.db").use { db ->
            db.player("WR_T", "WR", "CCC")
            for (week in 1..3) db.week("WR_T", 2024, week, "AAA", "targets" to 5.0, "receptions" to 3.0, "receiving_yards" to 40.0)

            run(db)

            // CCC's game has no line, so a CCC player has no game-script factor; an AAA player would.
            assertEquals(listOf("matchup"), factors(db, "WR_T"))
            // CCC plays BBB in week 3; his old team AAA plays DDD.
            assertEquals(
                "vs BBB: too early to rate defenses",
                db.query("SELECT note FROM player_week_projection_factor WHERE player_id = 'WR_T' AND season = 2025 AND week = 3").single()[0],
            )
        }
    }

    @Test
    fun `only the starting QB is projected to pass`() {
        league("a.db").use { db ->
            db.player("QB2_A", "QB", "AAA")
            db.week("QB2_A", 2024, 1, "AAA", "attempts" to 3.0, "completions" to 2.0, "passing_yards" to 15.0)

            run(db)

            assertEquals(
                listOf(listOf("QB_A")),
                db.query(
                    "SELECT player_id FROM player_week_projection WHERE season = 2025 AND week = 3 AND stage = 'final' " +
                        "AND metric_id = 'attempts' AND player_id IN ('QB_A', 'QB2_A')",
                ),
            )
        }
    }

    @Test
    fun `a team's players split exactly its targets and carries`() {
        league("a.db").use { db ->
            run(db)
            // AAA is team index 0, so playWeek's k is 1 + 0.05 x week; its 2025 week 2 WR had 9 targets.
            val weeks = listOf(2024 to 1, 2024 to 2, 2024 to 3, 2025 to 1, 2025 to 2)
            val targets = weeks.map { (s, w) -> 4.0 + if (s == 2025 && w == 2) 9.0 else 9 * (1.0 + 0.05 * w) }
            val carries = weeks.map { (_, w) -> 3.0 + 18 * (1.0 + 0.05 * w) }
            fun baseline(metric: String) = db.query(
                "SELECT SUM(mean) FROM player_week_projection WHERE season = 2025 AND week = 3 AND stage = 'baseline' " +
                    "AND metric_id = '$metric' AND player_id IN ('QB_A', 'RB_A', 'WR_A')",
            ).single()[0]!!.toDouble()

            assertEquals(ewma(targets, 4.0)!!, baseline("targets"), 1e-6)
            assertEquals(ewma(carries, 4.0)!!, baseline("carries"), 1e-6)
        }
    }

    @Test
    fun `a player who hasn't played for his team lately isn't projected`() {
        league("a.db").use { db ->
            db.player("WR_X", "WR", "AAA")
            for (week in 1..3) db.week("WR_X", 2024, week, "AAA", "targets" to 3.0, "receptions" to 2.0, "receiving_yards" to 20.0)

            run(db)

            assertEquals(
                listOf(listOf("0")),
                db.query("SELECT COUNT(*) FROM player_week_projection WHERE player_id = 'WR_X' AND season = 2025 AND week = 3"),
            )
        }
    }

    @Test
    fun `props blend into the upcoming week as a market factor, and into rest of season`() {
        league("a.db").use { plain ->
            league("b.db").use { priced ->
                assertNull(run(plain).props)
                val report = run(priced, props = wrAProps)

                assertEquals(PropsOutcome(blended = 1, unmatched = 0), report.props)
                val before = finalMean(plain, "WR_A", 3, "receiving_yards")
                val after = finalMean(priced, "WR_A", 3, "receiving_yards")
                assertTrue(after > before, "a 120.5-yard line pulls up a ${"%.1f".format(before)}-yard projection: $after")
                assertTrue(finalMean(priced, "WR_A", 3, "receiving_tds") > finalMean(plain, "WR_A", 3, "receiving_tds"))
                // The baseline stage is the model's alone.
                assertEquals(
                    plain.query("SELECT metric_id, mean FROM player_week_projection WHERE player_id = 'WR_A' AND week = 3 AND stage = 'baseline' ORDER BY 1"),
                    priced.query("SELECT metric_id, mean FROM player_week_projection WHERE player_id = 'WR_A' AND week = 3 AND stage = 'baseline' ORDER BY 1"),
                )
                assertEquals(listOf("game_script", "market", "matchup"), factors(priced, "WR_A"))
                assertEquals(
                    "Props: 120.5 rec yds, TD 62%",
                    priced.query("SELECT note FROM player_week_projection_factor WHERE player_id = 'WR_A' AND season = 2025 AND week = 3 AND factor = 'market'").single()[0],
                )
                // Rest of season carries the blended week 3 and an unchanged week 4.
                assertEquals(after - before, rosMean(priced, "WR_A", "receiving_yards") - rosMean(plain, "WR_A", "receiving_yards"), 1e-9)
                // Nobody else and no past week moved.
                val others = "SELECT player_id, season, week, metric_id, stage, mean FROM player_week_projection WHERE NOT (player_id = 'WR_A' AND week = 3) ORDER BY 1, 2, 3, 4, 5"
                assertEquals(plain.query(others), priced.query(others))
            }
        }
    }

    @Test
    fun `props for nobody projected, or for a game that isn't this week's, are counted and dropped`() {
        league("a.db").use { plain ->
            league("b.db").use { priced ->
                run(plain)
                val props = PropsSnapshot(
                    listOf(
                        PropEvent("AAA", "DDD", listOf(PropQuote("dk", "player_receptions", "Nobody Here", 4.5, 1.9, 1.9))),
                        PropEvent("AAA", "BBB", listOf(PropQuote("dk", "player_receptions", "Player WR_B", 4.5, 1.9, 1.9))),
                    ),
                )

                val report = run(priced, props = props)

                assertEquals(PropsOutcome(blended = 0, unmatched = 2), report.props)
                assertEquals(projections(plain), projections(priced))
                assertEquals(listOf("game_script", "matchup"), factors(priced, "WR_A"))
            }
        }
    }

    @Test
    fun `props with no upcoming week are all unmatched`() {
        league("a.db", allPlayed = true).use { db ->
            assertEquals(PropsOutcome(blended = 0, unmatched = 1), run(db, props = wrAProps).props)
        }
    }
}
