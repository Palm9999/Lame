# Dynasty & keepers — design

**Goal:** a long-term value for every player (dynasty trades) and, for a keeper league priced by draft round, which of your players to keep. Market values, not an app model (the user's call, 2026-10-05).

## Where

More → League → **Dynasty & keepers** (and ☰), always the current season. Two tabs: **Dynasty** and **Keepers**. Keepers needs a synced league with your team chosen; otherwise it says so.

## Data

**FantasyCalc** (`https://api.fantasycalc.com/values/current?isDynasty=true&numQbs=Q&numTeams=T&ppr=P`, keyless, JSON array of about 420 players). Each entry: `player.{name, position, espnId, maybeAge, maybeTeam}`, `value` (dynasty), `redraftValue`, `overallRank`, `positionRank`, `trend30Day`.

- Format: `T` = the active league's team count, else 12; `Q` = 2 when the league's slots include `OP`, else 1; `P` = 1, 0.5 or 0 from a WR catch's worth in the active profile (the thresholds `AdpParser.format` uses).
- `DynastyRepository` (`:core:data`) fetches when the screen opens, holds each format's list in memory for 6 hours, keeps the last good list on a failure and reports "Not updated: …" (`couldn't reach FantasyCalc` / `FantasyCalc changed its format`).
- Players link through `espnId` → `PlayerDirectory.playerIds`; a D/ST or K isn't valued by FantasyCalc (QB, RB, WR, TE, and rookie picks, which are dropped).

**ESPN draft** (keeper costs): the league's `view=mDraftDetail` read with the league login, `draftDetail.picks[]`: `playerId`, `roundId`, `teamId`, `keeper`. The shape is from memory, unverified; a missing or unreadable draft leaves every player at the undrafted round and the tab says "No draft found: every player costs round N". Read when the Keepers tab opens, cached with the league snapshot's time.

## Keeper rule

Per league, stored on `EspnLeagueEntry` in the prefs JSON (new fields with defaults, `formatVersion` stays 4):

- `keepers` (default 2), `keeperPenalty` rounds (default 1), `undraftedRound` (default: the league's roster size, its slot counts summed without IR), `keeperCosts`: per-player cost-round overrides.
- **Cost round** = override, else (drafted round − `keeperPenalty` if the pick was itself a keeper, else the drafted round), at least 1; never drafted = `undraftedRound`.
- **Worth round** = ceil(redraft rank among all valued players / teams), redraft rank by `redraftValue`.
- **Surplus** = cost round − worth round (positive: worth more than he costs). The top `keepers` by surplus, then dynasty value, are marked **Keep**; a player FantasyCalc doesn't value has no worth round and is listed last.

Pure functions in `:core:projections` (`Keepers.cost`, `Keepers.rank`), tested without Android.

## Screens (`:feature:projections`)

- **Dynasty:** rank, name, position, team, age, dynasty value, 30-day trend (+/−); position chips (All, QB, RB, WR, TE), owner tags and a Free agents chip when a league is synced; a row opens the Player page.
- **Keepers:** your roster by surplus: cost round, worth round, surplus, dynasty value, "Keep" on the top N. A settings row (keepers, penalty, undrafted round) edits in place; tapping a player's cost sets an override (clear resets it).

## Testing

- `DynastyRepositoryTest`: recorded FantasyCalc response (trimmed), format URL from league and profile, linking, cache and failure keeping the last list.
- `EspnDraftParserTest`: a hand-built `mDraftDetail` (flagged unverified), missing draft.
- `KeepersTest`: cost (drafted, keeper penalty, floor 1, undrafted, override), worth round, surplus ranking and ties.
- Prefs round trip for the new league fields; screen tests for both tabs.

## Out of scope

The app's own age-curve model; auction keepers; Superflex beyond the `OP` slot check; rookie draft picks' values; years-kept limits.
