package dev.endfold.model;

import java.util.UUID;

/** A granted achievement record. {@code spaceId} is the space in which it was earned (achievements are individual). */
public record Achievement(UUID playerUuid, String key, UUID spaceId, long grantedAt) {}
