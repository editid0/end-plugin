package dev.endfold.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationRunnerTest {
    @TempDir Path dir;
    SqliteDatabase db;
    final Logger log = Logger.getLogger("mig-test");

    @BeforeEach void open() throws Exception { db = new SqliteDatabase(dir.resolve("x.db"), log); }
    @AfterEach void close() { db.close(); }

    long count(String sql) { return db.<Long>txBlocking(tx -> tx.count(sql)); }

    MigrationRunner runner() {
        return new MigrationRunner(db, log, name -> getClass().getClassLoader().getResourceAsStream(name));
    }

    @Test void freshDatabaseAppliesAllMigrations() {
        MigrationRunner r = runner();
        int latest = r.load().size();
        assertEquals(latest, r.migrate());
        assertEquals(latest, r.currentVersion());
        assertEquals(1L, count("SELECT COUNT(*) FROM sqlite_master WHERE name='end_player_flags'"));
        assertEquals(1L, count("SELECT COUNT(*) FROM sqlite_master WHERE name='end_spaces'"));
    }

    @Test void rerunIsIdempotent() {
        runner().migrate();
        assertEquals(0, runner().migrate());
        assertEquals(runner().load().size(), runner().currentVersion());
    }

    /** An existing V1 installation (with data) is upgraded to the latest schema without losing rows. */
    @Test void upgradesExistingV1Database() {
        MigrationRunner v1Only = new MigrationRunner(db, log, name -> name.endsWith("index.txt")
                ? new java.io.ByteArrayInputStream("V1__init.sql\n".getBytes())
                : getClass().getClassLoader().getResourceAsStream(name));
        assertEquals(1, v1Only.migrate());
        db.txBlocking(tx -> tx.update("INSERT INTO end_player_profiles(player_uuid, last_name, first_seen, last_seen) VALUES ('p','Alice',1,1)"));
        assertEquals(runner().load().size() - 1, runner().migrate());
        assertEquals(runner().load().size(), runner().currentVersion());
        assertEquals(1L, count("SELECT COUNT(*) FROM end_player_profiles"));
        assertEquals(0L, count("SELECT COUNT(*) FROM end_player_flags"));
    }

    @Test void checksumMismatchThrows() {
        runner().migrate();
        db.txBlocking(tx -> tx.update("UPDATE schema_version SET checksum='deadbeef' WHERE version=1"));
        var e = assertThrows(MigrationRunner.MigrationException.class, () -> runner().migrate());
        assertTrue(e.getMessage().contains("checksum"));
    }

    @Test void databaseNewerThanPluginThrows() {
        runner().migrate();
        db.txBlocking(tx -> tx.update("INSERT INTO schema_version(version, description, checksum, applied_at) VALUES (99,'future','x',0)"));
        var e = assertThrows(MigrationRunner.MigrationException.class, () -> runner().migrate());
        assertTrue(e.getMessage().contains("newer"));
    }

    @Test void missingResourceThrows() {
        var r = new MigrationRunner(db, log, name -> null);
        assertThrows(MigrationRunner.MigrationException.class, r::migrate);
    }

    @Test void foreignKeysAreEnabled() {
        runner().migrate();
        assertThrows(RuntimeException.class, () -> db.txBlocking(tx ->
                tx.update("INSERT INTO end_space_members(space_id, player_uuid, role, joined_at) VALUES ('nope','p','OWNER',0)")));
    }

    @Test void splitHandlesRealMigrationFile() throws Exception {
        String sql;
        try (var in = getClass().getClassLoader().getResourceAsStream("db/migrations/V1__init.sql")) {
            sql = new String(in.readAllBytes());
        }
        List<String> stmts = MigrationRunner.split(sql);
        assertTrue(stmts.size() > 20);
        for (String s : stmts) {
            assertFalse(s.endsWith(";"), s);
            assertFalse(s.isBlank());
        }
        assertTrue(stmts.getFirst().startsWith("CREATE TABLE end_spaces"));
    }

    @Test void splitSkipsCommentsAndKeepsTrailingStatement() {
        List<String> s = MigrationRunner.split("-- c\nCREATE TABLE a (\n  x INT\n);\n\n-- more\nSELECT 1");
        assertEquals(2, s.size());
        assertEquals("CREATE TABLE a (\n  x INT\n)", s.get(0));
        assertEquals("SELECT 1", s.get(1));
    }

    @Test void failedMigrationRollsBack() {
        var r = new MigrationRunner(db, log, name -> {
            String content = name.endsWith("index.txt") ? "V1__bad.sql\n" : "CREATE TABLE t1 (a INT);\nTHIS IS NOT SQL;\n";
            return new java.io.ByteArrayInputStream(content.getBytes());
        });
        assertThrows(RuntimeException.class, r::migrate);
        assertEquals(0L, count("SELECT COUNT(*) FROM sqlite_master WHERE name='t1'"));
        assertEquals(0L, count("SELECT COUNT(*) FROM schema_version"));
    }
}
