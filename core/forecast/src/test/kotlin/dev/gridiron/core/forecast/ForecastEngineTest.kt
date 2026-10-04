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
    private fun league(
        name: String,
        allPlayed: Boolean = false,
        wrA2025Week2Targets: Double = 9.0,
        playedThrough: Int = 2,
        units: Boolean = false,
        dstA2025Week2Sacks: Double = 2.0,
    ): TestDb {
        val db = TestDb(File(dir, name))
        for (team in teams) {
            val letter = team.first()
            db.player("QB_$letter", "QB", team)
            db.player("RB_$letter", "RB", team)
            db.player("WR_$letter", "WR", team)
            if (units) {
                db.player("K_$letter", "K", team)
                db.player("DST_$team", "DST", team)
            }
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
                        if (units) {
                            // TestDb's played games end 21-17: the home defense allowed 17, the away one 21.
                            unitWeek(db, home, season, week, allowed = 17.0, dstA2025Week2Sacks)
                            unitWeek(db, away, season, week, allowed = 21.0, dstA2025Week2Sacks)
                        }
                    }
                }
            }
        }
        db.game(2025, 4, "AAA", "CCC", played = allPlayed, spread = -1.0, total = 41.0, homeCoach = "Coach AAA", awayCoach = "Coach CCC")
        if (allPlayed) {
            playWeek(db, "AAA", 2025, 4, wrA2025Week2Targets)
            playWeek(db, "CCC", 2025, 4, wrA2025Week2Targets)
            if (units) {
                unitWeek(db, "AAA", 2025, 4, allowed = 17.0, dstA2025Week2Sacks)
                unitWeek(db, "CCC", 2025, 4, allowed = 21.0, dstA2025Week2Sacks)
            }
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

    private fun unitWeek(db: TestDb, team: String, season: Int, week: Int, allowed: Double, dstA2025Week2Sacks: Double) {
        val k = 1.0 + 0.1 * teams.indexOf(team)
        db.week(
            "K_${team.first()}", season, week, team,
            "fg_att_0_39" to 1.0, "fg_made_0_39" to 1.0, "fg_att_40_49" to k, "fg_made_40_49" to 1.0,
            "xp_att" to 2.0 * k, "xp_made" to 2.0 * k,
        )
        val sacks = if (team == "AAA" && season == 2025 && week == 2) dstA2025Week2Sacks else 2.0 * k
        db.week(
            "DST_$team", season, week, team,
            "dst_sacks" to sacks, "dst_interceptions" to 1.0, "points_allowed" to allowed, "yards_allowed" to allowed * 15.0,
        )
    }

    private fun kickers(db: TestDb, season: Int, week: Int): List<String?> = db.query(
        "SELECT DISTINCT player_id FROM player_week_projection WHERE season = $season AND week = $week AND player_id LIKE 'K%' ORDER BY 1",
    ).map { it[0] }

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

    private fun baselineTargets(db: TestDb, player: String): Double = db.query(
        "SELECT mean FROM player_week_projection WHERE player_id = '$player' AND season = 2025 AND week = 3 AND stage = 'baseline' AND metric_id = 'targets'",
    ).single()[0]!!.toDouble()

    private fun weekRows(db: TestDb, player: String, season: Int, week: Int): Int = db.query(
        "SELECT COUNT(*) FROM player_week_projection WHERE player_id = '$player' AND season = $season AND week = $week",
    ).single()[0]!!.toInt()

    @Test
    fun `a player listed Out has no row that week and his team takes his targets, but keeps his later weeks`() {
        var baseWr = 0.0
        var baseRb = 0.0
        var baseRbWeek3 = 0.0
        var baseRbRos = 0.0
        var baseWrRos = 0.0
        league("base.db").use { db ->
            run(db)
            baseWr = baselineTargets(db, "WR_A")
            baseRb = baselineTargets(db, "RB_A")
            baseRbWeek3 = targets(db, "RB_A", 3)
            baseRbRos = rosTargets(db, "RB_A")
            baseWrRos = rosTargets(db, "WR_A")
        }
        league("hurt.db").use { db ->
            db.injury("WR_A", 2025, 3, "Out")
            run(db)

            assertEquals(0, weekRows(db, "WR_A", 2025, 3))
            // AAA's two target-getters split all its targets; with the WR out, the RB has them all, except the
            // WR's own season form (layer 2b), which stays his: 9.45 and 9 targets in 2025's two games.
            assertEquals(baseWr + baseRb - K.SEASON_FORM_WEIGHT * (9 * 1.05 + 9.0) / 2, baselineTargets(db, "RB_A"), 1e-9)
            assertTrue(targets(db, "RB_A", 3) > baseRbWeek3)
            // Week 4 doesn't know about the injury: the RB's rest of season past this week matches a healthy roster's.
            assertEquals(baseRbRos - baseRbWeek3, rosTargets(db, "RB_A") - targets(db, "RB_A", 3), 1e-9)
            // The WR keeps week 4 and loses only week 3.
            val wrRos = rosTargets(db, "WR_A")
            assertTrue(wrRos > 0.0 && wrRos < baseWrRos)
        }
    }

    @Test
    fun `a Questionable player is projected as usual`() {
        var base = 0.0
        league("base.db").use { db ->
            run(db)
            base = baselineTargets(db, "WR_A")
        }
        league("q.db").use { db ->
            db.injury("WR_A", 2025, 3, "Questionable")
            run(db)
            assertEquals(base, baselineTargets(db, "WR_A"), 1e-12)
        }
    }

    @Test
    fun `a past week's Out player is missing from that week only`() {
        league("past.db").use { db ->
            db.injury("WR_A", 2025, 2, "Doubtful")
            run(db)
            assertEquals(0, weekRows(db, "WR_A", 2025, 2))
            assertTrue(weekRows(db, "WR_A", 2025, 1) > 0)
            assertTrue(weekRows(db, "WR_A", 2025, 3) > 0)
        }
    }

    @Test
    fun `an Out starting QB hands the passing to the next QB, and with no other QB nobody passes`() {
        league("qb.db").use { db ->
            db.player("QB2_A", "QB", "AAA")
            db.week("QB2_A", 2025, 2, "AAA", "attempts" to 5.0, "completions" to 3.0, "passing_yards" to 30.0)
            db.injury("QB_A", 2025, 3, "Out")
            run(db)
            assertEquals(0, weekRows(db, "QB_A", 2025, 3))
            assertTrue(weekRows(db, "QB2_A", 2025, 3) > 0)
        }
        league("only.db").use { db ->
            db.injury("QB_A", 2025, 3, "Out")
            run(db)
            assertEquals(0, weekRows(db, "QB_A", 2025, 3))
            assertTrue(weekRows(db, "WR_A", 2025, 3) > 0)
        }
    }

    @Test
    fun `from the upcoming week on, the QB ESPN projects most starts, unless nflverse lists one ESPN doesn't doubt`() {
        fun starts(espnBackup: Double?, listed: String? = null, espnStarter: Double? = null): Pair<Boolean, Boolean> =
            league("qbe$espnBackup-$listed-$espnStarter.db").use { db ->
                db.player("QB2_A", "QB", "AAA")
                db.week("QB2_A", 2025, 2, "AAA", "attempts" to 5.0, "completions" to 3.0, "passing_yards" to 30.0)
                // Passing yards at 0.04 a yard: 25 per point.
                espnBackup?.let { db.espn("QB2_A", 2025, 3, "passing_yards" to it * 25) }
                espnStarter?.let { db.espn("QB_A", 2025, 3, "passing_yards" to it * 25) }
                listed?.let { db.exec("UPDATE game SET home_qb_id = ? WHERE season = 2025 AND week = 3 AND home_team = 'AAA'", it) }
                run(db)
                (weekRows(db, "QB_A", 2025, 3) > 0) to (weekRows(db, "QB2_A", 2025, 3) > 0)
            }
        // No listing: the usual starter, unless ESPN projects the other QB for enough points.
        assertEquals(true to false, starts(espnBackup = null))
        assertEquals(true to false, starts(espnBackup = K.STARTER_ESPN_POINTS - 1))
        assertEquals(false to true, starts(espnBackup = K.STARTER_ESPN_POINTS + 4))
        // Listed: he starts unless ESPN all but rules him out.
        assertEquals(false to true, starts(espnBackup = 12.0, listed = "QB_A"))
        assertEquals(true to false, starts(espnBackup = 12.0, listed = "QB_A", espnStarter = K.STARTER_DOUBT_ESPN_POINTS + 1))
        assertEquals(false to true, starts(espnBackup = null, listed = "QB2_A"))
    }

    @Test
    fun `a starter out this week keeps his rest of season even though ESPN projects his backup`() {
        league("qbout.db").use { db ->
            db.player("QB2_A", "QB", "AAA")
            db.week("QB2_A", 2025, 2, "AAA", "attempts" to 5.0, "completions" to 3.0, "passing_yards" to 30.0)
            db.espn("QB2_A", 2025, 3, "passing_yards" to 300.0)
            db.injury("QB_A", 2025, 3, "Out")
            run(db)
            assertEquals(0, weekRows(db, "QB_A", 2025, 3))
            assertTrue(weekRows(db, "QB2_A", 2025, 3) > 0)
            // Week 4 (rest of season) goes back to the usual starter.
            assertTrue(rosMean(db, "QB_A", "passing_yards") > 100.0)
            // The backup's rest of season is the week he starts, as ESPN projects it.
            assertEquals(300.0, rosMean(db, "QB2_A", "passing_yards"), 1e-9)
        }
    }

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
    fun `rest of season week by week sums to its total, players, kickers and defenses alike, with no bye row`() {
        league("ros-weeks.db", units = true).use { db ->
            run(db)
            val summed = db.query(
                """
                SELECT r.player_id, r.metric_id, r.mean, r.variance, SUM(w.mean), SUM(w.variance)
                FROM player_ros_projection r
                JOIN player_ros_week w ON w.player_id = r.player_id AND w.season = r.season
                  AND w.as_of_week = r.as_of_week AND w.metric_id = r.metric_id
                GROUP BY r.player_id, r.metric_id
                """.trimIndent(),
            )
            assertEquals(db.query("SELECT COUNT(*) FROM player_ros_projection").single()[0]!!.toInt(), summed.size)
            for (row in summed) {
                assertEquals(row[2]!!.toDouble(), row[4]!!.toDouble(), 1e-9, "${row[0]} ${row[1]} mean")
                assertEquals(row[3]!!.toDouble(), row[5]!!.toDouble(), 1e-9, "${row[0]} ${row[1]} variance")
            }
            // BBB is on bye in week 4: week 3 only; AAA plays both.
            assertEquals(listOf(listOf("3")), db.query("SELECT DISTINCT week FROM player_ros_week WHERE player_id = 'WR_B'"))
            assertEquals(listOf(listOf("3"), listOf("4")), db.query("SELECT DISTINCT week FROM player_ros_week WHERE player_id = 'DST_AAA' ORDER BY week"))
            assertEquals(finalMean(db, "WR_A", 3, "targets"), db.query(
                "SELECT mean FROM player_ros_week WHERE player_id = 'WR_A' AND week = 3 AND metric_id = 'targets'",
            ).single()[0]!!.toDouble(), 1e-9)
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
    fun `a team's players split exactly its targets and carries, then each moves toward his season form`() {
        league("a.db").use { db ->
            run(db)
            // AAA is team index 0, so playWeek's k is 1 + 0.05 x week; its 2025 week 2 WR had 9 targets.
            val weeks = listOf(2024 to 1, 2024 to 2, 2024 to 3, 2025 to 1, 2025 to 2)
            val targets = weeks.map { (s, w) -> 4.0 + if (s == 2025 && w == 2) 9.0 else 9 * (1.0 + 0.05 * w) }
            val carries = weeks.map { (_, w) -> 3.0 + 18 * (1.0 + 0.05 * w) }
            fun baseline(metric: String, ids: String = "'QB_A', 'RB_A', 'WR_A'") = db.query(
                "SELECT SUM(mean) FROM player_week_projection WHERE season = 2025 AND week = 3 AND stage = 'baseline' " +
                    "AND metric_id = '$metric' AND player_id IN ($ids)",
            ).single()[0]!!.toDouble()

            // Layer 2b pulls the RB and WR toward their own 2025 games, so the team's sum moves toward its 2025
            // average. The QB has no targets; his 3 carries a game also move a little toward the league's typical
            // starter (layer 2c), so carries match to within that.
            val w = K.SEASON_FORM_WEIGHT
            assertEquals((1 - w) * ewma(targets, 4.0)!! + w * targets.takeLast(2).average(), baseline("targets"), 1e-6)
            val qb = baseline("carries", "'QB_A'")
            assertEquals(qb + (1 - w) * (ewma(carries, 4.0)!! - qb) + w * (carries.takeLast(2).average() - 3.0), baseline("carries"), 0.05)
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
    fun `a player back from a long absence is projected once ESPN projects him for enough points`() {
        fun projected(espnYards: Double?): Int = league("back$espnYards.db").use { db ->
            db.player("WR_X", "WR", "AAA")
            for (week in 1..3) db.week("WR_X", 2024, week, "AAA", "targets" to 6.0, "receptions" to 4.0, "receiving_yards" to 50.0)
            db.espn("RB_A", 2025, 3, "carries" to 15.0, "rushing_yards" to 70.0)
            if (espnYards != null) db.espn("WR_X", 2025, 3, "receiving_yards" to espnYards)
            run(db)
            weekRows(db, "WR_X", 2025, 3)
        }
        assertTrue(projected(K.RETURN_MIN_ESPN_POINTS * 10) > 0)
        assertEquals(0, projected(K.RETURN_MIN_ESPN_POINTS * 10 - 1))
        assertEquals(0, projected(null))
    }

    @Test
    fun `a player out for now keeps rest of season from ESPN's projections for the games it expects him back`() {
        league("stash.db").use { db ->
            db.player("WR_X", "WR", "AAA")
            for (week in 1..3) db.week("WR_X", 2024, week, "AAA", "targets" to 6.0, "receptions" to 4.0, "receiving_yards" to 50.0)
            // Not back for the upcoming week 3 (under the bar); back for week 4.
            db.espn("WR_X", 2025, 3, "receiving_yards" to 10.0)
            db.espn("WR_X", 2025, 4, "receptions" to 5.0, "receiving_yards" to 80.0)
            run(db)
            assertEquals(0, weekRows(db, "WR_X", 2025, 3))
            assertEquals(80.0, rosMean(db, "WR_X", "receiving_yards"), 1e-9)
            assertEquals(5.0, rosMean(db, "WR_X", "receptions"), 1e-9)
        }
        league("nostash.db").use { db ->
            db.player("WR_X", "WR", "AAA")
            for (week in 1..3) db.week("WR_X", 2024, week, "AAA", "targets" to 6.0, "receptions" to 4.0, "receiving_yards" to 50.0)
            run(db)
            assertEquals(listOf(listOf("0")), db.query("SELECT COUNT(*) FROM player_ros_projection WHERE player_id = 'WR_X'"))
        }
    }

    @Test
    fun `a returning player nflverse lists Out stays out whatever ESPN projects`() {
        league("out.db").use { db ->
            db.player("WR_X", "WR", "AAA")
            for (week in 1..3) db.week("WR_X", 2024, week, "AAA", "targets" to 6.0, "receptions" to 4.0, "receiving_yards" to 50.0)
            db.espn("WR_X", 2025, 3, "receiving_yards" to 80.0)
            db.injury("WR_X", 2025, 3, "Out")
            run(db)
            assertEquals(0, weekRows(db, "WR_X", 2025, 3))
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
    fun `a matched player whose props can't be used is neither blended nor unmatched`() {
        league("a.db").use { db ->
            val oneSided = PropsSnapshot(listOf(PropEvent("AAA", "DDD", listOf(PropQuote("dk", "player_receptions", "Player WR_A", 5.5, 1.9, null)))))
            assertEquals(PropsOutcome(blended = 0, unmatched = 0), run(db, props = oneSided).props)
        }
    }

    @Test
    fun `props with no upcoming week are all unmatched`() {
        league("a.db", allPlayed = true).use { db ->
            assertEquals(PropsOutcome(blended = 0, unmatched = 1), run(db, props = wrAProps).props)
        }
    }

    @Test
    fun `kickers and team defenses get the upcoming week's stages and factors, and past weeks' final`() {
        league("units.db", units = true).use { db ->
            run(db)
            fun stages(id: String, week: Int) = db.query(
                "SELECT DISTINCT stage FROM player_week_projection WHERE player_id = '$id' AND season = 2025 AND week = $week ORDER BY stage",
            ).map { it[0] }
            for (id in listOf("K_A", "K_B", "DST_AAA", "DST_BBB")) {
                assertEquals(listOf("baseline", "final"), stages(id, 3), id)
                assertEquals(listOf("final"), stages(id, 2), id)
            }
            // AAA-DDD has a line: AAA's kicker follows it, and AAA's D/ST faces DDD's implied points.
            assertEquals(listOf("game_script"), factors(db, "K_A"))
            assertEquals(listOf("game_script", "matchup"), factors(db, "DST_AAA"))
            // BBB-CCC has none: no game script, so the final projection is the matchup's.
            assertEquals(emptyList<String?>(), factors(db, "K_B"))
            assertEquals(listOf("matchup"), factors(db, "DST_BBB"))
            // The matchup note names what the factor uses: the opponent's scoring as well as its sacks and turnovers.
            val note = db.query("SELECT note FROM player_week_projection_factor WHERE player_id = 'DST_BBB' AND season = 2025 AND week = 3 AND factor = 'matchup'").single()[0]!!
            assertTrue(Regex("""vs CCC: scores \d+\.\d pts, \d+ yards, gives up \d+\.\d sacks, \d+\.\d turnovers a game""").matches(note), note)
            assertEquals(
                db.query("SELECT metric_id, mean FROM player_week_projection WHERE player_id = 'K_B' AND season = 2025 AND week = 3 AND stage = 'baseline' ORDER BY 1"),
                db.query("SELECT metric_id, mean FROM player_week_projection WHERE player_id = 'K_B' AND season = 2025 AND week = 3 AND stage = 'final' ORDER BY 1"),
            )
            val stored = db.query("SELECT mean, variance FROM player_week_projection WHERE player_id LIKE 'K%' OR player_id LIKE 'DST%'")
            assertTrue(stored.isNotEmpty())
            for (row in stored) {
                assertTrue(row[0]!!.toDouble().isFinite() && row[0]!!.toDouble() > 0.0, "$row")
                assertTrue(row[1]!!.toDouble().isFinite() && row[1]!!.toDouble() >= 0.0, "$row")
            }
        }
    }

    @Test
    fun `a team defense's points allowed carry the measured spread, one game at a time`() {
        league("allowed.db", units = true).use { db ->
            run(db)
            val rows = db.query(
                "SELECT player_id, week, stage, mean, variance FROM player_week_projection " +
                    "WHERE player_id LIKE 'DST%' AND metric_id = 'points_allowed'",
            )
            assertTrue(rows.size >= 8, "$rows")
            // The synthetic league has too few lined games to measure the spread: the default.
            for (row in rows) {
                assertTrue(row[3]!!.toDouble() in 5.0..40.0, "$row")
                assertEquals(K.PA_SD_DEFAULT * K.PA_SD_DEFAULT, row[4]!!.toDouble(), 1e-9, "$row")
            }
            val games = db.query("SELECT DISTINCT mean, variance FROM player_week_projection WHERE player_id LIKE 'DST%' AND metric_id = 'g'")
            assertEquals(listOf(listOf("1.0", "0.0")), games)
            assertEquals(emptyList<List<String?>>(), db.query("SELECT metric_id FROM player_week_projection WHERE metric_id LIKE 'pa\\_%' ESCAPE '\\'"))
        }
    }

    @Test
    fun `a team defense's yards allowed are projected as their own stat with a spread that scales with the mean`() {
        league("yards.db", units = true).use { db ->
            run(db)
            val rows = db.query(
                "SELECT player_id, week, stage, mean, variance FROM player_week_projection " +
                    "WHERE player_id LIKE 'DST%' AND metric_id = 'yards_allowed'",
            )
            assertTrue(rows.size >= 8, "$rows")
            for (row in rows) {
                val mean = row[3]!!.toDouble()
                assertTrue(mean in 200.0..450.0, "$row")
                assertEquals(K.DST_YA_CV * mean * (K.DST_YA_CV * mean), row[4]!!.toDouble(), 1e-6, "$row")
            }
            // Rest of season sums the remaining games, like points allowed.
            assertTrue(rosMean(db, "DST_AAA", "yards_allowed") > 1.5 * finalMean(db, "DST_AAA", 3, "yards_allowed"))
            // A kicker has none.
            assertEquals(emptyList<List<String?>>(), db.query("SELECT 1 FROM player_week_projection WHERE player_id LIKE 'K%' AND metric_id = 'yards_allowed'"))
        }
    }

    @Test
    fun `every team's defense is projected however badly the matchup scores under the preset tiers`() {
        league("bad-defense.db", units = true).use { db ->
            // Every defense gives up 50 points and 750 yards: the tiers score -5 and -7, more than its takeaways earn.
            db.exec("UPDATE player_week_stat SET value = 50 WHERE metric_id = 'points_allowed'")
            db.exec("UPDATE player_week_stat SET value = 750 WHERE metric_id = 'yards_allowed'")
            run(db)
            for (team in listOf("AAA", "BBB", "CCC", "DDD")) {
                assertEquals(
                    listOf(listOf("baseline"), listOf("final")),
                    db.query("SELECT DISTINCT stage FROM player_week_projection WHERE player_id = 'DST_$team' AND season = 2025 AND week = 3 AND metric_id = 'yards_allowed' ORDER BY 1"),
                    team,
                )
                assertEquals(
                    listOf(listOf("final")),
                    db.query("SELECT DISTINCT stage FROM player_week_projection WHERE player_id = 'DST_$team' AND season = 2025 AND week = 2 AND metric_id = 'yards_allowed'"),
                    team,
                )
            }
            // Rest of season counts every remaining game: AAA plays weeks 3 and 4.
            assertEquals(2.0, rosMean(db, "DST_AAA", "g"), 1e-9)
        }
    }

    @Test
    fun `a defense with no yards history is projected without yards`() {
        league("no-yards.db", units = true).use { db ->
            db.exec("DELETE FROM player_week_stat WHERE metric_id = 'yards_allowed'")
            run(db)
            assertEquals(emptyList<List<String?>>(), db.query("SELECT 1 FROM player_week_projection WHERE metric_id = 'yards_allowed'"))
            assertTrue(finalMean(db, "DST_AAA", 3, "points_allowed") > 0.0)
        }
    }

    @Test
    fun `rest of season for kickers and defenses sums the remaining games and skips byes`() {
        league("unit-ros.db", units = true).use { db ->
            run(db)
            // BBB is on bye in week 4: its rest of season is week 3 alone.
            assertEquals(finalMean(db, "DST_BBB", 3, "dst_sacks"), rosMean(db, "DST_BBB", "dst_sacks"), 1e-9)
            assertEquals(finalMean(db, "K_B", 3, "xp_made"), rosMean(db, "K_B", "xp_made"), 1e-9)
            // AAA plays weeks 3 and 4: two games, whose points allowed sum.
            assertTrue(rosMean(db, "DST_AAA", "dst_sacks") > 1.5 * finalMean(db, "DST_AAA", 3, "dst_sacks"))
            assertEquals(2.0, rosMean(db, "DST_AAA", "g"), 1e-9)
            assertEquals(1.0, rosMean(db, "DST_BBB", "g"), 1e-9)
            assertTrue(rosMean(db, "DST_AAA", "points_allowed") > 1.5 * finalMean(db, "DST_AAA", 3, "points_allowed"))
        }
    }

    @Test
    fun `a later week's stats never change an earlier kicker's or defense's projection`() {
        val before = league("u1.db", units = true).use { db -> run(db); projections(db) }
        val after = league("u2.db", units = true, dstA2025Week2Sacks = 9.0).use { db -> run(db); projections(db) }
        val throughWeek2 = { row: List<String?> -> order(row[1]!!.toInt(), row[2]!!.toInt()) <= order(2025, 2) }

        assertEquals(before.filter(throughWeek2), after.filter(throughWeek2))
        val dstAWeek3 = { row: List<String?> -> row[0] == "DST_AAA" && row[1] == "2025" && row[2] == "3" }
        assertNotEquals(before.filter(dstAWeek3), after.filter(dstAWeek3))
    }

    @Test
    fun `each team has one kicker, never one kicker for two teams, and a released kicker isn't his old team's`() {
        league("kickers.db", units = true).use { db ->
            // AAA signed K_A2 for 2025; nflverse now lists K_A on BBB, where he hasn't kicked.
            db.player("K_A2", "K", "AAA")
            db.exec("UPDATE player SET team = 'BBB' WHERE player_id = 'K_A'")
            db.exec("UPDATE player_week_stat SET player_id = 'K_A2' WHERE player_id = 'K_A' AND season = 2025")
            run(db)

            // Upcoming: each team's listed kicker with the latest kick. BBB keeps K_B, who kicked for it last week.
            assertEquals(listOf("K_A2", "K_B", "K_C", "K_D"), kickers(db, 2025, 3))
            // Week 2: whoever kicked. Week 1: K_A2 has no kick before it, so AAA's is its last kicker, K_A.
            assertEquals(listOf("K_A2", "K_B", "K_C", "K_D"), kickers(db, 2025, 2))
            assertEquals(listOf("K_A", "K_B", "K_C", "K_D"), kickers(db, 2025, 1))
        }
    }
}
