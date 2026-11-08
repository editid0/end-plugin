package dev.endfold.model;

import java.util.UUID;

public record Invitation(UUID id, UUID spaceId, UUID inviteeUuid, UUID inviterUuid, InviteStatus status, long createdAt, long expiresAt) {
    public boolean isExpired(long now) { return now >= expiresAt; }

    public String shortId() { return id.toString().replace("-", "").substring(0, 8); }
}
