package dev.endfold.model;

import java.util.UUID;

/** Where a player entered a given EndSpace from, so they return to the same place. Stored per (player, space). */
public record ReturnLocation(UUID playerUuid, UUID spaceId, UUID worldUuid, String worldName, double x, double y, double z, float yaw, float pitch) {}
