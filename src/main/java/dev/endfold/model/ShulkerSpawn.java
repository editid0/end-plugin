package dev.endfold.model;

import java.util.UUID;

/** A shulker spawn point that belongs to a generated End City, with its persisted respawn schedule. */
public record ShulkerSpawn(UUID spaceId, BlockPos pos, String cityId, String attachFace, long nextSpawnAt, UUID entityUuid) {}
