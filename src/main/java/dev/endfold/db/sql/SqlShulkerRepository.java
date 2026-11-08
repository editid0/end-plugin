package dev.endfold.db.sql;

import dev.endfold.db.Tx;
import dev.endfold.model.BlockPos;
import dev.endfold.model.ShulkerSpawn;
import dev.endfold.repo.ShulkerRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class SqlShulkerRepository implements ShulkerRepository {
    @Override public boolean register(Tx tx, ShulkerSpawn s) throws SQLException {
        return tx.update("INSERT INTO end_shulker_spawns(space_id, x, y, z, city_id, attach_face, next_spawn_at, entity_uuid) VALUES (?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(space_id, x, y, z) DO NOTHING", s.spaceId(), s.pos().x(), s.pos().y(), s.pos().z(), s.cityId(), s.attachFace(),
                s.nextSpawnAt(), s.entityUuid() == null ? null : s.entityUuid().toString()) == 1;
    }

    @Override public List<ShulkerSpawn> list(Tx tx, UUID spaceId) throws SQLException {
        return tx.query("SELECT * FROM end_shulker_spawns WHERE space_id=?", rs -> new ShulkerSpawn(spaceId, new BlockPos(rs.getInt("x"), rs.getInt("y"),
                rs.getInt("z")), rs.getString("city_id"), rs.getString("attach_face"), rs.getLong("next_spawn_at"), Tx.uuid(rs, "entity_uuid")), spaceId);
    }

    @Override public void schedule(Tx tx, UUID spaceId, BlockPos p, long next, UUID entity) throws SQLException {
        tx.update("UPDATE end_shulker_spawns SET next_spawn_at=?, entity_uuid=? WHERE space_id=? AND x=? AND y=? AND z=?",
                next, entity == null ? null : entity.toString(), spaceId, p.x(), p.y(), p.z());
    }
}
