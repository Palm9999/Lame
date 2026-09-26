# Projection model: design

**Status:** approved in conversation, section by section (2026-09-26).
**Follows:** [live data refresh](2026-09-25-live-data-refresh-design.md), which deferred projections to this project.
**Supersedes:** the pipeline half of [projections design](2026-09-23-projections-design.md) (Python ETL stages, `projection_snapshot`, `accuracy_summary`). Its on-device half (`:core:projections` scorer, Monte Carlo, factor attribution, waterfall) is kept and reused.

## Goal

The phone computes weekly and rest-of-season projections for QB, RB, WR, TE, K and DST during Refresh, from the stats it already builds plus public schedules and (optionally) the user's own betting-props key. Projections are scored under the user's active league scoring on the phone, explain themselves in a waterfall, and prove themselves on an accuracy page.

## Decisions

| Topic | Decision |
|---|---|
| Purpose | Weekly start/sit plus rest of season (trades, waivers). |
| Engine | Approach A: a pure-Kotlin model that runs inside Refresh and writes the existing projection tables into `stats.db.new` before the swap. Rejected: computing per player on demand (matchup ratings need every team; backtests and rankings would repeat work per screen); fixing and porting the Python model with a parity gate (it is partial and broken, so parity buys nothing). |
| Reference | The Kotlin model is the source of truth. The Python projections code (`projections.py`, `shrinkage.py`, `odds.py`, their tests) is deleted in sub-project 1. |
| Game lines | Spread and total from nflverse's free `games.csv`. |
| Props | Yes, from The Odds API with a key the user enters in Settings (sub-project 3). |
| Positions | QB, RB, WR, TE, K, DST. |
| Accuracy | Yes, from a walk-forward backtest; no saved snapshot history. |
| Weather | Not modeled (wind is only known after a game is played). Roof is recorded for later use. |
| Injury redistribution | Not modeled. An Out/IR player's own projection shows "Out" and zero; teammates are unchanged. |
| Grid column | Not in this project. |

## Sub-projects

Built in this order, each with its own plan:

1. **Engine:** schedules, the QB/RB/WR/TE model, weekly and ROS, Player page card, Projections list, known-gap fixes, Python projections removed.
2. **Accuracy:** on-phone backtest scoring, the accuracy page, the CI accuracy gate.
3. **Props:** Odds API key, rationed fetch, market blend.
4. **K and DST:** scoring rules, kicking stats, K and DST models, list tabs.

## 1. Architecture and data flow

**New module `:core:forecast`** (pure JVM Kotlin, explicit API mode, JUnit Jupiter, like `:core:ingest`). It depends on `:core:ingest` for the database writer types and on nothing Android. Its entry point:

```kotlin
public class ForecastEngine(/* constants, clock */) {
    public fun run(db: ForecastDb, props: PropsSnapshot?): ForecastReport
}
```

`ForecastDb` reads `player_week_stat`, `player`, `game` and `team_week_defense` from the freshly written database and writes the projection tables into it. On the JVM it is backed by JDBC (tests, CLI, CI); on the phone by the bundled SQLite driver, the same split `:core:ingest` already uses.

**During Refresh:**

1. `IngestPipeline` builds and validates `stats.db.new` as today.
2. The pipeline fetches `games.csv` (conditional GET, validators under `source:games.csv`) and writes the `game` table for the built seasons.
3. `RefreshCoordinator` calls `ForecastEngine.run` on `stats.db.new`. Progress shows as "Projecting week N of season".
4. The swap happens as today. If the forecast throws, the projection tables are left empty, `schema_meta.forecast_status` records the reason, and the stats still swap in. Screens show "Projections unavailable: <reason>".

**Reuse.** A season is re-projected when its facts, `games.csv` or `forecast_version` changed; otherwise its projection rows are copied from the previous `stats.db`, the same way unchanged stat seasons are copied today. The current season always changes during the season, so this mainly saves work on past seasons.

**Stored as components, never fantasy points.** Scoring stays on the phone, so any league works and switching profiles re-scores instantly.

### Schema v7

