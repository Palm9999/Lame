"""Play-by-play to weekly per-player metrics.

Produces a long/narrow frame: (player_id, season, week, team, metric_id, value).

Design note: this deliberately emits *stat components*, never fantasy points.
League scoring is applied on-device so that any format — half-PPR, TE premium,
superflex, IDP — works offline without a server round trip.
"""

from __future__ import annotations

import logging
from pathlib import Path

import polars as pl

log = logging.getLogger(__name__)

# Columns pulled from the 372-column play-by-play file.
PBP_COLUMNS = [
    "season", "week", "season_type", "game_id", "posteam", "defteam", "play_type",
    "pass_attempt", "rush_attempt", "complete_pass", "air_yards", "yards_after_catch",
    "yards_gained", "passing_yards", "receiving_yards", "rushing_yards",
    "pass_touchdown", "rush_touchdown", "interception", "sack", "qb_scramble",
    "receiver_player_id", "rusher_player_id", "passer_player_id",
    "yardline_100", "epa", "success", "cpoe", "two_point_attempt",
    "first_down_pass", "first_down_rush", "fumble_lost", "fumbled_1_player_id",
    "two_point_conv_result",
]


# Scrimmage play types that carry official rushing/passing stats. Kneels and
# spikes are excluded by nflverse's own play-by-play convention from the
# `["pass", "run"]` shorthand elsewhere in this module, but they are real
# attempts: nflverse marks qb_kneel with rush_attempt=1 (always negative
# yardage, correctly deflating a QB's rushing total — cross-checked against
# ffopportunity, which includes them) and qb_spike with pass_attempt=1,
# complete_pass=0 (a zero-yard incompletion). Both must stay in the base scan
# so every downstream aggregation sees them.
SCRIMMAGE_PLAY_TYPES = ("pass", "run", "qb_kneel", "qb_spike")


def load_pbp(path: Path, season_types: tuple[str, ...] = ("REG", "POST")) -> pl.LazyFrame:
    """Scan play-by-play, keeping scrimmage and two-point plays that count for stats."""
    lf = pl.scan_csv(path, infer_schema_length=20_000)
    available = set(lf.collect_schema().names())
    missing = [c for c in PBP_COLUMNS if c not in available]
    if missing:
        log.warning("play-by-play is missing expected columns: %s", missing)
    cols = [c for c in PBP_COLUMNS if c in available]

    lf = lf.select(cols).filter(
        pl.col("season_type").is_in(season_types)
        & pl.col("play_type").is_in(list(SCRIMMAGE_PLAY_TYPES))
        & pl.col("posteam").is_not_null()
    )
    return lf


def _scrimmage(lf: pl.LazyFrame) -> pl.LazyFrame:
    """Plays that accrue normal stats. Two-point tries don't, and would distort shares."""
    if "two_point_attempt" not in lf.collect_schema().names():
        return lf
    return lf.filter(pl.col("two_point_attempt").fill_null(0) == 0)


def _long_td(td_col: str, yards_col: str, threshold: int) -> pl.Expr:
    """Touchdowns of at least `threshold` yards. A 55-yard score counts at 40 and at 50."""
    return (
        (pl.col(td_col).fill_null(0) == 1) & (pl.col(yards_col).fill_null(0) >= threshold)
    ).sum()


