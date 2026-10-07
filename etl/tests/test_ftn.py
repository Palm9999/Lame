"""FTN charting components. Mirrors core/ingest's FtnTest.kt: same plays, same expected numbers."""

import polars as pl
import pytest

from gridiron_etl import ftn

FLAG_COLS = ["is_play_action", "is_qb_out_of_pocket", "is_interception_worthy", "is_throw_away", "is_catchable_ball",
             "is_contested_ball", "is_created_reception", "is_drop", "n_blitzers",
             "is_screen_pass", "is_rpo", "is_motion", "is_no_huddle", "n_defense_box"]
COUNT_COLS = ("n_blitzers", "n_defense_box")


def play(play_id: int, **kw) -> dict:
    base = dict(season=2025, week=1, season_type="REG", game_id="g1", play_id=play_id, posteam="AAA",
                play_type="pass", pass_attempt=1, sack=0, qb_scramble=0, two_point_attempt=0,
                receiver_player_id="WR1", passer_player_id="QB1", rusher_player_id=None)
    base.update(kw)
    return base


COLUMN_OF = {"catchable": "is_catchable_ball", "contested": "is_contested_ball", "drop": "is_drop",
             "created": "is_created_reception", "play_action": "is_play_action", "qb_out_of_pocket": "is_qb_out_of_pocket",
             "throw_away": "is_throw_away", "interception_worthy": "is_interception_worthy",
             "screen": "is_screen_pass", "rpo": "is_rpo", "motion": "is_motion", "no_huddle": "is_no_huddle"}


def flags(**kw) -> dict:
    """Which of FTN's flags this play was charted with; anything unnamed is FALSE (or 0 blitzers)."""
    row = {c: "FALSE" for c in FLAG_COLS}
    row["n_blitzers"] = kw.pop("blitzers", 0)
    row["n_defense_box"] = kw.pop("box", 0)
    row.update({COLUMN_OF[k]: ("TRUE" if v else "FALSE") for k, v in kw.items()})
    return row


PBP_SCHEMA = {"season": pl.Int64, "week": pl.Int64, "season_type": pl.Utf8, "game_id": pl.Utf8, "play_id": pl.Int64,
              "posteam": pl.Utf8, "play_type": pl.Utf8, "pass_attempt": pl.Float64, "sack": pl.Float64,
              "qb_scramble": pl.Float64, "two_point_attempt": pl.Float64, "receiver_player_id": pl.Utf8,
              "passer_player_id": pl.Utf8, "rusher_player_id": pl.Utf8}


def run(*charted):
    """Components for plays, each paired with the FTN flags it was charted with (None: FTN has no row)."""
    plays = [p for p, _ in charted]
    rows = [{"nflverse_game_id": p["game_id"], "nflverse_play_id": p["play_id"], **f} for p, f in charted if f is not None]
    raw = pl.DataFrame(rows, schema={"nflverse_game_id": pl.Utf8, "nflverse_play_id": pl.Int64,
                                     **{c: (pl.Int64 if c in COUNT_COLS else pl.Utf8) for c in FLAG_COLS}})
    return ftn.components(pl.LazyFrame(plays, schema=PBP_SCHEMA), ftn.flags(raw))


def row(df: pl.DataFrame, player: str, week: int = 1) -> dict:
    return df.filter((pl.col("player_id") == player) & (pl.col("week") == week)).to_dicts()[0]


def test_flags_read_true_false_and_a_blank_as_false():
    raw = pl.DataFrame({"nflverse_game_id": ["g1", "g1"], "nflverse_play_id": [1, 2],
                        **{c: ["TRUE", ""] for c in FLAG_COLS if c not in COUNT_COLS}, "n_blitzers": ["2", ""],
                        "n_defense_box": ["7", ""]})
    out = ftn.flags(raw).sort("play_id")
    assert out["drop"].to_list() == [True, False]
    assert out["blitzers"].to_list() == [2, 0]


def test_a_missing_column_is_an_error():
    raw = pl.DataFrame({"nflverse_game_id": ["g1"], "nflverse_play_id": [1]})
    with pytest.raises(Exception):
        ftn.flags(raw)