`SCHEMA_VERSION` goes from 6 to 7:

```sql
CREATE TABLE game (
    game_id     TEXT PRIMARY KEY,
    season      INTEGER NOT NULL,
    week        INTEGER NOT NULL,
    game_type   TEXT NOT NULL,      -- REG, WC, DIV, CON, SB
    home_team   TEXT NOT NULL,
    away_team   TEXT NOT NULL,
    home_score  INTEGER,            -- NULL until played
    away_score  INTEGER,
    spread_line REAL,               -- home team favored by this many points; NULL if not posted
    total_line  REAL,
    roof        TEXT,
    home_qb_id  TEXT, away_qb_id TEXT,
    home_coach  TEXT, away_coach TEXT
) WITHOUT ROWID;
CREATE INDEX game_by_week ON game (season, week);
```

Team abbreviations go through the same `teams` normalization the stats use.

- `player_week_projection`, `player_week_projection_factor` and `player_ros_projection` keep their v6 shape.
- `projection_snapshot` and `accuracy_summary` are dropped: the backtest replaces both (see §4).
- `metric` already has `dist_family` and `zero_inflated`; sub-project 1 fills them for every projected component and threads them through `:core:data`'s `Catalog`/`MetricInfo` so Monte Carlo stops assuming Gamma.
- New `schema_meta` keys: `forecast_version`, `forecast_status` (`ok` or the failure reason), `forecast_week:<season>` (the upcoming week projected, if any), `forecast_built_at`.

**What gets stored per week:**

- **Upcoming week** (the first regular-season week of the latest built season with an unplayed game): `baseline` and `final` stages, plus factor rows.
- **Past weeks:** `final` only, for the backtest.
- **Remaining weeks:** summed into `player_ros_projection` only.

Off-season (no unplayed games) stores past weeks only and the UI says "No upcoming games".

## 2. The model (QB, RB, WR, TE)

The model is walk-forward: projecting week *w* of season *s* reads only facts from before week *w* of *s*, plus earlier seasons that are in the database.

The projected components are every scoring input the app reads:
- Passing: attempts, completions, passing yards, passing TDs, interceptions.
- Rushing: carries, rushing yards, rushing TDs.
- Receiving: targets, receptions, receiving yards, receiving TDs.
- Other: first downs by type, 2-pt by type, fumbles lost, and 40+/50+ TD counts.

Seven layers:

1. **Team volume.** EWMA of team pass attempts and carries per game, half-life 4 games.
2. **Player share.** EWMA of target share, carry share, and (QB) share of team dropbacks, with half-lives from the existing constants: `snap_share` 2.5, `target_share` 4.5, `carry_share` 4.5. Each is then shrunk toward the positional baseline with James-Stein `w = n/(n+k)`, using `target_share` k=5 and `carry_share` k=5.
   - **Carryover.** Last season's final EWMA enters week 1 with weight 0.55, decaying linearly to 0 by week 6.
   - **Regime break.** A new team, a new head coach, or (for pass catchers) a new primary starting QB zeroes the carryover. All three are detectable from `player_week_stat.team` and the `game` table's coach and QB columns.
3. **Efficiency.** Catch rate k=15, yards per target, yards per carry, completion rate, yards per attempt, and interception rate k=150. Each is shrunk toward the positional baseline. Efficiency half-life is 10.
4. **Touchdowns.** The rate comes from ffopportunity's expected TDs (`x_*_tds` per opportunity) rather than actual TDs, shrunk with k=200. 2-pt, 40+ and 50+ TD rates are league-average rates per opportunity.
5. **Matchup.** A ridge regression `y = μ + offense + defense + home` per outcome: yards per attempt, yards per carry, pass TD rate and rush TD rate.
   - **Fit.** Weekly, on team-game rows from before week *w*, L2-penalized.
   - **Shrinkage.** Ratings are shrunk by games played with a large k, so weeks 2–3 sit near league average.
   - **Caps.** Efficiency ±15%, volume ±5%, TD ±20%.
   - **Solver.** 65 parameters, solved by Cholesky decomposition on the normal equations; no library needed.
