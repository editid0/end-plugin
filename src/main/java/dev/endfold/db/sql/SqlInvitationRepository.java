package dev.endfold.db.sql;

import dev.endfold.db.Tx;
import dev.endfold.model.InviteStatus;
import dev.endfold.model.Invitation;
import dev.endfold.repo.InvitationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SqlInvitationRepository implements InvitationRepository {
    private static Invitation map(ResultSet rs) throws SQLException {
        return new Invitation(Tx.uuid(rs, "id"), Tx.uuid(rs, "space_id"), Tx.uuid(rs, "invitee_uuid"), Tx.uuid(rs, "inviter_uuid"),
                InviteStatus.valueOf(rs.getString("status")), rs.getLong("created_at"), rs.getLong("expires_at"));
    }

    @Override public void insert(Tx tx, Invitation i) throws SQLException {
        tx.update("INSERT INTO end_invitations(id, space_id, invitee_uuid, inviter_uuid, status, created_at, expires_at) VALUES (?,?,?,?,?,?,?)",
                i.id(), i.spaceId(), i.inviteeUuid(), i.inviterUuid(), i.status(), i.createdAt(), i.expiresAt());
    }

    @Override public Optional<Invitation> find(Tx tx, UUID id) throws SQLException {
        return tx.one("SELECT * FROM end_invitations WHERE id=?", SqlInvitationRepository::map, id);
    }

    @Override public boolean resolve(Tx tx, UUID id, InviteStatus to, long now) throws SQLException {
        return tx.update("UPDATE end_invitations SET status=?, resolved_at=? WHERE id=? AND status='PENDING'", to, now, id) == 1;
    }

    @Override public Optional<Invitation> findPending(Tx tx, UUID spaceId, UUID invitee) throws SQLException {
        return tx.one("SELECT * FROM end_invitations WHERE space_id=? AND invitee_uuid=? AND status='PENDING'", SqlInvitationRepository::map, spaceId, invitee);
    }

    @Override public List<Invitation> listPendingForInvitee(Tx tx, UUID invitee) throws SQLException {
        return tx.query("SELECT * FROM end_invitations WHERE invitee_uuid=? AND status='PENDING' ORDER BY created_at DESC", SqlInvitationRepository::map, invitee);
    }

    @Override public List<Invitation> listPendingForSpace(Tx tx, UUID spaceId) throws SQLException {
        return tx.query("SELECT * FROM end_invitations WHERE space_id=? AND status='PENDING' ORDER BY created_at DESC", SqlInvitationRepository::map, spaceId);
    }

    @Override public List<Invitation> listPendingByInviter(Tx tx, UUID inviter) throws SQLException {
        return tx.query("SELECT * FROM end_invitations WHERE inviter_uuid=? AND status='PENDING' ORDER BY created_at DESC", SqlInvitationRepository::map, inviter);
    }

    @Override public int expire(Tx tx, long now) throws SQLException {
        return tx.update("UPDATE end_invitations SET status='EXPIRED', resolved_at=? WHERE status='PENDING' AND expires_at<=?", now, now);
    }

    @Override public int revokeAllForSpace(Tx tx, UUID spaceId, long now) throws SQLException {
        return tx.update("UPDATE end_invitations SET status='REVOKED', resolved_at=? WHERE space_id=? AND status='PENDING'", now, spaceId);
    }

    @Override public int revokeByInviter(Tx tx, UUID spaceId, UUID inviter, long now) throws SQLException {
        return tx.update("UPDATE end_invitations SET status='REVOKED', resolved_at=? WHERE space_id=? AND inviter_uuid=? AND status='PENDING'", now, spaceId, inviter);
    }
}