def test_a_row_without_a_game_or_play_id_is_skipped():
    raw = pl.DataFrame({"nflverse_game_id": ["g1", None], "nflverse_play_id": [None, 2],
                        **{c: ["TRUE", "TRUE"] for c in FLAG_COLS}})
    assert ftn.flags(raw).height == 0


def test_the_target_receiver_is_credited_catchable_contested_drop_and_created():
    df = run((play(1), flags(catchable=True, contested=True, drop=True, created=True)), (play(2), flags()))
    wr = row(df, "WR1")
    assert (wr["ftn_targets"], wr["ftn_catchable"], wr["ftn_contested"]) == (2.0, 1.0, 1.0)
    assert (wr["ftn_drops"], wr["ftn_created_rec"]) == (1.0, 1.0)
    assert (wr["ftn_catchable_rate"], wr["ftn_contested_rate"], wr["ftn_drop_rate"]) == (0.5, 0.5, 0.5)


def test_screens_and_motion_go_to_the_target_and_the_passer_rpo_and_no_huddle_to_the_passer():
    df = run((play(1), flags(screen=True, motion=True, rpo=True)), (play(2), flags(no_huddle=True, motion=True)),
             (play(3), flags()), (play(4), flags()))
    wr = row(df, "WR1")
    assert (wr["ftn_screen_targets"], wr["ftn_screen_target_rate"], wr["ftn_motion_target_rate"]) == (1.0, 0.25, 0.5)
    qb = row(df, "QB1")
    assert (qb["ftn_screen_rate"], qb["ftn_rpo_rate"], qb["ftn_no_huddle_rate"], qb["ftn_motion_rate"]) == (0.25, 0.25, 0.25, 0.5)


def test_the_box_count_averages_over_the_rushers_carries_ftn_counted_it_on():
    def carry(play_id):
        return play(play_id, play_type="run", receiver_player_id=None, passer_player_id=None, rusher_player_id="RB1")
    df = run((carry(1), flags(box=8)), (carry(2), flags(box=6)), (carry(3), flags(box=0)))
    rb = row(df, "RB1")
    assert (rb["ftn_box_carries"], rb["ftn_box_sum"], rb["ftn_avg_box"]) == (2.0, 14.0, 7.0)


def test_the_passer_is_credited_with_the_dropback_weight_attempt_plus_sack_plus_scramble():
    scramble = play(3, play_type="run", pass_attempt=0, qb_scramble=1, rusher_player_id="QB1", receiver_player_id=None)
    sack = play(2, receiver_player_id=None, pass_attempt=0, sack=1)
    df = run((play(1), flags(play_action=True, blitzers=1)), (sack, flags(qb_out_of_pocket=True, blitzers=3)),
             (scramble, flags(play_action=True, qb_out_of_pocket=True)))
    qb = row(df, "QB1")
    assert qb["ftn_dropbacks"] == 3.0
    assert (qb["ftn_pa_db"], qb["ftn_blitz_db"], qb["ftn_oop_db"]) == (2.0, 2.0, 2.0)
    assert qb["ftn_play_action_rate"] == pytest.approx(2 / 3)
    assert qb["ftn_blitz_rate"] == pytest.approx(2 / 3)
    assert qb["ftn_out_of_pocket_rate"] == pytest.approx(2 / 3)


def test_throwaways_count_on_the_passers_dropbacks():
    qb = row(run((play(1), flags(throw_away=True)), (play(2), flags()), (play(3), flags()), (play(4), flags())), "QB1")
    assert (qb["ftn_throwaway"], qb["ftn_throwaway_rate"]) == (1.0, 0.25)


def test_interception_worthy_counts_only_on_attempts():
    sack = play(2, receiver_player_id=None, pass_attempt=0, sack=1)
    qb = row(run((play(1), flags(interception_worthy=True)), (play(3), flags()), (sack, flags(interception_worthy=True))), "QB1")
    assert (qb["ftn_attempts"], qb["ftn_int_worthy"], qb["ftn_int_worthy_rate"]) == (2.0, 1.0, 0.5)
    assert qb["ftn_dropbacks"] == 3.0


