"""ffopportunity: expected components in, actual components cross-checked."""

import polars as pl

from gridiron_etl import transform, validate


def ep_row(**kw) -> dict:
    """One ffopportunity player-week with every checked column present."""
    base = {"season": "2025", "week": 1.0, "player_id": "WR1", "posteam": "AAA"}
    for c in [
        "pass_completions", "receptions", "pass_yards_gained", "rec_yards_gained",
        "rush_yards_gained", "pass_touchdown", "rec_touchdown", "rush_touchdown",
        "pass_two_point_conv", "rec_two_point_conv", "rush_two_point_conv",
        "pass_first_down", "rec_first_down", "rush_first_down", "pass_interception",
        "rec_fumble_lost", "rush_fumble_lost", "total_fantasy_points",
        "total_fantasy_points_exp",
    ]:
        base[c] = 0.0
    for src in transform.EXPECTED_COLUMNS:
        base[src] = 0.0
    base.update(kw)
    return base


def weekly_row(**kw) -> dict:
    base = {"season": 2025, "week": 1, "team": "AAA", "player_id": "WR1"}
    for c in [
        "completions", "receptions", "passing_yards", "receiving_yards", "rushing_yards",
        "passing_tds", "receiving_tds", "rushing_tds", "passing_2pt", "receiving_2pt",
        "rushing_2pt", "passing_first_downs", "receiving_first_downs",
        "rushing_first_downs", "interceptions", "fumbles_lost",
    ]:
        base[c] = 0
    base.update(kw)
    return base


def test_expected_components_are_renamed_cast_and_keyed():
    ep = pl.DataFrame([
        ep_row(receptions_exp=5.25, rec_yards_gained_exp=61.4, week=3.0),
        ep_row(player_id=None, receptions_exp=9.0),
    ])
    out = transform.expected_components(ep)
    assert out.height == 1
    r = out.row(0, named=True)
    assert (r["season"], r["week"], r["team"]) == (2025, 3, "AAA")
    assert r["x_receptions"] == 5.25
    assert r["x_receiving_yards"] == 61.4
    assert out.schema["season"] == pl.Int64 and out.schema["week"] == pl.Int64


def test_cross_check_passes_when_sources_agree_and_flags_a_mismatch():
    weekly = pl.DataFrame([weekly_row(receptions=5, receiving_yards=61)])
    good = pl.DataFrame([ep_row(receptions=5.0, rec_yards_gained=61.0)])
    assert validate.cross_check(weekly, good) == []

    bad = pl.DataFrame([ep_row(receptions=5.0, rec_yards_gained=75.0)])
    problems = validate.cross_check(weekly, bad)
    assert len(problems) == 1 and "receiving_yards" in problems[0]


def test_cross_check_allows_our_extra_sack_fumbles_but_not_fewer():
    weekly = pl.DataFrame([weekly_row(fumbles_lost=2)])
    assert validate.cross_check(weekly, pl.DataFrame([ep_row(rush_fumble_lost=1.0)])) == []
    # Real 2024 data has ffopportunity mis-attribute an isolated fumble to the
    # wrong offensive player a handful of times (see validate.FUMBLE_TOLERANCE),
    # so trailing by exactly one is allowed...
    weekly = pl.DataFrame([weekly_row(fumbles_lost=0)])
    assert validate.cross_check(weekly, pl.DataFrame([ep_row(rec_fumble_lost=1.0)])) == []
    # ...but trailing by more than that still flags.
    problems = validate.cross_check(weekly, pl.DataFrame([ep_row(rec_fumble_lost=1.0, rush_fumble_lost=1.0)]))
    assert any("fumbles_lost" in p for p in problems)


def test_fantasy_contract_matches_the_reference_profile():
    # 5 rec, 61 yds, 1 TD, 1 fumble: 5 + 6.1 + 6 - 2 = 15.1 in the file.
    weekly = pl.DataFrame([weekly_row(receptions=5, receiving_yards=61, receiving_tds=1, fumbles_lost=1)])
    ep = pl.DataFrame([ep_row(
        receptions=5.0, rec_yards_gained=61.0, rec_touchdown=1.0, rec_fumble_lost=1.0,
        total_fantasy_points=15.1,
        receptions_exp=4.0, rec_yards_gained_exp=50.0, rec_touchdown_exp=0.5,
        total_fantasy_points_exp=12.0,  # 4 + 5 + 3
    )])
    assert validate.fantasy_contract(weekly, ep) == []

    # Beyond FANTASY_CONTRACT_TOLERANCE (documented alongside a couple of
    # real, isolated ffopportunity mismatches this small a swing would hide).
    off = ep.with_columns(total_fantasy_points=pl.lit(10.1))
    assert any("total_fantasy_points" in p for p in validate.fantasy_contract(weekly, off))
