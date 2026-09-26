package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.db.readMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class IngestPipelineTest {
    @TempDir
    lateinit var dir: File

    private val fetcher = FakeFetcher()
    private val workDir by lazy { File(dir, "work") }
    private val pipeline by lazy {
        IngestPipeline(fetcher, workDir, File(dir, "players.csv.gz"), now = { Instant.parse("2026-09-25T12:00:00Z") })
    }

    private val snapHeader = listOf("game_id", "season", "week", "team", "pfr_player_id", "offense_snaps", "offense_pct")
    private val injuryHeader = listOf("gsis_id", "season", "week", "team", "full_name", "position", "report_status", "report_primary_injury", "practice_status")
    private val expectedHeader = listOf("season", "week", "player_id", "posteam") + EXPECTED_COLUMNS.keys + EXPECTED_ACTUAL_COLUMNS

    private fun servePlayers(wr1Name: String = "Wide Receiver One", version: String = "p1") {
        val csv = Fixtures.csv(
            listOf("gsis_id", "display_name", "position", "latest_team", "pfr_id", "espn_id"),
            listOf(
                mapOf("gsis_id" to "QB1", "display_name" to "Quarter Back", "position" to "QB", "latest_team" to "AAA", "pfr_id" to "pQB1", "espn_id" to "101"),
                mapOf("gsis_id" to "WR1", "display_name" to wr1Name, "position" to "WR", "latest_team" to "AAA", "pfr_id" to "pWR1", "espn_id" to "102"),
                mapOf("gsis_id" to "WR2", "display_name" to "Receiver Two", "position" to "WR", "latest_team" to "AAA", "pfr_id" to "pWR2", "espn_id" to "103"),
                mapOf("gsis_id" to "RB1", "display_name" to "Running Back", "position" to "RB", "latest_team" to "AAA", "pfr_id" to "pRB1", "espn_id" to "104"),
                mapOf("gsis_id" to "OLD1", "display_name" to "Retired Guy", "position" to "WR", "latest_team" to "XXX", "espn_id" to "105"),
            ),
        )
        fetcher.serve(Sources.url(Input.PLAYERS), Fixtures.gzip(csv), version)
    }

    private fun ep(season: Int, id: String, vararg values: Pair<String, Any?>): Map<String, Any?> =
        mapOf("season" to season, "week" to 1, "player_id" to id, "posteam" to "AAA") +
            (EXPECTED_COLUMNS.keys + EXPECTED_ACTUAL_COLUMNS).associateWith { 0.0 } + values

    private fun serveSeason(season: Int, version: String = "v1", snapsVersion: String = version, expected: Boolean = true, wr1Receptions: Int = 1) {
        val plays = listOf(
            Fixtures.pbp("season" to season, "receiver_player_id" to "WR1", "passer_player_id" to "QB1", "pass_attempt" to 1,
                "complete_pass" to 1, "air_yards" to 10, "receiving_yards" to 15, "passing_yards" to 15, "yards_gained" to 15),
            Fixtures.pbp("season" to season, "receiver_player_id" to "WR2", "passer_player_id" to "QB1", "pass_attempt" to 1, "air_yards" to 5),
            Fixtures.pbp("season" to season, "play_type" to "run", "rusher_player_id" to "RB1", "rushing_yards" to 4, "yards_gained" to 4),
        )
        fetcher.serve(Sources.url(Input.PBP, season), Fixtures.gzip(Fixtures.pbpCsv(plays)), version)
        fetcher.serve(
            Sources.url(Input.SNAP_COUNTS, season),
            Fixtures.gzip(Fixtures.csv(snapHeader, listOf(
                mapOf("game_id" to "g1", "season" to season, "week" to 1, "team" to "AAA", "pfr_player_id" to "pWR1", "offense_snaps" to 50, "offense_pct" to 0.78),
                mapOf("game_id" to "g1", "season" to season, "week" to 1, "team" to "AAA", "pfr_player_id" to "pRB1", "offense_snaps" to 30, "offense_pct" to 0.47),
                mapOf("game_id" to "g1", "season" to season, "week" to 1, "team" to "AAA", "pfr_player_id" to "pOL1", "offense_snaps" to 64, "offense_pct" to 1.0),
            ))),
            snapsVersion,
        )
        fetcher.serve(
            Sources.url(Input.INJURIES, season),
            Fixtures.gzip(Fixtures.csv(injuryHeader, listOf(mapOf(
                "gsis_id" to "WR2", "season" to season, "week" to 1, "team" to "AAA", "full_name" to "Receiver Two",
                "position" to "WR", "report_status" to "Questionable", "report_primary_injury" to "Hamstring",
                "practice_status" to "Limited Participation in Practice",
            )))),
            version,
        )
        if (expected) {
            val rows = listOf(
                ep(season, "WR1", "receptions" to wr1Receptions, "rec_yards_gained" to 15, "receptions_exp" to 0.8,
                    "rec_yards_gained_exp" to 11.0, "total_fantasy_points" to 2.5, "total_fantasy_points_exp" to 1.9),
                ep(season, "QB1", "pass_completions" to 1, "pass_yards_gained" to 15, "pass_completions_exp" to 0.7,
                    "pass_yards_gained_exp" to 11.0, "total_fantasy_points" to 0.6, "total_fantasy_points_exp" to 0.44),
                ep(season, "RB1", "rush_yards_gained" to 4, "rush_yards_gained_exp" to 3.5, "total_fantasy_points" to 0.4,
                    "total_fantasy_points_exp" to 0.35),
            )
            fetcher.serve(Sources.url(Input.EXPECTED, season), Fixtures.csv(expectedHeader, rows).toByteArray(), version)
        } else {
            fetcher.remove(Sources.url(Input.EXPECTED, season))
        }
    }

    private fun facts(file: File) = query(file, "SELECT player_id, season, week, metric_id, value FROM player_week_stat ORDER BY 1, 2, 3, 4")

    @Test
    fun `a first build downloads everything and writes a validated database`() = runTest {
        servePlayers()
        serveSeason(2024)
        serveSeason(2025)
        val out = File(dir, "stats.db")

        val report = pipeline.build(listOf(2025, 2024), previous = null, out = out)

        assertEquals(listOf(2024, 2025), report.built)
        assertEquals(emptyList<Int>(), report.reused)
        val meta = readMeta(out)!!
        assertEquals("6", meta["schema_version"])
        assertEquals("1", meta["ingest_version"])
        assertEquals("2024,2025", meta["seasons"])
        assertEquals("1", meta["expected_through_week:2025"])
        assertNotNull(meta[Sources.metaKey(Input.PBP, 2025)])
        assertEquals(listOf("QB1", "RB1", "WR1", "WR2"), query(out, "SELECT player_id FROM player ORDER BY 1").map { it[0] })
        assertEquals(listOf(listOf("OLD1")), query(out, "SELECT player_id FROM player_xref WHERE espn_id = '105'"))
        assertEquals(listOf(listOf("0.5")), query(out, "SELECT value FROM player_week_stat WHERE player_id = 'WR1' AND season = 2025 AND metric_id = 'target_share'"))
        assertEquals(listOf(listOf("0.78")), query(out, "SELECT value FROM player_week_stat WHERE player_id = 'WR1' AND season = 2025 AND metric_id = 'snap_share'"))
        assertEquals(listOf(listOf("0.8")), query(out, "SELECT value FROM player_week_stat WHERE player_id = 'WR1' AND season = 2025 AND metric_id = 'x_receptions'"))
        assertEquals(listOf(listOf("Questionable")), query(out, "SELECT status FROM injury_report WHERE season = 2025"))
        assertTrue(report.facts > 0)
        assertEquals(emptyList<String>(), workDir.list()!!.toList())
    }

    @Test
    fun `unchanged seasons are copied, not downloaded or recomputed`() = runTest {
        servePlayers()
        serveSeason(2024)
        serveSeason(2025)
        val first = File(dir, "first.db")
        pipeline.build(listOf(2024, 2025), null, first)
        fetcher.calls.clear()

        val second = File(dir, "second.db")
        val report = pipeline.build(listOf(2024, 2025), first, second)

        assertEquals(listOf(2024, 2025), report.reused)
        assertEquals(emptyList<Int>(), report.built)
        assertEquals(facts(first), facts(second))
        assertTrue(fetcher.calls.all { (_, previous) -> previous != null }, "${fetcher.calls}")
    }

    @Test
    fun `a changed input rebuilds its season and refetches the season's other files in full`() = runTest {
        servePlayers()
        serveSeason(2024)
        serveSeason(2025)
        val first = File(dir, "first.db")
        pipeline.build(listOf(2024, 2025), null, first)
        serveSeason(2025, snapsVersion = "v2")
        fetcher.calls.clear()

        val report = pipeline.build(listOf(2024, 2025), first, File(dir, "second.db"))

        assertEquals(listOf(2025), report.built)
        assertEquals(listOf(2024), report.reused)
        val pbpCalls = fetcher.calls.filter { it.first == Sources.url(Input.PBP, 2025) }.map { it.second }
        assertEquals(listOf(Validators("\"v1\"", null), null), pbpCalls)
    }

    @Test
    fun `an unpublished season is skipped with a note`() = runTest {
        servePlayers()
        serveSeason(2025)
        val report = pipeline.build(listOf(2025, 2026), null, File(dir, "stats.db"))
        assertEquals(listOf(2025), report.built)
        assertEquals(mapOf(2026 to "play-by-play isn't published yet"), report.skipped)
    }

    @Test
    fun `a truncated optional file is left out with a warning, not a failed build`() = runTest {
        // nflverse's snap_counts_2012.csv.gz is a header-only gzip with no trailer.
        servePlayers()
        serveSeason(2025)
        fetcher.serve(Sources.url(Input.SNAP_COUNTS, 2025), Fixtures.gzip("x".repeat(500)).copyOf(12), "v1")
        fetcher.serve(Sources.url(Input.INJURIES, 2025), Fixtures.gzip("x".repeat(500)).copyOf(12), "v1")
        val out = File(dir, "stats.db")

        val report = pipeline.build(listOf(2025), null, out)

        assertEquals(listOf(2025), report.built)
        assertTrue(report.warnings.any { it.startsWith("2025: snap counts") && "unreadable" in it }, "${report.warnings}")
        assertTrue(report.warnings.any { it.startsWith("2025: injury report") && "unreadable" in it }, "${report.warnings}")
        assertEquals(emptyList<List<String>>(), query(out, "SELECT value FROM player_week_stat WHERE metric_id = 'snap_share'"))
    }

    @Test
    fun `a truncated play-by-play file fails the build naming its season`() = runTest {
        servePlayers()
        serveSeason(2025)
        fetcher.serve(Sources.url(Input.PBP, 2025), Fixtures.gzip("x".repeat(500)).copyOf(12), "v1")
        val out = File(dir, "stats.db")

        val e = runCatching { pipeline.build(listOf(2025), null, out) }.exceptionOrNull()

        assertTrue(e?.message.orEmpty().startsWith("2025 play-by-play"), "got $e")
        assertFalse(out.exists())
    }

    @Test
    fun `a built season is kept when its play-by-play briefly disappears`() = runTest {
        servePlayers()
        serveSeason(2025)
        val first = File(dir, "first.db")
        pipeline.build(listOf(2025), null, first)
        fetcher.remove(Sources.url(Input.PBP, 2025))

        val second = File(dir, "second.db")
        val report = pipeline.build(listOf(2025), first, second)

        assertEquals(listOf(2025), report.reused)
        assertEquals(emptyMap<Int, String>(), report.skipped)
        assertEquals(facts(first), facts(second))
        assertTrue(report.warnings.any { it.startsWith("2025:") && "kept" in it }, "${report.warnings}")
        assertEquals(readMeta(first)!![Sources.metaKey(Input.PBP, 2025)], readMeta(second)!![Sources.metaKey(Input.PBP, 2025)])
    }

    @Test
    fun `a season without expected points still builds, at week zero`() = runTest {
        servePlayers()
        serveSeason(2025, expected = false)
        val out = File(dir, "stats.db")
        val report = pipeline.build(listOf(2025), null, out)
        assertEquals(listOf(2025), report.built)
        assertEquals("0", readMeta(out)!!["expected_through_week:2025"])
        assertTrue(report.warnings.any { "expected-points" in it }, "${report.warnings}")
    }

    @Test
    fun `an unreadable or foreign previous database means a full rebuild`() = runTest {
        servePlayers()
        serveSeason(2025)
        val junk = File(dir, "junk.db").apply { writeText("not a database") }
        val report = pipeline.build(listOf(2025), junk, File(dir, "stats.db"))
        assertEquals(listOf(2025), report.built)
        assertTrue(fetcher.calls.all { (_, previous) -> previous == null })
    }

    @Test
    fun `a renamed player shows the new name even in a copied season`() = runTest {
        servePlayers()
        serveSeason(2025)
        val first = File(dir, "first.db")
        pipeline.build(listOf(2025), null, first)
        servePlayers(wr1Name = "Renamed Receiver", version = "p2")

        val second = File(dir, "second.db")
        val report = pipeline.build(listOf(2025), first, second)

        assertEquals(listOf(2025), report.reused)
        assertEquals(listOf(listOf("Renamed Receiver", "renamed receiver")), query(second, "SELECT full_name, search_name FROM player WHERE player_id = 'WR1'"))
    }

    @Test
    fun `a failed validation leaves no output behind`() = runTest {
        servePlayers()
        serveSeason(2025, wr1Receptions = 20) // 19 receptions off: beyond the hard cap
        val out = File(dir, "stats.db")
        val e = runCatching { pipeline.build(listOf(2025), null, out) }.exceptionOrNull()
        assertTrue(e is ValidationException, "got $e")
        assertFalse(out.exists())
    }

    @Test
    fun `a cancelled build leaves no output behind and the previous database untouched`() = runTest {
        servePlayers()
        serveSeason(2025)
        val previous = File(dir, "previous.db")
        pipeline.build(listOf(2025), null, previous)
        val before = previous.readBytes()
        serveSeason(2025, version = "v2")
        val reached = CompletableDeferred<Unit>()
        fetcher.onFetch = { url ->
            if (url == Sources.url(Input.EXPECTED, 2025)) {
                reached.complete(Unit)
                awaitCancellation()
            }
        }
        val out = File(dir, "stats.db")
        val job = launch { pipeline.build(listOf(2025), previous, out) }
        reached.await()
        job.cancelAndJoin()
        assertFalse(out.exists())
        assertTrue(before.contentEquals(previous.readBytes()))
    }
}
