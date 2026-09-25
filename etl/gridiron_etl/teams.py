"""Team defense and injury report tables.

Both are small, wide, per-week tables the app reads directly: no metric
registry, no rate recomputation. Kept apart from the player fact table because
defense is keyed on team and injuries are text, not numbers.
"""

from __future__ import annotations

from pathlib import Path

import polars as pl

_DEF_COLUMNS = ["season", "week", "season_type", "game_id", "defteam", "play_type",
                "yards_gained", "sack", "interception", "fumble_lost", "touchdown",
                "td_team", "home_team", "away_team", "total_home_score", "total_away_score"]


def team_defense(pbp_path: Path) -> pl.DataFrame:
    """One row per (team, season, week): what the defense allowed and took away."""
    lf = pl.scan_csv(pbp_path, infer_schema_length=20_000)
    cols = [c for c in _DEF_COLUMNS if c in lf.collect_schema().names()]
    return team_defense_from(lf.select(cols))


def team_defense_from(lf: pl.LazyFrame) -> pl.DataFrame:
    lf = lf.filter(pl.col("season_type").is_in(["REG", "POST"]))
    num = lambda c: pl.col(c).cast(pl.Float64, strict=False).fill_null(0)  # noqa: E731

    plays = (
        lf.filter(pl.col("defteam").is_not_null())
        .group_by(["defteam", "season", "week"])
        .agg(
            yards_allowed=num("yards_gained").filter(pl.col("play_type").is_in(["pass", "run"])).sum(),
            sacks=num("sack").sum(),
            interceptions=num("interception").sum(),
            fumbles_recovered=num("fumble_lost").sum(),
            defensive_tds=(num("touchdown") * (pl.col("td_team") == pl.col("defteam")).fill_null(False)).sum(),
        )
        .rename({"defteam": "team"})
    )

    games = lf.group_by(["game_id", "season", "week"]).agg(
        pl.col("home_team").first(), pl.col("away_team").first(),
        home=num("total_home_score").max(), away=num("total_away_score").max(),
    )
    points = pl.concat([
        games.select("season", "week", team=pl.col("home_team"), points_allowed=pl.col("away")),
        games.select("season", "week", team=pl.col("away_team"), points_allowed=pl.col("home")),
    ])

    return (
        points.join(plays, on=["team", "season", "week"], how="left")
        .with_columns(pl.col("season", "week").cast(pl.Int64))
        .fill_null(0)
        .sort(["season", "week", "team"])
        .collect()
    )


def injuries(path: Path) -> pl.DataFrame:
    """nflverse's weekly injury report, trimmed to what the app shows."""
    df = pl.read_csv(path, infer_schema_length=0)
    return df.filter(pl.col("gsis_id").is_not_null()).select(
        player_id=pl.col("gsis_id"),
        season=pl.col("season").cast(pl.Int64),
        week=pl.col("week").cast(pl.Int64),
        team=pl.col("team"),
        name=pl.col("full_name"),
        position=pl.col("position"),
        status=pl.col("report_status"),
        injury=pl.col("report_primary_injury"),
        practice=pl.col("practice_status"),
    ).unique(subset=["player_id", "season", "week"], keep="last")
