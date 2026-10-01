# League matchups and scores (part B)

## Goal

Show the user's ESPN league matchups for a week: every head-to-head with both team totals, and a lineup view per matchup with each player's points. ESPN's points are the primary numbers (they match the league's scoring and update live); the app's own points, scored with the active profile, sit beside them.

Builds on the ESPN league import (`FantasyLeagueRepository`, `EspnFantasyParser`, `EspnLeagueConfig` cookies) and the NFL Scores game view (`ScoresRepository.detail`).

## Rulings from the brainstorm (2026-10-01)

- Scope is **scoreboard plus lineups**, not scoreboard alone and not the user's matchup only.
- ESPN's numbers lead; the app's numbers are added, not substituted.
- Matchup list shows the app total in parentheses; the lineup view gets its own **App** column.

## Out of scope

Projected points, free agents, a roster-aware projection total, playoff-bracket views, an offline snapshot of matchups, lineup editing. Weather stays out of scope.

## Display

**Matchup list.** A week stepper (opens on the league's current week) and one card per matchup: both team names, ESPN's totals large, the app total in parentheses beneath each, e.g. `112.4` / `(app 108.9)`. The user's own team is marked. A bye (odd team count) shows as a one-sided card. A matchup not yet scored shows `0.0` from ESPN, as ESPN does.

**Lineup view.** Tap a matchup: both lineups, starters first in ESPN's slot order, then bench and IR. Row: slot, player, ESPN points, App points. A player the app can't match, or has no stats for that week, shows a dash in App. Tapping a matched player opens the Player page (as the League screen does). The team total row sums starters only, for ESPN and for the app.

**App points availability.** nflverse stats land after games finish and arrive on refresh, so App is a dash for live games and for weeks the database lacks. The screen says so in one line when any App cell is a dash for that reason.

**Entry point.** A "Matchups" button on ☰ → ESPN league, shown once a league is synced.

## Data

**ESPN read.** `https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/{season}/segments/0/leagues/{id}?view=mMatchup&view=mMatchupScore&scoringPeriodId={week}`, same cookies as the league sync. The `schedule` array holds one entry per matchup (`matchupPeriodId`, `home` and `away` with `teamId`, `totalPoints`, `rosterForCurrentScoringPeriod.entries`). Each entry has `playerId`, `lineupSlotId` and `playerPoolEntry.appliedStatTotal` (ESPN's points) and `playerPoolEntry.player.fullName`. Entries are filtered to `schedule` items whose `matchupPeriodId` fits the week. **This shape is from memory and unverified**: no league is reachable from the container, so tests use hand-built fixtures, and the phone check confirms it.

**Types** (in `FantasyLeague.kt`):

- `MatchupPlayer(espnId, name, slot, espnPoints: Double?, playerId: String?, appPoints: Double?)`
- `MatchupSide(teamId, espnTotal: Double, appTotal: Double?, lineup: List<MatchupPlayer>)`
- `LeagueMatchup(week, home: MatchupSide, away: MatchupSide?)`

**Parser.** `EspnFantasyParser.matchups(text, week)` walks the JSON like the existing parser: skips what it can't read; a response with no `schedule` or no readable matchup is a `LiveFormatException`. Slots reuse the existing slot map, `D/ST` ids map through `dstPlayerId`.

**Repository.** `FantasyLeagueRepository.matchups(season, week, scoring): MatchupsResult`. It reads ESPN, maps ESPN ids to app ids with `PlayerDirectory.playerIds` (D/ST via `dstPlayerId`), and scores the matched players' week with the profile from `stats.db` through the same query `ScoresRepository.detail` uses (a `playerIds` set, one week, `FANTASY_POINTS`). That query moves to a shared helper both repositories call, rather than being copied. Result carries `matchups`, `fetchedAtMillis` and `error: String?`. Errors use the sync's friendly text (private league, 404, format change). In memory only: a failed fetch keeps the screen's last result. The repository needs the stats executor, a new constructor argument supplied by `GridironApplication`.

**Team app total.** The sum of starters' `appPoints` (slots other than BE and IR); null if no starter has one.

## Components

| Unit | Does | Depends on |
|------|------|-----------|
| `EspnFantasyParser.matchups` | ESPN JSON to `LeagueMatchup`s | none |
| shared week-points query | player ids + week + profile to points | `QueryExecutor`, `StatQueryBuilder` |
| `FantasyLeagueRepository.matchups` | fetch, map ids, add app points | parser, `PlayerDirectory`, query helper, prefs |
| `MatchupsScreen` | week stepper, list, lineup view | repository, active scoring profile |

## Testing

- **Parser:** `FantasyLeagueTest` gains hand-built `mMatchup` fixtures: two matchups, a bye, a D/ST, an unreadable entry skipped, a response with no schedule fails.
- **Repository:** hand-built HTTP fixture plus the real database (`GRIDIRON_STATS_DB`): app points equal `ScoresRepository.detail`'s for the same player and week; an unmatched player gets null; starters-only total; error texts for 401, 404 and a format change; a failed fetch reports the error.
- **Shared query:** `ScoresRepositoryTest` still passes after the move.
- **Screen:** `MatchupsScreen` test in `app` (Robolectric): the list shows ESPN and app totals, a dash for a missing app number, the stepper changes the week, tapping opens the lineup. `feature`/`app` tests need the Android SDK and may not run in the container; say so if they don't.

## Phone check (goes in HANDOFF)

☰ → ESPN league → Matchups: ESPN totals should match the ESPN app for the week; step back to a finished week and the App column should fill; report any "ESPN changed its league format" error text.

## Risks

- ESPN's `mMatchup` shape is unverified; the parser degrades by skipping, and the error text names the change.
- App points and ESPN points will differ whenever the active profile differs from the league's; that is the point of showing both.