def _receiving(lf: pl.LazyFrame) -> pl.LazyFrame:
    tgt = pl.col("receiver_player_id").is_not_null()
    return (
        lf.filter(tgt)
        .group_by(["season", "week", "posteam", "receiver_player_id"])
        .agg(
            targets=pl.len(),
            receptions=pl.col("complete_pass").fill_null(0).sum(),
            receiving_yards=pl.col("receiving_yards").fill_null(0).sum(),
            air_yards=pl.col("air_yards").fill_null(0).sum(),
            yac=pl.col("yards_after_catch").fill_null(0).sum(),
            receiving_tds=pl.col("pass_touchdown").fill_null(0).sum(),
            rz_targets=(pl.col("yardline_100") <= 20).sum(),
            # A target thrown to or past the goal line.
            ez_targets=(pl.col("air_yards") >= pl.col("yardline_100")).sum(),
            rec_epa=pl.col("epa").fill_null(0).sum(),
            receiving_first_downs=pl.col("first_down_pass").fill_null(0).sum(),
            receiving_tds_40=_long_td("pass_touchdown", "receiving_yards", 40),
            receiving_tds_50=_long_td("pass_touchdown", "receiving_yards", 50),
        )
        .rename({"receiver_player_id": "player_id", "posteam": "team"})
    )


def _rushing(lf: pl.LazyFrame) -> pl.LazyFrame:
    has_scramble = "qb_scramble" in lf.collect_schema().names()
    # Scrambles are rushing production but not designed usage; keep them in the
    # totals and flag designed goal-line work separately.
    designed = (
        (pl.col("qb_scramble").fill_null(0) == 0) if has_scramble else pl.lit(True)
    )
    return (
        lf.filter(pl.col("rusher_player_id").is_not_null())
        .group_by(["season", "week", "posteam", "rusher_player_id"])
        .agg(
            carries=pl.len(),
            rushing_yards=pl.col("rushing_yards").fill_null(0).sum(),
            rushing_tds=pl.col("rush_touchdown").fill_null(0).sum(),
            rz_carries=(pl.col("yardline_100") <= 20).sum(),
            gz_carries=(pl.col("yardline_100") <= 10).sum(),
            gl_carries=(pl.col("yardline_100") <= 5).sum(),
            qb_rush_inside_5=((pl.col("yardline_100") <= 5) & designed).sum(),
            rush_epa=pl.col("epa").fill_null(0).sum(),
            rush_successes=pl.col("success").fill_null(0).sum(),
            rushing_first_downs=pl.col("first_down_rush").fill_null(0).sum(),
            rushing_tds_40=_long_td("rush_touchdown", "rushing_yards", 40),
            rushing_tds_50=_long_td("rush_touchdown", "rushing_yards", 50),
        )
        .rename({"rusher_player_id": "player_id", "posteam": "team"})
    )


def _passing(lf: pl.LazyFrame) -> pl.LazyFrame:
    names = lf.collect_schema().names()
    is_scramble = (
        (pl.col("qb_scramble").fill_null(0) == 1) if "qb_scramble" in names else pl.lit(False)
    )
    aggs = dict(
        attempts=pl.col("pass_attempt").fill_null(0).sum(),
        completions=pl.col("complete_pass").fill_null(0).sum(),
        passing_yards=pl.col("passing_yards").fill_null(0).sum(),
        passing_tds=pl.col("pass_touchdown").fill_null(0).sum(),
        interceptions=pl.col("interception").fill_null(0).sum(),
        sacks_taken=pl.col("sack").fill_null(0).sum(),
        dropbacks=(
            pl.col("pass_attempt").fill_null(0) + pl.col("sack").fill_null(0)
            + is_scramble.cast(pl.Int64)
        ).sum(),
        pass_epa=pl.col("epa").fill_null(0).sum(),
        passing_first_downs=pl.col("first_down_pass").fill_null(0).sum(),
        passing_tds_40=_long_td("pass_touchdown", "passing_yards", 40),
        passing_tds_50=_long_td("pass_touchdown", "passing_yards", 50),
    )
    if "cpoe" in names:
        aggs["cpoe"] = pl.col("cpoe").mean()
        # Sum and count, so CPOE over a range is attempt-weighted rather than a
        # mean of weekly means.
        aggs["cpoe_sum"] = pl.col("cpoe").sum()
        aggs["cpoe_n"] = pl.col("cpoe").is_not_null().sum()

    return (
        lf.filter(pl.col("passer_player_id").is_not_null())
        .group_by(["season", "week", "posteam", "passer_player_id"])
        .agg(**aggs)
        .rename({"passer_player_id": "player_id", "posteam": "team"})
    )


