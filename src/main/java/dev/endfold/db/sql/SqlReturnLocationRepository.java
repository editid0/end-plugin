package dev.endfold.db.sql;

import dev.endfold.db.Tx;
import dev.endfold.model.ReturnLocation;
import dev.endfold.repo.ReturnLocationRepository;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public final class SqlReturnLocationRepository implements ReturnLocationRepository {
    @Override public void upsert(Tx tx, ReturnLocation l, long now) throws SQLException {
        tx.update("INSERT INTO end_return_locations(player_uuid, space_id, world_uuid, world_name, x, y, z, yaw, pitch, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(player_uuid, space_id) DO UPDATE SET world_uuid=excluded.world_uuid, world_name=excluded.world_name, x=excluded.x, "
                        + "y=excluded.y, z=excluded.z, yaw=excluded.yaw, pitch=excluded.pitch, updated_at=excluded.updated_at",
                l.playerUuid(), l.spaceId(), l.worldUuid(), l.worldName(), l.x(), l.y(), l.z(), (double) l.yaw(), (double) l.pitch(), now);
    }

    @Override public Optional<ReturnLocation> find(Tx tx, UUID player, UUID spaceId) throws SQLException {
        return tx.one("SELECT * FROM end_return_locations WHERE player_uuid=? AND space_id=?",
                rs -> new ReturnLocation(player, spaceId, Tx.uuid(rs, "world_uuid"), rs.getString("world_name"), rs.getDouble("x"), rs.getDouble("y"),
                        rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch")), player, spaceId);
    }

    @Override public void saveSession(Tx tx, ReturnLocation l, long now) throws SQLException {
        tx.update("INSERT INTO end_player_sessions(player_uuid, space_id, x, y, z, yaw, pitch, updated_at) VALUES (?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(player_uuid) DO UPDATE SET space_id=excluded.space_id, x=excluded.x, y=excluded.y, z=excluded.z, "
                        + "yaw=excluded.yaw, pitch=excluded.pitch, updated_at=excluded.updated_at",
                l.playerUuid(), l.spaceId(), l.x(), l.y(), l.z(), (double) l.yaw(), (double) l.pitch(), now);
    }

    @Override public Optional<ReturnLocation> session(Tx tx, UUID player) throws SQLException {
        return tx.one("SELECT * FROM end_player_sessions WHERE player_uuid=?", rs -> new ReturnLocation(player, Tx.uuid(rs, "space_id"), null, null,
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch")), player);
    }

    @Override public void clearSession(Tx tx, UUID player) throws SQLException {
        tx.update("DELETE FROM end_player_sessions WHERE player_uuid=?", player);
    }
}
