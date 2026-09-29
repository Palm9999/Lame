# Session Handoff

**How the user wants to work:**
- One fresh session per gap, with `/clear` after each. Keep replies short; ask only when blocked, one line at a time.
- Every session starts by reading this file, runs the next task, updates this file in the same PR, and stops.
- Cost and context stay low: see "Working rules" in `CLAUDE.md` (short plans, no docs-only PRs, specs and plans deleted once executed).

**Branch:** `claude/dreamy-euler-phbdq1`, based on `claude/relaxed-hypatia-73hhub` (the repo's main branch). One session at a time on it. After a PR merges, reset it: `git fetch origin claude/relaxed-hypatia-73hhub && git checkout -B claude/dreamy-euler-phbdq1 origin/claude/relaxed-hypatia-73hhub`.

**History:** executed specs and plans, and older handoff logs, are in git: `git log --diff-filter=D -- docs/superpowers` finds them, `git show <sha>^:<path>` reads one. Shipped work is in the merged PRs (#4–#18).

## Where things stand

Built and merged: the projection engine (K and D/ST included), accuracy page, props blend, live data refresh, Player page season stats, D/ST yards-allowed tiers (PR #14) and injured players' share to teammates (PR #17). Nothing is open. `INGEST_VERSION` 5, `FORECAST_VERSION` 6, prefs `formatVersion` 3.

**Next: more metrics (NGS/FTN).** The user picked it (2026-09-29). Start with brainstorming, and ask the scope question first: NGS only (recommended: three all-seasons weekly files, one row per player-week; passing, rushing, receiving), FTN only (play-level charting, 2022+, needs aggregation), or both. The Python ETL already downloads the NGS files (`sources.py`), but nothing transforms them and the Kotlin builder (`Sources.kt`) doesn't know them. FTN isn't wired anywhere. Touch points: `etl/gridiron_etl` (transform, `metrics.py`), `:core:ingest` (`Sources.kt`, `Metrics.kt`, the pipeline), the CI parity gate, and the metric counts asserted in `MetricsTest`, `StatsDbWriterTest` and `etl/tests/test_registry.py`. Bump `INGEST_VERSION` and rebuild `etl/build/stats.db` and `accuracy.db` with the Kotlin builder. FTN is CC BY-SA 4.0: credit "FTN Data via nflverse". Candidate metrics are listed in `docs/research/research-stats-catalog.md` (rows marked B/NGS/FTN).

**After it:** saved Grid presets (planned home: a `user.db`, not built) and season rollups (pre-aggregated totals for the full-season Grid view). Each gets its own brainstorm, spec, plan and PR.

## Rulings that still bind

- **Weather is out of scope** (the user, 2026-09-28). Don't propose modeling it.
- **Points-allowed and yards-allowed tiers** are each profile's own and editable, ESPN's by default; stored as numbers, scored in expectation per game on the phone. Saved profiles were migrated once (prefs `formatVersion` 2 and 3), with no fallback. ESPN's yards table is from memory, unverified.
- **The accuracy gate covers QB, RB, WR, TE, K and D/ST.** If a position loses to the season-to-date average, tune its constants in `ForecastConstants.kt`.
- **The Grid's K and D/ST chips** bring their own packs (Kicking, Defense); every other chip leaves them out.
- **Judgments, not fits:** `MARKET_VARIANCE_RATIO` (0.5), `ONE_SIDED_OVERROUND` (1.08), `DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY`, `DST_YA_CV` (0.25), the kicking scoring defaults (3/4/5, −1, 1, −1). Props and yards can't be backtested.
- **Every team's D/ST is always projected** (the min-points gates skip only kickers).
- **Deferred minors (yards work):** the 0–800 `yards_allowed` range check hard-fails a build on one out-of-range game (observed 75–647); no dedicated `BacktestTest` case for yards; no refit note beside `RANGE_WIDENING`; the matchup note reads "scores 24.1 pts, 331 yards".

## Open checks on the phone (non-blocking)

- **Odds API shape:** hand-built fixtures, no key in the container. On the first refresh with the user's key (☰ → Settings → Betting props, then Refresh stats), the toast should say "Props moved N projections".
- **Season stats timing:** open a player with a full season; the section runs up to 20 small queries. Report how long it takes to appear.
- **Yards allowed:** ☰ → Settings → a profile: the "Yards allowed" tier section edits and saves, and a D/ST's Grid points and Player page log include yards after a refresh (the first refresh rebuilds).
- **Accuracy page:** ☰ → Projection accuracy, season 2025. Report how long "Scoring every projected week…" shows; the "held" figures should read about 78–83%.

## Container notes

`etl/build/stats.db` (2024–2026) and `etl/build/accuracy.db` (2024–2025) exist here; rebuild them if the container is fresh (commands in `CLAUDE.md`). `RealDatabaseContractTest > scoring a full season for every player is fast` fails here on timing with or without any change; CI passes it. Bash sometimes fails with a transient "classifier gave no verdict" error: retry once, or use Read, Grep and Glob meanwhile.
