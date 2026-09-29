# Session Handoff

**How the user wants to work:**
- One fresh session per batch of **4 tasks**, with `/clear` after each.
- Every session starts by reading this file, runs the next tasks, then updates this file, commits, pushes, and stops.
- Keep replies short. Ask a question only when blocked, one line at a time.

**Branch:** `claude/dreamy-euler-phbdq1`, based on `claude/relaxed-hypatia-73hhub` (the repo's main branch). Each new project goes in a new draft PR from this branch. After a PR merges, restart the branch first: `git fetch origin claude/relaxed-hypatia-73hhub && git checkout -B claude/dreamy-euler-phbdq1 origin/claude/relaxed-hypatia-73hhub`.

**History:** the full session-by-session log (projection engine, accuracy, props, K/D/ST, live data refresh) was removed from this file on 2026-09-28. It stays in git: `git show b991467:docs/superpowers/HANDOFF.md` (blob `3251e5059b3956029d6284f97179a35050c1696e`). The specs and plans in `docs/superpowers/` are executed and historical.

## Where things stand

The four projection sub-projects and the Player page season stats are built, reviewed and merged (PRs #4, #5, #6, #7, #9, #10, #11). The projection design is `specs/2026-09-26-projection-model-design.md`, which supersedes the pipeline half of `specs/2026-09-23-projections-design.md`. Only the docs-only draft [PR #12](https://github.com/Palm9999/Lame/pull/12) is open (HANDOFF and the yards-allowed spec).

**Just shipped (2026-09-29): D/ST yards-allowed tiers** (spec `specs/2026-09-29-dst-yards-allowed-design.md`, plan `plans/2026-09-29-dst-yards-allowed.md`, on draft PR #14). Yards allowed (net) are a D/ST weekly stat scored through editable per-profile tiers (ESPN's on by default in every preset and, migrated once at prefs `formatVersion` 3, every saved profile; the table is from memory and unverified), projected as their own stat, drawn jointly with points allowed in the Monte Carlo, and shown in the Defense pack, Compare and the Player page's D/ST log. Measured on 2024–2025: points/yards correlation 0.666 (constant 0.67); yards spread 0.239 of the league mean (`DST_YA_CV` 0.25). `RANGE_WIDENING[DST]` stayed 1.22 (pooled held ≈ 80%). The 2025 gate has the model at 5.26 MAE against the season average's 6.00 at D/ST. Every team's D/ST is always projected (the min-points gates skip only kickers). `INGEST_VERSION` 5, `FORECAST_VERSION` 5. **Next:** the user reviews PR #14 and merges it; then the next gap.

**The other candidate gaps** (the user picks after this one; each gets its own brainstorm, spec, plan and PR):
- **Injured player's share to teammates:** the forecast shows an Out/IR player as Out and doesn't move his share.
- **More metrics (NGS/FTN):** wired in `sources.py` but not transformed; needs the Python ETL, the Kotlin port and the parity gate.
- **Saved Grid presets:** planned home is a `user.db` (not built).
- **Season rollups:** pre-aggregated season totals for the common full-season Grid view.

**Just shipped (PR #11):** Player page "Season stats" (chips, season line with position percentiles, game log), K and D/ST metric sets for Compare (no Scatter tab for them), and the docs cleanup. Spec `specs/2026-09-29-player-season-stats-design.md`, plan `plans/2026-09-29-player-season-stats.md`.

## Rulings that still bind

- **Weather is out of scope** (the user, 2026-09-28: "Don't worry about weather"). Don't propose modeling it.
- **Points-allowed tiers** are each profile's own and editable, ESPN's by default (0, 1–6, 7–13, 14–17, 18–21, 22–27, 28–34, 35–45, 46+: 5, 4, 3, 1, 0, −1, −4, −5, −5). Points allowed are stored as a number; the phone scores the tiers in expectation (per game for rest of season). Saved profiles were migrated once (prefs `formatVersion` 2), with no fallback.
- **The accuracy gate covers K and D/ST** as well as QB, RB, WR and TE. If a position loses to the season-to-date average, tune its constants in `ForecastConstants.kt`.
- **The Grid's K and D/ST chips** bring their own packs (Kicking, Defense); every other chip leaves them out.
- **Judgments, not fits:** `MARKET_VARIANCE_RATIO` (0.5) and `ONE_SIDED_OVERROUND` (1.08). Props can't be backtested. The user kept both.
- **Unverified defaults:** the kicking scoring defaults (3/4/5, −1, 1, −1) are common values, not checked against ESPN's. ESPN's yards-allowed tiers are from memory; blocked-kick D/ST scoring isn't modeled.

## Open checks on the phone (non-blocking)

- **Odds API shape:** the fixtures are hand-built from the v4 docs, because there's no key in the container. On the first refresh with the user's key (☰ → Settings → Betting props, then Refresh stats), the toast should say "Props moved N projections", and Settings shows credits left.
- **Season stats timing:** open a player with a full season (☰ → any Grid row). The section runs up to 20 small queries; report how long it takes to appear.
- **Accuracy page:** ☰ → Projection accuracy, season 2025. Report how long "Scoring every projected week…" shows. The floor-to-ceiling "held" figures should read about 78–83%.

## Container notes

`etl/build/stats.db` (2024–2026) and `etl/build/accuracy.db` (2024–2025) exist in this container. Rebuild them if it is fresh; the commands are in `CLAUDE.md`.

`RealDatabaseContractTest > scoring a full season for every player is fast` fails in this container (300–500 ms against a 250 ms budget) with or without any change; CI passes it. Don't chase it here.

Bash tool calls sometimes fail with a transient "classifier gave no verdict" error; retry once, or use Read, Grep and Glob meanwhile.

The `doc-cleanup` skill (`.claude/skills/doc-cleanup`) audits Markdown for stale claims; the 2026-09-28 pass fixed `CLAUDE.md`, `README.md`, `etl/README.md`, `PRODUCT_SPEC.md` and this file. `docs/research/*` and the plan bodies were not audited.
