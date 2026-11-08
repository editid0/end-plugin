package dev.endfold.db.sql;

import dev.endfold.db.Tx;
import dev.endfold.model.EndSpace;
import dev.endfold.model.JoinMode;
import dev.endfold.model.SpaceStatus;
import dev.endfold.model.SpaceType;
import dev.endfold.repo.SpaceRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class SqlSpaceRepository implements SpaceRepository {
    private static final String SELECT = "SELECT s.*, l.join_mode AS l_mode, l.description AS l_desc FROM end_spaces s "
            + "LEFT JOIN end_public_listings l ON l.space_id = s.id ";

    static EndSpace map(ResultSet rs) throws SQLException {
        String mode = rs.getString("l_mode");
        String desc = rs.getString("l_desc");
        return new EndSpace(
                Tx.uuid(rs, "id"),
                SpaceType.valueOf(rs.getString("type")),
                rs.getString("name"),
                Tx.uuid(rs, "owner_uuid"),
                rs.getString("world_name"),
                Tx.uuid(rs, "world_uuid"),
                rs.getLong("seed"),
                SpaceStatus.valueOf(rs.getString("status")),
                rs.getInt("max_members"),
                mode != null,
                mode == null ? JoinMode.APPROVAL : JoinMode.valueOf(mode),
                desc == null ? "" : desc,
                rs.getLong("created_at"),
                rs.getLong("last_active_at"));
    }

    @Override
    public void insert(Tx tx, EndSpace s) throws SQLException {
        tx.update("INSERT INTO end_spaces(id,type,name,name_key,owner_uuid,solo_owner_uuid,world_name,world_uuid,seed,status,"
                        + "max_members,member_count,created_at,last_active_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?,?,?)",
                s.id(), s.type(), s.name(), s.name().toLowerCase(Locale.ROOT).strip(), s.ownerUuid(),
                s.type() == SpaceType.SOLO ? s.ownerUuid().toString() : null, s.worldName(),
                s.worldUuid() == null ? null : s.worldUuid().toString(), s.seed(), s.status(), s.maxMembers(),
                s.createdAt(), s.lastActiveAt(), s.createdAt());
        writeListing(tx, s);
    }

    @Override
    public void update(Tx tx, EndSpace s) throws SQLException {
        tx.update("UPDATE end_spaces SET name=?, name_key=?, owner_uuid=?, world_uuid=?, status=?, max_members=?, last_active_at=?, "
                        + "updated_at=? WHERE id=?",
                s.name(), s.name().toLowerCase(Locale.ROOT).strip(), s.ownerUuid(), s.worldUuid() == null ? null : s.worldUuid().toString(),
                s.status(), s.maxMembers(), s.lastActiveAt(), System.currentTimeMillis(), s.id());
        writeListing(tx, s);
    }

    private void writeListing(Tx tx, EndSpace s) throws SQLException {
        if (s.isPublic()) {
            long now = System.currentTimeMillis();
            tx.update("INSERT INTO end_public_listings(space_id, join_mode, description, listed_at, updated_at) VALUES (?,?,?,?,?) "
                    + "ON CONFLICT(space_id) DO UPDATE SET join_mode=excluded.join_mode, description=excluded.description, updated_at=excluded.updated_at",
                    s.id(), s.joinMode(), s.description(), now, now);
        } else {
            tx.update("DELETE FROM end_public_listings WHERE space_id=?", s.id());
        }
    }

    @Override
    public Optional<EndSpace> find(Tx tx, UUID id) throws SQLException {
        return tx.one(SELECT + "WHERE s.id=?", SqlSpaceRepository::map, id);
    }

    @Override
    public Optional<EndSpace> findByWorldName(Tx tx, String worldName) throws SQLException {
        return tx.one(SELECT + "WHERE s.world_name=?", SqlSpaceRepository::map, worldName);
    }

    @Override
    public List<EndSpace> listNotDeleted(Tx tx) throws SQLException {
        return tx.query(SELECT + "WHERE s.status <> 'DELETED'", SqlSpaceRepository::map);
    }

    @Override
    public List<EndSpace> listAll(Tx tx) throws SQLException {
        return tx.query(SELECT, SqlSpaceRepository::map);
    }

    @Override
    public int countOwnedLive(Tx tx, UUID owner, SpaceType type) throws SQLException {
        return (int) tx.count("SELECT COUNT(*) FROM end_spaces WHERE owner_uuid=? AND type=? AND status IN ('ACTIVE','BROKEN')", owner, type);
    }

    @Override
    public boolean worldNameExists(Tx tx, String worldName) throws SQLException {
        return tx.count("SELECT COUNT(*) FROM end_spaces WHERE world_name=?", worldName) > 0;
    }

    @Override
    public boolean reserveSlot(Tx tx, UUID spaceId) throws SQLException {
        return tx.update("UPDATE end_spaces SET member_count = member_count + 1 WHERE id=? AND member_count < max_members", spaceId) == 1;
    }

    @Override
    public void releaseSlot(Tx tx, UUID spaceId) throws SQLException {
        tx.update("UPDATE end_spaces SET member_count = member_count - 1 WHERE id=? AND member_count > 0", spaceId);
    }

    @Override
    public int memberCount(Tx tx, UUID spaceId) throws SQLException {
        return (int) tx.count("SELECT member_count FROM end_spaces WHERE id=?", spaceId);
    }

    @Override
    public void touch(Tx tx, UUID spaceId, long now) throws SQLException {
        tx.update("UPDATE end_spaces SET last_active_at=? WHERE id=?", now, spaceId);
    }

    @Override
    public void purge(Tx tx, UUID spaceId) throws SQLException {
        tx.update("DELETE FROM end_spaces WHERE id=?", spaceId);
    }
}
