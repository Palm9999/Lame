# League history — design

**Goal:** the active ESPN league's past: each season's champion and standings, an all-time table, your head-to-head records and league records. Approved 2026-10-05.

## Data

- **Seasons:** the league's `status.previousSeasons` (from `?view=mStatus`, read with the sync) plus the current season. Shape from memory, unverified.
- **One season:** `seasons/{y}/segments/0/leagues/{id}?view=mTeam&view=mStandings&view=mSettings&view=mMatchupScore` for 2018 on; before 2018 `leagueHistory/{id}?seasonId={y}&view=…` (same views), which answers a one-element array. Both with the league login. Parsed into `HistorySeason`:
  - `teams`: id, name, `primaryOwner` (owner id), wins/losses/ties, points for/against, `rankCalculatedFinal` (final place, 0 when unset), `playoffSeed`.
  - `members`: owner id → `displayName`.
  - `games`: from `schedule[]`: `matchupPeriodId`, home/away `teamId` and `totalPoints`, `winner` (`HOME`/`AWAY`/`TIE`/`UNDECIDED`), `playoffTierType` (`NONE` = regular season; `WINNERS_BRACKET` = playoffs; other tiers, consolation, are left out of records and head-to-head).
  - `playoffTeams` from `scheduleSettings.playoffTeamCount`.
- **Cache:** a finished season (any season before the current one) is stored once as the parsed season's JSON in `noBackupFilesDir/history-<league>-<year>.json` and never fetched again; the current season is read live each open. A season that fails is skipped and named ("Couldn't read 2019: …").
- **Managers:** keyed by owner id across seasons; shown under their latest `displayName` (else the latest team name). A team with no owner id is keyed `team:<season>:<id>`.

## Tables (`LeagueHistory.of(seasons, me: ownerId?)`, pure, `:core:data/live`)

- **Seasons:** per season, best first: champion = `rankCalculatedFinal == 1`, else the winner of the last decided winners-bracket game, else none ("in progress"); standings by final place, then wins, then points for.
- **All-time:** per manager: seasons, W-L-T (regular season), win %, titles, playoff trips (`playoffSeed` ≤ `playoffTeams`), points for, average finish (final place, finished seasons only). Sorted by titles, then win %.
- **Head-to-head:** for `me`, against each other manager: W-L-T and points for/against, regular season and winners-bracket games, decided games only. Empty without `me`.
- **Records:** highest and lowest single-week score, biggest win (margin), best regular-season record (win %), most points in a regular season: each with manager and season (and week). Decided games only.

## Screen (`:feature:projections`, More → League → League history, and ☰)

Tabs Seasons, All-time, Head-to-head, Records. Your manager is marked "(you)". A loading line while seasons fetch ("Reading 2019…" not needed: one spinner), then the tables; skipped seasons are listed under the tabs.

## Testing

Parser on hand-built JSON (2018+ object and pre-2018 array), cache (finished season read once, current season every time), `LeagueHistory` tables on hand-built seasons (champion fallbacks, ties, consolation games excluded, manager renamed across seasons, no owner id), screen tabs.

## Out of scope

Keeper/trade history, transactions, draft history, other leagues than the active one, records by position.
