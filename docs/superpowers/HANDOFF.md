# Session Handoff: Live Data Refresh

**How the user wants to work:** one fresh session per plan-writing step and per task, with `/clear` after each. Every new session starts by reading this file, does the single **Next step** below, updates this file (tick the step, fill in the next one), commits, pushes, and stops.

**Branch:** `claude/dreamy-euler-phbdq1`. It is based on `claude/relaxed-hypatia-73hhub`, which serves as the repo's main branch. PR #2 was merged.

## Where things stand

- [x] **Design approved.** See `docs/superpowers/specs/2026-09-25-live-data-refresh-design.md`. Summary:
  - No stats come from the repo. The phone pulls stats from nflverse/ffopportunity and injuries/news from ESPN at refresh time.
  - Projections come later, in their own follow-up project.
  - Seasons are selectable in Settings.
- [x] **Plan 1 written.** `docs/superpowers/plans/2026-09-25-kotlin-ingest.md` has 12 tasks and covers the `:core:ingest` module, which ports the Python ETL to Kotlin. It includes a CI parity gate and an early "Time a stats build" menu item.
- [ ] **Plan 2 not written yet.** It will be `docs/superpowers/plans/2026-09-25-live-refresh-app.md`.
- [ ] The user has reviewed neither plan and has not chosen an execution method. For the previous project they chose subagent-driven development.

## Next step

**Write Plan 2** with the `writing-plans` skill, from the spec above. It depends on Plan 1's public API: `IngestPipeline`, `IngestProgress`, `IngestReport`, `ValidationException`, `HttpFetcher`, `readMeta`, `currentSeason`, and the `player_xref` table (espn_id → player_id). Plan 1's Task 11 **Interfaces** block lists the exact names.

What Plan 2 must cover:

**Refresh**
- A `RefreshCoordinator` in `:app`. It runs `IngestPipeline` into `stats.db.new`, swaps the file atomically, reopens the executor, and bumps a data-version signal. Screens reload on that signal instead of the app restarting as it does today.
- Progress text, e.g. "Downloading 2026 play-by-play 12/19 MB".
- A "Load stats" screen when no database exists yet.
- Keep an existing install's database until the first refresh, and prompt the user to refresh.

**Settings:** a seasons checklist from 2012 through `currentSeason()`, defaulting to the current season plus the two before it. It lives in `UserPrefs` (`:core:datastore`).

**Live data**
- `live.db`, updated in place: news items, `news_player`, `injury_status` and `injury_note`, pruned at 30 days.
- ESPN endpoints, checked 2026-09-25 and needing no key:
  - `https://site.api.espn.com/apis/site/v2/sports/football/nfl/news?limit=50`. Its `articles[]` have `headline`, `description`, `published` and `links.web.href`, plus `categories[]` entries of `type=athlete` carrying `athleteId`. 36 of 50 articles had one.
  - `https://site.api.espn.com/apis/site/v2/sports/football/nfl/injuries`. About 350 KB gzipped. Shape: `injuries[]` (one per team), each with `injuries[]` of `{ id, status, shortComment, longComment, date, athlete{ displayName, position, team, links[] } }`. The ESPN athlete id comes from `athlete.links[].href` (`/id/<n>/`).
- The News screen auto-fetches when its data is more than 15 minutes old.

**Screens**
- **News** (☰): headlines; tapping one opens the browser; a player chip opens the player page.
- **Player page:** status, note timeline and tagged news. A Grid row tap opens it instead of `ProjectionsKey`.
- **Grid:** a Q/D/O/IR badge next to injured players.
- **Injury report:** switches to ESPN's live list, with nflverse practice participation alongside.
- Hide the projection and accuracy menu items and entry points.

**Removals**
- `BundleStatsDb` in `app/build.gradle.kts`.
- `StatsDbInstaller` (both the asset copy and the repo download).
- `.github/workflows/etl.yml`, and every mention of the `data` release.
- The "Time a stats build" menu item.
- Update CLAUDE.md: data flow, schema v6, Known Gaps.

**Files to read first:**
- `app/src/main/kotlin/dev/gridiron/app/{GridironApplication,GridironNavHost,NavKeys,StatsDbInstaller,TeamScreens}.kt`
- `core/database/src/main/kotlin/dev/gridiron/core/database/*`
- `core/data/src/main/kotlin/dev/gridiron/core/data/TeamsRepository.kt`
- `core/datastore`
- `feature/players/.../GridScreen.kt`, which holds the ☰ `menu` param and `onRowClick`.

## After Plan 2

1. Ask the user to review both plans and choose an execution method.
2. Execute Plan 1 first, one task per session. With subagent-driven development the ledger is at `.superpowers/sdd/2026-09-25-kotlin-ingest/progress.md`. It is git-ignored, so also tick tasks in this file.
3. Then execute Plan 2 the same way.
