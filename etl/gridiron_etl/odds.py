"""Betting-market ingestion: The Odds API player props, de-vigged to fair
probabilities. Player props are a per-event, per-market call, unlike the
season-partitioned files `sources.py` handles, so this module has its own
fetch shape rather than reusing `Source`.

Availability caveat (spec §1 stage 5, PRODUCT_SPEC §10): the free tier is
request-limited. A missing or one-sided market degrades to "no market signal
for this player" — never a build failure and never a divide against a
missing price.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path

import polars as pl
import requests

log = logging.getLogger(__name__)

BASE = "https://api.the-odds-api.com/v4"
API_KEY_ENV = "GRIDIRON_ODDS_API_KEY"

PLAYER_PROP_MARKETS = [
    "player_reception_yds", "player_receptions", "player_rush_yds",
    "player_pass_yds", "player_anytime_td",
]


def _american_to_raw_prob(price: int) -> float:
    if price < 0:
        return (-price) / (-price + 100)
    return 100 / (price + 100)


def devig(price_over: int, price_under: int) -> float:
    """Proportional (multiplicative) de-vig: fair P(over) from the two-sided market."""
    p_over = _american_to_raw_prob(price_over)
    p_under = _american_to_raw_prob(price_under)
    return p_over / (p_over + p_under)


def parse_player_props(event: dict) -> pl.DataFrame:
    """One row per (player, market) with a two-sided line, fair-probability of the
    Over. One-sided or malformed markets are skipped, not raised.

    Loops markets on the outside and bookmakers on the inside so that every
    market in PLAYER_PROP_MARKETS is checked, not just the first one a
    bookmaker happens to list data for — an event can have both
    `player_reception_yds` and `player_receptions`, and both belong in the
    output.
    """
    rows: list[dict] = []
    bookmakers = event.get("bookmakers", [])
    for market_key in PLAYER_PROP_MARKETS:
        for book in bookmakers:
            market = next(
                (m for m in book.get("markets", []) if m["key"] == market_key), None
            )
            if market is None:
                continue
            by_player: dict[str, dict[str, dict]] = {}
            for outcome in market.get("outcomes", []):
                name = outcome.get("description")
                side = outcome.get("name")
                if name is None or side not in ("Over", "Under"):
                    continue
                by_player.setdefault(name, {})[side] = outcome
            market_rows = []
            for player_name, sides in by_player.items():
                if "Over" not in sides or "Under" not in sides:
                    continue
                over, under = sides["Over"], sides["Under"]
                if over.get("point") != under.get("point"):
                    continue
                market_rows.append({
                    "player_name": player_name,
                    "market": market_key,
                    "line": float(over["point"]),
                    "fair_prob": devig(int(over["price"]), int(under["price"])),
                })
            # Only the first bookmaker with usable data for THIS market — a
            # future improvement could average across books, not needed for
            # v1. Once found, move on to the next market rather than
            # continuing to scan bookmakers for this one.
            if market_rows:
                rows.extend(market_rows)
                break
    return pl.DataFrame(rows, schema={"player_name": pl.String, "market": pl.String,
                                       "line": pl.Float64, "fair_prob": pl.Float64})


def fetch_events(sport_key: str, api_key: str | None = None, cache_dir: Path | None = None,
                  force: bool = False, timeout: int = 30) -> list[dict]:
    """This week's NFL events. Live HTTP — not unit tested, same convention as
    `sources.fetch`."""
    key = api_key or os.environ.get(API_KEY_ENV)
    if not key:
        log.warning("no odds API key set (%s); market stage will be skipped", API_KEY_ENV)
        return []
    resp = requests.get(f"{BASE}/sports/{sport_key}/events",
                         params={"apiKey": key}, timeout=timeout)
    resp.raise_for_status()
    return resp.json()


def fetch_player_props(event_id: str, sport_key: str, markets: list[str],
                        api_key: str | None = None, cache_dir: Path | None = None,
                        force: bool = False, timeout: int = 30) -> dict:
    key = api_key or os.environ.get(API_KEY_ENV)
    if not key:
        log.warning("no odds API key set (%s); market stage will be skipped", API_KEY_ENV)
        return {}
    resp = requests.get(
        f"{BASE}/sports/{sport_key}/events/{event_id}/odds",
        params={"apiKey": key, "regions": "us", "markets": ",".join(markets),
                "oddsFormat": "american"},
        timeout=timeout,
    )
    resp.raise_for_status()
    return resp.json()
