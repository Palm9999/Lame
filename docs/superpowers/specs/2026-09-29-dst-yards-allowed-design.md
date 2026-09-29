# D/ST yards-allowed scoring

**Status:** approved in conversation (2026-09-29). Next: implementation plan.

## Purpose

ESPN's default D/ST scoring rewards yards allowed as well as points allowed. Gridiron scores only points allowed, so a defense's fantasy score, its Grid rank and its projection all differ from the user's league. This adds yards-allowed tiers, editable per profile like the points-allowed tiers, everywhere a D/ST is scored: real games, projections, the editor, and the screens.

**Success:** a D/ST's fantasy points in the Grid equal points-allowed points plus yards-allowed points under the active profile; its projection (mean, floor, ceiling, waterfall) includes yards; the profile editor edits both tier lists; and CI's accuracy gate still has the model beating the season-to-date average at D/ST.

**Decisions from the user:**
- ESPN's yards tiers are on by default in every preset **and** every saved profile (migrated once).
- Yards allowed is projected as its own stat, like points allowed (not derived from points).

**Not in scope:** blocked-kick scoring; weather; other D/ST scoring rules.

## Scoring model

**Tiers.** `ScoringProfile` gains `yardsAllowedTiers: List<ScoringTier>` beside `pointsAllowedTiers`.
- `ScoringTier(min: Int, points: Double)` is the existing `PointsAllowedTier`, renamed. `PointsAllowedTier` stays as a `typealias` so existing references don't change. Same validation: `min >= 0`, finite points; a list is empty or starts at 0 and rises.
- `ScoringProfile.yardsAllowedPoints(allowed: Double)` and `expectedYardsAllowedPoints(mean, sd)` mirror the points-allowed pair and share one implementation (`tierPoints(tiers, x)` and `expectedTierPoints(tiers, mean, sd)`). Yards land on whole yards, so the expected-value helper keeps its half-unit boundary.
- **ESPN's default tiers** (my recollection of ESPN's default; unverified, like the kicking defaults, and editable): 0–99: 5, 100–199: 3, 200–299: 2, 300–349: 0, 350–399: −1, 400–449: −3, 450–499: −5, 500–549: −6, 550+: −7. `ESPN_YARDS_ALLOWED` holds them, and every preset carries them.
- Yards allowed are **net yards** (rushing plus net passing, sacks subtracted), which is what `team_week_defense.yards_allowed` already stores.

**The stat.** `yards_allowed` is a new D/ST weekly component (`Components.YARDS_ALLOWED`, `StatColumn.YARDS_ALLOWED`, lower is better), stored like points allowed: a number, with a shutout's 0 stored, and a kicker's week has none.

**SQL and `score()`.**
- `StatQueryBuilder`: the special-component pivot (`ws`) gains `yards_allowed`; `tiers()` takes a tier list and a component, and the D/ST week's `fp` adds both `CASE`s. The SQL shape depends only on the number of tiers, as today.
- `:core:projections` `score()` adds `profile.yardsAllowedPoints(...)` when a week's map has `Components.YARDS_ALLOWED`. `ACTUAL_SCORING_COMPONENTS` and `SCORING_COMPONENTS` include it, so the backtest and the query read it.

## Data

- `Dst.kt` writes `"yards_allowed" to r.yardsAllowed` on each D/ST week. `Metrics.kt` registers `yards_allowed` (name "Yards Allowed", abbreviation "YA", group defense, decimals 0, distribution `normal`), and the Python ETL matches it: `teams.dst_weekly`, `metrics.py`, its distribution table. CI's parity job must pass for 2025.
- Range checks, Kotlin and Python: a team's yards allowed in a game is between 0 and 800.
- `INGEST_VERSION` goes to 5, so phones rebuild. `SCHEMA_VERSION` stays 8 (a metric row is data).

## Forecast and projections

**Storage.** Each D/ST week's projection gains a `yards_allowed` metric: `mean` per game and `variance` = sd². It carries `g` = 1 like points allowed, so rest of season sums games and the phone scores each game's tier. `FORECAST_VERSION` goes to 5.

**Model** (`Defense.kt`, `Units.kt`, all constants in `ForecastConstants.kt`):
- **Own rate:** the defense's yards allowed per game, recency-weighted and shrunk toward the league's (`DST_YA_K`), like `unitRate` for points allowed.
- **Matchup:** yards the opponent's offense gains against defenses (this season and last), shrunk (`DST_OPP_K`) and capped to 1 ± `DST_CAP` of the league's, multiplies the rate.
- **Game script:** when a line is posted, yards are scaled by the opponent's implied points relative to the matchup's expected points, with a damped elasticity (`DST_YA_SCRIPT_ELASTICITY`, about 0.5, since yards move less than points), inside the existing `IMPLIED_RATIO_MIN/MAX`.
- **Spread:** sd = `DST_YA_CV` × the projected mean. There is no posted yards line to measure misses against, so the coefficient of variation is fitted once on 2024–2025 pooled and kept as a constant, like `EMPIRICAL_CV`.
- **Waterfall:** a D/ST's baseline, matchup and game-script stages include yards. `points()` (the reference score behind each factor's log ratio) adds the preset tiers' expected yards points, so a factor's size reflects both stats. The matchup note gains the opponent's yards gained per game.

