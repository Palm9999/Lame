# League history Implementation Plan

> Run with `executing-plans` in this session (CLAUDE.md). Steps per task: failing test, run, implement, run, commit.

**Goal:** More → League history: seasons, all-time table, head-to-head and records for the active ESPN league.

**Architecture:** `EspnHistoryParser` reads one season; `FantasyLeagueRepository.history()` lists seasons, caches finished ones as files; pure `LeagueHistory.of` builds the tables; one tabbed screen.

**Spec:** `docs/superpowers/specs/2026-10-05-league-history-design.md`

## Global Constraints

- ESPN shapes from memory, unverified: say so in KDoc and HANDOFF.
- ASCII test names; Android tests via `testDebugUnitTest`; Gradle through scratchpad `retry.sh`.

## Review Focus

- A season ESPN answers with no schedule (a league that didn't play): standings still show, no games. Test in Task 1.
- A manager who left and came back under a new team name: one row under the latest name. Test in Task 3.
- Two co-owners: keyed by `primaryOwner`. Test in Task 3.
- The current season mid-way: no champion ("in progress"), not in average finish. Test in Task 3.
- A cached file that can't be read is fetched again. Test in Task 2.

---

### Task 1: Season parser

**Files:** create `core/data/.../live/LeagueHistoryData.kt` (types + `EspnHistoryParser`), test `LeagueHistoryParserTest.kt`.

**Produces:** `HistoryTeam(id, name, ownerId: String?, wins, losses, ties, pointsFor, pointsAgainst, finalRank: Int?, playoffSeed: Int?)`, `HistoryGame(week, homeId, awayId: Int?, homePoints, awayPoints, winner: String, playoff: Boolean)` (consolation dropped at parse), `HistorySeason(season, teams, members: Map<String,String>, games, playoffTeams: Int?)`; `EspnHistoryParser.url(leagueId, season)` (2018+ or `leagueHistory`), `parse(text, season)`, `previousSeasons(text): List<Int>`; `HistorySeason.toJson()` / `historySeasonFromJson`.

**Tests:** `parses teams, owners and final ranks`, `games keep regular season and winners bracket only`, `a pre-2018 array answer parses`, `no schedule is a season without games`, `previous seasons from status`, `json round trip`.

### Task 2: Repository with file cache

**Files:** modify `FantasyLeagueRepository.kt` (+ `FantasyLeagueTest`).

**Produces:** `data class HistoryResult(seasons: List<HistorySeason>, skipped: List<Pair<Int,String>>, me: String?, error: String?)`; `suspend fun history(currentSeason: Int): HistoryResult` (seasons from `mStatus` read, `me` = the chosen team's `primaryOwner` in the current season).

**Tests:** `history reads each season once and the current one every time`, `a failed season is skipped and named`, `an unreadable cached file is fetched again`, `no league says so`.

### Task 3: Tables

**Files:** create `LeagueHistory.kt`, `LeagueHistoryTest.kt`.

**Produces:** `LeagueHistory.of(seasons, me): HistoryTables(seasons: List<SeasonSummary>, allTime: List<ManagerRow>, headToHead: List<RivalRow>, records: List<HistoryRecord>)` with `Manager(key, name)`.

**Tests:** `champion from final rank, else the last bracket game, else in progress`, `all-time sums regular seasons and counts titles and playoff trips`, `a renamed manager is one row under the latest name`, `co-owners key by primary owner`, `head-to-head counts decided games against each rival`, `records name manager, season and week`, `consolation games count nowhere`, `average finish skips the unfinished season`.

### Task 4: Screen and wiring

**Files:** create `HistoryScreen.kt`, `HistoryScreenTest.kt`; modify NavKeys (`HistoryKey`), NavHost (More "League", ☰), no new deps (uses `deps.league`).

**Tests:** `seasons tab names champions`, `all-time marks you`, `head-to-head lists rivals`, `records tab`, `skipped seasons are named`, `no league says sync one`.

### Task 5: Docs

ARCHITECTURE (Features: League history; schema note for cache files), HANDOFF (built, unverified shapes, phone check, deferred minors); delete spec and plan; push.
