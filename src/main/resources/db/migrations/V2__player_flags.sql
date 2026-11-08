-- Endfold schema V2: per-player progression flags (e.g. "has entered an End through a real End portal").
CREATE TABLE end_player_flags (
    player_uuid TEXT    NOT NULL,
    flag        TEXT    NOT NULL,
    set_at      INTEGER NOT NULL,
    PRIMARY KEY (player_uuid, flag)
);
CREATE INDEX ix_player_flags_flag ON end_player_flags(flag);