def test_a_sack_is_a_dropback_but_not_an_attempt_and_has_no_interception_worthy_rate():
    qb = row(run((play(1, receiver_player_id=None, pass_attempt=0, sack=1), flags(blitzers=2))), "QB1")
    assert (qb["ftn_dropbacks"], qb["ftn_attempts"], qb["ftn_blitz_rate"]) == (1.0, 0.0, 1.0)
    assert qb["ftn_int_worthy_rate"] is None


def test_kneels_spikes_and_two_point_tries_count_nowhere():
    kneel = play(1, play_type="qb_kneel", pass_attempt=0, receiver_player_id=None, rusher_player_id="QB1")
    spike = play(2, play_type="qb_spike", receiver_player_id=None)
    tried = play(3, two_point_attempt=1)
    assert run((kneel, flags(play_action=True)), (spike, flags(play_action=True)), (tried, flags(drop=True))).height == 0


def test_a_play_with_no_ftn_row_counts_nowhere_denominators_included():
    df = run((play(1), flags(drop=True)), (play(2), None), (play(3), None))
    assert row(df, "WR1")["ftn_targets"] == 1.0
    assert row(df, "QB1")["ftn_dropbacks"] == 1.0
    assert row(df, "WR1")["ftn_drop_rate"] == 1.0


def test_a_play_with_no_receiver_or_passer_credits_nobody():
    assert run((play(1, receiver_player_id=None, passer_player_id=None), flags(drop=True, play_action=True))).height == 0


def test_a_play_from_another_game_does_not_match():
    p = play(1, game_id="g1")
    raw = pl.DataFrame([{"nflverse_game_id": "g2", "nflverse_play_id": 1, **flags(drop=True)}])
    df = ftn.components(pl.LazyFrame([p], schema=PBP_SCHEMA), ftn.flags(raw))
    assert df.height == 0


def test_a_zero_drop_week_stores_zeros_and_the_weekly_rates():
    wr = row(run((play(1), flags(catchable=True)), (play(2), flags(catchable=True))), "WR1")
    assert (wr["ftn_drops"], wr["ftn_created_rec"], wr["ftn_contested"]) == (0.0, 0.0, 0.0)
    assert (wr["ftn_drop_rate"], wr["ftn_contested_rate"], wr["ftn_catchable_rate"]) == (0.0, 0.0, 1.0)


def test_two_weeks_give_separate_rows():
    df = run((play(1, week=1), flags(drop=True)), (play(2, week=2), flags()))
    assert row(df, "WR1", 1)["ftn_drops"] == 1.0
    assert row(df, "WR1", 2)["ftn_drops"] == 0.0
    assert row(df, "WR1", 1)["team"] == "AAA"


def test_a_player_who_both_threw_and_was_targeted_has_one_row_with_both_sets():
    trick = play(5, receiver_player_id="QB1", passer_player_id="WR1")
    df = run((play(1), flags()), (trick, flags(catchable=True)))
    wr = row(df, "WR1")
    assert (wr["ftn_targets"], wr["ftn_dropbacks"]) == (1.0, 1.0)
    assert df.filter(pl.col("player_id") == "WR1").height == 1


def test_coverage_counts_eligible_pass_attempts_and_the_charted_ones():
    plays = [play(1), play(2), play(3), play(4, two_point_attempt=1)]
    raw = pl.DataFrame([{"nflverse_game_id": "g1", "nflverse_play_id": 1, **flags()}])
    assert ftn.coverage(pl.LazyFrame(plays, schema=PBP_SCHEMA), ftn.flags(raw)) == (3, 1)


def test_coverage_warning_only_under_ninety_percent():
    w = ftn.coverage_warning(2025, 200, 150)
    assert w is not None and "2025" in w and "75%" in w and "FTN charting covers" in w
    assert ftn.coverage_warning(2025, 200, 180) is None
    assert ftn.coverage_warning(2025, 0, 0) is None
