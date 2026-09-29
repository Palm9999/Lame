# Injured players' share to teammates

**Status:** design approved in conversation (2026-09-29). Next: user review of this spec, then the implementation plan.

## Purpose

The forecast ignores injuries. A player nflverse lists Out for a week is still in his team's active set if he played in its last two games, and his share of the team's targets and carries is spent on a man who won't play. His teammates are under-projected by the same amount. This drops Out and Doubtful players from a week's projection and lets their team's shares renormalize, so the volume goes to the teammates who will play.

**Success:** for every week with an injury report, an Out or Doubtful QB/RB/WR/TE has no projection row, his team's target and carry shares still sum to one, and the next QB takes the passing share when the starter is out. CI's accuracy gate still has the model beating the season-to-date average at QB, RB, WR, TE, K and D/ST, and the 2025 MAE for QB, RB, WR and TE doesn't get worse than before the change.

**Decisions from the user:**
- Past weeks and the upcoming week both use it, so the backtest and the gate measure it.
- Questionable counts as playing: projections stay conditional on the player playing.

**Not in scope:** role-aware routing (an RB's carries only to RBs, a WR's targets to WRs first); weighting a Questionable player by his chance of playing; injuries in future weeks (rest of season); kickers and D/STs; weather; ESPN's live injury feed (the forecast reads `stats.db` only).

## Data

`injury_report` (nflverse) has one row per (player, season, week) for 2024 onward: weeks 1–22 for 2024 and 2025, and the weeks published so far in 2026. Measured on 2024–2026 QB/RB/WR/TE rows:

| Status | Rows | Played that week |
|---|---|---|
| Out | 782 | 0 |
| Doubtful | 114 | 0 |
| Questionable | 841 | 429 (51%) |
| none (practice note only) | 2102 | 1656 (79%) |

So Out and Doubtful mean "did not play" without exception and the rest is uncertain. Only Out and Doubtful count as absent.

The report is published Wednesday to Friday, before the game, so reading it for a past week uses information that was known before kickoff. There is no look-ahead in the backtest. For the upcoming week, an unpublished report has no rows and behavior is unchanged.

## Design

**Inputs.** `ForecastInputs` gains `absent: Set<Triple<String, Int, Int>>`: (player id, season, week) for every `injury_report` row with status Out or Doubtful. `loadInputs` reads it with one query joined to `player` for the four offense positions. It defaults to empty, so existing tests construct inputs unchanged.

**Non-QB players** (`Projector.prepareWeek`). `isActive` returns false for a player in `absent` for the week. His `Draft` still exists (the same `drafts` list feeds the props matcher), but he isn't in `kept`, so he gets no shares, no `Prepared` and no emitted rows. `normalizeShares` scales the remaining players' target shares to one and carry shares to one, which spends the freed volume in proportion to each teammate's share. Team volume (layer 1) is unchanged.

**QBs.** `expectedStarter` ignores a QB in `absent` at every step: the game record's starter, the most recent listed starter, and the attempts fallback. Its candidate list is `onTeam` minus the absent, and the game record's starter still wins when he isn't absent. If every QB on the team is absent, there is no starter and no QB is projected that week, as with a team with no QB candidate today. An absent QB is never in `kept`.

**Limit, unchanged:** a teammate who hasn't played for the team in its last `ACTIVE_WINDOW` (2) games has no share and still gets none. A newly promoted backup with no recent games won't absorb the freed volume; it lands on the other active players.

**Rest of season.** An injury this week says nothing about later weeks, but rest of season is summed from the upcoming week's roster, so dropping an Out player there would erase all his later weeks and inflate his teammates'. `prepareWeek` therefore builds the upcoming week's team twice when anyone is absent: once with `absent` applied (this week's emitted rows, and this week's contribution to the sum) and once without (the baseline every later week starts from). A player who is Out this week has no row for it, adds nothing to the sum for it, and keeps his later weeks.

**No new constants.** `FORECAST_VERSION` becomes 6 so the phone rebuilds projections on its next refresh.

## Testing

- **Unit (`:core:forecast`):** an Out WR gets no row and his team's remaining target shares sum to one, each larger than before in the same ratio; the same for a Doubtful RB's carries; a Questionable player is unchanged; a QB listed Out hands the passing share to the next QB; every QB absent means no QB projection; an empty `absent` reproduces today's numbers exactly.
- **Loader:** `loadInputs` on a small database reads Out and Doubtful rows and ignores Questionable, Note and null.
- **Accuracy gate:** run before and after on 2024–2025 and report the 2025 MAE by position (QB, RB, WR, TE, K, D/ST) and the floor-to-ceiling "held" figures. A position that gets worse is fixed or excluded from the change before shipping; the gate is never skipped. Injured players' own weeks leave the backtest after the change, which alone can move a position's MAE; if a position worsens, diagnose on the player-weeks both builds project before deciding.
- **Real database:** the existing contract tests still pass (the scoring speed test is known to fail in this container, see `HANDOFF.md`).

## Risks

- **Fewer backtest rows.** Removing injured players' rows changes what the gate averages over. A position that worsens gets a comparison on the shared player-weeks; the gate's own table stays the CI check.
- **Doubtful players who play.** None did in the sample, but nflverse can change its statuses; a wrong Out drops a real player. Accepted, since the report is the league's official designation.
- **Proportional redistribution is crude.** A WR1 out lifts a TE and RBs' targets by the same factor as the other WRs'. Role-aware routing is the follow-up if the gate shows a gap.

## Docs

Update this spec's parent (`2026-09-26-projection-model-design.md`: the "Injury redistribution" row and the out-of-scope list), `CLAUDE.md`'s "Not modeled" line and its `:core:forecast` module note, and `HANDOFF.md`.
