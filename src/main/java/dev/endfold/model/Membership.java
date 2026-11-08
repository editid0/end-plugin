package dev.endfold.model;

import java.util.UUID;

public record Membership(UUID spaceId, UUID playerUuid, Role role, long joinedAt) {
    public Membership withRole(Role r) { return new Membership(spaceId, playerUuid, r, joinedAt); }
}
