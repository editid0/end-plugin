package dev.endfold.model;

/** Immutable integer block position, free of Bukkit types so it can be used by repositories and tests. */
public record BlockPos(int x, int y, int z) {
    public String key() { return x + "," + y + "," + z; }

    public static BlockPos parse(String key) {
        String[] p = key.split(",");
        if (p.length != 3) throw new IllegalArgumentException("Bad block key: " + key);
        return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
    }

    @Override public String toString() { return key(); }
}