def _fumbles(lf: pl.LazyFrame) -> pl.LazyFrame:
    """Fumbles lost, credited to the ball carrier: the rusher, the receiver
    after a catch, or the passer (sack and scramble fumbles). A defender who
    fumbles during a return is not an offensive player and is not counted."""
    carrier = pl.col("fumbled_1_player_id")
    involved = (
        (carrier == pl.col("rusher_player_id"))
        | (carrier == pl.col("receiver_player_id"))
        | (carrier == pl.col("passer_player_id"))
    ).fill_null(False)
    return (
        lf.filter((pl.col("fumble_lost").fill_null(0) == 1) & carrier.is_not_null() & involved)
        .group_by(["season", "week", "posteam", "fumbled_1_player_id"])
        .agg(fumbles_lost=pl.len())
        .rename({"fumbled_1_player_id": "player_id", "posteam": "team"})
    )


def _two_point(lf: pl.LazyFrame) -> pl.LazyFrame | None:
    """Successful two-point conversions: the passer and receiver on a pass,
    the rusher on a run. None when the source predates the columns."""
    names = lf.collect_schema().names()
    if "two_point_attempt" not in names or "two_point_conv_result" not in names:
        return None
    ok = lf.filter(
        (pl.col("two_point_attempt").fill_null(0) == 1)
        & (pl.col("two_point_conv_result") == "success")
    )
    keys = ["season", "week", "team", "player_id"]

    def credit(id_col: str, name: str) -> pl.LazyFrame:
        return (
            ok.filter(pl.col(id_col).is_not_null())
            .group_by(["season", "week", "posteam", id_col])
            .agg(pl.len().alias(name))
            .rename({id_col: "player_id", "posteam": "team"})
        )

    return (
        credit("passer_player_id", "passing_2pt")
        .join(credit("receiver_player_id", "receiving_2pt"), on=keys, how="full", coalesce=True)
        .join(credit("rusher_player_id", "rushing_2pt"), on=keys, how="full", coalesce=True)
    )


def _team_context(lf: pl.LazyFrame) -> pl.LazyFrame:
    """Denominators for share metrics, computed on the same filtered play set."""
    return lf.group_by(["season", "week", "posteam"]).agg(
        team_targets=pl.col("receiver_player_id").is_not_null().sum(),
        team_air_yards=pl.col("air_yards").fill_null(0).sum(),
        team_carries=pl.col("rusher_player_id").is_not_null().sum(),
        team_plays=pl.len(),
    ).rename({"posteam": "team"})


