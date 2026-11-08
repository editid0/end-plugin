package dev.endfold.model;

import java.util.UUID;

/** Per-player state. {@code selectedSpaceId} is the destination used by End portals; it never implies membership changes. */
public record PlayerProfile(UUID playerUuid, String lastName, UUID selectedSpaceId, long firstSeen, long lastSeen) {
    public PlayerProfile withSelected(UUID s) { return new PlayerProfile(playerUuid, lastName, s, firstSeen, lastSeen); }
    public PlayerProfile withName(String n, long seen) { return new PlayerProfile(playerUuid, n, selectedSpaceId, firstSeen, seen); }
}
