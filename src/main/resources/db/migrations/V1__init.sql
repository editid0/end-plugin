-- Endfold schema V1.
-- Portable SQL: UUIDs as TEXT, timestamps as INTEGER epoch millis, blobs via ${BLOB}.
-- The migration runner splits statements on a semicolon at the end of a line.

CREATE TABLE end_spaces (
    id              TEXT    PRIMARY KEY,
    type            TEXT    NOT NULL CHECK (type IN ('SOLO','GROUP')),
    name            TEXT    NOT NULL,
    name_key        TEXT    NOT NULL,
    owner_uuid      TEXT    NOT NULL,
    solo_owner_uuid TEXT,
    world_name      TEXT    NOT NULL UNIQUE,
    world_uuid      TEXT,
    seed            INTEGER NOT NULL,
    status          TEXT    NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED','BROKEN','DELETED')),
    max_members     INTEGER NOT NULL CHECK (max_members >= 1),
    member_count    INTEGER NOT NULL DEFAULT 0 CHECK (member_count >= 0),
    created_at      INTEGER NOT NULL,
    last_active_at  INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    CHECK (type = 'GROUP' OR solo_owner_uuid IS NOT NULL)
);
-- At most one live SOLO space per player: concurrent "create solo" requests hit a unique violation.
CREATE UNIQUE INDEX ux_spaces_solo_owner ON end_spaces(solo_owner_uuid) WHERE type = 'SOLO' AND status IN ('ACTIVE','BROKEN');
CREATE INDEX ix_spaces_owner ON end_spaces(owner_uuid, status);
CREATE INDEX ix_spaces_status ON end_spaces(status);

CREATE TABLE end_space_members (
    space_id    TEXT    NOT NULL REFERENCES end_spaces(id) ON DELETE CASCADE,
    player_uuid TEXT    NOT NULL,
    role        TEXT    NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER')),
    joined_at   INTEGER NOT NULL,
    PRIMARY KEY (space_id, player_uuid)
);
CREATE INDEX ix_members_player ON end_space_members(player_uuid);
-- Exactly one owner row per space; ownership transfer demotes before promoting inside one transaction.
CREATE UNIQUE INDEX ux_members_one_owner ON end_space_members(space_id) WHERE role = 'OWNER';

CREATE TABLE end_player_profiles (
    player_uuid       TEXT    PRIMARY KEY,
    last_name         TEXT    NOT NULL,
    selected_space_id TEXT    REFERENCES end_spaces(id) ON DELETE SET NULL,
    first_seen        INTEGER NOT NULL,
    last_seen         INTEGER NOT NULL
);
CREATE INDEX ix_profiles_name ON end_player_profiles(last_name);

CREATE TABLE end_invitations (
    id           TEXT    PRIMARY KEY,
    space_id     TEXT    NOT NULL REFERENCES end_spaces(id) ON DELETE CASCADE,
    invitee_uuid TEXT    NOT NULL,
    inviter_uuid TEXT    NOT NULL,
    status       TEXT    NOT NULL CHECK (status IN ('PENDING','ACCEPTED','DECLINED','REVOKED','EXPIRED')),
    created_at   INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL,
    resolved_at  INTEGER
);
CREATE UNIQUE INDEX ux_invites_pending ON end_invitations(space_id, invitee_uuid) WHERE status = 'PENDING';
CREATE INDEX ix_invites_invitee ON end_invitations(invitee_uuid, status);
CREATE INDEX ix_invites_space ON end_invitations(space_id, status);

