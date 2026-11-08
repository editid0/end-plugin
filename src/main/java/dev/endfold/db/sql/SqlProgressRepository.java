package dev.endfold.db.sql;

import dev.endfold.db.Tx;
import dev.endfold.model.Achievement;
import dev.endfold.model.PlayerProgress;
import dev.endfold.model.SpaceProgress;
import dev.endfold.repo.ProgressRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class SqlProgressRepository implements ProgressRepository {
    private static PlayerProgress mapPlayer(ResultSet rs) throws SQLException {
        return new PlayerProgress(Tx.uuid(rs, "space_id"), Tx.uuid(rs, "player_uuid"), rs.getInt("dragon_participations"),
                rs.getInt("dragon_kills"), rs.getDouble("damage_dealt"), rs.getInt("chests_looted"), rs.getInt("elytra_claimed"),
                rs.getInt("shulkers_killed"));
    }

    @Override public SpaceProgress space(Tx tx, UUID spaceId) throws SQLException {
        return tx.one("SELECT * FROM end_space_progress WHERE space_id=?", rs -> new SpaceProgress(spaceId, rs.getInt("dragon_defeated") == 1,
                rs.getInt("dragon_kills"), rs.getInt("gateways_activated"), rs.getLong("first_kill_at"), rs.getLong("last_kill_at")), spaceId)
                .orElse(SpaceProgress.empty(spaceId));
    }

    @Override public void saveSpace(Tx tx, SpaceProgress p, long now) throws SQLException {
        tx.update("INSERT INTO end_space_progress(space_id, dragon_defeated, dragon_kills, gateways_activated, first_kill_at, last_kill_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?) ON CONFLICT(space_id) DO UPDATE SET dragon_defeated=excluded.dragon_defeated, "
                        + "dragon_kills=excluded.dragon_kills, gateways_activated=excluded.gateways_activated, first_kill_at=excluded.first_kill_at, "
                        + "last_kill_at=excluded.last_kill_at, updated_at=excluded.updated_at",
                p.spaceId(), p.dragonDefeated(), p.dragonKills(), p.gatewaysActivated(), p.firstKillAt(), p.lastKillAt(), now);
    }

    @Override public PlayerProgress player(Tx tx, UUID spaceId, UUID player) throws SQLException {
        return tx.one("SELECT * FROM end_player_progress WHERE space_id=? AND player_uuid=?", SqlProgressRepository::mapPlayer, spaceId, player)
                .orElse(PlayerProgress.empty(spaceId, player));
    }

    @Override public List<PlayerProgress> playerAll(Tx tx, UUID player) throws SQLException {
        return tx.query("SELECT * FROM end_player_progress WHERE player_uuid=?", SqlProgressRepository::mapPlayer, player);
    }

    @Override public List<PlayerProgress> spacePlayers(Tx tx, UUID spaceId) throws SQLException {
        return tx.query("SELECT * FROM end_player_progress WHERE space_id=?", SqlProgressRepository::mapPlayer, spaceId);
    }

    @Override public void addPlayer(Tx tx, UUID spaceId, UUID player, int participations, int kills, double damage, int chests, int elytra,
                                    int shulkers, long now) throws SQLException {
        tx.update("INSERT INTO end_player_progress(space_id, player_uuid, dragon_participations, dragon_kills, damage_dealt, chests_looted, "
                        + "elytra_claimed, shulkers_killed, updated_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(space_id, player_uuid) DO UPDATE SET "
                        + "dragon_participations=dragon_participations+excluded.dragon_participations, dragon_kills=dragon_kills+excluded.dragon_kills, "
                        + "damage_dealt=damage_dealt+excluded.damage_dealt, chests_looted=chests_looted+excluded.chests_looted, "
                        + "elytra_claimed=elytra_claimed+excluded.elytra_claimed, shulkers_killed=shulkers_killed+excluded.shulkers_killed, "
                        + "updated_at=excluded.updated_at",
                spaceId, player, participations, kills, damage, chests, elytra, shulkers, now);
    }

    @Override public boolean grantAchievement(Tx tx, UUID player, String key, UUID spaceId, long now) throws SQLException {
        return tx.update("INSERT INTO end_player_achievements(player_uuid, achievement, space_id, granted_at) VALUES (?,?,?,?) "
                + "ON CONFLICT(player_uuid, achievement) DO NOTHING", player, key, spaceId == null ? null : spaceId.toString(), now) == 1;
    }

    @Override public List<Achievement> achievements(Tx tx, UUID player) throws SQLException {
        return tx.query("SELECT * FROM end_player_achievements WHERE player_uuid=? ORDER BY granted_at",
                rs -> new Achievement(player, rs.getString("achievement"), Tx.uuid(rs, "space_id"), rs.getLong("granted_at")), player);
    }

    @Override public Optional<UUID> activeFight(Tx tx, UUID spaceId) throws SQLException {
        return tx.one("SELECT id FROM end_dragon_fights WHERE space_id=? AND outcome='ACTIVE'", rs -> Tx.uuid(rs, "id"), spaceId);
    }

    @Override public UUID startFight(Tx tx, UUID spaceId, long now) throws SQLException {
        Optional<UUID> active = activeFight(tx, spaceId);
        if (active.isPresent()) return active.get();
        int no = fightCount(tx, spaceId) + 1;
        UUID id = UUID.randomUUID();
        tx.update("INSERT INTO end_dragon_fights(id, space_id, fight_no, outcome, started_at) VALUES (?,?,?,'ACTIVE',?)", id, spaceId, no, now);
        return id;
    }

    @Override public void endFight(Tx tx, UUID fightId, String outcome, UUID killer, long now) throws SQLException {
        tx.update("UPDATE end_dragon_fights SET outcome=?, ended_at=?, killer_uuid=? WHERE id=? AND outcome='ACTIVE'",
                outcome, now, killer == null ? null : killer.toString(), fightId);
    }

    @Override public void saveParticipants(Tx tx, UUID fightId, Map<UUID, Double> damage, Set<UUID> participated) throws SQLException {
        java.util.Set<UUID> all = new java.util.HashSet<>(damage.keySet());
        all.addAll(participated);
        for (UUID p : all) {
            tx.update("INSERT INTO end_dragon_fight_participants(fight_id, player_uuid, damage, participated) VALUES (?,?,?,?) "
                            + "ON CONFLICT(fight_id, player_uuid) DO UPDATE SET damage=excluded.damage, participated=excluded.participated",
                    fightId, p, damage.getOrDefault(p, 0.0), participated.contains(p));
        }
    }

    @Override public int fightCount(Tx tx, UUID spaceId) throws SQLException {
        return (int) tx.count("SELECT COUNT(*) FROM end_dragon_fights WHERE space_id=?", spaceId);
    }
}