**On the phone.**
- `projectedScore` adds `games * expectedYardsAllowedPoints(mean / games, sd)` for a `yards_allowed` component, as it does for points allowed.
- `MonteCarlo` draws each game's points allowed and yards allowed **jointly** from a bivariate Normal with correlation `DST_POINTS_YARDS_CORRELATION`, and scores both tiers per game. The correlation is measured once from `team_week_defense` (2024–2025 pooled) and pinned by a test against the real database within ±0.05; it lives beside `RANGE_WIDENING` in `:core:projections`.
- `RANGE_WIDENING[DST]` is refit on 2024–2025 pooled so the floor-to-ceiling range holds about 80% of games, as it was for every position.
- **Accuracy gate:** unchanged in form. With yards scored, the model's 2025 D/ST MAE under PPR must still be below the season-to-date average's. If it isn't, tune the yards constants (`DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY`, `DST_YA_CV`) up to 8 rebuilds, then stop and ask, the same rule as the K/D/ST work.

## Editor and saved profiles

- **Editor:** `ScoringEditViewModel` and `ScoringEditScreen` get a second tier section, "Yards allowed", under the points-allowed one, with the same row editor, "+ Add tier", validation ("Whole yards, 0–9999"; "Another tier starts at N"; "One tier must start at 0") and Reset to preset. Test tags are distinct per list (`ytier:min:<key>` and so on), and draft keys are unique across both lists.
- **Prefs:** `formatVersion` goes to 3. `UserPrefsJson` stores `yardsAllowed: List<TierDto>`. Loading a version-1 or version-2 profile sets its yards tiers to ESPN's once; a later stored list (even an empty one) is kept as saved. A stored list that is invalid (doesn't start at 0 and rise) becomes empty, like points allowed.
- **New profiles** duplicated from a preset copy its yards tiers; `basedOn` resets them with the rest.

## Screens

- **Grid:** the Defense pack gains `YARDS_ALLOWED` (after points allowed).
- **Compare:** the D/ST set's Efficiency group gains `YARDS_ALLOWED`; the D/ST radar gains it as an axis (7 axes).
- **Player page:** a D/ST's season line gains it through Compare's set; its game log columns become fantasy points, points allowed, yards allowed, sacks, interceptions.
- **Team defense** already shows yards allowed from `team_week_defense`; unchanged.
- **Waterfall and Projections list:** no layout change; D/ST points include yards.

## Testing

- **`ScoringProfileTest`** and a new `TierTest`: tier lookup, validation, the Normal expectation for yards, ESPN's default table.
- **`ScoringQueryTest`** (statquery): a D/ST week's fantasy points equal both tiers' points on hand-computed cases, an empty yards list adds 0, a kicker's week (no yards) adds none, equal profiles give identical SQL.
- **`ScorerTest`, `ProjectedScoreTest`, `MonteCarloTest`, `BacktestTest`:** yards scored per game and in expectation; the joint draw reproduces the correlation and never scores a tier twice.
- **`DstTest`, `MetricsTest`, `DatabaseChecksTest`, `IngestPipelineTest`:** the stat is written, registered and range-checked; Python `test_teams.py` and parity.
- **`DefenseTest`, `ForecastEngineTest`:** yards baseline, matchup, script, spread; the projection has `yards_allowed` with `g` = 1.
- **`UserPrefsStoreTest`:** the version-2 to 3 migration (yards tiers become ESPN's), a saved empty list stays empty at version 3, an invalid list is dropped.
- **`ScoringEditViewModelTest`** and a screen test: editing, adding and removing yards tiers, errors, save round trip.
- **Contract tests:** `RealDatabaseContractTest` (a D/ST's Grid points equal an independent sum from the weekly rows), the correlation pin, and the accuracy gate at D/ST.
- **Docs:** CLAUDE.md (scoring, forecast, `ForecastConstants`, Known Gaps), README, HANDOFF.

## Open judgment calls

- ESPN's yards tiers above are from memory of ESPN's default, not checked against ESPN; the user can edit them.
- `DST_YA_CV`, `DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY` are judgments tuned only as far as the gate needs. Yards allowed can't be backtested against a posted line.
- Phone timing isn't affected in a way I can measure here: the Grid gains one `CASE`, the Monte Carlo one more draw per game.
