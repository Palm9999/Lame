# Gridiron

**A free, no-paywall NFL fantasy football analytics and prediction app for Android.**

> The whole stat sheet, free.

Status: **pre-implementation.** This repository currently contains the product specification and supporting research. No application code yet.

---

## What this is

A complete fantasy football toolkit today costs roughly **$337/yr stacked across five products** — rankings at FantasyPros, advanced metrics at PlayerProfiler, dynasty values at KeepTradeCut, ADP at Fantasy Football Calculator, and your roster at ESPN or Sleeper. Nobody joins that data, and the mobile experiences are uniformly poor.

Roughly **85% of the metrics those products sell are computable from free, openly-licensed sources** — primarily [nflverse](https://github.com/nflverse/nflverse-data) (CC BY 4.0), which publishes play-by-play back to 1999, snap counts, depth charts, injuries, Next Gen Stats and more with no API key and no rate limit.

This app aims to unify them, compute the advanced metrics in-house, and ship it as a genuinely good phone experience with no paywall.

## Core differentiators

**The Grid** — a real mobile stat table. Frozen player column, 200+ sortable stats organized into packs, two-tier filtering, week-range recomputation, saved presets, sparklines, CSV export. Mobile stat tables are broken market-wide; this is the least-defended surface in the sector.

**N-way comparison** — every competing tool compares exactly two players. This one compares up to four, across twenty columns, with percentile bars and an xFP-vs-actual scatter that explains regression without words.

**Transparent projections** — opportunity-first, market-anchored, regression-heavy. Every projection ships a factor waterfall showing exactly why the number is what it is. No black box.

**Honest accuracy** — weekly fantasy projection R² is only 3–23%, and season-long industry projections carry a documented +21.6 point optimism bias. Every competitor hides this. We publish live accuracy tracking against naive baselines and consensus.

**Scoring is configuration** — the server ships stat components, never fantasy points. Your exact league scoring is applied on-device, so half-PPR, TE-premium, superflex and IDP all work offline, for free.

## Stack

Kotlin · Jetpack Compose · Room 3 · Hilt · Navigation 3 · WorkManager · Vico
Backend: GitHub Actions ETL → GitHub Releases as CDN → Cloudflare Worker (odds proxy only). Target cost: $0.

## Documentation

**[Product & Technical Specification](docs/PRODUCT_SPEC.md)** — start here.

Supporting research:

| Document | Contents |
|---|---|
| [Data sources](docs/research/research-data-sources.md) | Free NFL data catalog, licensing analysis, ingestion architecture. Endpoints live-tested. |
| [Stat catalog](docs/research/research-stats-catalog.md) | ~450 metrics by position and category, with computability tiers and comparison UX patterns. |
| [Prediction models](docs/research/research-prediction-models.md) | Projection methodology, distributions, correlation, on-device compute split, explainability. |
| [Android architecture](docs/research/research-android-architecture.md) | Module structure, the sticky-column table problem, charting, sync, accessibility. |
| [Competitive analysis](docs/research/research-competitive.md) | 25 competitors, paywall map, feature gaps, league-sync legality, Play compliance. |

## Open risks

Three items in the spec are flagged red and need resolution before implementation — see [§3](docs/PRODUCT_SPEC.md#3-locked-decisions) and [§11](docs/PRODUCT_SPEC.md#11-compliance-checklist):

1. Ad monetization combined with full odds display is a specifically-named Google Play violating pattern. Mitigations are concrete; verification is a launch blocker.
2. The Sleeper API is non-commercial-only, and ad support plausibly makes this app commercial. One email resolves it.
3. NFL trademarks — no logos, wordmarks or headshots; team colors and abbreviations only.

## Attribution

Player and team data derived from [nflverse](https://github.com/nflverse/nflverse-data), licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). ADP data courtesy of [Fantasy Football Calculator](https://fantasyfootballcalculator.com/). Weather from the [US National Weather Service](https://www.weather.gov/documentation/services-web-api).

Not affiliated with, endorsed by, or associated with the National Football League or any of its teams.