def weekly_player_stats(lf: pl.LazyFrame) -> pl.DataFrame:
    """Join the usage frames, add team shares and derived rate metrics."""
    keys = ["season", "week", "team", "player_id"]
    plays = _scrimmage(lf)
    rec, rush, pas = _receiving(plays), _rushing(plays), _passing(plays)

    joined = (
        rec.join(rush, on=keys, how="full", coalesce=True)
        .join(pas, on=keys, how="full", coalesce=True)
        .join(_fumbles(plays), on=keys, how="full", coalesce=True)
    )
    two = _two_point(lf)
    if two is not None:
        joined = joined.join(two, on=keys, how="full", coalesce=True)
    df = joined.join(_team_context(plays), on=["season", "week", "team"], how="left").collect()

    counting = [
        "targets", "receptions", "receiving_yards", "air_yards", "yac", "receiving_tds",
        "rz_targets", "ez_targets", "carries", "rushing_yards", "rushing_tds",
        "rz_carries", "gz_carries", "gl_carries", "qb_rush_inside_5", "attempts",
        "completions", "passing_yards", "passing_tds", "interceptions", "sacks_taken",
        "dropbacks", "rush_successes", "cpoe_n",
        "passing_first_downs", "rushing_first_downs", "receiving_first_downs",
        "passing_2pt", "rushing_2pt", "receiving_2pt", "fumbles_lost",
        "passing_tds_40", "passing_tds_50", "rushing_tds_40", "rushing_tds_50",
        "receiving_tds_40", "receiving_tds_50",
    ]
    present = [c for c in counting if c in df.columns]
    df = df.with_columns([pl.col(c).fill_null(0) for c in present])

    def ratio(num: str, den: str) -> pl.Expr:
        """Guarded division — a zero denominator yields null, not an error or a zero."""
        return (
            pl.when(pl.col(den) > 0)
            .then(pl.col(num) / pl.col(den))
            .otherwise(None)
        )

    df = df.with_columns(
        g=pl.lit(1, dtype=pl.Int64),
        target_share=ratio("targets", "team_targets"),
        air_yards_share=ratio("air_yards", "team_air_yards"),
        carry_share=ratio("carries", "team_carries"),
        adot=ratio("air_yards", "targets"),
        racr=ratio("receiving_yards", "air_yards"),
        catch_rate=ratio("receptions", "targets"),
        rush_success_rate=ratio("rush_successes", "carries"),
        rush_epa_per_carry=ratio("rush_epa", "carries"),
        epa_per_dropback=ratio("pass_epa", "dropbacks"),
        weighted_opportunities=pl.col("carries") + 2.6 * pl.col("targets"),
        total_epa=(
            pl.col("rec_epa").fill_null(0) + pl.col("rush_epa").fill_null(0)
        ),
    )
    # WOPR depends on the two share columns, so it needs a second pass.
    #
    # Air yards share is legitimately allowed outside [0, 1]: roughly 18% of
    # pass attempts carry negative air yards (screens and checkdowns behind the
    # line), so a screen-only target profile produces a negative share, and a
    # player can exceed 1.0 when teammates go negative. That is correct for the
    # raw metric and is preserved.
    #
    # WOPR, however, is defined as an opportunity *rating* and its coefficients
    # assume a share in [0, 1]. Feeding a negative share through produces a
    # negative rating, which is meaningless. Clamp for this composite only.
    df = df.with_columns(
        wopr=(
            1.5 * pl.col("target_share").fill_null(0).clip(0.0, 1.0)
            + 0.7 * pl.col("air_yards_share").fill_null(0).clip(0.0, 1.0)
        )
    )
    return df


# Candidate offsets above the observed max when solving for team snaps.
_SNAP_SEARCH = 25
# Half of the published 0.01 rounding step, plus float slack.
_PCT_TOLERANCE = 0.0051


def team_offense_snaps(snaps: pl.DataFrame) -> pl.DataFrame:
    """Solve for each team's offensive snaps in each game.

    Not simply the max snaps any player logged: in some games nobody plays
    every snap (2025 SF week 11 ran 55 plays; the most any player logged was
    53). Nor a single back-solve of snaps / pct, which the two-decimal rounding
    of the published percentage makes ambiguous.

    Instead: the true total is the integer D, at or above the observed max,
    that is consistent with every player's published percentage, i.e.
    |snaps / D - pct| <= 0.005 for all players. Choose the D with the fewest
    violations, then least squared error, then the smallest D.
    """
    played = snaps.filter(pl.col("offense_snaps") > 0)
    cands = (
        played.group_by(["game_id", "team"])
        .agg(mx=pl.col("offense_snaps").max())
        .join(pl.DataFrame({"k": list(range(_SNAP_SEARCH + 1))}), how="cross")
        .with_columns(d=pl.col("mx") + pl.col("k"))
        .select(["game_id", "team", "d"])
    )
    scored = (
        played.join(cands, on=["game_id", "team"])
        .with_columns(err=(pl.col("offense_snaps") / pl.col("d") - pl.col("offense_pct")).abs())
        .group_by(["game_id", "team", "d"])
        .agg(bad=(pl.col("err") > _PCT_TOLERANCE).sum(), sse=(pl.col("err") ** 2).sum())
        .sort(["game_id", "team", "bad", "sse", "d"])
        .group_by(["game_id", "team"], maintain_order=True)
        .first()
    )
    return scored.select(["game_id", "team", pl.col("d").alias("team_offense_snaps")])


