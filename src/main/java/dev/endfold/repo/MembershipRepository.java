package dev.endfold.repo;

import dev.endfold.db.Tx;
import dev.endfold.model.Membership;
import dev.endfold.model.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository {
    void insert(Tx tx, Membership m) throws Exception;

    boolean delete(Tx tx, UUID spaceId, UUID player) throws Exception;

    boolean updateRole(Tx tx, UUID spaceId, UUID player, Role role) throws Exception;

    Optional<Membership> find(Tx tx, UUID spaceId, UUID player) throws Exception;

    List<Membership> listBySpace(Tx tx, UUID spaceId) throws Exception;

    List<Membership> listByPlayer(Tx tx, UUID player) throws Exception;

    List<Membership> listAll(Tx tx) throws Exception;

    void deleteAllForSpace(Tx tx, UUID spaceId) throws Exception;
}
