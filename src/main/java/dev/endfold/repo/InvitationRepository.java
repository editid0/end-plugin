package dev.endfold.repo;

import dev.endfold.db.Tx;
import dev.endfold.model.InviteStatus;
import dev.endfold.model.Invitation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository {
    void insert(Tx tx, Invitation inv) throws Exception;

    Optional<Invitation> find(Tx tx, UUID id) throws Exception;

    /** Resolves the invitation only if still PENDING (compare-and-set). */
    boolean resolve(Tx tx, UUID id, InviteStatus to, long now) throws Exception;

    Optional<Invitation> findPending(Tx tx, UUID spaceId, UUID invitee) throws Exception;

    List<Invitation> listPendingForInvitee(Tx tx, UUID invitee) throws Exception;

    List<Invitation> listPendingForSpace(Tx tx, UUID spaceId) throws Exception;

    List<Invitation> listPendingByInviter(Tx tx, UUID inviter) throws Exception;

    int expire(Tx tx, long now) throws Exception;

    int revokeAllForSpace(Tx tx, UUID spaceId, long now) throws Exception;

    int revokeByInviter(Tx tx, UUID spaceId, UUID inviter, long now) throws Exception;
}
