# Player page season stats, and K and D/ST on Compare

**Status:** approved in conversation (2026-09-29). Next: implementation plan.

## Purpose

The Player page shows status, injury notes, news and a "This week" projection card, but no stats. Compare has metric sets for QB, RB and WR/TE only, so a kicker or D/ST there shows offense columns and blanks. This work gives every position a season-stats section on the Player page, and makes Compare work for kickers and D/STs. It closes the last Known Gap left by the K/D/ST sub-project.

**Success:** open any player, kicker or team defense from the Grid and see a season line (total, per game, percentile at the position) and a week-by-week game log, scored with the active profile. Compare a kicker with a kicker, or a D/ST with a D/ST, and get their own stats.

**Not in scope:** the Grid, projections, new metrics, career totals, splits, charts on the Player page.

## Player page: "Season stats" section

Placed after "This week" and "Rosters", before "Status".

**Season chips.** One chip per built season in which the player has games. The page opens on the latest. Picking a chip reloads only this section. A player with no games in any built season sees "No games in the built seasons." and no chips.

**Season line.** One row per stat for the player's position (table below): the stat's abbreviation, **Total**, **Per game**, and a percentile bar. A header line gives games played and the qualifying bar in use ("min 3 targets per game, 4+ games").
- Values come from the Grid's own query, so they match the Grid.
- The percentile is the per-game percentile among qualified players at the position over the season's regular weeks, the same population and bar Compare uses. A player below the bar shows values and no bar, with the note "Below the ranking bar".
- Rates (target share, CPOE and so on) are recomputed over the season, never averaged. Fantasy points use the active scoring profile.

**Game log.** One row per week the player has stats, oldest first: week, opponent with home or away and result ("@DAL W 27–20"), fantasy points, and three or four key stats. A week with no row (bye, injured, did not play) is omitted. If the schedule row is missing, the opponent cell reads "–".

| Position | Season line stats | Game log stats |
|---|---|---|
| QB | Compare's QB set | Pass yds, pass TD, INT, rush yds |
| RB | Compare's RB set | Carries, rush yds, receptions, TD |
| WR, TE | Compare's WR set | Targets, receptions, rec yds, TD |
| K | FGM, FGA, FG 50+, XPM, XPA, fantasy points | FGM/FGA, FG 50+, XPM |
| D/ST | Points allowed, sacks, INT, fumble recoveries, TDs, safeties, fantasy points | Points allowed, sacks, INT, fumble recoveries, TD |

K and D/ST have no expected points, so they show no xFP or FPOE. An unknown position gets the WR set, as Compare does today.

**Hidden when** the stats repository or the active profile is unavailable (the same rule as the "This week" card): the rest of the page still loads. A failure loading the section shows one line ("Season stats aren't available.") and never blocks the page.

## Compare: kickers and D/STs

`CompareMetricSets` gains K and D/ST sets:
- **K:** qualifier `FG_ATT`. Opportunity: FGA, XPA. Efficiency: FG 50+. Scoring: fantasy points, FGM, XPM.
- **D/ST:** qualifier `POINTS_ALLOWED` (every team is ranked). Efficiency: points allowed (lower is better, as the Grid already treats it). Scoring: fantasy points, defensive TDs, safeties. Context: sacks, interceptions, fumble recoveries.
- Groups with no rows are omitted from the table. The radar takes at least three axes for K and D/ST (the existing test's six to eight applies to the four offense positions only).
- The xFP-vs-actual scatter is not offered when the first slot is a K or D/ST, because they have no expected points; the tab is hidden, not empty.
- Comparing across kinds (a QB with a kicker) keeps working as a union of both sets, as a QB with a WR does now.

## Architecture

- **`:core:data`** gets `PlayerStatSets` (the table above, one place to tune, reusing `CompareMetricSets` for the offense) and `PlayerStatsRepository(executor)`: `stats(playerId, position, season, scoring, catalog): PlayerStats`, plus `seasons(playerId)`.
- **Season line:** two Grid queries, `TOTAL` and `PER_GAME`, over the season's default regular weeks, `positions = {position}`, `playerIds` = the player, `includeUnqualified = true`, percentiles on. The query builder computes percentiles before it narrows to the player, as Compare relies on.
- **Game log:** one Grid query per played week with `WeekRange.single(week)` and the log's columns, plus one parameterized query for each week's team and the `game` row (opponent, scores). At most 18 small queries; they hit the covering index, like the Grid's sparklines.
- **`:app`:** a `PlayerStatsSection` composable and `PlayerPage.stats`, loaded in `PlayerRoute` beside the projection card, off the main thread, reloading on the data version bump, profile change and chip change. Percentile bars reuse `:core:charts`.
- **`:core:data` (Compare):** `CompareMetricSets` and `CompareRepository` handle the new positions; nothing in `:feature:compare` changes except hiding the scatter tab when the page has no scatter.
- No schema, ingest or forecast change. No new module.

## Testing

- **`PlayerStatSetsTest`:** every position has a season set and a log set, K and D/ST carry no xFP or FPOE, every column is a real `StatColumn`.
- **`PlayerStatsRepositoryTest`** on a small hand-built database with hand-computed values: a season line's total and per-game, a below-the-bar player, the game log's weeks and opponents, a missing schedule row, a bye week, a player in two seasons, a K and a D/ST.
- **Contract test** against the real database: for a sample of players in each position, the season line's totals equal the sum of the game log's weekly values, and equal the Grid's row for the same player.
- **`CompareMetricSetsTest`** and **`CompareRepositoryTest`** extended: K and D/ST sets, qualifiers, no scatter for K or D/ST, a mixed-kind comparison.
- **Compose test** for the section: chips, the below-the-bar note, the empty state.
- **Docs:** CLAUDE.md's Known Gaps line is removed once this lands, and the module notes mention the Player page's stats.

## Open judgment calls

- The game log's stat choices per position (table above) are a first pass; they are one list to edit in `PlayerStatSets`.
- Phone timing for the section (up to 18 queries) isn't measured here; report it after the first run on the device, like the accuracy page.
