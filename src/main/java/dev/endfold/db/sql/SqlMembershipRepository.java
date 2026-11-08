package dev.endfold.db.sql;

import dev.endfold.db.Tx;
import dev.endfold.model.Membership;
import dev.endfold.model.Role;
import dev.endfold.repo.MembershipRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SqlMembershipRepository implements MembershipRepository {
    private static Membership map(ResultSet rs) throws SQLException {
        return new Membership(Tx.uuid(rs, "space_id"), Tx.uuid(rs, "player_uuid"), Role.valueOf(rs.getString("role")), rs.getLong("joined_at"));
    }

    @Override public void insert(Tx tx, Membership m) throws SQLException {
        tx.update("INSERT INTO end_space_members(space_id, player_uuid, role, joined_at) VALUES (?,?,?,?)", m.spaceId(), m.playerUuid(), m.role(), m.joinedAt());
    }

    @Override public boolean delete(Tx tx, UUID spaceId, UUID player) throws SQLException {
        return tx.update("DELETE FROM end_space_members WHERE space_id=? AND player_uuid=?", spaceId, player) == 1;
    }

    @Override public boolean updateRole(Tx tx, UUID spaceId, UUID player, Role role) throws SQLException {
        return tx.update("UPDATE end_space_members SET role=? WHERE space_id=? AND player_uuid=?", role, spaceId, player) == 1;
    }

    @Override public Optional<Membership> find(Tx tx, UUID spaceId, UUID player) throws SQLException {
        return tx.one("SELECT * FROM end_space_members WHERE space_id=? AND player_uuid=?", SqlMembershipRepository::map, spaceId, player);
    }

    @Override public List<Membership> listBySpace(Tx tx, UUID spaceId) throws SQLException {
        return tx.query("SELECT * FROM end_space_members WHERE space_id=? ORDER BY joined_at", SqlMembershipRepository::map, spaceId);
    }

    @Override public List<Membership> listByPlayer(Tx tx, UUID player) throws SQLException {
        return tx.query("SELECT * FROM end_space_members WHERE player_uuid=? ORDER BY joined_at", SqlMembershipRepository::map, player);
    }

    @Override public List<Membership> listAll(Tx tx) throws SQLException {
        return tx.query("SELECT * FROM end_space_members", SqlMembershipRepository::map);
    }

    @Override public void deleteAllForSpace(Tx tx, UUID spaceId) throws SQLException {
        tx.update("DELETE FROM end_space_members WHERE space_id=?", spaceId);
    }
}
