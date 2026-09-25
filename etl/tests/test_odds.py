import pytest

from gridiron_etl import odds


def test_devig_proportional_method():
    # -150/+130: p_raw_over = 150/250 = 0.60, p_raw_under = 100/230 ≈ 0.4348
    # fair_over = 0.60 / (0.60 + 0.4348) ≈ 0.5798
    p = odds.devig(-150, 130)
    assert p == pytest.approx(0.5798, abs=0.001)


def test_devig_is_symmetric_for_even_money_both_sides():
    assert odds.devig(-110, -110) == pytest.approx(0.5, abs=1e-9)


def test_parse_player_props_extracts_receptions_market():
    event = {
        "id": "evt1",
        "bookmakers": [{
            "key": "draftkings",
            "markets": [{
                "key": "player_receptions",
                "outcomes": [
                    {"name": "Over", "description": "Justin Jefferson", "point": 6.5, "price": -115},
                    {"name": "Under", "description": "Justin Jefferson", "point": 6.5, "price": -105},
                ],
            }],
        }],
    }
    df = odds.parse_player_props(event)
    assert df.height == 1
    row = df.row(0, named=True)
    assert row["player_name"] == "Justin Jefferson"
    assert row["market"] == "player_receptions"
    assert row["line"] == 6.5
    assert 0.5 < row["fair_prob"] < 0.55


def test_parse_player_props_includes_all_markets_not_just_the_first():
    # An event with two different markets on the same (first) bookmaker.
    # Before the fix, finding rows for player_reception_yds broke out of the
    # whole markets loop and player_receptions was silently dropped.
    event = {
        "id": "evt1",
        "bookmakers": [{
            "key": "draftkings",
            "markets": [
                {
                    "key": "player_reception_yds",
                    "outcomes": [
                        {"name": "Over", "description": "Justin Jefferson", "point": 75.5, "price": -110},
                        {"name": "Under", "description": "Justin Jefferson", "point": 75.5, "price": -110},
                    ],
                },
                {
                    "key": "player_receptions",
                    "outcomes": [
                        {"name": "Over", "description": "Justin Jefferson", "point": 6.5, "price": -115},
                        {"name": "Under", "description": "Justin Jefferson", "point": 6.5, "price": -105},
                    ],
                },
            ],
        }],
    }
    df = odds.parse_player_props(event)
    markets = sorted(df["market"].to_list())
    assert markets == ["player_reception_yds", "player_receptions"]


def test_parse_player_props_still_limits_to_first_bookmaker_per_market():
    # Preserve the "only the first bookmaker with usable data for THIS
    # market" behavior: a second bookmaker's data for the same market must
    # not be averaged in or override the first.
    event = {
        "id": "evt1",
        "bookmakers": [
            {
                "key": "draftkings",
                "markets": [{
                    "key": "player_receptions",
                    "outcomes": [
                        {"name": "Over", "description": "Justin Jefferson", "point": 6.5, "price": -115},
                        {"name": "Under", "description": "Justin Jefferson", "point": 6.5, "price": -105},
                    ],
                }],
            },
            {
                "key": "fanduel",
                "markets": [{
                    "key": "player_receptions",
                    "outcomes": [
                        {"name": "Over", "description": "Justin Jefferson", "point": 7.5, "price": -120},
                        {"name": "Under", "description": "Justin Jefferson", "point": 7.5, "price": 100},
                    ],
                }],
            },
        ],
    }
    df = odds.parse_player_props(event)
    assert df.height == 1
    assert df.row(0, named=True)["line"] == 6.5


def test_parse_player_props_skips_one_sided_markets():
    # Missing the Under side — can't de-vig, must be skipped, not crash or divide by zero.
    event = {
        "id": "evt1",
        "bookmakers": [{
            "key": "draftkings",
            "markets": [{
                "key": "player_reception_yds",
                "outcomes": [
                    {"name": "Over", "description": "Justin Jefferson", "point": 75.5, "price": -110},
                ],
            }],
        }],
    }
    assert odds.parse_player_props(event).height == 0
