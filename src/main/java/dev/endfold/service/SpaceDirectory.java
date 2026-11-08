package dev.endfold.service;

import dev.endfold.model.EndSpace;
import dev.endfold.model.Membership;
import dev.endfold.model.PlayerProfile;
import dev.endfold.model.Role;
import dev.endfold.model.SpaceStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, write-through, in-memory view of spaces, memberships and selections.
 *
 * <p>The database is the source of truth. This directory is updated only from after-commit hooks on the DB thread and
 * read from the server thread, which lets synchronous Bukkit events (teleports, portals) perform access checks in O(1)
 * without blocking on I/O. Services always re-validate inside their transactions.</p>
 */
public final class SpaceDirectory {
    private final Map<UUID, EndSpace> spaces = new ConcurrentHashMap<>();
    private final Map<String, UUID> byWorldName = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Membership>> membersBySpace = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> spacesByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();

    public void load(Collection<EndSpace> allSpaces, Collection<Membership> allMembers, Collection<PlayerProfile> allProfiles) {
        spaces.clear();
        byWorldName.clear();
        membersBySpace.clear();
        spacesByPlayer.clear();
        profiles.clear();
        allSpaces.forEach(this::putSpace);
        allMembers.forEach(this::putMember);
        allProfiles.forEach(this::putProfile);
    }

    // ---- writes (after commit) ----

    public void putSpace(EndSpace s) {
        if (s.status() == SpaceStatus.DELETED) {
            removeSpace(s.id());
            return;
        }
        spaces.put(s.id(), s);
        byWorldName.put(s.worldName().toLowerCase(java.util.Locale.ROOT), s.id());
    }

    public void removeSpace(UUID id) {
        EndSpace s = spaces.remove(id);
        if (s != null) byWorldName.remove(s.worldName().toLowerCase(java.util.Locale.ROOT));
        Map<UUID, Membership> m = membersBySpace.remove(id);
        if (m != null) m.keySet().forEach(p -> {
            Set<UUID> set = spacesByPlayer.get(p);
            if (set != null) set.remove(id);
        });
        profiles.replaceAll((k, v) -> id.equals(v.selectedSpaceId()) ? v.withSelected(null) : v);
    }

    public void putMember(Membership m) {
        membersBySpace.computeIfAbsent(m.spaceId(), k -> new ConcurrentHashMap<>()).put(m.playerUuid(), m);
        spacesByPlayer.computeIfAbsent(m.playerUuid(), k -> ConcurrentHashMap.newKeySet()).add(m.spaceId());
    }

    public void removeMember(UUID spaceId, UUID player) {
        Map<UUID, Membership> m = membersBySpace.get(spaceId);
        if (m != null) m.remove(player);
        Set<UUID> set = spacesByPlayer.get(player);
        if (set != null) set.remove(spaceId);
    }

    public void putProfile(PlayerProfile p) { profiles.put(p.playerUuid(), p); }

    public void setSelected(UUID player, UUID spaceId) {
        profiles.computeIfPresent(player, (k, v) -> v.withSelected(spaceId));
    }

    // ---- reads ----

    public Optional<EndSpace> space(UUID id) { return Optional.ofNullable(id == null ? null : spaces.get(id)); }

    public Optional<EndSpace> spaceByWorldName(String worldName) {
        UUID id = byWorldName.get(worldName.toLowerCase(java.util.Locale.ROOT));
        return id == null ? Optional.empty() : space(id);
    }

    /** Finds a space by full UUID, 8-char short id or world name. */
    public Optional<EndSpace> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String t = token.strip().toLowerCase(java.util.Locale.ROOT);
        try {
            return space(UUID.fromString(t));
        } catch (IllegalArgumentException ignored) {
            // not a full uuid
        }
        Optional<EndSpace> byWorld = spaceByWorldName(t.startsWith("endspace_") ? t : "endspace_" + t);
        return byWorld;
    }

    public Collection<EndSpace> allSpaces() { return List.copyOf(spaces.values()); }

    public List<Membership> members(UUID spaceId) {
        Map<UUID, Membership> m = membersBySpace.get(spaceId);
        if (m == null) return List.of();
        List<Membership> list = new ArrayList<>(m.values());
        list.sort(Comparator.comparingInt((Membership x) -> -x.role().rank()).thenComparingLong(Membership::joinedAt));
        return list;
    }

    public int memberCount(UUID spaceId) {
        Map<UUID, Membership> m = membersBySpace.get(spaceId);
        return m == null ? 0 : m.size();
    }

    public Optional<Membership> membership(UUID spaceId, UUID player) {
        Map<UUID, Membership> m = membersBySpace.get(spaceId);
        return Optional.ofNullable(m == null ? null : m.get(player));
    }

    public boolean isMember(UUID spaceId, UUID player) { return membership(spaceId, player).isPresent(); }

    public Optional<Role> role(UUID spaceId, UUID player) { return membership(spaceId, player).map(Membership::role); }

    /** All non-deleted spaces the player belongs to, ordered SOLO first then by name. */
    public List<EndSpace> spacesOf(UUID player) {
        Set<UUID> ids = spacesByPlayer.get(player);
        if (ids == null) return List.of();
        List<EndSpace> out = new ArrayList<>();
        for (UUID id : ids) space(id).ifPresent(out::add);
        out.sort(Comparator.comparing((EndSpace s) -> s.type().ordinal()).thenComparing(s -> s.name().toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    public Optional<PlayerProfile> profile(UUID player) { return Optional.ofNullable(profiles.get(player)); }

    public Optional<UUID> selectedId(UUID player) { return profile(player).map(PlayerProfile::selectedSpaceId); }

    public Optional<String> lastKnownName(UUID player) { return profile(player).map(PlayerProfile::lastName); }

    public Optional<PlayerProfile> profileByName(String name) {
        return profiles.values().stream().filter(p -> p.lastName().equalsIgnoreCase(name)).max(Comparator.comparingLong(PlayerProfile::lastSeen));
    }

    public Collection<PlayerProfile> allProfiles() { return List.copyOf(profiles.values()); }

    /** Access policy used by portal routing and teleport guards: active space and a current member. */
    public boolean canEnter(UUID spaceId, UUID player) {
        return space(spaceId).map(s -> s.status() == SpaceStatus.ACTIVE).orElse(false) && isMember(spaceId, player);
    }
}
