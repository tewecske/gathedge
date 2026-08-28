-- Every game now records its plays for its owner to see — the opt-in track_results flag and its NotTracked
-- refusal are gone. game_plays/game_play_answers were always written unconditionally, so recorded history is
-- unchanged; only the owner-facing read stops being gated. Existing FALSE games become readable by their
-- owner, which is the intended effect.
ALTER TABLE games DROP COLUMN track_results;
