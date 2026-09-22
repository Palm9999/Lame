# Gridiron

**A personal-use NFL fantasy football analytics and prediction app for Android.**

Status: **pre-implementation.** This repository currently contains the product specification and supporting research. No application code yet.

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

## Status

| Phase | State |
|---|---|
| Spec and research | Done: [docs/](docs/PRODUCT_SPEC.md) |
| **Data pipeline** | Done: [`etl/`](etl/README.md). nflverse → 6.3 MB pre-indexed SQLite, validated on every build. |
| **Query builder** | Done: [`core/statquery/`](core/statquery/README.md). Verified against the real database: 413,179 values agree with the ETL. |
| The Grid (first Android UI) | Next. Needs an environment with the Android SDK. |

## Building

```bash
# Data
cd etl && pip install -r requirements.txt
python -m gridiron_etl.build --seasons 2024 2025 --out build/stats.db

# Kotlin modules (JDK 17+)
./gradlew build
GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:statquery:test   # + contract tests
```

## Attribution

Player and team data derived from [nflverse](https://github.com/nflverse/nflverse-data), licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). ADP from [Fantasy Football Calculator](https://fantasyfootballcalculator.com/). Weather from the [US National Weather Service](https://www.weather.gov/documentation/services-web-api).
