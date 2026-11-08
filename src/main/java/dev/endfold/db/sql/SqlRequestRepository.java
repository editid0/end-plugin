package dev.endfold.db.sql;

import dev.endfold.db.Tx;
import dev.endfold.model.MembershipRequest;
import dev.endfold.model.RequestStatus;
import dev.endfold.repo.RequestRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SqlRequestRepository implements RequestRepository {
    private static MembershipRequest map(ResultSet rs) throws SQLException {
        return new MembershipRequest(Tx.uuid(rs, "id"), Tx.uuid(rs, "space_id"), Tx.uuid(rs, "player_uuid"),
                RequestStatus.valueOf(rs.getString("status")), rs.getLong("created_at"));
    }

    @Override public void insert(Tx tx, MembershipRequest r) throws SQLException {
        tx.update("INSERT INTO end_membership_requests(id, space_id, player_uuid, status, created_at) VALUES (?,?,?,?,?)",
                r.id(), r.spaceId(), r.playerUuid(), r.status(), r.createdAt());
    }

    @Override public Optional<MembershipRequest> find(Tx tx, UUID id) throws SQLException {
        return tx.one("SELECT * FROM end_membership_requests WHERE id=?", SqlRequestRepository::map, id);
    }

    @Override public Optional<MembershipRequest> findPending(Tx tx, UUID spaceId, UUID player) throws SQLException {
        return tx.one("SELECT * FROM end_membership_requests WHERE space_id=? AND player_uuid=? AND status='PENDING'", SqlRequestRepository::map, spaceId, player);
    }

    @Override public boolean resolve(Tx tx, UUID id, RequestStatus to, UUID by, long now) throws SQLException {
        return tx.update("UPDATE end_membership_requests SET status=?, resolved_at=?, resolved_by=? WHERE id=? AND status='PENDING'",
                to, now, by == null ? null : by.toString(), id) == 1;
    }

    @Override public List<MembershipRequest> listPendingForSpace(Tx tx, UUID spaceId) throws SQLException {
        return tx.query("SELECT * FROM end_membership_requests WHERE space_id=? AND status='PENDING' ORDER BY created_at", SqlRequestRepository::map, spaceId);
    }

    @Override public List<MembershipRequest> listPendingForPlayer(Tx tx, UUID player) throws SQLException {
        return tx.query("SELECT * FROM end_membership_requests WHERE player_uuid=? AND status='PENDING' ORDER BY created_at", SqlRequestRepository::map, player);
    }

    @Override public int cancelAllForSpace(Tx tx, UUID spaceId, long now) throws SQLException {
        return tx.update("UPDATE end_membership_requests SET status='CANCELLED', resolved_at=? WHERE space_id=? AND status='PENDING'", now, spaceId);
    }
}
