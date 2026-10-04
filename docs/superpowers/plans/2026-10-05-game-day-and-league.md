# Game day and league plan (2026-10-05)

One commit per feature, each with its HANDOFF section and phone check.

1. **Trade value** — `Value` tab in Rest of season: ROS points over replacement, all positions. `ReplacementLevel.of(rows, teams, slots)` (core/projections): per position the (teams × starting slots + FLEX share)-th best. Tests: `ReplacementLevelTest`.
2. **Power rankings** — top of Playoff odds: each team's `Trades.value`, rank, strongest/weakest position (starters' value over replacement vs league average). Test: screen test.
3. **Planner chip** — byes (from `game` table per NFL team) per remaining week: empty slots labelled bye / no projection, best free-agent fill (`Lineups.pickups` on that week); D/ST and K streamers for the next 3 weeks. Tests: `PlannerTest`.
4. **Matchup preview** — in My lineup with an opponent: slot-by-slot edges and swing players (widest floor-ceiling). Test: `MatchupPreviewTest`.
5. **Live win chance** — My lineup during games: ESPN live points + projection × share of game left, sd × √share; clock parser; 1-minute refresh while live. Tests: `GameClockTest`, `LiveWinTest`.
6. **Widget** — `AppWidgetProvider` + RemoteViews reading a one-line summary file written by My lineup and the 2-hourly worker. Test: `WidgetSummaryTest`.
7. **Draft assistant** — ☰ → Draft: FFC ADP (name match) + ESPN preseason projection or last season's points; tap = drafted, long-press = mine; best available by need. Tests: `DraftBoardTest`, ADP parse.
