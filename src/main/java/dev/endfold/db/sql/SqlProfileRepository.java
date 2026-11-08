package dev.endfold.db.sql;

import dev.endfold.db.Tx;
import dev.endfold.model.PlayerProfile;
import dev.endfold.repo.ProfileRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SqlProfileRepository implements ProfileRepository {
    private static PlayerProfile map(ResultSet rs) throws SQLException {
        return new PlayerProfile(Tx.uuid(rs, "player_uuid"), rs.getString("last_name"), Tx.uuid(rs, "selected_space_id"),
                rs.getLong("first_seen"), rs.getLong("last_seen"));
    }

    @Override public PlayerProfile upsertSeen(Tx tx, UUID player, String name, long now) throws SQLException {
        tx.update("INSERT INTO end_player_profiles(player_uuid, last_name, selected_space_id, first_seen, last_seen) VALUES (?,?,NULL,?,?) "
                + "ON CONFLICT(player_uuid) DO UPDATE SET last_name=excluded.last_name, last_seen=excluded.last_seen", player, name, now, now);
        return find(tx, player).orElseThrow();
    }

    @Override public Optional<PlayerProfile> find(Tx tx, UUID player) throws SQLException {
        return tx.one("SELECT * FROM end_player_profiles WHERE player_uuid=?", SqlProfileRepository::map, player);
    }

    @Override public Optional<PlayerProfile> findByName(Tx tx, String name) throws SQLException {
        return tx.one("SELECT * FROM end_player_profiles WHERE lower(last_name)=lower(?) ORDER BY last_seen DESC LIMIT 1", SqlProfileRepository::map, name);
    }

    @Override public void setSelected(Tx tx, UUID player, UUID spaceId) throws SQLException {
        tx.update("UPDATE end_player_profiles SET selected_space_id=? WHERE player_uuid=?", spaceId == null ? null : spaceId.toString(), player);
    }

    @Override public boolean clearSelectedIf(Tx tx, UUID player, UUID spaceId) throws SQLException {
        return tx.update("UPDATE end_player_profiles SET selected_space_id=NULL WHERE player_uuid=? AND selected_space_id=?", player, spaceId) == 1;
    }

    @Override public List<UUID> clearSelectedForSpace(Tx tx, UUID spaceId) throws SQLException {
        List<UUID> affected = tx.query("SELECT player_uuid FROM end_player_profiles WHERE selected_space_id=?", rs -> Tx.uuid(rs, "player_uuid"), spaceId);
        tx.update("UPDATE end_player_profiles SET selected_space_id=NULL WHERE selected_space_id=?", spaceId);
        return affected;
    }

    @Override public List<PlayerProfile> listAll(Tx tx) throws SQLException {
        return tx.query("SELECT * FROM end_player_profiles", SqlProfileRepository::map);
    }
}
