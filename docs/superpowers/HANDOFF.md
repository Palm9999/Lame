# Session Handoff

**How the user wants to work:**
- One fresh session per gap, with `/clear` after each. Keep replies short; ask only when blocked, one line at a time.
- Every session starts by reading this file, runs the next task, updates this file in the same PR, and stops.
- Cost and context stay low: see "Working rules" in `CLAUDE.md` (short plans, no docs-only PRs, specs and plans deleted once executed).

**Branch:** `claude/dreamy-euler-phbdq1`, based on `claude/relaxed-hypatia-73hhub` (the repo's main branch). One session at a time on it. After a PR merges, reset it: `git fetch origin claude/relaxed-hypatia-73hhub && git checkout -B claude/dreamy-euler-phbdq1 origin/claude/relaxed-hypatia-73hhub`.

**History:** executed specs and plans, and older handoff logs, are in git: `git log --diff-filter=D -- docs/superpowers` finds them, `git show <sha>^:<path>` reads one. Shipped work is in the merged PRs (#4–#18).

## Where things stand

Built and merged: the projection engine (K and D/ST included), accuracy page, props blend, live data refresh, Player page season stats, D/ST yards-allowed tiers (PR #14) and injured players' share to teammates (PR #17). Nothing is open. `INGEST_VERSION` 7 (FTN, on this branch), `FORECAST_VERSION` 6, prefs `formatVersion` 3.

**Just built: Next Gen Stats** (PR for this branch; user chose NGS only, headline set of ten). Grid packs NGS Passing, Rushing and Receiving; ingest v6 stores each NGS average as average x weight beside its weight plus the weekly average under the metric's own id, none sparse. NGS is Grid-only: Compare and the Player page don't use it. Facts to keep: NGS numbers the Super Bowl one week after play-by-play (regular season + 5 vs + 4) and publishes only weeks with 15+ attempts, 10+ carries or 5+ targets; the rushing file has no QB rows and the receiving file is WR/TE only; efficiency is weighted by rush yards.

**In progress: FTN charting** (this branch; spec `docs/superpowers/specs/2026-09-29-ftn-metrics-design.md`, plan `docs/superpowers/plans/2026-09-29-ftn-metrics.md`, executed natively with a ledger in `.superpowers/sdd/2026-09-29-ftn-metrics/progress.md`). Tasks done: 1 (registry, components, columns, FTN Passing and FTN Receiving packs), 2 (Kotlin FTN reader and aggregator), 3 (pipeline, INGEST_VERSION 7), 4 (Python twin), 5 (parity OK 2022 and 2025, real-database test, databases rebuilt). Resume at the first task the ledger doesn't mark complete; until Task 5 rebuilds the databases, run Kotlin tests with `env -u GRIDIRON_STATS_DB`. After it merges: saved Grid presets and season rollups (start with brainstorming).

**Deferred minors (NGS):** an average missing or dropped still leaves its weight in the denominator (2 rows in 2025); "no NGS rows published yet" also fires for 2012-2015; `loadNgs` drops a group silently on a 404; NGS facts carry NGS team codes ("LAR", not "LA"); all seasons' NGS rows stay in memory for a build and the three files are re-downloaded when any season is rebuilt and NGS is unchanged; the Python twin drops all NGS if one file fails; `NgsRealDatabaseTest` needs the Python download cache, so CI's Kotlin-only job skips it; no `predicts` text on the ten metrics.

## Rulings that still bind

- **Weather is out of scope** (the user, 2026-09-28). Don't propose modeling it.
- **Points-allowed and yards-allowed tiers** are each profile's own and editable, ESPN's by default; stored as numbers, scored in expectation per game on the phone. Saved profiles were migrated once (prefs `formatVersion` 2 and 3), with no fallback. ESPN's yards table is from memory, unverified.
- **The accuracy gate covers QB, RB, WR, TE, K and D/ST.** If a position loses to the season-to-date average, tune its constants in `ForecastConstants.kt`.
- **The Grid's K and D/ST chips** bring their own packs (Kicking, Defense); every other chip leaves them out.
- **Judgments, not fits:** `MARKET_VARIANCE_RATIO` (0.5), `ONE_SIDED_OVERROUND` (1.08), `DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY`, `DST_YA_CV` (0.25), the kicking scoring defaults (3/4/5, −1, 1, −1). Props and yards can't be backtested.
- **Every team's D/ST is always projected** (the min-points gates skip only kickers).
- **Deferred minors (yards work):** the 0–800 `yards_allowed` range check hard-fails a build on one out-of-range game (observed 75–647); no dedicated `BacktestTest` case for yards; no refit note beside `RANGE_WIDENING`; the matchup note reads "scores 24.1 pts, 331 yards".

## Open checks on the phone (non-blocking)

- **NGS:** after the first refresh (it rebuilds every season: ingest v6), open the Grid, pick the NGS Passing, NGS Rushing and NGS Receiving packs and confirm columns fill (QB chip for passing, RB for rushing, WR or TE for receiving). Report the refresh time.
- **Odds API shape:** hand-built fixtures, no key in the container. On the first refresh with the user's key (☰ → Settings → Betting props, then Refresh stats), the toast should say "Props moved N projections".
- **Season stats timing:** open a player with a full season; the section runs up to 20 small queries. Report how long it takes to appear.
- **Yards allowed:** ☰ → Settings → a profile: the "Yards allowed" tier section edits and saves, and a D/ST's Grid points and Player page log include yards after a refresh (the first refresh rebuilds).
- **Accuracy page:** ☰ → Projection accuracy, season 2025. Report how long "Scoring every projected week…" shows; the "held" figures should read about 78–83%.

## Container notes

`etl/build/stats.db` (2024–2026) and `etl/build/accuracy.db` (2024–2025) exist here; rebuild them if the container is fresh (commands in `CLAUDE.md`). `RealDatabaseContractTest > scoring a full season for every player is fast` fails here on timing with or without any change; CI passes it. Bash sometimes fails with a transient "classifier gave no verdict" error: retry once, or use Read, Grep and Glob meanwhile.
