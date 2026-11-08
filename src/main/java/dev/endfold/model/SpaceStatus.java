package dev.endfold.model;

/**
 * Lifecycle status of an EndSpace record.
 *
 * <ul>
 *   <li>ACTIVE - usable by members.</li>
 *   <li>ARCHIVED - disbanded by its owner. World files and all records are retained; only admins can restore.</li>
 *   <li>BROKEN - the world failed to load or its folder is missing. Needs admin repair; never silently recreated.</li>
 *   <li>DELETED - purged by an admin. The world folder was moved to the trash directory.</li>
 * </ul>
 */
public enum SpaceStatus {
    ACTIVE, ARCHIVED, BROKEN, DELETED;

    public boolean isLive() { return this == ACTIVE || this == BROKEN; }
}
