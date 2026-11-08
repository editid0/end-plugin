package dev.endfold.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigUpdaterTest {
    private static final Logger LOG = Logger.getLogger("test");

    private static final String BUNDLED = """
            config-version: 2

            portals:
              # Seconds between routings.
              cooldown-seconds: 3
              direct-teleport:
                # How players may enter.
                mode: AFTER_PORTAL
                count-vanilla-advancement: true
            gui:
              enabled: true
              titles: [a, b]
            """;

    @TempDir Path dir;

    private File write(String content) throws Exception {
        Path p = dir.resolve("config.yml");
        Files.writeString(p, content);
        return p.toFile();
    }

    private static ConfigUpdater.Result run(File f, String bundled) {
        return ConfigUpdater.update(f, new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8)), LOG);
    }

    @Test
    void addsMissingKeysKeepsUserValuesAndBacksUp() throws Exception {
        File f = write("""
                config-version: 1
                portals:
                  # my note
                  cooldown-seconds: 10
                custom: kept
                """);
        ConfigUpdater.Result r = run(f, BUNDLED);

        assertEquals(ConfigUpdater.Status.UPDATED, r.status());
        assertEquals(List.of("portals.direct-teleport.mode", "portals.direct-teleport.count-vanilla-advancement",
                "gui.enabled", "gui.titles"), r.added());
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        assertEquals(2, y.getInt("config-version"));
        assertEquals(10, y.getInt("portals.cooldown-seconds"));
        assertEquals("AFTER_PORTAL", y.getString("portals.direct-teleport.mode"));
        assertEquals(List.of("a", "b"), y.getStringList("gui.titles"));
        assertEquals("kept", y.getString("custom"));
        String text = Files.readString(f.toPath());
        assertTrue(text.contains("# my note"), text);
        assertTrue(text.contains("# How players may enter."), text);
        assertTrue(r.backup().isFile());
        assertTrue(Files.readString(r.backup().toPath()).contains("cooldown-seconds: 10"));
        assertEquals("config.yml.v1.bak", r.backup().getName());
    }

    @Test
    void missingVersionCountsAsZero() throws Exception {
        File f = write("portals:\n  cooldown-seconds: 5\n");
        ConfigUpdater.Result r = run(f, BUNDLED);
        assertEquals(ConfigUpdater.Status.UPDATED, r.status());
        assertEquals(0, r.fromVersion());
        assertEquals(2, YamlConfiguration.loadConfiguration(f).getInt("config-version"));
    }

    @Test
    void currentFileIsNotTouched() throws Exception {
        String content = "config-version: 2\nportals:\n  cooldown-seconds: 5\n";
        File f = write(content);
        assertEquals(ConfigUpdater.Status.UP_TO_DATE, run(f, BUNDLED).status());
        assertEquals(content, Files.readString(f.toPath()));
        assertFalse(new File(dir.toFile(), "config.yml.v2.bak").exists());
    }

    @Test
    void newerFileIsNotTouched() throws Exception {
        String content = "config-version: 9\n";
        File f = write(content);
        assertEquals(ConfigUpdater.Status.NEWER_THAN_BUNDLED, run(f, BUNDLED).status());
        assertEquals(content, Files.readString(f.toPath()));
    }

    @Test
    void brokenFileIsNotTouched() throws Exception {
        String content = "config-version: 1\nportals: [unclosed\n";
        File f = write(content);
        assertEquals(ConfigUpdater.Status.FAILED, run(f, BUNDLED).status());
        assertEquals(content, Files.readString(f.toPath()));
    }

    @Test
    void doesNotOverwriteScalarWhereSectionExpected() throws Exception {
        File f = write("config-version: 1\nportals:\n  direct-teleport: false\n");
        ConfigUpdater.Result r = run(f, BUNDLED);
        assertEquals(ConfigUpdater.Status.UPDATED, r.status());
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        assertFalse(y.getBoolean("portals.direct-teleport"));
        assertEquals(3, y.getInt("portals.cooldown-seconds"));
    }

    @Test
    void bundledFilesAllHaveAVersion() throws Exception {
        for (String name : List.of("config.yml", "messages.yml", "gui.yml")) {
            try (var in = ConfigUpdater.class.getClassLoader().getResourceAsStream(name)) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                assertTrue(y.isInt(ConfigUpdater.VERSION_KEY), name);
            }
        }
    }
}
