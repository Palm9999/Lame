# Live Data Refresh — Design

**Status:** approved in conversation, section by section (2026-09-25).
**Follow-up (separate project):** projections computed on-device.

## Goal

No stats come from the repo. Tapping Refresh in the app pulls the latest stats,
injuries and news straight from public sources over the internet. There is no
bundled database, no repo pipeline, and no hosted backend.

## Decisions

| Question | Decision |
|---|---|
| Freshness | Mixed. Injuries and news are near-live (ESPN). The deep stat catalog is next-morning (nflverse updates overnight). |
| News | Both a league headlines feed and per-player news on player pages. |
| Projections | Deferred to a follow-up project that ports the pipeline on-device. Projection entry points are hidden until then. |
| Seasons | User-selectable in Settings, from 2012 through the current season. Default is the current season plus the two before it. |
| Stats engine | Approach A: port the Python stat pipeline to Kotlin and run it on the phone. Rejected: nflverse's pre-aggregated weekly stats (loses play-level metrics such as long-TD bonuses), a hosted backend or Supabase (still a pipeline to run, can't do the crunching on its free tier, and breaks the offline SQLite query model). |

## Architecture

- **`:core:ingest`** (new, pure JVM Kotlin, testable on CI):
  - `Sources`: per-season URLs for nflverse and ffopportunity.
  - `Downloader`: conditional GET using ETag/Last-Modified. It follows GitHub's release redirects, and the validators come from the final response.
  - Streaming, column-selecting CSV/CSV.gz reader.
  - Kotlin ports of `transform.py`, `metrics.py` and `teams.py`, plus the `validate.py` checks.
  - `StatsDbWriter`: the schema v5 DDL from `schema.py`, plus the same indexes and `ANALYZE`.
  - SQLite is reached through a small writable-database interface. JVM tests use sqlite-jdbc; the app uses the Android driver.
- **`:core:data` → `live` package** (new): an ESPN client (headlines, injuries) that writes to a separate, in-place-updated `live.db`.
- **`:app`**:
  - A `RefreshCoordinator` (application-scope coroutine, progress `StateFlow`) replaces `StatsDbInstaller`.
  - The stats `QueryExecutor` can be reopened after a swap, and screens reload on a data-version signal instead of an app restart.
- **Unchanged:** `:core:statquery`, the repositories, and the Grid, Compare, Scoring and team screens. They read the same tables.

## Inputs

Per selected season (all from `github.com/nflverse/nflverse-data/releases/download/…` except where noted):

| File | Approx. size |
|---|---|
| `pbp/play_by_play_{season}.csv.gz` | 19 MB |
| `snap_counts/snap_counts_{season}.csv.gz` | 0.5 MB |
| `injuries/injuries_{season}.csv.gz` | 0.1 MB |
| `ffverse/ffopportunity` `latest-data/ep_weekly_{season}.csv` (no gzip variant) | 5.4 MB |

Once: `players/players.csv.gz` (2.5 MB). It supplies the `espn_id` used to match ESPN players.

Live (ESPN, no key, unofficial):

- `site.api.espn.com/apis/site/v2/sports/football/nfl/news?limit=50`. Articles carry `categories[type=athlete].athleteId`.
- `site.api.espn.com/apis/site/v2/sports/football/nfl/injuries`. About 350 KB gzipped, around 800 entries with `status`, `shortComment`, `longComment` and `date`.

## Refresh flow

**Triggers:**
- The ☰ Refresh item runs stats plus live data.
- A fresh install shows a "Load stats" screen with the same action and a Retry.
- The News screen auto-fetches live data when it is more than 15 minutes old. That fetch never touches stats.

**Stats refresh:**
1. Conditional-GET each input for each selected season, plus `players` once. Store the ETag/Last-Modified for each file.
2. A season whose inputs are all unchanged, and whose rows already exist in the current `stats.db`, has those rows copied over. There is no download and no recompute.
3. Changed seasons are streamed row by row with only the needed columns. Per player-week and per team-week aggregates are held in memory, which is small; whole files are never loaded. The steps are:
   - the metric computation;
   - snap share, team defense and the injury report;
   - the validation checks.
