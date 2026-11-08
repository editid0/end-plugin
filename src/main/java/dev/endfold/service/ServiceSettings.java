package dev.endfold.service;

/**
 * Pure (Bukkit-free) business limits, built from the plugin configuration so services remain unit-testable.
 */
public record ServiceSettings(
        boolean soloEnabled,
        boolean groupEnabled,
        int groupDefaultMaxMembers,
        int groupHardMaxMembers,
        int maxOwnedGroups,
        int maxMembershipsPerPlayer,
        long inviteTtlMillis,
        int maxPendingInvitesPerSpace,
        boolean membersCanInvite,
        boolean publicEnabled,
        OwnerLeavePolicy ownerLeavePolicy,
        int nameMinLength,
        int nameMaxLength,
        SeedMode seedMode,
        long fixedSeed,
        long overworldSeed,
        int elytraPerShip,
        int elytraPerSpace,
        int elytraGlobalLimit,
        long elytraCooldownMillis) {

    public enum OwnerLeavePolicy { REQUIRE_TRANSFER, AUTO_TRANSFER }

    public enum SeedMode { RANDOM, FIXED, OVERWORLD }

    public static ServiceSettings defaults() {
        return new ServiceSettings(true, true, 10, 50, 2, 25, 72L * 3600_000L, 20, false, true, OwnerLeavePolicy.REQUIRE_TRANSFER,
                3, 24, SeedMode.RANDOM, 0L, 0L, 1, 0, 0, 0L);
    }
}
