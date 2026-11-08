package dev.endfold.model;

import java.util.UUID;

/** A player's application to join an approval-required public expedition. */
public record MembershipRequest(UUID id, UUID spaceId, UUID playerUuid, RequestStatus status, long createdAt) {}
