"""Team defense and injury report tables.

Both are small, wide, per-week tables the app reads directly: no metric
registry, no rate recomputation. Kept apart from the player fact table because
defense is keyed on team and injuries are text, not numbers.
"""

from __future__ import annotations

from pathlib import Path

import polars as pl

_DEF_COLUMNS = ["season", "week", "season_type", "game_id", "posteam", "defteam", "play_type", "safety",
                "yards_gained", "sack", "interception", "fumble_lost", "touchdown",
                "td_team", "home_team", "away_team", "total_home_score", "total_away_score",
                "posteam_score", "posteam_score_post"]


def team_defense(pbp_path: Path) -> pl.DataFrame:
    """One row per (team, season, week): what the defense allowed and took away."""
    lf = pl.scan_csv(pbp_path, infer_schema_length=20_000)
    cols = [c for c in _DEF_COLUMNS if c in lf.collect_schema().names()]
    return team_defense_from(lf.select(cols))


def team_defense_from(lf: pl.LazyFrame) -> pl.DataFrame:
    lf = lf.filter(pl.col("season_type").is_in(["REG", "POST"]))
    num = lambda c: pl.col(c).cast(pl.Float64, strict=False).fill_null(0)  # noqa: E731
    names = lf.collect_schema().names()
    lf = lf.with_columns(pl.lit(None, pl.Float64).alias(c) for c in ("posteam_score", "posteam_score_post") if c not in names)

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

    # A safety is the defense's on the play, unless posteam's score went up by 2
    # (a punt returner tackled in his own end zone scores for the punting team).
    posteam_scored = (pl.col("posteam_score_post").cast(pl.Float64, strict=False)
                      - pl.col("posteam_score").cast(pl.Float64, strict=False)) == 2
    safeties = (
        lf.filter(num("safety") > 0)
        .with_columns(team=pl.when(posteam_scored.fill_null(False)).then(pl.col("posteam")).otherwise(pl.col("defteam")))
        .filter(pl.col("team").is_not_null())
        .group_by(["team", "season", "week"])
        .agg(safeties=num("safety").sum())
    )

    # nflverse lists the receiving team as posteam on a kickoff, so a return TD
    # is posteam's. Punt return TDs score for defteam: already defensive_tds.
    returns = (
        lf.filter(pl.col("posteam").is_not_null() & (pl.col("play_type") == "kickoff")
                  & (pl.col("td_team") == pl.col("posteam")))
        .group_by(["posteam", "season", "week"])
        .agg(kick_return_tds=num("touchdown").sum())
        .rename({"posteam": "team"})
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
        .join(safeties, on=["team", "season", "week"], how="left")
        .join(returns, on=["team", "season", "week"], how="left")
        .with_columns(pl.col("season", "week").cast(pl.Int64))
        .fill_null(0)
        .sort(["season", "week", "team"])
        .collect()
    )


def dst_weekly(defense: pl.DataFrame) -> pl.DataFrame:
    """Each team-week as its D/ST pseudo-player's week (core/ingest's dstWeeks).

    Points allowed are stored as a number; the scoring profile's own tiers score them.
    """
    return defense.select(
        player_id=pl.concat_str([pl.lit("DST_"), pl.col("team")]),
        season=pl.col("season").cast(pl.Int64),
        week=pl.col("week").cast(pl.Int64),
        team=pl.col("team"),
        g=pl.lit(1.0),
        dst_sacks=pl.col("sacks").cast(pl.Float64),
        dst_interceptions=pl.col("interceptions").cast(pl.Float64),
        dst_fumble_recoveries=pl.col("fumbles_recovered").cast(pl.Float64),
        dst_tds=(pl.col("defensive_tds") + pl.col("kick_return_tds")).cast(pl.Float64),
        dst_safeties=pl.col("safeties").cast(pl.Float64),
        points_allowed=pl.col("points_allowed").cast(pl.Float64),
        yards_allowed=pl.col("yards_allowed").cast(pl.Float64),
    )


def dst_players(teams_: list[str]) -> pl.DataFrame:
    """A player row per team's D/ST: "KC D/ST", position DST (core/ingest's dstPlayer)."""
    return pl.DataFrame(
        {
            "player_id": [f"DST_{t}" for t in teams_],
            "full_name": [f"{t} D/ST" for t in teams_],
            "position": ["DST"] * len(teams_),
            "team": list(teams_),
            "pfr_player_id": [None] * len(teams_),
        },
        schema={"player_id": pl.String, "full_name": pl.String, "position": pl.String,
                "team": pl.String, "pfr_player_id": pl.String},
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
