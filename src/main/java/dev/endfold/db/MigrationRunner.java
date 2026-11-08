package dev.endfold.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Applies versioned SQL migrations listed in {@code db/migrations/index.txt}. Each migration runs in its own
 * transaction and is recorded in {@code schema_version} with a checksum. The runner refuses to start when a
 * previously applied script changed or when the database is newer than the plugin.
 */
public final class MigrationRunner {
    private static final Pattern NAME = Pattern.compile("V(\\d+)__([A-Za-z0-9_]+)\\.sql");

    public record Migration(int version, String description, String sql) {}

    public static final class MigrationException extends RuntimeException {
        public MigrationException(String msg) { super(msg); }
        public MigrationException(String msg, Throwable cause) { super(msg, cause); }
    }

    private final Database db;
    private final Logger logger;
    private final Function<String, InputStream> resources;

    public MigrationRunner(Database db, Logger logger, Function<String, InputStream> resources) {
        this.db = db;
        this.logger = logger;
        this.resources = resources;
    }

    public List<Migration> load() {
        String index = read("db/migrations/index.txt");
        List<Migration> out = new ArrayList<>();
        for (String line : index.split("\\R")) {
            String file = line.trim();
            if (file.isEmpty() || file.startsWith("#")) continue;
            Matcher m = NAME.matcher(file);
            if (!m.matches()) throw new MigrationException("Invalid migration file name: " + file);
            out.add(new Migration(Integer.parseInt(m.group(1)), m.group(2), read("db/migrations/" + file)));
        }
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).version() != i + 1) throw new MigrationException("Migrations must be numbered consecutively from 1");
        }
        return out;
    }

    /** @return number of migrations applied in this run */
    public int migrate() {
        List<Migration> migrations = load();
        db.txBlocking(tx -> {
            try (Statement st = tx.connection().createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, description TEXT NOT NULL, "
                        + "checksum TEXT NOT NULL, applied_at INTEGER NOT NULL)");
            }
            return null;
        });
        Map<Integer, String> applied = db.txBlocking(tx -> tx.query("SELECT version, checksum FROM schema_version",
                rs -> Map.entry(rs.getInt(1), rs.getString(2))).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        int latestKnown = migrations.size();
        int latestApplied = applied.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (latestApplied > latestKnown) {
            throw new MigrationException("Database schema version " + latestApplied + " is newer than this plugin supports ("
                    + latestKnown + "). Refusing to start to protect your data; install a newer Endfold version.");
        }
        int count = 0;
        for (Migration m : migrations) {
            String resolved = db.dialect().resolve(m.sql());
            String checksum = sha256(resolved);
            String existing = applied.get(m.version());
            if (existing != null) {
                if (!existing.equals(checksum)) {
                    throw new MigrationException("Migration V" + m.version() + " was modified after being applied (checksum mismatch).");
                }
                continue;
            }
            long start = System.currentTimeMillis();
            db.txBlocking(tx -> {
                try (Statement st = tx.connection().createStatement()) {
                    for (String stmt : split(resolved)) st.execute(stmt);
                }
                tx.update("INSERT INTO schema_version(version, description, checksum, applied_at) VALUES (?,?,?,?)",
                        m.version(), m.description(), checksum, System.currentTimeMillis());
                return null;
            });
            logger.info("Applied database migration V" + m.version() + " (" + m.description() + ") in " + (System.currentTimeMillis() - start) + "ms");
            count++;
        }
        return count;
    }

    public int currentVersion() {
        return db.txBlocking(tx -> (int) tx.count("SELECT COALESCE(MAX(version),0) FROM schema_version"));
    }

    static List<String> split(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("--") || t.isEmpty()) continue;
            cur.append(line).append('\n');
            if (t.endsWith(";")) {
                String s = cur.toString().strip();
                out.add(s.substring(0, s.length() - 1));
                cur.setLength(0);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().strip());
        return out;
    }

    private String read(String path) {
        try (InputStream in = resources.apply(path)) {
            if (in == null) throw new MigrationException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MigrationException("Cannot read " + path, e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
