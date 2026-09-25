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
        first_down_pass=0, first_down_rush=0, fumble_lost=0,
        fumbled_1_player_id=None, two_point_conv_result=None,
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


def kneel(qb: str, yds: float = -1, *, epa: float = -0.5, **kw) -> dict:
    """A QB kneel: `rush_attempt=1`, always a loss, like a real clock-killer."""
    return play(
        play_type="qb_kneel", rush_attempt=1, rushing_yards=yds, rusher_player_id=qb,
        success=0, epa=epa, **kw,
    )


def spike(qb: str, *, epa: float = -0.1, **kw) -> dict:
    """A QB spike: `pass_attempt=1`, always an incompletion for 0 yards."""
    return play(
        play_type="qb_spike", pass_attempt=1, complete_pass=0,
        passer_player_id=qb, epa=epa, **kw,
    )


def run(plays: list[dict]) -> pl.DataFrame:
    lf = pl.LazyFrame(plays, schema_overrides={
        "air_yards": pl.Float64, "yards_after_catch": pl.Float64,
        "passing_yards": pl.Float64, "receiving_yards": pl.Float64,
        "rushing_yards": pl.Float64, "cpoe": pl.Float64,
        "receiver_player_id": pl.String, "rusher_player_id": pl.String,
        "passer_player_id": pl.String, "fumbled_1_player_id": pl.String,
        "two_point_conv_result": pl.String,
    })
    # Mirrors `load_pbp`'s own filter and flag, so these synthetic frames
    # exercise kneels/spikes exactly as the real pipeline does.
    lf = lf.filter(
        pl.col("season_type").is_in(["REG", "POST"])
        & pl.col("play_type").is_in(list(transform.SCRIMMAGE_PLAY_TYPES))
        & pl.col("posteam").is_not_null()
    ).with_columns(
        is_efficiency_play=~pl.col("play_type").is_in(list(transform._RATE_EXCLUDED_PLAY_TYPES))
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


# ---------------------------------------------------------------- kneels and spikes

def test_kneel_counts_as_a_carry_and_its_yards_count():
    df = run([carry("QB1", 5), kneel("QB1", -2)])
    r = row(df, "QB1")
    assert r["carries"] == 2
    assert r["rushing_yards"] == 3  # 5 - 2, matching the box score


def test_kneel_does_not_change_rush_success_rate_epa_per_carry_or_carry_share():
    df = run([
        carry("RB1", 10, success=1, epa=1.0),
        carry("RB1", 5, success=0, epa=-0.2),
        kneel("QB1", -2),  # same team, different player — must not dilute the below
        carry("RB2", 3, success=1, epa=0.5),
    ])
    rb1 = row(df, "RB1")
    # Rate over RB1's own 2 real carries only — the QB's kneel is a different
    # player anyway, but pins that a kneel never enters a success/EPA rate.
    assert rb1["rush_success_rate"] == pytest.approx(0.5)
    assert rb1["rush_epa_per_carry"] == pytest.approx((1.0 - 0.2) / 2)
    # carry_share: RB1's 2 real carries over the team's 3 real carries
    # (RB1's 2 + RB2's 1) — the QB's kneel excluded from both sides.
    assert rb1["carry_share"] == pytest.approx(2 / 3)


def test_carries_eff_is_stored_and_excludes_kneels_from_the_long_facts():
    from gridiron_etl.metrics import METRICS

    df = run([carry("RB1", 5), kneel("QB1", -2)])
    long = transform.to_long(df, list(METRICS.keys()))

    def value(pid: str, metric_id: str) -> float | None:
        hit = long.filter((pl.col("player_id") == pid) & (pl.col("metric_id") == metric_id))
        return hit["value"].item() if hit.height else None

    # The box-score fact counts the kneel; the efficiency denominator doesn't.
    assert value("QB1", "carries") == 1
    assert value("QB1", "carries_eff") == 0
    assert value("RB1", "carries") == 1
    assert value("RB1", "carries_eff") == 1


def test_kneel_at_the_3_is_a_carry_but_not_goal_line_usage():
    # A kneel inside the 5 is a box-score carry with its yards, but not a
    # designed goal-line run, a red/green-zone carry or a weighted
    # opportunity: those are usage signals, and a kneel never scores.
    df = run([
        carry("QB1", 2, yl=3),
        kneel("QB1", -1, yardline_100=3),
        target("QB1", 2, qb="QB2"),
    ])
    r = row(df, "QB1")
    assert r["carries"] == 2
    assert r["rushing_yards"] == 1
    assert r["carries_eff"] == 1
    assert (r["rz_carries"], r["gz_carries"], r["gl_carries"]) == (1, 1, 1)
    assert r["qb_rush_inside_5"] == 1
    assert r["weighted_opportunities"] == pytest.approx(1 + 2.6 * 1)


def test_spike_counts_as_a_pass_attempt_but_not_a_dropback():
    df = run([
        target("WR1", 10, complete=True, yds=10, qb="QB1", epa=0.8),
        spike("QB1", epa=-0.1),
    ])
    r = row(df, "QB1")
    assert r["attempts"] == 2
    assert r["dropbacks"] == 1
    assert r["epa_per_dropback"] == pytest.approx(0.8)


# ---------------------------------------------------------------- joins

def test_player_with_rushing_and_receiving_is_one_row():
    df = run([carry("RB1", 5), target("RB1", 3, complete=True, yds=8)])
    r = row(df, "RB1")
    assert (r["carries"], r["targets"], r["receptions"]) == (1, 1, 1)


def test_snap_join_does_not_inflate_rows():
    weekly = run([target("WR1", 10), carry("RB1", 3)])
    snaps = pl.DataFrame({
        "season": [2025, 2025], "week": [1, 1], "game_id": ["g1", "g1"],
        "team": ["AAA", "AAA"], "pfr_player_id": ["pWR1", "pRB1"],
        "offense_snaps": [50, 30], "offense_pct": [0.8, 0.5],
    })
    xwalk = pl.DataFrame({"player_id": ["WR1", "RB1"], "pfr_player_id": ["pWR1", "pRB1"]})
    out = transform.add_snap_share(weekly, snaps, xwalk)
    assert out.height == weekly.height
    assert row(out, "WR1")["snap_share"] == pytest.approx(0.8)


def test_unmapped_snaps_leave_null_rather_than_dropping_the_player():
    weekly = run([target("WR1", 10)])
    snaps = pl.DataFrame({
        "season": [2025], "week": [1], "game_id": ["g1"], "team": ["AAA"],
        "pfr_player_id": ["unknown"], "offense_snaps": [50], "offense_pct": [0.8],
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


# ---------------------------------------------------------------- range components

def test_every_player_week_gets_one_game():
    df = run([target("WR1", 5), carry("RB1")])
    assert row(df, "WR1")["g"] == 1
    assert row(df, "RB1")["g"] == 1


def test_team_denominators_are_stored_for_range_recomputation():
    df = run([target("WR1", 10), target("WR1", 20), target("WR2", 30), carry("RB1")])
    r = row(df, "WR1")
    assert r["team_targets"] == 3
    assert r["team_air_yards"] == pytest.approx(60)
    assert r["team_carries"] == 1


def test_cpoe_components_allow_attempt_weighting():
    df = run([
        target("WR1", 5, cpoe=10.0),
        target("WR1", 5, cpoe=-4.0),
        target("WR1", 5, cpoe=None),
    ])
    qb = row(df, "QB1")
    assert qb["cpoe_sum"] == pytest.approx(6.0)
    assert qb["cpoe_n"] == 2
    assert qb["cpoe"] == pytest.approx(3.0)


def test_team_offense_snaps_when_nobody_plays_every_snap():
    # Real case: 2025 SF week 11 ran 55 offensive plays, but the most any
    # player logged was 53. The max-snaps shortcut would say 53.
    snaps = pl.DataFrame({
        "game_id": ["g"] * 4, "team": ["SF"] * 4,
        "offense_snaps": [53, 50, 48, 39],
        "offense_pct": [0.96, 0.91, 0.87, 0.71],
    })
    solved = transform.team_offense_snaps(snaps)
    assert solved["team_offense_snaps"].to_list() == [55]


def test_team_offense_snaps_is_the_max_on_the_team_that_game():
    weekly = run([target("WR1", 10), carry("RB1", 3)])
    snaps = pl.DataFrame({
        "season": [2025] * 4, "week": [1] * 4, "game_id": ["g1"] * 4,
        "team": ["AAA", "AAA", "AAA", "BBB"],
        "pfr_player_id": ["pWR1", "pRB1", "pOL1", "pOTHER"],
        "offense_snaps": [50, 30, 64, 80],
        "offense_pct": [0.78, 0.47, 1.0, 1.0],
    })
    xwalk = pl.DataFrame({"player_id": ["WR1", "RB1"], "pfr_player_id": ["pWR1", "pRB1"]})
    out = transform.add_snap_share(weekly, snaps, xwalk)
    # 64 from the lineman on AAA, not 80 from the other team.
    assert row(out, "WR1")["team_offense_snaps"] == 64
    assert row(out, "RB1")["team_offense_snaps"] == 64


# ---------------------------------------------------------------- scoring inputs

def test_first_downs_credit_passer_receiver_and_rusher():
    df = run([
        target("WR1", 8, complete=True, yds=12, first_down_pass=1),
        target("WR1", 3, complete=True, yds=4),
        carry("RB1", 11, first_down_rush=1),
    ])
    assert row(df, "WR1")["receiving_first_downs"] == 1
    assert row(df, "QB1")["passing_first_downs"] == 1
    assert row(df, "RB1")["rushing_first_downs"] == 1


def test_long_touchdowns_count_at_40_and_50_and_nest():
    df = run([
        target("WR1", 30, complete=True, yds=55, td=1),
        target("WR1", 20, complete=True, yds=42, td=1),
        target("WR1", 5, complete=True, yds=39, td=1),
        target("WR1", 45, complete=True, yds=60),  # long, but not a touchdown
        carry("RB1", 61, td=1),
    ])
    wr, qb, rb = row(df, "WR1"), row(df, "QB1"), row(df, "RB1")
    assert (wr["receiving_tds_40"], wr["receiving_tds_50"]) == (2, 1)
    assert (qb["passing_tds_40"], qb["passing_tds_50"]) == (2, 1)
    assert (rb["rushing_tds_40"], rb["rushing_tds_50"]) == (1, 1)


def test_fumbles_lost_are_credited_to_the_ball_carrier():
    df = run([
        carry("RB1", 3, fumble_lost=1, fumbled_1_player_id="RB1"),
        target("WR1", 5, complete=True, yds=9, fumble_lost=1, fumbled_1_player_id="WR1"),
        # A sack fumble belongs to the passer.
        play(play_type="pass", sack=1, passer_player_id="QB1",
             fumble_lost=1, fumbled_1_player_id="QB1"),
    ])
    assert row(df, "RB1")["fumbles_lost"] == 1
    assert row(df, "WR1")["fumbles_lost"] == 1
    assert row(df, "QB1")["fumbles_lost"] == 1


def test_recovered_fumbles_and_defender_fumbles_do_not_count():
    df = run([
        carry("RB1", 3, fumble_lost=0, fumbled_1_player_id="RB1"),
        # A defender fumbling on the return is not an offensive player's fumble.
        target("WR1", 5, complete=True, yds=9, fumble_lost=1, fumbled_1_player_id="CB9"),
    ])
    assert row(df, "RB1")["fumbles_lost"] == 0
    assert row(df, "WR1")["fumbles_lost"] == 0
    assert df.filter(pl.col("player_id") == "CB9").height == 0


def test_successful_two_point_conversions_are_credited_and_add_no_targets():
    df = run([
        target("WR1", 10),
        target("WR1", 2, complete=True, yds=2,
               two_point_attempt=1, two_point_conv_result="success"),
        target("WR1", 2, two_point_attempt=1, two_point_conv_result="failure"),
        carry("RB1", 2, two_point_attempt=1, two_point_conv_result="success"),
    ])
    wr = row(df, "WR1")
    assert wr["receiving_2pt"] == 1
    assert wr["targets"] == 1
    assert row(df, "QB1")["passing_2pt"] == 1
    assert row(df, "RB1")["rushing_2pt"] == 1


def test_sparse_metrics_drop_zeros_in_long_format():
    df = run([target("WR1", 10), carry("RB1", 3)])
    long = transform.to_long(df, ["targets", "fumbles_lost"], sparse=frozenset({"fumbles_lost"}))
    assert long.filter(pl.col("metric_id") == "fumbles_lost").height == 0
    # Non-sparse metrics keep their zeros, as before.
    assert long.filter((pl.col("metric_id") == "targets") & (pl.col("player_id") == "RB1")).height == 1
