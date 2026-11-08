package dev.endfold.model;

import java.util.UUID;

/** Shared progression of an EndSpace. The dragon belongs to the space, not to any player. */
public record SpaceProgress(UUID spaceId, boolean dragonDefeated, int dragonKills, int gatewaysActivated, long firstKillAt, long lastKillAt) {
    public static SpaceProgress empty(UUID spaceId) { return new SpaceProgress(spaceId, false, 0, 0, 0, 0); }
}
