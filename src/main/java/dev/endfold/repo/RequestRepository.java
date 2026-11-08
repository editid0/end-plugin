package dev.endfold.repo;

import dev.endfold.db.Tx;
import dev.endfold.model.MembershipRequest;
import dev.endfold.model.RequestStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequestRepository {
    void insert(Tx tx, MembershipRequest r) throws Exception;

    Optional<MembershipRequest> find(Tx tx, UUID id) throws Exception;

    Optional<MembershipRequest> findPending(Tx tx, UUID spaceId, UUID player) throws Exception;

    boolean resolve(Tx tx, UUID id, RequestStatus to, UUID by, long now) throws Exception;

    List<MembershipRequest> listPendingForSpace(Tx tx, UUID spaceId) throws Exception;

    List<MembershipRequest> listPendingForPlayer(Tx tx, UUID player) throws Exception;

    int cancelAllForSpace(Tx tx, UUID spaceId, long now) throws Exception;
}