def add_snap_share(df: pl.DataFrame, snaps: pl.DataFrame,
                   crosswalk: pl.DataFrame) -> pl.DataFrame:
    """Attach snap counts.

    Snap counts key on `pfr_player_id` while everything else uses gsis ids, so
    this has to route through the players crosswalk. Rows that fail to map keep
    null snap data rather than being dropped.
    """
    snaps = snaps.join(team_offense_snaps(snaps), on=["game_id", "team"], how="left")
    mapped = (
        snaps.join(crosswalk, on="pfr_player_id", how="inner")
        .select(["season", "week", "player_id", "offense_snaps",
                 "team_offense_snaps", "offense_pct"])
        .rename({"offense_pct": "snap_share"})
    )
    return df.join(mapped, on=["season", "week", "player_id"], how="left")


def to_long(df: pl.DataFrame, metric_ids: list[str],
            sparse: frozenset[str] = frozenset()) -> pl.DataFrame:
    """Unpivot to the long/narrow fact shape the database stores.

    Metrics in `sparse` drop zero values: absent means zero. The scoring inputs
    are zero for most player-weeks (a receiver's completions, anyone's
    fumbles), and storing those zeros would roughly double the database.
    """
    present = [m for m in metric_ids if m in df.columns]
    long = (
        df.select(["player_id", "season", "week", "team", *present])
        .unpivot(
            index=["player_id", "season", "week", "team"],
            on=present,
            variable_name="metric_id",
            value_name="value",
        )
        .filter(pl.col("value").is_not_null())
        .filter(~(pl.col("metric_id").is_in(list(sparse)) & (pl.col("value") == 0)))
        .with_columns(pl.col("value").cast(pl.Float64))
    )
    return long


# ffopportunity column -> our expected component. Actual counterparts come from
# play-by-play; only the model's expectations are taken from this source.
# rec_interception_exp (targets intercepted) is deliberately not charged to
# receivers, which matches every mainstream scoring system.
EXPECTED_COLUMNS: dict[str, str] = {
    "pass_completions_exp": "x_completions",
    "receptions_exp": "x_receptions",
    "pass_yards_gained_exp": "x_passing_yards",
    "rush_yards_gained_exp": "x_rushing_yards",
    "rec_yards_gained_exp": "x_receiving_yards",
    "pass_touchdown_exp": "x_passing_tds",
    "rush_touchdown_exp": "x_rushing_tds",
    "rec_touchdown_exp": "x_receiving_tds",
    "pass_two_point_conv_exp": "x_passing_2pt",
    "rush_two_point_conv_exp": "x_rushing_2pt",
    "rec_two_point_conv_exp": "x_receiving_2pt",
    "pass_first_down_exp": "x_passing_first_downs",
    "rush_first_down_exp": "x_rushing_first_downs",
    "rec_first_down_exp": "x_receiving_first_downs",
    "pass_interception_exp": "x_interceptions",
}


def expected_components(ep: pl.DataFrame) -> pl.DataFrame:
    """ffopportunity's weekly expectations in our key shape.

    The file stores season as text and week as a float, and has rows with no
    player id (unidentified ball carriers), which are dropped.
    """
    return ep.filter(pl.col("player_id").is_not_null()).select(
        pl.col("player_id"),
        pl.col("season").cast(pl.Int64),
        pl.col("week").cast(pl.Int64),
        pl.col("posteam").alias("team"),
        *[pl.col(src).fill_null(0.0).cast(pl.Float64).alias(dst)
          for src, dst in EXPECTED_COLUMNS.items()],
    )