CREATE TABLE end_public_listings (
    space_id    TEXT    PRIMARY KEY REFERENCES end_spaces(id) ON DELETE CASCADE,
    join_mode   TEXT    NOT NULL CHECK (join_mode IN ('OPEN','APPROVAL')),
    description TEXT    NOT NULL DEFAULT '',
    listed_at   INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

CREATE TABLE end_membership_requests (
    id          TEXT    PRIMARY KEY,
    space_id    TEXT    NOT NULL REFERENCES end_spaces(id) ON DELETE CASCADE,
    player_uuid TEXT    NOT NULL,
    status      TEXT    NOT NULL CHECK (status IN ('PENDING','APPROVED','DENIED','CANCELLED')),
    created_at  INTEGER NOT NULL,
    resolved_at INTEGER,
    resolved_by TEXT
);
CREATE UNIQUE INDEX ux_requests_pending ON end_membership_requests(space_id, player_uuid) WHERE status = 'PENDING';
CREATE INDEX ix_requests_space ON end_membership_requests(space_id, status);
CREATE INDEX ix_requests_player ON end_membership_requests(player_uuid, status);

CREATE TABLE end_space_progress (
    space_id           TEXT    PRIMARY KEY REFERENCES end_spaces(id) ON DELETE CASCADE,
    dragon_defeated    INTEGER NOT NULL DEFAULT 0,
    dragon_kills       INTEGER NOT NULL DEFAULT 0,
    gateways_activated INTEGER NOT NULL DEFAULT 0,
    first_kill_at      INTEGER NOT NULL DEFAULT 0,
    last_kill_at       INTEGER NOT NULL DEFAULT 0,
    updated_at         INTEGER NOT NULL
);

CREATE TABLE end_player_progress (
    space_id              TEXT    NOT NULL REFERENCES end_spaces(id) ON DELETE CASCADE,
    player_uuid           TEXT    NOT NULL,
    dragon_participations INTEGER NOT NULL DEFAULT 0,
    dragon_kills          INTEGER NOT NULL DEFAULT 0,
    damage_dealt          REAL    NOT NULL DEFAULT 0,
    chests_looted         INTEGER NOT NULL DEFAULT 0,
    elytra_claimed        INTEGER NOT NULL DEFAULT 0,
    shulkers_killed       INTEGER NOT NULL DEFAULT 0,
    updated_at            INTEGER NOT NULL,
    PRIMARY KEY (space_id, player_uuid)
);
CREATE INDEX ix_pprogress_player ON end_player_progress(player_uuid);

-- Achievements are individual. space_id records where it was first earned (no FK so a purge keeps the record).
CREATE TABLE end_player_achievements (
    player_uuid TEXT    NOT NULL,
    achievement TEXT    NOT NULL,
    space_id    TEXT,
    granted_at  INTEGER NOT NULL,
    PRIMARY KEY (player_uuid, achievement)
);

CREATE TABLE end_dragon_fights (
    id          TEXT    PRIMARY KEY,
    space_id    TEXT    NOT NULL REFERENCES end_spaces(id) ON DELETE CASCADE,
    fight_no    INTEGER NOT NULL,
    outcome     TEXT    NOT NULL CHECK (outcome IN ('ACTIVE','KILLED','ABORTED')),
    started_at  INTEGER NOT NULL,
    ended_at    INTEGER,
    killer_uuid TEXT,
    UNIQUE (space_id, fight_no)
);
CREATE UNIQUE INDEX ux_fights_active ON end_dragon_fights(space_id) WHERE outcome = 'ACTIVE';

CREATE TABLE end_dragon_fight_participants (
    fight_id     TEXT    NOT NULL REFERENCES end_dragon_fights(id) ON DELETE CASCADE,
    player_uuid  TEXT    NOT NULL,
    damage       REAL    NOT NULL DEFAULT 0,
    participated INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (fight_id, player_uuid)
);

-- Generic once-per-(space, player, key) claims. Personal chest generation inserts 'chest:x,y,z' here.
CREATE TABLE end_loot_claims (
    space_id    TEXT    NOT NULL REFERENCES end_spaces(id) ON DELETE CASCADE,
    player_uuid TEXT    NOT NULL,
    claim_key   TEXT    NOT NULL,
    claimed_at  INTEGER NOT NULL,
    PRIMARY KEY (space_id, player_uuid, claim_key)
);

-- Known End City loot containers per space (registered at chunk load while the vanilla loot table is still unrolled).
CREATE TABLE end_loot_containers (
    space_id   TEXT    NOT NULL REFERENCES end_spaces(id) ON DELETE CASCADE,
    x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
    loot_table TEXT    NOT NULL,
    registered_at INTEGER NOT NULL,
    PRIMARY KEY (space_id, x, y, z)
);

CREATE TABLE end_personal_inventories (
    space_id    TEXT    NOT NULL REFERENCES end_spaces(id) ON DELETE CASCADE,
    player_uuid TEXT    NOT NULL,
    x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
    loot_table  TEXT    NOT NULL,
    size        INTEGER NOT NULL,
    contents    ${BLOB} NOT NULL,
    rev         INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (space_id, player_uuid, x, y, z)
);
CREATE INDEX ix_inv_player ON end_personal_inventories(player_uuid);

CREATE TABLE end_elytra_claims (
    space_id    TEXT    NOT NULL REFERENCES end_spaces(id) ON DELETE CASCADE,
    player_uuid TEXT    NOT NULL,
    ship_id     TEXT    NOT NULL,
    delivery_id TEXT    NOT NULL UNIQUE,
    claimed_at  INTEGER NOT NULL,
    PRIMARY KEY (space_id, player_uuid, ship_id)
);
CREATE INDEX ix_elytra_player ON end_elytra_claims(player_uuid, claimed_at);

-- Outbox: items owed to players. A row is written in the same transaction as the claim it pays out.
CREATE TABLE end_pending_deliveries (
    delivery_id  TEXT    PRIMARY KEY,
    player_uuid  TEXT    NOT NULL,
    space_id     TEXT,
    kind         TEXT    NOT NULL,
    item_bytes   ${BLOB} NOT NULL,
    created_at   INTEGER NOT NULL,
    delivered_at INTEGER
);
CREATE INDEX ix_deliv_player_pending ON end_pending_deliveries(player_uuid, delivered_at);

CREATE TABLE end_return_locations (
    player_uuid TEXT    NOT NULL,
    space_id    TEXT    NOT NULL REFERENCES end_spaces(id) ON DELETE CASCADE,
    world_uuid  TEXT    NOT NULL,
    world_name  TEXT    NOT NULL,
    x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,
    yaw REAL NOT NULL, pitch REAL NOT NULL,
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (player_uuid, space_id)
);

CREATE TABLE end_shulker_spawns (
    space_id      TEXT    NOT NULL REFERENCES end_spaces(id) ON DELETE CASCADE,
    x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
    city_id       TEXT    NOT NULL,
    attach_face   TEXT    NOT NULL,
    next_spawn_at INTEGER NOT NULL DEFAULT 0,
    entity_uuid   TEXT,
    PRIMARY KEY (space_id, x, y, z)
);
CREATE INDEX ix_shulker_due ON end_shulker_spawns(space_id, next_spawn_at);

-- Admin and lifecycle audit trail. No FK so entries survive a purge.
CREATE TABLE end_audit_log (
    id         ${AUTOID},
    ts         INTEGER NOT NULL,
    space_id   TEXT,
    actor_uuid TEXT,
    action     TEXT    NOT NULL,
    detail     TEXT
);
CREATE INDEX ix_audit_space ON end_audit_log(space_id, ts);

-- Where a player logged out inside an EndSpace, so they can be put back after a restart (when that world is unloaded).
CREATE TABLE end_player_sessions (
    player_uuid TEXT    PRIMARY KEY,
    space_id    TEXT    NOT NULL REFERENCES end_spaces(id) ON DELETE CASCADE,
    x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,
    yaw REAL NOT NULL, pitch REAL NOT NULL,
    updated_at  INTEGER NOT NULL
);
