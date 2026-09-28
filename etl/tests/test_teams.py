import polars as pl

from gridiron_etl import teams


def test_team_defense_counts_what_each_defense_allowed_and_took_away():
    base = {"season": 2025, "week": 1, "season_type": "REG", "game_id": "g1",
            "home_team": "KC", "away_team": "BUF"}
    plays = pl.LazyFrame([
        {**base, "posteam": "BUF", "defteam": "KC", "safety": 0, "play_type": "pass", "yards_gained": 20, "sack": 0,
         "interception": 0, "fumble_lost": 0, "touchdown": 0, "td_team": None,
         "total_home_score": 0, "total_away_score": 7},
        {**base, "posteam": "BUF", "defteam": "KC", "safety": 0, "play_type": "pass", "yards_gained": 0, "sack": 0,
         "interception": 1, "fumble_lost": 0, "touchdown": 1, "td_team": "KC",
         "total_home_score": 7, "total_away_score": 7},
        {**base, "posteam": "KC", "defteam": "BUF", "safety": 0, "play_type": "run", "yards_gained": -3, "sack": 1,
         "interception": 0, "fumble_lost": 1, "touchdown": 0, "td_team": None,
         "total_home_score": 10, "total_away_score": 7},
    ])
    out = {r["team"]: r for r in teams.team_defense_from(plays).to_dicts()}

    assert out["KC"]["points_allowed"] == 7
    assert out["KC"]["yards_allowed"] == 20
    assert out["KC"]["interceptions"] == 1
    assert out["KC"]["defensive_tds"] == 1
    assert out["BUF"]["points_allowed"] == 10
    assert out["BUF"]["sacks"] == 1
    assert out["BUF"]["fumbles_recovered"] == 1


def test_a_safety_goes_to_the_team_that_scored_it_and_a_kickoff_return_td_to_the_receiving_team():
    base = {"season": 2025, "week": 1, "season_type": "REG", "game_id": "g1",
            "home_team": "KC", "away_team": "BUF", "yards_gained": 0, "sack": 0,
            "interception": 0, "fumble_lost": 0}
    plays = pl.LazyFrame([
        # With no scores on the play, the defense on it scored the safety.
        {**base, "posteam": "BUF", "defteam": "KC", "play_type": "run", "safety": 1, "touchdown": 0,
         "td_team": None, "total_home_score": 2, "total_away_score": 0,
         "posteam_score": None, "posteam_score_post": None},
        # A punt returner tackled in his own end zone: the punting team (posteam) scores it.
        {**base, "posteam": "KC", "defteam": "BUF", "play_type": "punt", "safety": 1, "touchdown": 0,
         "td_team": None, "total_home_score": 4, "total_away_score": 0,
         "posteam_score": 2, "posteam_score_post": 4},
        # An offense tackled in its own end zone, with scores: the defense scores it.
        {**base, "posteam": "BUF", "defteam": "KC", "play_type": "pass", "safety": 1, "touchdown": 0,
         "td_team": None, "total_home_score": 6, "total_away_score": 0,
         "posteam_score": 0, "posteam_score_post": 0},
        {**base, "posteam": "BUF", "defteam": "KC", "play_type": "kickoff", "safety": 0, "touchdown": 1,
         "td_team": "BUF", "total_home_score": 2, "total_away_score": 6},
        {**base, "posteam": "KC", "defteam": "BUF", "play_type": "punt", "safety": 0, "touchdown": 1,
         "td_team": "BUF", "total_home_score": 2, "total_away_score": 12},
    ])
    out = {r["team"]: r for r in teams.team_defense_from(plays).to_dicts()}

    assert out["KC"]["safeties"] == 3
    assert out["BUF"]["safeties"] == 0
    assert out["KC"]["kick_return_tds"] == 0
    assert out["BUF"]["kick_return_tds"] == 1
    assert out["BUF"]["defensive_tds"] == 1


def _defense(points: float) -> pl.DataFrame:
    return pl.DataFrame([{"team": "KC", "season": 2025, "week": 1, "points_allowed": points,
                          "yards_allowed": 300.0, "sacks": 3.0, "interceptions": 1.0,
                          "fumbles_recovered": 2.0, "defensive_tds": 1.0, "safeties": 1.0,
                          "kick_return_tds": 1.0}])


def test_a_team_week_becomes_its_team_defenses_week():
    row = teams.dst_weekly(_defense(17)).to_dicts()[0]
    assert (row["player_id"], row["team"], row["g"]) == ("DST_KC", "KC", 1)
    assert (row["dst_sacks"], row["dst_interceptions"], row["dst_fumble_recoveries"]) == (3, 1, 2)
    assert (row["dst_tds"], row["dst_safeties"], row["points_allowed"]) == (2, 1, 17)
    assert not any(k.startswith("pa_") for k in row), "tiers are the profile's, never stored"


def test_a_shutout_stores_its_zero_points_allowed():
    assert teams.dst_weekly(_defense(0)).to_dicts()[0]["points_allowed"] == 0


def test_a_team_defense_is_named_for_its_team():
    assert teams.dst_players(["KC"]).to_dicts() == [
        {"player_id": "DST_KC", "full_name": "KC D/ST", "position": "DST", "team": "KC", "pfr_player_id": None},
    ]
