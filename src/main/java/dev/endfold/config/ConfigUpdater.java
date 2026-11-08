package dev.endfold.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Brings an on-disk YAML file up to date with the version bundled in the jar.
 *
 * <p>Every bundled file carries a top-level {@value #VERSION_KEY}. When the server's copy has a lower version (or none
 * at all), every key that exists in the bundled file but not in the server's copy is added together with its comments,
 * the version is raised and the file is saved. Values the admin already set are never changed, and keys the admin
 * added or that were removed from the bundled file are left alone. The previous file is kept as
 * {@code <name>.v<old>.bak} first.</p>
 *
 * <p>A file with a higher version than the bundled one (plugin downgrade) or one that cannot be parsed is not touched.</p>
 */
public final class ConfigUpdater {
    public static final String VERSION_KEY = "config-version";

    /** Outcome of {@link #update}. {@code added} lists the paths that were inserted. */
    public record Result(Status status, int fromVersion, int toVersion, List<String> added, File backup) {}

    public enum Status { UP_TO_DATE, UPDATED, NEWER_THAN_BUNDLED, NO_FILE, NO_BUNDLED_VERSION, FAILED }

    private ConfigUpdater() {}

    /**
     * Updates {@code file} against {@code bundled}. Never throws; problems are logged and reported as
     * {@link Status#FAILED}.
     */
    public static Result update(File file, InputStream bundled, Logger logger) {
        String name = file.getName();
        if (!file.isFile()) return new Result(Status.NO_FILE, 0, 0, List.of(), null);
        if (bundled == null) return new Result(Status.NO_BUNDLED_VERSION, 0, 0, List.of(), null);

        YamlConfiguration defaults = new YamlConfiguration();
        YamlConfiguration user = new YamlConfiguration();
        defaults.options().parseComments(true);
        user.options().parseComments(true);
        try (InputStreamReader in = new InputStreamReader(bundled, StandardCharsets.UTF_8)) {
            defaults.load(in);
        } catch (IOException | InvalidConfigurationException e) {
            logger.warning("Could not read bundled " + name + ": " + e.getMessage());
            return new Result(Status.FAILED, 0, 0, List.of(), null);
        }
        if (!defaults.isInt(VERSION_KEY)) return new Result(Status.NO_BUNDLED_VERSION, 0, 0, List.of(), null);
        int target = defaults.getInt(VERSION_KEY);

        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            logger.warning(name + " could not be parsed, so it was not updated: " + e.getMessage());
            return new Result(Status.FAILED, 0, target, List.of(), null);
        }
        int current = user.isInt(VERSION_KEY) ? user.getInt(VERSION_KEY) : 0;
        if (current == target) return new Result(Status.UP_TO_DATE, current, target, List.of(), null);
        if (current > target) {
            logger.warning(name + " is version " + current + " but this Endfold build ships version " + target
                    + " (downgrade?). The file was left unchanged.");
            return new Result(Status.NEWER_THAN_BUNDLED, current, target, List.of(), null);
        }

        List<String> added = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (key.equals(VERSION_KEY) || user.contains(key, true) || blockedByScalar(user, key)) continue;
            if (defaults.isConfigurationSection(key)) user.createSection(key);
            else {
                user.set(key, defaults.get(key));
                added.add(key);
            }
            user.setComments(key, defaults.getComments(key));
            user.setInlineComments(key, defaults.getInlineComments(key));
        }
        user.set(VERSION_KEY, target);

        File backup = new File(file.getParentFile(), name + ".v" + current + ".bak");
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            // Bukkit indents the blank lines between sections; strip that so the file stays tidy.
            Files.writeString(file.toPath(), user.saveToString().replaceAll("(?m)^[ \\t]+$", ""), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("Could not update " + name + ": " + e.getMessage());
            return new Result(Status.FAILED, current, target, added, null);
        }
        logger.info("Updated " + name + " from version " + current + " to " + target + ": " + added.size()
                + " new setting(s) added" + (added.isEmpty() ? "" : " (" + String.join(", ", added) + ")")
                + ". Previous file saved as " + backup.getName() + ".");
        return new Result(Status.UPDATED, current, target, added, backup);
    }

    /** True if an ancestor of {@code key} is set to a plain value by the admin; adding the key would erase it. */
    private static boolean blockedByScalar(YamlConfiguration user, String key) {
        int dot = key.lastIndexOf('.');
        while (dot > 0) {
            String parent = key.substring(0, dot);
            if (user.contains(parent, true)) return !user.isConfigurationSection(parent);
            dot = parent.lastIndexOf('.');
        }
        return false;
    }
}
