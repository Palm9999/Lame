"""CLI entry point: nflverse releases -> stats.db.

    python -m gridiron_etl.build --seasons 2024 2025 --out build/stats.db
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import polars as pl
import requests

from . import sources, schema, transform, validate as validation
from .metrics import METRICS, metric_rows, sparse_metric_ids

log = logging.getLogger("gridiron.build")



def _search_name(expr: pl.Expr) -> pl.Expr:
    """Normalize for the indexed prefix-range search.

    Must stay identical to `normalizeSearch` in :core:statquery — a contract
    test there checks every player's stored search_name against it. NFKD first
    so accented letters fold to their base letter instead of being dropped.
    """
    return (
        expr.str.normalize("NFKD")
        .str.to_lowercase()
        .str.replace_all(r"[^a-z0-9 ]", "")
        .str.strip_chars()
    )


def build_players(players_csv: Path) -> pl.DataFrame:
    df = pl.read_csv(players_csv, infer_schema_length=20_000)
    cols = set(df.columns)

    # nflverse has renamed these over time; probe rather than assume.
    id_col = next((c for c in ("gsis_id", "player_id", "gsis_it_id") if c in cols), None)
    name_col = next((c for c in ("display_name", "full_name", "football_name") if c in cols), None)
    if id_col is None or name_col is None:
        raise RuntimeError(f"players.csv missing id/name columns; saw {sorted(cols)[:40]}")

    pos_col = next((c for c in ("position", "position_group") if c in cols), None)
    team_col = next((c for c in ("latest_team", "team_abbr", "team") if c in cols), None)
    pfr_col = next((c for c in ("pfr_id", "pfr_player_id") if c in cols), None)

    out = df.select(
        player_id=pl.col(id_col),
        full_name=pl.col(name_col),
        position=pl.col(pos_col) if pos_col else pl.lit(None, dtype=pl.String),
        team=pl.col(team_col) if team_col else pl.lit(None, dtype=pl.String),
        pfr_player_id=pl.col(pfr_col) if pfr_col else pl.lit(None, dtype=pl.String),
    ).filter(
        pl.col("player_id").is_not_null() & pl.col("full_name").is_not_null()
    ).unique(subset=["player_id"], keep="first")

    return out.with_columns(search_name=_search_name(pl.col("full_name")))


def _published(season: int, cache: Path | None, force: bool) -> Path | None:
    """The season's play-by-play, or None if nflverse hasn't published it yet
    (the days before a season kicks off)."""
    try:
        return sources.fetch("pbp", season, cache_dir=cache, force=force)
    except requests.HTTPError as exc:
        if exc.response is not None and exc.response.status_code == 404:
            return None
        raise


def _expected(season: int, cache: Path | None, force: bool) -> pl.DataFrame | None:
    """ffopportunity's weekly expectations, or None if not published for the season."""
    try:
        path = sources.fetch("ep_weekly", season, cache_dir=cache, force=force)
    except requests.HTTPError as exc:
        if exc.response is not None and exc.response.status_code == 404:
            return None
        raise
    return pl.read_parquet(path)


def build(seasons: list[int], out: Path, cache: Path | None, force: bool,
          skip_missing: bool = False) -> None:
    players = build_players(sources.fetch("players", cache_dir=cache, force=force))
    log.info("players: %d", players.height)

    crosswalk = (
        players.filter(pl.col("pfr_player_id").is_not_null())
        .select(["player_id", "pfr_player_id"])
    )
    log.info("pfr crosswalk: %d of %d players (%.0f%%)",
             crosswalk.height, players.height, 100 * crosswalk.height / players.height)

    metric_ids = list(METRICS.keys())
    frames: list[pl.DataFrame] = []
    built: list[int] = []
    meta: dict[str, str] = {}

    for season in seasons:
        pbp_path = _published(season, cache, force)
        if pbp_path is None:
            if not skip_missing:
                raise RuntimeError(f"no play-by-play published for {season}")
            log.warning("season %d: not published yet, skipping", season)
            continue
        built.append(season)
        lf = transform.load_pbp(pbp_path)
        weekly = transform.weekly_player_stats(lf)
        log.info("season %d: %d player-weeks from play-by-play", season, weekly.height)

        try:
            snaps_path = sources.fetch("snap_counts", season, cache_dir=cache, force=force)
            snaps = pl.read_csv(snaps_path, infer_schema_length=10_000)
            before = weekly.height
            weekly = transform.add_snap_share(weekly, snaps, crosswalk)
            matched = weekly.filter(pl.col("snap_share").is_not_null()).height
            log.info("season %d: snap data attached to %d/%d rows", season, matched, before)
        except Exception as exc:  # snap counts are a nice-to-have, not a blocker
            log.warning("season %d: snap counts unavailable (%s)", season, exc)

        ep = _expected(season, cache, force)
        through = validation.expected_coverage(season, weekly, ep)
        meta[f"expected_through_week:{season}"] = str(through)
        log.info("season %d: expected points through week %d", season, through)
        if ep is None:
            if not skip_missing:
                raise RuntimeError(f"no ffopportunity data published for {season}")
            log.warning("season %d: no expected-points data yet; xFP will be missing", season)
        else:
            problems = validation.cross_check(weekly, ep) + validation.fantasy_contract(weekly, ep)
            for p in problems:
                log.error("VALIDATION: season %d: %s", season, p)
            if problems:
                raise validation.ValidationError(f"{len(problems)} ffopportunity check(s) failed; first: {problems[0]}")
            expected = transform.expected_components(ep)
            log.info("season %d: %d expected player-weeks", season, expected.height)
            frames.append(transform.to_long(expected, metric_ids, sparse_metric_ids()))

        frames.append(transform.to_long(weekly, metric_ids, sparse_metric_ids()))

    if not frames:
        raise RuntimeError(f"none of the seasons {seasons} has published play-by-play")
    long = pl.concat(frames, how="vertical_relaxed")
    # Drop stat lines for ids that aren't in the player table (practice-squad
    # oddities, retired ids) so the foreign key relationship actually holds.
    known = set(players["player_id"].to_list())
    before = long.height
    long = long.filter(pl.col("player_id").is_in(known))
    if before != long.height:
        log.info("dropped %d facts for unknown player ids", before - long.height)

    # players.csv covers all of NFL history (~25k), but only players with a
    # recorded stat in the built seasons belong in stats.db.
    referenced = set(long["player_id"].unique().to_list())
    players = players.filter(pl.col("player_id").is_in(referenced))
    log.info("players with stats in built seasons: %d", players.height)

    conn = schema.create(out)
    schema.load_metrics(conn, metric_rows())
    schema.load_players(conn, players)
    n = schema.load_facts(conn, long)
    schema.finalize(conn, built, meta)
    validation.validate(conn)
    conn.close()

    size_mb = out.stat().st_size / 1e6
    log.info("wrote %s — %d facts, %.1f MB", out, n, size_mb)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Build the Gridiron stats database.")
    ap.add_argument("--seasons", type=int, nargs="+", required=True)
    ap.add_argument("--out", type=Path, default=Path("build/stats.db"))
    ap.add_argument("--cache", type=Path, default=None)
    ap.add_argument("--force", action="store_true", help="ignore the download cache")
    ap.add_argument("--skip-missing", action="store_true",
                    help="skip seasons nflverse hasn't published yet instead of failing")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)-7s %(message)s",
    )
    build(args.seasons, args.out, args.cache, args.force, args.skip_missing)
    return 0


if __name__ == "__main__":
    sys.exit(main())
