# Gridiron

**A personal-use NFL fantasy football analytics and prediction app for Android.**

Target device: Samsung Galaxy S24 Ultra. Sideloaded, single user, not distributed.

---

## What this is

A private fantasy analytics tool. A complete commercial toolkit costs roughly **$337/yr stacked across five products** — rankings at FantasyPros, advanced metrics at PlayerProfiler, dynasty values at KeepTradeCut, ADP at Fantasy Football Calculator, and your roster at ESPN or Sleeper. Nobody joins that data, and the mobile experiences are uniformly poor.

Roughly **85% of what those products sell is computable from [nflverse](https://github.com/nflverse/nflverse-data)** — CC BY 4.0, play-by-play back to 1999, snap counts, depth charts, injuries, Next Gen Stats, with no API key and no rate limit.

## Core features

**The Grid** — a real mobile stat table. Frozen player column, 200+ sortable stats in packs, two-tier filtering, week-range recomputation with correct rate recalculation, saved presets, sparklines, CSV export. S Pen hover previews stat definitions.

**N-way comparison** — every commercial tool compares exactly two players. This compares up to four, across twenty columns, with percentile bars and an xFP-vs-actual scatter that identifies buy-low and sell-high candidates without words.

**Transparent projections** — opportunity-first, market-anchored, regression-heavy. Every projection ships a factor waterfall: baseline → matchup → game script → weather → injury.

**Scoring as configuration** — the pipeline ships stat components, never fantasy points. Your exact league settings are applied on-device, so every format works offline and switching leagues is instant.

**Offline-first** — it has to work on Sunday morning with no signal.

## Stack

Kotlin · Jetpack Compose · Room 3 · Hilt · Navigation 3 · WorkManager · Vico
Pipeline: GitHub Actions ETL → GitHub Releases as CDN → prebuilt SQLite shipped to the device. Cost: $0.

## Documentation

**[Product & Technical Specification](docs/PRODUCT_SPEC.md)** — start here.

| Document | Contents |
|---|---|
| [Data sources](docs/research/research-data-sources.md) | Free NFL data catalog, licensing, ingestion architecture. Endpoints live-tested. |
| [Stat catalog](docs/research/research-stats-catalog.md) | ~450 metrics by position and category, computability tiers, comparison UX patterns. |
| [Prediction models](docs/research/research-prediction-models.md) | Projection methodology, distributions, correlation, compute split, explainability. |
| [Android architecture](docs/research/research-android-architecture.md) | Module structure, the sticky-column table problem, charting, sync, accessibility. |
| [Competitive analysis](docs/research/research-competitive.md) | Feature gaps worth stealing. Positioning and monetization sections now moot. |

## Install on your phone

The app builds automatically and is always at the same link:

**https://github.com/Palm9999/Lame/releases/download/app/gridiron.apk**

Open that on the phone, then tap the download to install. One-time setup on a Galaxy phone:

1. **Settings → Security and privacy → Auto Blocker**: turn it off if it's on. It silently blocks installs from outside the Play Store.
2. **Settings → Security and privacy → More security settings → Install unknown apps**: allow the browser you downloaded with.
3. If Play Protect warns about an unrecognized app, choose **Install anyway**. It says that about any app not from the Play Store.

Every build is signed with the same key (`app/gridiron.keystore`, committed on purpose for this never-published app), so a new APK installs over the old one. A new build is published on every change and every Tuesday morning, after Monday Night Football.

## Status

| Phase | State |
|---|---|
| Spec and research | Done: [docs/](docs/PRODUCT_SPEC.md) |
| Data pipeline | Done: [`etl/`](etl/README.md). nflverse → pre-indexed SQLite, validated on every build. |
| Query builder | Done: [`core/statquery/`](core/statquery/README.md). 413k+ values checked against the ETL. |
| **The Grid** | **Done: first installable app.** Six stat packs, position filters, week ranges, per-game mode, positional heat map, tap-to-sort, hold-for-definition, name search. |
| Comparison | Next. |

## Modules

| Module | Kind | Role |
|---|---|---|
| `:app` | Android app | Wiring; ships `stats.db` inside the APK and copies it out on first launch |
| `:feature:players` | Android | The Grid screen and its ViewModel |
| `:core:table` | Android | Frozen-column stat table: one shared horizontal scroll state |
| `:core:designsystem` | Android | Theme, dark mode, colorblind-safe heat scale |
| `:core:data` | JVM | Stat packs, qualifying bars, formatting, repository |
| `:core:database` | JVM | Read-only SQLite access via the bundled driver |
| `:core:statquery` | JVM | Query builder |
| `:core:model` | JVM | Shared types |
| `:core:testing` | JVM | Test fixtures: a JDBC executor over the real database |

The data modules are plain JVM, so the phone's own database code, including the bundled SQLite driver, runs under test here. A test proves the JDBC fixture returns bit-identical results to that driver, so screenshot and ViewModel tests use JDBC over the real database with confidence.

### Where this departs from the spec

- **`stats.db` is read with the SQLite driver, not Room.** Room checks a prebuilt database against its entity definitions and can't express this one's `WITHOUT ROWID` table or covering index. The database is read-only and queried only through generated SQL. Room remains the plan for `user.db` (presets, rosters).
- **No Hilt yet.** One screen is wired by hand in `GridironApplication`. Hilt comes in with the second feature.
- **No navigation library yet.** One screen.

## Building

```bash
# Data
cd etl && pip install -r requirements.txt
python -m gridiron_etl.build --seasons 2024 2025 2026 --out build/stats.db && cd ..

# Everything else (JDK 17+, Android SDK with platform 37)
GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test                  # all tests, against the real data
GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :app:assembleRelease  # the APK
./gradlew :feature:players:recordRoborazziDebug                      # screenshots of the Grid
```

## Attribution

Player and team data derived from [nflverse](https://github.com/nflverse/nflverse-data), licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). ADP from [Fantasy Football Calculator](https://fantasyfootballcalculator.com/). Weather from the [US National Weather Service](https://www.weather.gov/documentation/services-web-api).