4. Write `stats.db.new` (schema, rows, indexes, `ANALYZE`).
5. Close the executor, atomically rename the new file into place, reopen, and bump the data version.

**Progress, interruption and storage:**
- Progress text shows the stage and bytes, e.g. "Downloading 2026 play-by-play 12/19 MB" or "Crunching 2026…".
- Refresh continues if the user leaves the screen. If the process is killed, the old database stays intact and the next refresh restarts.
- Downloaded files are deleted after a successful build; only their validators are kept. Steady state is about 45 MB, with a first-build peak of about 160 MB.
- Target: under one minute per season on a mid-range phone. This is measured early, before the full port.

## Injuries and news

**`live.db` tables:**
- `news_item(id, published, headline, description, url)` and `news_player(news_id, player_id)`.
- `injury_status(player_id, status, short_comment, long_comment, updated_at)`: the current snapshot, replaced on each fetch.
- `injury_note(player_id, noted_at, comment)`: appended whenever a player's comment changes.
- Rows older than 30 days are pruned.

**Player matching:**
- ESPN athlete id is mapped to the app's `player_id` through `players.espn_id`.
- Unmatched items still appear in the feed, just not linked to a player.

**Official data:** nflverse's official injury report (practice participation) stays in `stats.db` (`injury_report`, next-morning).

**Screens:**
- **News** (☰): headlines, newest first. Tapping one opens the URL in the browser. A player chip opens the Player page.
- **Player page** (new):
  - Shows name, team, position, current status and comment, the note timeline, and tagged news.
  - Grid row tap opens it instead of the projection screen.
  - The projections follow-up adds its card here.
- **Grid:** a status badge (Q/D/O/IR) next to injured players.
- **Injury report** (existing): ESPN's live list grouped by team, with official practice participation alongside when available.

## Settings

- **Seasons:** checkboxes from 2012 through the current season. The default is the current season plus the two before it.
- Changes apply on the next refresh: an added season is fetched and crunched, and a removed season's rows are dropped.
- A season nflverse hasn't published yet (404 before kickoff) is skipped with a note.

## Error handling

Existing data is never lost.

- **No network:** a clear message and current data kept. A fresh install shows Retry.
- **Validation failure:** the new database is discarded and the message names the failed check.
- **Upstream column missing or renamed:** "nflverse changed column X", and the old data is kept.
- **ESPN down:** the last live data is shown with an "as of" time. Stats refresh is independent.
- **Disk full:** temporary files are cleaned up and the old data is kept.

## Testing

- **Unit tests per ported transform:** hand-computed fixtures in `:core:ingest`, with the Python tests ported alongside.
- **Parity gate (CI):** the Python ETL and the Kotlin ingest run on the same real season's inputs. Every `(player, season, week, metric)` value must match within float tolerance, as must `team_week_defense` and `injury_report`. A ported transform lands only once parity passes.
- **Query contract tests:** `:core:statquery`'s tests run against a Kotlin-built database.
- **ESPN parsing:** tested against recorded real responses.
- **On-device timing:** an early build reports refresh duration so real phone numbers are known before the full port.

## Removals

- APK asset bundling of `stats.db` (the `BundleStatsDb` task in `app/build.gradle.kts`), `StatsDbInstaller`'s asset copy and its repo-download refresh.
- `.github/workflows/etl.yml` and the `data` release.
- **Python ETL:** stays only as a CI parity reference; it never produces data the app uses. It is deleted after the projections follow-up, which needs it as a reference too.
- Projection menu items and entry points are hidden until the follow-up.
- **Existing installs:** the database already on the device keeps working until the first on-device refresh replaces it, and the app prompts to refresh.
- Update CLAUDE.md's data-flow, schema and known-gap sections.

## Out of scope

- Projections on device (follow-up).
- Cross-device sync of user data. This is where a backend such as Supabase could help later.
- Live in-game stats.
- NGS, FTN charting and schedules transforms (not implemented today).

## Risks

- **Phone crunch time and memory** for about 50k play-by-play rows × 40 columns: measured in the first milestone.
- **ESPN endpoints are unofficial** and can change shape. Mitigations: parsing is isolated in one client with recorded-response tests, and failure degrades to "as of" data.
- **nflverse column drift:** caught by explicit required-column checks.
