package dev.endfold.config;

import dev.endfold.service.AchievementType;
import dev.endfold.service.ServiceSettings;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed, validated view of config.yml. Invalid values are replaced by documented defaults and reported through
 * {@link #problems()} so the plugin can log them clearly instead of silently disabling features.
 */
public final class EndfoldConfig {
    public enum EntityPolicy { BLOCK, VANILLA_LOCAL }

    public enum TextInput { DIALOG, CHAT }

    /** Whether players may teleport into an EndSpace without walking through an End portal (menu / /end enter). */
    public enum DirectTeleport { ALWAYS, AFTER_PORTAL, NEVER }

    private final List<String> problems = new ArrayList<>();

    // instances
    public final boolean soloEnabled;
    public final boolean soloCreateOnFirstEntry;
    public final String soloDefaultName;
    public final boolean groupEnabled;
    public final int groupMaxMembers;
    public final int groupHardMaxMembers;
    public final int maxOwnedGroups;
    public final boolean membersCanInvite;
    public final ServiceSettings.OwnerLeavePolicy ownerLeavePolicy;
    public final int maxMemberships;
    public final int nameMin;
    public final int nameMax;
    public final long inviteTtlMillis;
    public final int maxPendingInvites;
    // worlds
    public final ServiceSettings.SeedMode seedMode;
    public final long fixedSeed;
    public final int unloadAfterMinutes;
    public final int maxLoadedWorlds;
    public final int createCooldownSeconds;
    public final boolean autoBackup;
    public final int backupsToKeep;
    public final boolean databaseBackups;
    // dragon
    public final boolean dragonRepeatable;
    public final boolean dragonHealthScaling;
    public final double dragonScalePerPlayer;
    public final double dragonScaleMax;
    public final double participationMinDamage;
    public final int participationMinSeconds;
    public final int participationRadius;
    // loot
    public final boolean personalChests;
    public final boolean protectContainers;
    public final Set<AchievementType> enabledAchievements;
    // elytra
    public final boolean elytraEnabled;
    public final int elytraPerSpace;
    public final int elytraGlobalLimit;
    public final long elytraCooldownMillis;
    // shulkers
    public final boolean shulkerRespawn;
    public final long shulkerCooldownMillis;
    public final int shulkerMaxPopulation;
    public final int shulkerMaxPerCity;
    public final int shulkerActivationRadius;
    public final int shulkerCheckIntervalSeconds;
    // portals
    public final boolean routeToSelected;
    public final boolean returnToEntry;
    public final boolean blockUnmanagedEnd;
    public final String fallbackWorld;
    public final EntityPolicy entityPolicy;
    public final int portalCooldownSeconds;
    public final DirectTeleport directTeleport;
    public final boolean directTeleportVanillaAdvancement;
    // gui
    public final boolean guiEnabled;
    public final boolean useAccessItem;
    public final Material accessItemMaterial;
    public final int paginationSize;
    public final boolean guiSounds;
    public final int clickCooldownMs;
    public final TextInput textInput;
    public final boolean debug;

    public EndfoldConfig(FileConfiguration c) {
        soloEnabled = c.getBoolean("instances.solo.enabled", true);
        soloCreateOnFirstEntry = c.getBoolean("instances.solo.create-on-first-entry", true);
        soloDefaultName = str(c, "instances.solo.default-name", "{player}'s End");
        groupEnabled = c.getBoolean("instances.group.enabled", true);
        groupHardMaxMembers = range(c, "instances.group.hard-max-members", 50, 2, 1000);
        groupMaxMembers = range(c, "instances.group.max-members", 10, 2, groupHardMaxMembers);
        maxOwnedGroups = range(c, "instances.group.max-owned-per-player", 2, 0, 1000);
        membersCanInvite = c.getBoolean("instances.group.members-can-invite", false);
        ownerLeavePolicy = enumVal(c, "instances.group.owner-leave-policy", ServiceSettings.OwnerLeavePolicy.class, ServiceSettings.OwnerLeavePolicy.REQUIRE_TRANSFER);
        maxMemberships = range(c, "instances.max-memberships-per-player", 25, 1, 10_000);
        nameMin = range(c, "instances.names.min-length", 3, 1, 32);
        nameMax = range(c, "instances.names.max-length", 24, nameMin, 48);
        inviteTtlMillis = (long) rangeD(c, "instances.invitations.expire-after-hours", 72, 0.1, 24 * 365) * 3_600_000L;
        maxPendingInvites = range(c, "instances.invitations.max-pending-per-space", 20, 1, 1000);

        seedMode = enumVal(c, "instances.worlds.seed-mode", ServiceSettings.SeedMode.class, ServiceSettings.SeedMode.RANDOM);
        fixedSeed = c.getLong("instances.worlds.fixed-seed", 0L);
        unloadAfterMinutes = range(c, "instances.worlds.unload-after-minutes", 10, 0, 24 * 60);
        maxLoadedWorlds = range(c, "instances.worlds.max-loaded", 30, 0, 10_000);
        createCooldownSeconds = range(c, "instances.worlds.create-cooldown-seconds", 30, 0, 86_400);
        autoBackup = c.getBoolean("instances.worlds.auto-backup", true);
        backupsToKeep = range(c, "instances.worlds.backups-to-keep", 5, 1, 1000);
        databaseBackups = c.getBoolean("instances.worlds.database-backups", true);

        dragonRepeatable = c.getBoolean("progression.dragon.repeatable", true);
        dragonHealthScaling = c.getBoolean("progression.dragon.health-scaling", false);
        dragonScalePerPlayer = rangeD(c, "progression.dragon.health-scaling-per-player", 0.25, 0, 10);
        dragonScaleMax = rangeD(c, "progression.dragon.health-scaling-max-multiplier", 3.0, 1, 20);
        participationMinDamage = rangeD(c, "progression.dragon.participation-min-damage", 10.0, 0, 10_000);
        participationMinSeconds = range(c, "progression.dragon.participation-min-seconds", 60, 0, 36_000);
        participationRadius = range(c, "progression.dragon.participation-radius", 160, 16, 1024);

        personalChests = c.getBoolean("progression.loot.personal-chests", true);
        protectContainers = c.getBoolean("progression.loot.protect-containers", true);
        Set<AchievementType> ach = EnumSet.allOf(AchievementType.class);
        for (String s : c.getStringList("progression.achievements.disabled")) {
            try {
                ach.remove(AchievementType.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                problems.add("progression.achievements.disabled: unknown achievement '" + s + "'");
            }
        }
        enabledAchievements = ach;

        elytraEnabled = c.getBoolean("resources.elytra.enabled", true);
        int claimLimit = c.getInt("resources.elytra.claim-limit", 1);
        if (claimLimit != 1) problems.add("resources.elytra.claim-limit: only 1 is supported (one Elytra per ship per player); using 1");
        elytraPerSpace = range(c, "resources.elytra.per-space-limit", 0, 0, 10_000);
        elytraGlobalLimit = range(c, "resources.elytra.global-limit", 0, 0, 10_000);
        elytraCooldownMillis = (long) (rangeD(c, "resources.elytra.cooldown-hours", 0, 0, 24 * 365) * 3_600_000L);

        shulkerRespawn = c.getBoolean("resources.shulkers.respawn", true);
        shulkerCooldownMillis = (long) (rangeD(c, "resources.shulkers.cooldown-hours", 72, 0.01, 24 * 365) * 3_600_000L);
        shulkerMaxPopulation = range(c, "resources.shulkers.max-population", 50, 0, 1000);
        shulkerMaxPerCity = range(c, "resources.shulkers.max-per-city", 12, 0, 100);
        shulkerActivationRadius = range(c, "resources.shulkers.activation-radius", 48, 8, 256);
        shulkerCheckIntervalSeconds = range(c, "resources.shulkers.check-interval-seconds", 60, 5, 3600);

        routeToSelected = c.getBoolean("portals.route-to-selected-space", true);
        returnToEntry = c.getBoolean("portals.return-to-entry-location", true);
        blockUnmanagedEnd = c.getBoolean("portals.block-unmanaged-end-access", true);
        fallbackWorld = str(c, "portals.fallback-world", "");
        entityPolicy = enumVal(c, "portals.entity-policy", EntityPolicy.class, EntityPolicy.BLOCK);
        portalCooldownSeconds = range(c, "portals.cooldown-seconds", 3, 0, 60);
        directTeleport = enumVal(c, "portals.direct-teleport.mode", DirectTeleport.class, DirectTeleport.AFTER_PORTAL);
        directTeleportVanillaAdvancement = c.getBoolean("portals.direct-teleport.count-vanilla-advancement", true);

        guiEnabled = c.getBoolean("gui.enabled", true);
        useAccessItem = c.getBoolean("gui.use-access-item", false);
        accessItemMaterial = material(c, "gui.access-item-material", Material.ENDER_EYE);
        int ps = c.getInt("gui.pagination-size", 28);
        if (ps < 7 || ps > 28) {
            problems.add("gui.pagination-size must be between 7 and 28; using 28");
            ps = 28;
        }
        paginationSize = ps;
        guiSounds = c.getBoolean("gui.sounds", true);
        clickCooldownMs = range(c, "gui.click-cooldown-ms", 200, 0, 5000);
        textInput = enumVal(c, "gui.text-input", TextInput.class, TextInput.DIALOG);
        debug = c.getBoolean("debug", false);

        if (!soloEnabled && !groupEnabled) problems.add("Both solo and group EndSpaces are disabled; players cannot create any EndSpace.");
    }

    public List<String> problems() { return List.copyOf(problems); }

    public ServiceSettings toServiceSettings(long overworldSeed) {
        return new ServiceSettings(soloEnabled, groupEnabled, groupMaxMembers, groupHardMaxMembers, maxOwnedGroups, maxMemberships,
                inviteTtlMillis, maxPendingInvites, membersCanInvite, true, ownerLeavePolicy, nameMin, nameMax, seedMode, fixedSeed,
                overworldSeed, 1, elytraPerSpace, elytraGlobalLimit, elytraCooldownMillis);
    }

    private String str(ConfigurationSection c, String path, String def) {
        Object v = c.get(path);
        if (v == null) return def;
        if (!(v instanceof String s)) {
            problems.add(path + " must be text; using '" + def + "'");
            return def;
        }
        return s;
    }

    private int range(ConfigurationSection c, String path, int def, int min, int max) {
        Object v = c.get(path);
        if (v == null) return def;
        if (!(v instanceof Number n)) {
            problems.add(path + " must be a number; using " + def);
            return def;
        }
        int i = n.intValue();
        if (i < min || i > max) {
            problems.add(path + " must be between " + min + " and " + max + " (was " + i + "); using " + Math.max(min, Math.min(max, def)));
            return Math.max(min, Math.min(max, def));
        }
        return i;
    }

    private double rangeD(ConfigurationSection c, String path, double def, double min, double max) {
        Object v = c.get(path);
        if (v == null) return def;
        if (!(v instanceof Number n)) {
            problems.add(path + " must be a number; using " + def);
            return def;
        }
        double d = n.doubleValue();
        if (d < min || d > max) {
            problems.add(path + " must be between " + min + " and " + max + " (was " + d + "); using " + def);
            return def;
        }
        return d;
    }

    private <E extends Enum<E>> E enumVal(ConfigurationSection c, String path, Class<E> type, E def) {
        String raw = c.getString(path);
        if (raw == null) return def;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add(path + ": '" + raw + "' is not one of " + java.util.Arrays.toString(type.getEnumConstants()) + "; using " + def);
            return def;
        }
    }

    private Material material(ConfigurationSection c, String path, Material def) {
        String raw = c.getString(path);
        if (raw == null) return def;
        Material m = Material.matchMaterial(raw);
        if (m == null || !m.isItem()) {
            problems.add(path + ": unknown item material '" + raw + "'; using " + def);
            return def;
        }
        return m;
    }
}
