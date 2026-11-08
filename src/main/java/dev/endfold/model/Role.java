package dev.endfold.model;

public enum Role {
    OWNER(3), ADMIN(2), MEMBER(1);

    private final int rank;

    Role(int rank) { this.rank = rank; }

    public int rank() { return rank; }

    public boolean atLeast(Role other) { return rank >= other.rank; }

    /** Whether a member with this role may manage (kick / change role of) a member with {@code target}. */
    public boolean outranks(Role target) { return rank > target.rank; }
}
