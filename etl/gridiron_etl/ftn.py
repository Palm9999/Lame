"""FTN charting: nflverse's play-level flags (2022 on), one row per play.

Twin of core/ingest's Ftn.kt; the parity job holds the two to identical facts.

FTN has no player ids, so a flag is credited through play-by-play: receiver
flags to the target's receiver, quarterback flags to the passer. Every count
sits beside FTN's own denominator (the plays FTN charted), so a week
play-by-play has but FTN lacks never drags a rate toward zero, and a range
recomputes as sum(count) / sum(denominator). Nothing is sparse: a week with no
drops stores a 0.
"""

from __future__ import annotations

import polars as pl

from .transform import SCRIMMAGE_PLAY_TYPES, _RATE_EXCLUDED_PLAY_TYPES

FIRST_SEASON = 2022
MIN_COVERAGE = 0.9

# FTN's column -> the name used here.
_FLAGS = {
    "is_catchable_ball": "catchable", "is_contested_ball": "contested", "is_drop": "drop",
    "is_created_reception": "created", "is_play_action": "play_action", "is_qb_out_of_pocket": "out_of_pocket",
    "is_throw_away": "throw_away", "is_interception_worthy": "int_worthy",
}
_REQUIRED = ["nflverse_game_id", "nflverse_play_id", *_FLAGS, "n_blitzers"]

COMPONENTS = [
    "ftn_targets", "ftn_catchable", "ftn_contested", "ftn_drops", "ftn_created_rec",
    "ftn_catchable_rate", "ftn_drop_rate", "ftn_contested_rate",
    "ftn_dropbacks", "ftn_attempts", "ftn_pa_db", "ftn_blitz_db", "ftn_oop_db", "ftn_throwaway", "ftn_int_worthy",
    "ftn_play_action_rate", "ftn_blitz_rate", "ftn_out_of_pocket_rate", "ftn_throwaway_rate", "ftn_int_worthy_rate",
]

_SEASON_TYPES = ["REG", "POST"]
_KEY = ["season", "week", "team", "player_id"]


def flags(raw: pl.DataFrame) -> pl.DataFrame:
    """FTN's file as one row per charted play: game_id, play_id and a boolean per flag (blank reads as false)."""
    missing = [c for c in _REQUIRED if c not in raw.columns]
    if missing:
        raise ValueError(f"FTN charting is missing column(s) {', '.join(missing)}")
    return (
        raw.select(
            pl.col("nflverse_game_id").cast(pl.String).alias("game_id"),
            pl.col("nflverse_play_id").cast(pl.Int64, strict=False).alias("play_id"),
            *[(pl.col(c).cast(pl.String).str.to_uppercase() == "TRUE").fill_null(False).alias(name)
              for c, name in _FLAGS.items()],
            pl.col("n_blitzers").cast(pl.Int64, strict=False).fill_null(0).alias("blitzers"),
        )
        .filter(pl.col("game_id").is_not_null() & pl.col("play_id").is_not_null())
        .unique(subset=["game_id", "play_id"], keep="last", maintain_order=True)
    )


def load(path) -> pl.DataFrame:
    """Read FTN's csv (every column as text, so TRUE/FALSE and blanks read alike) into [flags]'s shape."""
    return flags(pl.read_csv(path, infer_schema=False))


def _eligible(pbp: pl.LazyFrame) -> pl.LazyFrame:
    """Same eligibility as the play-by-play aggregator: regular season and postseason scrimmage plays, no two-point
    tries, no kneels or spikes."""
    return pbp.filter(
        pl.col("posteam").is_not_null()
        & pl.col("season_type").is_in(_SEASON_TYPES)
        & pl.col("play_type").is_in(list(SCRIMMAGE_PLAY_TYPES))
        & ~pl.col("play_type").is_in(list(_RATE_EXCLUDED_PLAY_TYPES))
        & (pl.col("two_point_attempt").fill_null(0) == 0)
    )


def _charted(pbp: pl.LazyFrame, charted: pl.DataFrame) -> pl.LazyFrame:
    return _eligible(pbp).join(charted.lazy(), on=["game_id", "play_id"], how="inner")


