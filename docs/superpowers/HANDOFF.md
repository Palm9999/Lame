# Session Handoff

**How the user wants to work:**
- One fresh session per gap, with `/clear` after each. Keep replies short; ask only when blocked, one line at a time.
- Every session starts by reading this file, runs the next task, updates this file in the same PR, and stops.
- Cost and context stay low: see "Working rules" in `CLAUDE.md` (short plans, no docs-only PRs, specs and plans deleted once executed).

**Branch:** `claude/dreamy-euler-phbdq1`, based on `claude/relaxed-hypatia-73hhub` (the repo's main branch). One session at a time on it. After a PR merges, reset it: `git fetch origin claude/relaxed-hypatia-73hhub && git checkout -B claude/dreamy-euler-phbdq1 origin/claude/relaxed-hypatia-73hhub`.

**History:** executed specs and plans, and older handoff logs, are in git: `git log --diff-filter=D -- docs/superpowers` finds them, `git show <sha>^:<path>` reads one. Shipped work is in the merged PRs (#4–#18).

## Where things stand

Built and merged: the projection engine (K and D/ST included), accuracy page, props blend, live data refresh, Player page season stats, D/ST yards-allowed tiers (PR #14) and injured players' share to teammates (PR #17). Nothing is open. `INGEST_VERSION` 5, `FORECAST_VERSION` 6, prefs `formatVersion` 3.

**In progress: NGS metrics** (user chose NGS only, headline set of ten, 2026-09-29). Spec `docs/superpowers/specs/2026-09-29-ngs-metrics-design.md`, plan `docs/superpowers/plans/2026-09-29-ngs-metrics.md` (6 tasks, native execution on this branch). To resume after `/clear`: read the plan, then `.superpowers/sdd/2026-09-29-ngs-metrics/progress.md` (the ledger, git-ignored; if it is gone, trust the task list below and `git log`), and continue at the first task not marked done. Update this list in the same commit as each task.

- [x] Task 1: registry (100 to 122 metrics), components, `StatColumn`s, `StatFormat`, three NGS packs (`NGS_PASSING`, `NGS_RUSHING`, `NGS_RECEIVING`; a ruling: three packs instead of the spec's one, so each chip's default sort has data).
- [x] Task 2: Kotlin `Ngs.kt` (`readNgsPassing/Rushing/Receiving`, `remapPostseasonWeeks`, `mergeNgs`) and `Input.NGS_*` in `Sources.kt`; not yet wired into `IngestPipeline` (Task 3)
- [x] Task 3: pipeline integration in `IngestPipeline.kt` (`fetchNgs`, lazy `ngs()`, reuse rule), `validate/NgsChecks.kt`, `INGEST_VERSION` 6
- [x] Task 4: Python `etl/gridiron_etl/ngs.py` (`components`, `remap_postseason_weeks`, `drop_impossible`, `load`), hooked into `build.py`; a 2025 Python build stores 13 NGS metrics (1,282 receiving rows)
- [x] Task 5: parity OK for 2025 (299,853 facts, 122 metrics), `etl/build/stats.db` and `accuracy.db` rebuilt with the Kotlin builder (ingest v6), `NgsRealDatabaseTest` (Grid column over weeks 1-8 equals the CSV's weighted average), accuracy gate passes. Ruling: visible NGS ratios also store their weekly average and no NGS metric is sparse (the single-week contract test needs both)
- [ ] Task 6: docs, delete spec and plan, open the PR

The databases in `etl/build` are already rebuilt with NGS; the full suite runs with `GRIDIRON_STATS_DB=etl/build/stats.db` (only the known timing test fails).

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
