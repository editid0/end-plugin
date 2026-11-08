package dev.endfold.service;

/**
 * Built-in individual achievements. Achievements are always personal: joining an already-progressed EndSpace never
 * grants them. Each can be disabled in the configuration.
 */
public enum AchievementType {
    FIRST_ARRIVAL("Enter any EndSpace for the first time."),
    DRAGON_PARTICIPANT("Take part in defeating an Ender Dragon."),
    DRAGON_FINAL_BLOW("Land the killing blow on an Ender Dragon."),
    DRAGON_VETERAN("Take part in 5 dragon victories."),
    CITY_EXPLORER("Open your first End City treasure chest."),
    TREASURE_HUNTER("Loot 10 End City treasure chests."),
    WINGS_OF_THE_VOID("Claim an Elytra from an End Ship."),
    SHELL_COLLECTOR("Defeat 10 Shulkers in your EndSpaces."),
    EXPEDITIONIST("Be a member of 3 EndSpaces at once.");

    private final String hint;

    AchievementType(String hint) { this.hint = hint; }

    public String hint() { return hint; }

    public String key() { return name().toLowerCase(java.util.Locale.ROOT); }
}