def coverage(pbp: pl.LazyFrame, charted: pl.DataFrame) -> tuple[int, int]:
    """(eligible pass attempts, how many of them FTN charted): the coverage check's inputs."""
    eligible = _eligible(pbp).filter(pl.col("pass_attempt").fill_null(0) > 0)
    attempts = eligible.select(pl.len()).collect().item()
    covered = eligible.join(charted.lazy().select("game_id", "play_id"), on=["game_id", "play_id"], how="semi") \
        .select(pl.len()).collect().item()
    return attempts, covered


def coverage_warning(season: int, attempts: int, covered: int) -> str | None:
    """A warning when FTN charts under 90% of a season's pass attempts (it lags play-by-play mid-season)."""
    if attempts <= 0 or covered / attempts >= MIN_COVERAGE:
        return None
    return (f"{season}: FTN charting covers {round(100 * covered / attempts)}% of pass attempts; "
            "its rates use only the charted plays")


def components(pbp: pl.LazyFrame, charted: pl.DataFrame) -> pl.DataFrame:
    """One row per (season, week, team, player) with every FTN component the player has a denominator for."""
    plays = _charted(pbp, charted)

    receivers = (
        plays.filter(pl.col("receiver_player_id").is_not_null())
        .group_by(["season", "week", "posteam", "receiver_player_id"])
        .agg(
            ftn_targets=pl.len().cast(pl.Float64),
            ftn_catchable=pl.col("catchable").sum().cast(pl.Float64),
            ftn_contested=pl.col("contested").sum().cast(pl.Float64),
            ftn_drops=pl.col("drop").sum().cast(pl.Float64),
            ftn_created_rec=pl.col("created").sum().cast(pl.Float64),
        )
        .rename({"posteam": "team", "receiver_player_id": "player_id"})
        .with_columns(
            ftn_catchable_rate=pl.col("ftn_catchable") / pl.col("ftn_targets"),
            ftn_drop_rate=pl.col("ftn_drops") / pl.col("ftn_targets"),
            ftn_contested_rate=pl.col("ftn_contested") / pl.col("ftn_targets"),
        )
        .collect()
    )

    attempt = pl.col("pass_attempt").fill_null(0).cast(pl.Float64)
    weight = attempt + pl.col("sack").fill_null(0).cast(pl.Float64) + (pl.col("qb_scramble").fill_null(0) == 1).cast(pl.Float64)
    passers = (
        plays.filter(pl.col("passer_player_id").is_not_null())
        .with_columns(_weight=weight, _attempt=attempt)
        .filter(pl.col("_weight") > 0)
        .group_by(["season", "week", "posteam", "passer_player_id"])
        .agg(
            ftn_dropbacks=pl.col("_weight").sum(),
            ftn_attempts=pl.col("_attempt").sum(),
            ftn_pa_db=(pl.col("_weight") * pl.col("play_action")).sum(),
            ftn_blitz_db=(pl.col("_weight") * (pl.col("blitzers") > 0)).sum(),
            ftn_oop_db=(pl.col("_weight") * pl.col("out_of_pocket")).sum(),
            ftn_throwaway=(pl.col("_weight") * pl.col("throw_away")).sum(),
            ftn_int_worthy=(pl.col("_attempt") * pl.col("int_worthy")).sum(),
        )
        .rename({"posteam": "team", "passer_player_id": "player_id"})
        .with_columns(
            ftn_play_action_rate=pl.col("ftn_pa_db") / pl.col("ftn_dropbacks"),
            ftn_blitz_rate=pl.col("ftn_blitz_db") / pl.col("ftn_dropbacks"),
            ftn_out_of_pocket_rate=pl.col("ftn_oop_db") / pl.col("ftn_dropbacks"),
            ftn_throwaway_rate=pl.col("ftn_throwaway") / pl.col("ftn_dropbacks"),
            ftn_int_worthy_rate=pl.when(pl.col("ftn_attempts") > 0).then(pl.col("ftn_int_worthy") / pl.col("ftn_attempts")),
        )
        .collect()
    )

    return (
        receivers.join(passers, on=_KEY, how="full", coalesce=True)
        .select(["player_id", "season", "week", "team", *COMPONENTS])
        .with_columns(pl.col("season").cast(pl.Int64), pl.col("week").cast(pl.Int64))
        .sort(["season", "week", "team", "player_id"])
    )
