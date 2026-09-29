# Session Handoff

**How the user wants to work:**
- One fresh session per batch of **4 tasks**, with `/clear` after each.
- Every session starts by reading this file, runs the next tasks, then updates this file, commits, pushes, and stops.
- Keep replies short. Ask a question only when blocked, one line at a time.

**Branch:** `claude/dreamy-euler-phbdq1`, based on `claude/relaxed-hypatia-73hhub` (the repo's main branch). Each new project goes in a new PR from this branch.

**History:** the full session-by-session log (projection engine, accuracy, props, K/D/ST, live data refresh) was removed from this file on 2026-09-28. It stays in git: `git show b991467:docs/superpowers/HANDOFF.md` (blob `3251e5059b3956029d6284f97179a35050c1696e`). The specs and plans in `docs/superpowers/` are executed and historical.

## Where things stand

The four projection sub-projects are built, reviewed and merged (PRs #4, #5, #6, #7, #9, #10): engine, accuracy page, Odds API props, and K/D/ST. The design is `specs/2026-09-26-projection-model-design.md`, which supersedes the pipeline half of `specs/2026-09-23-projections-design.md`. [PR #11](https://github.com/Palm9999/Lame/pull/11) (docs only) is open.

**In flight (2026-09-29):** working through CLAUDE.md's Known Gaps, one at a time, each with its own design. The first is built on this branch (spec `specs/2026-09-29-player-season-stats-design.md`, plan `plans/2026-09-29-player-season-stats.md`): a Player page "Season stats" section (season chips, season line with percentiles, game log) and K and D/ST metric sets for Compare. Phone timing for the section (up to 20 queries) isn't measured yet. The other gaps: yards-allowed tiers for D/ST, shifting an injured player's share to teammates, more metrics (NGS/FTN), saved Grid presets, season rollups.

## Rulings that still bind

- **Weather is out of scope** (the user, 2026-09-28: "Don't worry about weather"). Don't propose modeling it.
- **Points-allowed tiers** are each profile's own and editable, ESPN's by default (0, 1–6, 7–13, 14–17, 18–21, 22–27, 28–34, 35–45, 46+: 5, 4, 3, 1, 0, −1, −4, −5, −5). Points allowed are stored as a number; the phone scores the tiers in expectation (per game for rest of season). Saved profiles were migrated once (prefs `formatVersion` 2), with no fallback.
- **The accuracy gate covers K and D/ST** as well as QB, RB, WR and TE. If a position loses to the season-to-date average, tune its constants in `ForecastConstants.kt`.
- **The Grid's K and D/ST chips** bring their own packs (Kicking, Defense); every other chip leaves them out.
- **Judgments, not fits:** `MARKET_VARIANCE_RATIO` (0.5) and `ONE_SIDED_OVERROUND` (1.08). Props can't be backtested. The user kept both.
- **Unverified defaults:** the kicking scoring defaults (3/4/5, −1, 1, −1) are common values, not checked against ESPN's. ESPN's yards-allowed and blocked-kick D/ST scoring isn't modeled.

## Open checks on the phone (non-blocking)

- **Odds API shape:** the fixtures are hand-built from the v4 docs, because there's no key in the container. On the first refresh with the user's key (☰ → Settings → Betting props, then Refresh stats), the toast should say "Props moved N projections", and Settings shows credits left.
- **Accuracy page:** ☰ → Projection accuracy, season 2025. Report how long "Scoring every projected week…" shows. The floor-to-ceiling "held" figures should read about 78–83%.

## Container notes

`etl/build/stats.db` (2024–2026) and `etl/build/accuracy.db` (2024–2025) exist in this container. Rebuild them if it is fresh; the commands are in `CLAUDE.md`.
