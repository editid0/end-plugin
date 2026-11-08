package dev.endfold.model;

import java.util.UUID;

/** Outbox entry for an item owed to a player (crash-safe reward delivery). */
public record PendingDelivery(UUID deliveryId, UUID playerUuid, UUID spaceId, String kind, byte[] itemBytes, long createdAt) {}
