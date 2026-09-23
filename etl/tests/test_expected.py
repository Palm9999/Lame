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


def test_cross_check_passes_when_sources_agree():
    weekly = pl.DataFrame([weekly_row(receptions=5, receiving_yards=61)])
    good = pl.DataFrame([ep_row(receptions=5.0, rec_yards_gained=61.0)])
    assert validate.cross_check(weekly, good) == []


def test_cross_check_flags_a_mismatch_as_a_warning_not_a_failure(caplog):
    # A single mismatch, well under ComparisonPolicy's hard cap and season
    # budget, is an outlier: logged as a warning (with the player-week key
    # and both sides' values), but must not fail the build by itself — a
    # scheduled build (`.github/workflows/etl.yml`) can't stop over one
    # isolated ffopportunity quirk.
    weekly = pl.DataFrame([weekly_row(receptions=5, receiving_yards=61)])
    bad = pl.DataFrame([ep_row(receptions=5.0, rec_yards_gained=75.0)])
    with caplog.at_level("WARNING"):
        problems = validate.cross_check(weekly, bad)
    assert problems == []
    assert any("receiving_yards" in r.message and "WR1" in r.message for r in caplog.records)


def test_cross_check_allows_our_extra_sack_fumbles():
    weekly = pl.DataFrame([weekly_row(fumbles_lost=2)])
    assert validate.cross_check(weekly, pl.DataFrame([ep_row(rush_fumble_lost=1.0)])) == []


def test_cross_check_fumbles_trailing_by_one_is_reported(caplog):
    # The brief's original strict case: ours trailing ffopportunity's fumble
    # count must never be silently swallowed. Under the shared policy (see
    # ComparisonPolicy) a single such row is an outlier — reported as a
    # warning, not a build failure — but it must still be visible.
    weekly = pl.DataFrame([weekly_row(fumbles_lost=0)])
    with caplog.at_level("WARNING"):
        problems = validate.cross_check(weekly, pl.DataFrame([ep_row(rec_fumble_lost=1.0)]))
    assert problems == []
    assert any("fumbles_lost" in r.message for r in caplog.records)


def test_cross_check_fumbles_systematic_regression_fails():
    # The reviewer's case: ours reports 0 fumbles everywhere theirs has 1+,
    # across many player-weeks. Each row's own magnitude (trailing by 1) is
    # nowhere near FUMBLE_POLICY's hard cap, so only the season budget can
    # catch this — and must, since it's a real regression, not isolated
    # per-play upstream misattribution.
    n = validate.FUMBLE_POLICY.season_budget + 5
    weekly = pl.DataFrame([weekly_row(week=w, fumbles_lost=0) for w in range(1, n + 1)])
    ep = pl.DataFrame([ep_row(week=float(w), rec_fumble_lost=1.0) for w in range(1, n + 1)])
    problems = validate.cross_check(weekly, ep)
    assert any("fumbles_lost" in p and "budget" in p for p in problems)


def test_fantasy_contract_matches_the_reference_profile(caplog):
    # 5 rec, 61 yds, 1 TD, 1 fumble: 5 + 6.1 + 6 - 2 = 15.1 in the file.
    weekly = pl.DataFrame([weekly_row(receptions=5, receiving_yards=61, receiving_tds=1, fumbles_lost=1)])
    ep = pl.DataFrame([ep_row(
        receptions=5.0, rec_yards_gained=61.0, rec_touchdown=1.0, rec_fumble_lost=1.0,
        total_fantasy_points=15.1,
        receptions_exp=4.0, rec_yards_gained_exp=50.0, rec_touchdown_exp=0.5,
        total_fantasy_points_exp=12.0,  # 4 + 5 + 3
    )])
    assert validate.fantasy_contract(weekly, ep) == []

    # Beyond FANTASY_CONTRACT_TOLERANCE, but under FANTASY_CONTRACT_HARD_CAP:
    # an outlier, reported as a warning — detection still works, but a single
    # such row doesn't fail the build (see ComparisonPolicy).
    off = ep.with_columns(total_fantasy_points=pl.lit(10.1))
    with caplog.at_level("WARNING"):
        problems = validate.fantasy_contract(weekly, off)
    assert problems == []
    assert any("total_fantasy_points" in r.message for r in caplog.records)


# ---------------------------------------------------------------- ComparisonPolicy


def _policy_rows(magnitudes: list[float], season: int = 2025) -> pl.DataFrame:
    """A minimal frame shaped like `_join_sources`'s output: keys + `m`."""
    return pl.DataFrame({
        "player_id": [f"P{i}" for i in range(len(magnitudes))],
        "season": [season] * len(magnitudes),
        "week": list(range(1, len(magnitudes) + 1)),
        "m": magnitudes,
    })


def test_policy_outlier_within_budget_passes_and_warns(caplog):
    policy = validate.ComparisonPolicy("test", tight=1.0, hard_cap=10.0, season_budget=3)
    joined = _policy_rows([2.0])  # beyond tight (1.0), well under cap and budget
    with caplog.at_level("WARNING"):
        problems = validate._apply_policy(joined, pl.col("m"), policy, ["m"])
    assert problems == []
    assert any("test" in r.message for r in caplog.records)


def test_policy_budget_plus_one_outliers_in_one_season_fails():
    policy = validate.ComparisonPolicy("test", tight=1.0, hard_cap=10.0, season_budget=3)
    joined = _policy_rows([2.0] * (policy.season_budget + 1))
    problems = validate._apply_policy(joined, pl.col("m"), policy, ["m"])
    assert any("budget" in p for p in problems)


def test_policy_budget_respects_season_boundaries():
    # The same outlier count split across two seasons stays within budget for
    # each — the budget is a per-season limit, not a limit on the whole call.
    policy = validate.ComparisonPolicy("test", tight=1.0, hard_cap=10.0, season_budget=3)
    joined = pl.concat([_policy_rows([2.0] * 3, season=2024), _policy_rows([2.0] * 3, season=2025)])
    assert validate._apply_policy(joined, pl.col("m"), policy, ["m"]) == []


def test_policy_single_row_beyond_hard_cap_fails():
    policy = validate.ComparisonPolicy("test", tight=1.0, hard_cap=5.0, season_budget=10)
    joined = _policy_rows([6.0])
    problems = validate._apply_policy(joined, pl.col("m"), policy, ["m"])
    assert any("hard cap" in p for p in problems)
