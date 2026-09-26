# Session Handoff: Live Data Refresh

**How the user wants to work:** one fresh session per plan-writing step and per task, with `/clear` after each. Every new session starts by reading this file, does the single **Next step** below, updates this file (tick the step, fill in the next one), commits, pushes, and stops.

**Branch:** `claude/dreamy-euler-phbdq1`. It is based on `claude/relaxed-hypatia-73hhub`, which serves as the repo's main branch. PR #2 was merged.

## Where things stand

- [x] **Design approved.** See `docs/superpowers/specs/2026-09-25-live-data-refresh-design.md`. Summary:
  - No stats come from the repo. The phone pulls stats from nflverse/ffopportunity and injuries/news from ESPN at refresh time.
  - Projections come later, in their own follow-up project.
  - Seasons are selectable in Settings.
- [x] **Plan 1 written.** `docs/superpowers/plans/2026-09-25-kotlin-ingest.md` has 12 tasks and covers the `:core:ingest` module, which ports the Python ETL to Kotlin. It includes a CI parity gate and an early "Time a stats build" menu item.
- [x] **Plan 2 written.** `docs/superpowers/plans/2026-09-25-live-refresh-app.md` has 11 tasks:
  1. `ReopenableQueryExecutor`
  2. Grid/Compare reload on a data version
  3. Seasons choice
  4. ESPN parser and HTTP client
  5. `live.db` store
  6. `LiveRepository` and `PlayerDirectory`
  7. `RefreshCoordinator`
  8. App wiring, Load stats and Settings, removing the bundled database
  9. News, Player page and live Injury report
  10. Grid injury badges
  11. Removing `etl.yml` and the benchmark, plus docs

  Its ESPN test fixtures are real responses recorded 2026-09-25, cut down and embedded in Task 4.
- [x] **Plans reviewed and approved** by the user (2026-09-26). **Execution method: native.**
  - Each session implements one task itself with the `executing-plans` skill: no per-task subagents or reviewers.
  - After Plan 2's last task, one fresh reviewer on the most capable model checks the whole branch.

## Next step

**Execute Plan 1, Task 4: Derived rates and the long fact shape** (`docs/superpowers/plans/2026-09-25-kotlin-ingest.md`), natively, with the `executing-plans` skill.

1. Follow the task's steps exactly: failing test, implementation, passing test, commit.
2. Tick the task under **Execution progress** below.
3. Set this section to the next task.
4. Commit, push, and stop.

Plan 2 depends on all of Plan 1. Start Plan 2 only after Plan 1's Task 12 (the parity gate) is green.

## Execution progress

**Plan 1** (`2026-09-25-kotlin-ingest.md`):
- [x] Task 1: `:core:ingest` module and CSV reader (commit 8f2536c; `./gradlew :core:ingest:test` → 9/9 pass).
  - Ruling: the byte order mark is written as a `\uFEFF` escape, not a literal invisible character. The runtime bytes are the same. Cost if wrong: none.
- [x] Task 2: Metric registry (commit 28068e2; `./gradlew :core:ingest:test` → 14/14 pass).
  - No rulings. A throwaway dump of all 81 `Metric` rows diffed identical to Python's `METRICS` on every field, case preserved.
- [x] Task 3: Play-by-play reader and per player-week aggregation (commit 8dce2dd; `./gradlew :core:ingest:test` → 36/36 pass, 22 of them new).
  - No rulings. The brief's code blocks were written verbatim, and the tests were watched failing (unresolved `Play`) before the implementation went in.
- Tasks 4–12 not started.

The executing-plans ledger lives in git-ignored `.superpowers/` and does not survive the container. Rulings are copied here so the final reviewer sees them.

**Plan 2** (`2026-09-25-live-refresh-app.md`): tasks 1–11 not started.
