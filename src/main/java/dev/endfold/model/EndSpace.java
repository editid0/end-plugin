package dev.endfold.model;

import java.util.UUID;

/**
 * Immutable snapshot of an EndSpace record. Mutations produce a new instance via the {@code with*} methods.
 *
 * @param worldName deterministic world name derived from the id ({@code endspace_<first 8 hex of id>})
 * @param worldUuid UUID of the Bukkit world once generated, otherwise {@code null}
 */
public record EndSpace(
        UUID id,
        SpaceType type,
        String name,
        UUID ownerUuid,
        String worldName,
        UUID worldUuid,
        long seed,
        SpaceStatus status,
        int maxMembers,
        boolean isPublic,
        JoinMode joinMode,
        String description,
        long createdAt,
        long lastActiveAt) {

    public EndSpace withStatus(SpaceStatus s) { return new EndSpace(id, type, name, ownerUuid, worldName, worldUuid, seed, s, maxMembers, isPublic, joinMode, description, createdAt, lastActiveAt); }
    public EndSpace withOwner(UUID o) { return new EndSpace(id, type, name, o, worldName, worldUuid, seed, status, maxMembers, isPublic, joinMode, description, createdAt, lastActiveAt); }
    public EndSpace withName(String n) { return new EndSpace(id, type, n, ownerUuid, worldName, worldUuid, seed, status, maxMembers, isPublic, joinMode, description, createdAt, lastActiveAt); }
    public EndSpace withWorldUuid(UUID w) { return new EndSpace(id, type, name, ownerUuid, worldName, w, seed, status, maxMembers, isPublic, joinMode, description, createdAt, lastActiveAt); }
    public EndSpace withPublic(boolean p, JoinMode m) { return new EndSpace(id, type, name, ownerUuid, worldName, worldUuid, seed, status, maxMembers, p, m, description, createdAt, lastActiveAt); }
    public EndSpace withMaxMembers(int m) { return new EndSpace(id, type, name, ownerUuid, worldName, worldUuid, seed, status, m, isPublic, joinMode, description, createdAt, lastActiveAt); }
    public EndSpace withDescription(String d) { return new EndSpace(id, type, name, ownerUuid, worldName, worldUuid, seed, status, maxMembers, isPublic, joinMode, d, createdAt, lastActiveAt); }
    public EndSpace withLastActive(long t) { return new EndSpace(id, type, name, ownerUuid, worldName, worldUuid, seed, status, maxMembers, isPublic, joinMode, description, createdAt, t); }

    /** Short, stable, human-readable id used in commands and world names. */
    public String shortId() { return shortId(id); }

    public static String shortId(UUID id) { return id.toString().replace("-", "").substring(0, 8); }

    public static String worldNameFor(UUID id) { return "endspace_" + shortId(id); }
}
