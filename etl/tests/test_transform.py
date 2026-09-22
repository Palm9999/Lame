"""Transform tests on synthetic play-by-play with hand-computed expected values.

These pin the math independent of live nflverse data, so a regression in a
share or a threshold fails here in milliseconds rather than surfacing as a
subtly wrong number in the app.
"""

from __future__ import annotations

import polars as pl
import pytest

from gridiron_etl import transform


def play(**kw) -> dict:
    """One play-by-play row with neutral defaults."""
    base = dict(
        season=2025, week=1, season_type="REG", game_id="g1", posteam="AAA",
        defteam="BBB", play_type="pass", pass_attempt=0, rush_attempt=0,
        complete_pass=0, air_yards=None, yards_after_catch=None, yards_gained=0,
        passing_yards=None, receiving_yards=None, rushing_yards=None,
        pass_touchdown=0, rush_touchdown=0, interception=0, sack=0, qb_scramble=0,
        receiver_player_id=None, rusher_player_id=None, passer_player_id=None,
        yardline_100=50, epa=0.0, success=0, cpoe=None, two_point_attempt=0,
    )
    base.update(kw)
    return base


def target(rec: str, air: float, *, complete: bool = False, yds: float = 0,
           yl: int = 50, td: int = 0, qb: str = "QB1", **kw) -> dict:
    return play(
        play_type="pass", pass_attempt=1, complete_pass=int(complete),
        air_yards=air, receiving_yards=yds if complete else None,
        passing_yards=yds if complete else None, pass_touchdown=td,
        receiver_player_id=rec, passer_player_id=qb, yardline_100=yl, **kw,
    )


def carry(rb: str, yds: float = 0, *, yl: int = 50, td: int = 0,
          success: int = 0, epa: float = 0.0, **kw) -> dict:
    return play(
        play_type="run", rush_attempt=1, rushing_yards=yds, rusher_player_id=rb,
        rush_touchdown=td, yardline_100=yl, success=success, epa=epa, **kw,
    )


def run(plays: list[dict]) -> pl.DataFrame:
    lf = pl.LazyFrame(plays, schema_overrides={
        "air_yards": pl.Float64, "yards_after_catch": pl.Float64,
        "passing_yards": pl.Float64, "receiving_yards": pl.Float64,
        "rushing_yards": pl.Float64, "cpoe": pl.Float64,
        "receiver_player_id": pl.String, "rusher_player_id": pl.String,
        "passer_player_id": pl.String,
    })
    lf = lf.filter(
        pl.col("season_type").is_in(["REG", "POST"])
        & pl.col("play_type").is_in(["pass", "run"])
        & pl.col("posteam").is_not_null()
        & (pl.col("two_point_attempt").fill_null(0) == 0)
    )
    return transform.weekly_player_stats(lf)


def row(df: pl.DataFrame, pid: str) -> dict:
    hit = df.filter(pl.col("player_id") == pid)
    assert hit.height == 1, f"expected one row for {pid}, got {hit.height}"
    return hit.row(0, named=True)


# ---------------------------------------------------------------- shares

def test_target_share_is_player_over_team():
    df = run([
        target("WR1", 10), target("WR1", 10), target("WR1", 10),
        target("WR2", 10),
    ])
    assert row(df, "WR1")["target_share"] == pytest.approx(0.75)
    assert row(df, "WR2")["target_share"] == pytest.approx(0.25)


def test_air_yards_share_can_go_negative_and_is_preserved():
    # WR1: 20 air yards. RB1: a screen at -5. Team total 15.
    df = run([target("WR1", 20), target("RB1", -5)])
    assert row(df, "RB1")["air_yards_share"] == pytest.approx(-5 / 15)
    # And a teammate exceeds 1.0 because the other went negative.
    assert row(df, "WR1")["air_yards_share"] == pytest.approx(20 / 15)


def test_wopr_clamps_shares_so_it_never_goes_negative():
    df = run([target("WR1", 20), target("RB1", -5)])
    rb = row(df, "RB1")
    # target_share 0.5, air_yards_share clamped from -0.333 to 0.
    assert rb["wopr"] == pytest.approx(1.5 * 0.5 + 0.7 * 0.0)
    assert rb["wopr"] >= 0
    wr = row(df, "WR1")
    # air_yards_share clamped from 1.333 to 1.0.
    assert wr["wopr"] == pytest.approx(1.5 * 0.5 + 0.7 * 1.0)


def test_carry_share():
    df = run([carry("RB1"), carry("RB1"), carry("RB1"), carry("RB2")])
    assert row(df, "RB1")["carry_share"] == pytest.approx(0.75)


# ---------------------------------------------------------------- rate metrics

def test_adot_and_racr():
    df = run([
        target("WR1", 10, complete=True, yds=15),
        target("WR1", 30),
    ])
    r = row(df, "WR1")
    assert r["adot"] == pytest.approx(20.0)            # 40 air / 2 targets
    assert r["racr"] == pytest.approx(15 / 40)          # 15 rec yds / 40 air
    assert r["catch_rate"] == pytest.approx(0.5)