6. **Game script.** Implied team points are `total/2 + spread/2` for home and `total/2 − spread/2` for away (nflverse's `spread_line` is positive when the home team is favored).
   - **Implied-points multiplier.** Implied points relative to the league average scale TD rates most, yards less and attempts least; the elasticities are constants in `ForecastConstants`.
   - **Pass-rate shift.** Pass rate moves 0.6 percentage points per point of spread, toward passing for the underdog.
   - **Missing lines.** With no line posted, this layer is a no-op with note "No line yet".
7. **Distributions.** The variance of each component follows `σ = a·μ^0.75`, calibrated so the CV at μ=10 equals the position's empirical CV: QB 0.40, RB 0.57, WR 0.70, TE 0.77. The distribution family and zero-inflation come from `metric`.

**Stages and factors.**
- `baseline` is the output of layers 1–4.
- `final` is the output of layers 5–6.
- Factor rows (`matchup`, `game_script`) hold the log multiplier on the player's projected points under the reference PPR profile, which is what the existing `FactorAttribution` apportions. Each carries a short note, for example "vs DAL: 3rd-most WR yards allowed" or "Implied 27.5 pts (+4.3)".

**Rest of season.** The sum of weekly means and variances over the team's remaining scheduled regular-season games. Byes are skipped because they have no game row. Each remaining week uses the current ratings with that week's opponent and, where posted, that week's line.

**Who is projected.** Every player with a fact in the current or previous season at QB/RB/WR/TE whose current team has a game that week. Rookies with no history start at the positional baseline, weighted by snap share once they have one.

All constants live in one `ForecastConstants` object with the provenance of each (the research doc section, or "carried from the Python ETL"). `forecast_version` is bumped whenever a constant changes.

## 3. Screens (sub-project 1)

**Player page:** a "This week" card.
- It shows projected points under the active scoring profile and the player's real position, the floor and ceiling (10th and 90th percentile, from the existing Monte Carlo), and the opponent, spread and total.
- Tapping it opens the existing waterfall (`ProjectionsKey`).
- The same card shows ROS total points and points per game.
- An ESPN Out/IR status from `live.db` shows "Out" and 0 for that week.

**☰ → Projections**, a new list screen:
- Position tabs: QB, RB, WR, TE, FLEX, plus K and DST in sub-project 4.
- A Week N / Rest of season toggle.
- Rows ranked by projected points, each showing floor–ceiling and the Grid's injury badge. A row tap opens the Player page.

**Known gaps closed:**
- `ProjectionsRoute` and the list read the active profile from `ScoringRepository` (as `CompareRoute` does) and the player's position from `player`, instead of `ScoringPresets.PPR` and `position = null`.
- Monte Carlo uses each metric's real `dist_family`.

**Status line:** "Projections for week 4 · built Tue 7:02am", or "Projections unavailable: <reason>", or "No upcoming games".

## 4. Accuracy (sub-project 2)

The backtest's past-week `final` rows are joined to `player_week_stat` on the phone and scored under the active profile. Per position and season, the page shows:

- **Error:** MAE, bias (mean error), and R².
- **Calibration:** the share of actual scores that fell between floor and ceiling. The target is about 80%.
- **Baselines:** the same numbers for two baselines computed on the fly from `player_week_stat`: the season-to-date average and the last-4-games average.

Only player-weeks where the model projected at least 5 points and the player appeared count, so benched players don't flatter the numbers. The page notes that past weeks are projected without props.

`AccuracyRepository` switches from `accuracy_summary` to this computation, and the page goes into ☰.

**CI gate:** a new check in the parity job builds 2024–2025. It fails if, for 2025 under PPR, the model's MAE isn't below the season-to-date baseline's for each of QB, RB, WR and TE.

## 5. Props (sub-project 3)

- **Key.** Settings gets an "Odds API key" field. The key is stored in app-private preferences and sent only to `api.the-odds-api.com`.
- **Fetch.** During Refresh, before the forecast: `/v4/sports/americanfootball_nfl/events` (free), then, for each game in the upcoming week that hasn't kicked off and wasn't fetched in the last 24 hours, `/events/{id}/odds` with `regions=us`.
  - **Markets:** `player_anytime_td`, `player_receptions`, `player_reception_yds`, `player_rush_yds`, `player_pass_yds`. That is 5 credits per game, about 80 for a full slate.
- **Budget.** The `x-requests-remaining` header is recorded after every call. The next call is skipped if it would go below zero. Settings shows credits left and when props were last fetched.
- **Storage.** Props go in `live.db` as a new `prop_line` table, pruned after the game. The ingest then hands a `PropsSnapshot` to the engine.
- **Matching.** Prop names are matched to players by normalized name plus team. Unmatched props are dropped and counted in the refresh report.
- **Conversion.** Each book is de-vigged proportionally, then:
  - Anytime TD `p` becomes `λ = −ln(1−p)`.
  - An over/under at a line becomes a mean via a Gamma with the position's CV.
- **Blend.** Inverse-variance weighting with the model's final mean. It is recorded as a `market` factor with a note like "Props: 64.5 rec yds".
- **Failures.** A bad key, no credits left, or a network error leaves the model's number untouched. Settings shows the reason.

## 6. K and DST (sub-project 4)

**Scoring.** `ScoringRule` gains:
- K rules: FG made 0–39, 40–49 and 50+; XP made; FG missed; XP missed.
- DST rules: sack, interception, fumble recovery, defensive/special-teams TD, safety, and points-allowed tiers (0, 1–6, 7–13, 14–20, 21–27, 28–34, 35+).

Presets get the common defaults, and the scoring editor shows the new rules. `score()` and `StatQueryBuilder` both learn them.

**Stats.**
- `:core:ingest` adds kicking metrics from play-by-play: FG attempts and makes by distance bucket, XP attempts and makes.
- DST facts come from `team_week_defense`, keyed to a team pseudo-player (`DST_<TEAM>`, name "<TEAM> D/ST", position DST) written to `player`.
- Parity: the Python ETL gains the same kicking metrics so the parity gate still covers everything.

**K model.** Implied team points drive FG and XP attempts, using a linear model fit walk-forward. The kicker's distance mix and make rate per bucket are shrunk toward the league average. Empirical CV is 0.52.

**DST model.**
- Sacks and turnovers are the unit's own EWMA rates times the opponent's offensive ratings.
- Points allowed are modeled from the opponent's implied points; the tier probabilities follow from that.
- Empirical CV is 0.85.

**Screens.** K and DST tabs in the Projections list, and Player page cards. The Grid is unchanged.

## Testing

- **Each layer:** unit tests with hand-computed cases (EWMA, shrinkage, carryover and regime breaks, ridge on a tiny synthetic league, game-script multipliers, the variance formula).
- **Engine:** an end-to-end test on a synthetic two-season database. It checks walk-forward: no fact from week ≥ *w* changes week *w*'s projection.
- **Contract test:** against the CI-built database, `ProjectionsRepository` → `ProjectionsViewModel` produces non-empty, finite projections for every starter. This closes the gap CLAUDE.md lists.
- **Accuracy:** the CI gate in §4.
- **Props:** parser tests against recorded Odds API responses; budget tests with a fake fetcher.
- **Performance:** the refresh toast reports forecast time separately. The target is under 30 seconds on the phone for three seasons. Sub-project 1's first milestone measures it with a JVM timing test, and the user checks it on the phone.

## Risks

- **Phone time for the backtest.** Every past week needs a projection pass. If this is too slow, past seasons are reused (copied) and only the current season is recomputed, which the reuse rule already does.
- **Accuracy gate.** It could fail on first build. In that case the layer constants get tuned before the gate is enabled; the gate is never skipped.
- **Odds API.** The free tier's 500 credits a month cover about six full-slate fetches. The budget guard makes running out a message, not a failure.
- **Name matching for props.** Some players won't match. The refresh report shows the unmatched count.

## Out of scope

- Weather.
- Redistributing an injured player's share to teammates.
- A Grid projection column.
- IDP.
- Correlated multi-player simulation (win probability, lineup optimizer).
- Historical props.
