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
- [ ] The user has reviewed neither plan and has not chosen an execution method. For the previous project they chose subagent-driven development.

## Next step

**Get the user's review of both plans and their choice of execution method.** Once they have answered, record the method here.

Then execute **Plan 1, Task 1**, one task per session. With subagent-driven development, the ledger is at `.superpowers/sdd/2026-09-25-kotlin-ingest/progress.md`. It is git-ignored, so also tick tasks below.

Plan 2 depends on all of Plan 1. Execute Plan 2 only after Plan 1's Task 12 (the parity gate) is green.

## Execution progress

**Plan 1** (`2026-09-25-kotlin-ingest.md`): tasks 1–12 not started.

**Plan 2** (`2026-09-25-live-refresh-app.md`): tasks 1–11 not started.
