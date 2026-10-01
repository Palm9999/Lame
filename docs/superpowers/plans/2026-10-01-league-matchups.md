# League Matchups Plan
**Goal:** An ESPN league matchups view (week stepper, matchup cards, lineups) showing ESPN's points with the app's points beside them.
**Approach:** Parse ESPN's `mMatchup` into new types, score each matched player's week with the active profile through a query shared with the Scores game view, then add a screen behind ☰ → ESPN league. Core first, UI last.
**Stack:** Kotlin, kotlinx-serialization JSON, Compose, JUnit 5 and Robolectric, the real `stats.db` through `JdbcQueryExecutor`.
**Spec:** `docs/superpowers/specs/2026-10-01-league-matchups-design.md`

Every task ends in a commit on `claude/relaxed-hypatia-73hhub` (`git pull --no-rebase` before pushing). No PR is opened, as with the league import.

## Task 1: Share the week-points query
**Intent:** Move the query in `ScoresRepository.detail` that scores a set of players for one week into one helper, so matchups reuse it without copying.
**Files:** new `core/data/src/main/kotlin/dev/gridiron/core/data/WeekPoints.kt`; edit `ScoresRepository.kt`.
**Interfaces:** `internal suspend fun QueryExecutor.weekPoints(season: Int, week: Int, playerIds: Collection<String>, scoring: ScoringProfile): List<GamePlayer>` (id, name, position, points; unqualified players included, empty `playerIds` returns empty). `detail` calls it for the ids it finds.
**Check:** `./gradlew :core:data:test --tests "dev.gridiron.core.data.ScoresRepositoryTest"` passes unchanged. New `WeekPointsTest` (`scores only the ids asked for`, `an empty id set asks nothing`).
**Risks:** `StatQuerySpec.MAX_LIMIT` caps the rows; a lineup is far below it.

## Task 2: Types and parser (tests first)
**Intent:** Read ESPN's matchup response into `LeagueMatchup`s.
**Files:** edit `core/data/.../live/FantasyLeague.kt`; edit `core/data/src/test/.../live/FantasyLeagueTest.kt`; new fixture `core/data/src/test/resources/espn/fantasy_matchups.json` (hand-built).
**Interfaces:**
- `MatchupPlayer(espnId, name, slot, espnPoints: Double?, playerId: String? = null, appPoints: Double? = null)`
- `MatchupSide(teamId: Int, espnTotal: Double, lineup: List<MatchupPlayer>, appTotal: Double? = null)`
- `LeagueMatchup(week: Int, home: MatchupSide, away: MatchupSide?)`
- `EspnFantasyParser.matchupsUrl(leagueId, season, week)`: the `mMatchup` and `mMatchupScore` views plus `scoringPeriodId`
- `EspnFantasyParser.matchups(text: String, week: Int): List<LeagueMatchup>`: keeps `schedule` items whose `matchupPeriodId` equals `week`; a missing `away` is a bye; the lineup comes from `rosterForCurrentScoringPeriod.entries`; skips unreadable entries; no `schedule` or no readable matchup throws `LiveFormatException`.
**Tests:** `parses two matchups with totals and lineups`, `a missing away side is a bye`, `only the asked week's matchups are kept`, `a D/ST entry gets its name and slot`, `unreadable entries are skipped`, `a response without a schedule is a format error`, `the url asks for the matchup views and the week`.
**Check:** `./gradlew :core:data:test --tests "dev.gridiron.core.data.live.FantasyLeagueTest"` green.

## Task 3: Repository `matchups` with app points
**Intent:** Fetch, map ESPN ids to app ids, add app points, return a result that never throws.
**Files:** edit `FantasyLeagueRepository.kt`; edit `FantasyLeagueTest.kt`; new `core/data/src/test/.../live/LeagueMatchupsTest.kt` (real database).
**Interfaces:**
- constructor gains `stats: QueryExecutor? = null` before `clock`; the existing call sites keep working.
- `public data class MatchupsResult(val matchups: List<LeagueMatchup>, val fetchedAtMillis: Long, val error: String?)`
- `public suspend fun matchups(season: Int, week: Int, scoring: ScoringProfile): MatchupsResult`: same cookies and `friendly` error text as `sync`; `appPoints` from `weekPoints`, null when the player is unmatched or has no row; `appTotal` sums starters (slots other than BE and IR), null when no starter has a value; no league configured gives `error = "no league id set"`.
**Tests:** `matchups sends the cookies and the week`, `app points equal the Scores game view's for the same player and week` (real database), `an unmatched player has no app points`, `app total sums starters only`, `a D/ST gets app points through its DST id`, `401 says the league is private`, `a format change reports the error`.
**Check:** `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "*FantasyLeagueTest" --tests "*LeagueMatchupsTest"` green.
**Risks:** `PlayerDirectory.playerIds` in tests is an empty xref, so the real-database test builds a directory over the real `stats.db` to match ESPN ids.
**Checkpoint:** stop here and report before the UI (the data layer is the part that rests on the unverified ESPN shape).

## Task 4: Screen, route and wiring
**Intent:** Matchups screen reachable from ☰ → ESPN league.
**Files:** new `app/src/main/kotlin/dev/gridiron/app/MatchupsScreen.kt`; edit `LeagueScreen.kt` (a "Matchups" button once a league is synced), `GridironNavHost.kt` (`MatchupsKey`, entry, `onMatchups`), `GridironApplication.kt` (pass `executor` as `stats`); new `app/src/test/.../MatchupsScreenTest.kt`.
**Interfaces:** `MatchupsRoute(league: FantasyLeagueRepository, scoring: ScoringRepository, season: Int, onPlayer, onBack, dataVersion)` loads the result for the week (starting at `league.league.value.week`) on week, profile or data change and holds the last good result across a failed fetch; `MatchupsScreen(...)` is stateless over a `MatchupsResult?` and an open matchup, so it tests without a repository. Rows use the Scores game view's layout; App cells show `–` and a one-line note when any is missing.
**Tests:** `the list shows ESPN totals with the app total in parentheses`, `a missing app number shows a dash and the note`, `the stepper changes the week`, `tapping a matchup opens both lineups with an App column`, `a matched player opens the Player page`, `an error keeps the last result and shows the message`.
**Check:** `./gradlew :app:testDebugUnitTest --tests "*MatchupsScreenTest" --tests "*LeagueScreenTest"` if the Android SDK is installed (it is absent in a fresh container; install per HANDOFF's container notes, else say the tests did not run).

## Task 5: Docs and clean-up
**Intent:** Close the feature in the same push.
**Files:** `docs/superpowers/HANDOFF.md` (just built, the next task, the phone check from the spec, unverified `mMatchup` shape), `CLAUDE.md` (Known Gaps entry for league matchups), `docs/ARCHITECTURE.md` (the league section); delete the spec and this plan (they stay in git history).
**Check:** `git status` clean after the commit; push succeeds; HANDOFF's "Next" names the following follow-on.