def test_zero_denominator_yields_null_not_error():
    # A pure runner has no targets, so aDOT must be null rather than 0 or inf.
    df = run([carry("RB1", 5)])
    r = row(df, "RB1")
    assert r["adot"] is None
    assert r["catch_rate"] is None


def test_rush_success_rate():
    df = run([carry("RB1", success=1), carry("RB1", success=0),
              carry("RB1", success=1), carry("RB1", success=1)])
    assert row(df, "RB1")["rush_success_rate"] == pytest.approx(0.75)


def test_weighted_opportunities():
    df = run([carry("RB1"), carry("RB1"), target("RB1", 2)])
    assert row(df, "RB1")["weighted_opportunities"] == pytest.approx(2 + 2.6 * 1)


# ---------------------------------------------------------------- zones

@pytest.mark.parametrize("yl, rz, gz, gl", [
    (25, 0, 0, 0),
    (20, 1, 0, 0),   # red zone boundary is inclusive
    (10, 1, 1, 0),   # green zone boundary is inclusive
    (5, 1, 1, 1),    # goal line boundary is inclusive
    (1, 1, 1, 1),
])
def test_carry_zone_thresholds(yl, rz, gz, gl):
    r = row(run([carry("RB1", yl=yl)]), "RB1")
    assert (r["rz_carries"], r["gz_carries"], r["gl_carries"]) == (rz, gz, gl)


def test_end_zone_target_when_air_yards_reach_goal_line():
    df = run([
        target("WR1", 15, yl=15),   # exactly reaches the goal line
        target("WR1", 14, yl=15),   # one short
        target("WR1", 30, yl=15),   # beyond
    ])
    assert row(df, "WR1")["ez_targets"] == 2


def test_designed_qb_rush_excludes_scrambles():
    df = run([
        carry("QB1", yl=3, qb_scramble=0),
        carry("QB1", yl=3, qb_scramble=1),
    ])
    r = row(df, "QB1")
    assert r["gl_carries"] == 2
    assert r["qb_rush_inside_5"] == 1


# ---------------------------------------------------------------- filtering

def test_two_point_attempts_do_not_count():
    df = run([target("WR1", 5), target("WR1", 2, two_point_attempt=1)])
    assert row(df, "WR1")["targets"] == 1


def test_non_scrimmage_plays_excluded():
    df = run([target("WR1", 10), play(play_type="no_play", receiver_player_id="WR1")])
    assert row(df, "WR1")["targets"] == 1


def test_preseason_excluded():
    df = run([target("WR1", 10), target("WR1", 10, season_type="PRE")])
    assert row(df, "WR1")["targets"] == 1


# ---------------------------------------------------------------- joins

def test_player_with_rushing_and_receiving_is_one_row():
    df = run([carry("RB1", 5), target("RB1", 3, complete=True, yds=8)])
    r = row(df, "RB1")
    assert (r["carries"], r["targets"], r["receptions"]) == (1, 1, 1)


def test_snap_join_does_not_inflate_rows():
    weekly = run([target("WR1", 10), carry("RB1", 3)])
    snaps = pl.DataFrame({
        "season": [2025, 2025], "week": [1, 1],
        "pfr_player_id": ["pWR1", "pRB1"],
        "offense_snaps": [50, 30], "offense_pct": [0.8, 0.5],
    })
    xwalk = pl.DataFrame({"player_id": ["WR1", "RB1"], "pfr_player_id": ["pWR1", "pRB1"]})
    out = transform.add_snap_share(weekly, snaps, xwalk)
    assert out.height == weekly.height
    assert row(out, "WR1")["snap_share"] == pytest.approx(0.8)


def test_unmapped_snaps_leave_null_rather_than_dropping_the_player():
    weekly = run([target("WR1", 10)])
    snaps = pl.DataFrame({
        "season": [2025], "week": [1], "pfr_player_id": ["unknown"],
        "offense_snaps": [50], "offense_pct": [0.8],
    })
    xwalk = pl.DataFrame({"player_id": ["WR1"], "pfr_player_id": ["pWR1"]})
    out = transform.add_snap_share(weekly, snaps, xwalk)
    # A pass yields both a receiver row and a passer row; neither may be dropped.
    assert out.height == weekly.height
    assert row(out, "WR1")["snap_share"] is None


# ---------------------------------------------------------------- long format

def test_to_long_drops_nulls_and_keeps_values():
    df = run([carry("RB1", 5)])
    long = transform.to_long(df, ["carries", "adot"])
    metrics = set(long["metric_id"].to_list())
    assert "carries" in metrics
    assert "adot" not in metrics   # null for a non-receiver, so filtered
    assert long["value"].null_count() == 0
