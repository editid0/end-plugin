package dev.endfold.config;

import dev.endfold.service.Failure;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Configurable MiniMessage texts loaded from {@code messages.yml} and {@code gui.yml}. Missing keys fall back to the
 * defaults bundled in the jar, so an outdated file never produces blank messages.
 *
 * <p>Security: player-provided text (space names, player names) must be passed with {@link #arg}, which inserts it
 * unparsed so it can never inject MiniMessage tags or click events.</p>
 */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private final Logger logger;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Map<String, List<String>> lists = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void reload() {
        values.clear();
        lists.clear();
        load("messages.yml");
        load("gui.yml");
    }

    private void load(String file) {
        File f = new File(plugin.getDataFolder(), file);
        if (!f.exists() && plugin.getResource(file) != null) plugin.saveResource(file, false);
        try (InputStream in = plugin.getResource(file)) {
            ConfigUpdater.update(f, in, logger);
        } catch (Exception e) {
            logger.warning("Could not update " + file + ": " + e.getMessage());
        }
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = plugin.getResource(file)) {
            if (in != null) defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warning("Could not read bundled " + file + ": " + e.getMessage());
        }
        YamlConfiguration user = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        user.setDefaults(defaults);
        for (String key : defaults.getKeys(true)) if (!key.equals(ConfigUpdater.VERSION_KEY)) put(user, key);
        for (String key : user.getKeys(true)) put(user, key);
    }

    private void put(YamlConfiguration y, String key) {
        if (y.isConfigurationSection(key)) return;
        if (y.isList(key)) lists.put(key, y.getStringList(key));
        else if (y.isString(key)) values.put(key, y.getString(key));
        else if (y.get(key) != null) values.put(key, String.valueOf(y.get(key)));
    }

    // ------------------------------------------------------------------ placeholders

    /** Inserts untrusted text literally (no tag parsing). */
    public static TagResolver arg(String name, Object value) { return Placeholder.unparsed(name, String.valueOf(value)); }

    public static TagResolver comp(String name, Component value) { return Placeholder.component(name, value); }

    /** Trusted MiniMessage fragment (only for plugin-controlled strings). */
    public static TagResolver parsed(String name, String value) { return Placeholder.parsed(name, value); }

    // ------------------------------------------------------------------ access

    public String raw(String key) {
        String v = values.get(key);
        if (v == null) {
            if (warned.add(key)) logger.warning("Missing message key '" + key + "'");
            return key;
        }
        return v;
    }

    public boolean has(String key) { return values.containsKey(key) || lists.containsKey(key); }

    public Component get(String key, TagResolver... resolvers) {
        return MM.deserialize(raw(key), TagResolver.resolver(resolvers));
    }

    /**
     * Like {@link #get} but first substitutes {@code {token}} occurrences (e.g. inside click-event commands, where
     * MiniMessage placeholders are not expanded). Token values must be plugin-generated and contain only
     * {@code [A-Za-z0-9_-]} - anything else is rejected to prevent injection.
     */
    public Component tokens(String key, Map<String, String> tokens, TagResolver... resolvers) {
        String s = raw(key);
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            if (!e.getValue().matches("[A-Za-z0-9_-]*")) throw new IllegalArgumentException("Unsafe token value for " + e.getKey());
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return MM.deserialize(s, TagResolver.resolver(resolvers));
    }

    public void sendTokens(Audience to, String key, Map<String, String> tokens, TagResolver... resolvers) {
        to.sendMessage(get("prefix").append(tokens(key, tokens, resolvers)));
    }

    /** Component without the default italic that Minecraft applies to item names and lore. */
    public Component item(String key, TagResolver... resolvers) {
        return get(key, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public List<Component> lore(String key, TagResolver... resolvers) {
        List<String> l = lists.get(key);
        List<Component> out = new ArrayList<>();
        if (l == null) {
            if (values.containsKey(key)) {
                for (String line : values.get(key).split("\n")) out.add(line(line, resolvers));
            } else if (warned.add(key)) {
                logger.warning("Missing message list '" + key + "'");
            }
            return out;
        }
        for (String line : l) out.add(line(line, resolvers));
        return out;
    }

    private Component line(String s, TagResolver... r) {
        return MM.deserialize(s, TagResolver.resolver(r)).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public Component parse(String miniMessage, TagResolver... resolvers) { return MM.deserialize(miniMessage, TagResolver.resolver(resolvers)); }

    public void send(Audience to, String key, TagResolver... resolvers) {
        to.sendMessage(get("prefix").append(get(key, resolvers)));
    }

    public void sendRaw(Audience to, Component c) { to.sendMessage(get("prefix").append(c)); }

    public void fail(Audience to, Failure f) { send(to, f.messageKey()); }

    public static String escape(String s) { return MM.escapeTags(s); }
}
