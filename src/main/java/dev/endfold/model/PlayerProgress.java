package dev.endfold.model;

import java.util.UUID;

/** Individual progression of one player in one EndSpace. */
public record PlayerProgress(UUID spaceId, UUID playerUuid, int dragonParticipations, int dragonKills, double damageDealt,
                             int chestsLooted, int elytraClaimed, int shulkersKilled) {
    public static PlayerProgress empty(UUID spaceId, UUID player) { return new PlayerProgress(spaceId, player, 0, 0, 0, 0, 0, 0); }
}
