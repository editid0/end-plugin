package dev.endfold.command;

import dev.endfold.EndfoldPlugin;
import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.PlayerProfile;
import dev.endfold.service.Result;
import dev.endfold.util.Sync;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Shared helpers for command classes: async result replies, player lookup and destructive-action confirmation. */
final class CommandSupport {
    private final EndfoldPlugin plugin;
    private final Map<String, Long> confirmations = new ConcurrentHashMap<>();

    CommandSupport(EndfoldPlugin plugin) { this.plugin = plugin; }

    Messages msg() { return plugin.messages(); }

    /** Replies on the server thread: failures are translated to their configured message, successes go to onOk. */
    <T> void reply(CommandSender to, CompletableFuture<Result<T>> f, Consumer<T> onOk) {
        Sync.then(f, r -> {
            if (r.isOk()) onOk.accept(r.value());
            else msg().fail(to, r.failure());
        });
    }

    record Target(UUID uuid, String name) {}

    /** Resolves a player by name: online first, then Endfold's known profiles, then the server's offline cache. */
    Optional<Target> player(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return Optional.of(new Target(online.getUniqueId(), online.getName()));
        Optional<PlayerProfile> known = plugin.services().dir().profileByName(name);
        if (known.isPresent()) return Optional.of(new Target(known.get().playerUuid(), known.get().lastName()));
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.getName() != null) return Optional.of(new Target(cached.getUniqueId(), cached.getName()));
        return Optional.empty();
    }

    String nameOf(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        return plugin.services().dir().lastKnownName(uuid).orElseGet(() -> {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            return op.getName() == null ? msg().raw("common.unknown-player") : op.getName();
        });
    }

    /** A space the player belongs to, by id token; or their selected space when token is null. */
    Optional<EndSpace> ownSpace(Player p, String token) {
        if (token == null) return plugin.services().dir().selectedId(p.getUniqueId()).flatMap(plugin.services().dir()::space);
        Optional<EndSpace> byId = plugin.services().dir().resolve(token);
        if (byId.isPresent() && plugin.services().dir().isMember(byId.get().id(), p.getUniqueId())) return byId;
        // allow matching by name among the player's spaces
        return plugin.services().dir().spacesOf(p.getUniqueId()).stream().filter(s -> s.name().equalsIgnoreCase(token)).findFirst();
    }

    /**
     * Returns true when the sender already asked for this exact action within 30 seconds (two-step confirmation for
     * destructive commands). Otherwise records it and tells the sender how to confirm.
     */
    boolean confirmed(CommandSender sender, String action, String[] args) {
        boolean hasConfirm = args.length > 0 && args[args.length - 1].equalsIgnoreCase("confirm");
        String key = sender.getName().toLowerCase(Locale.ROOT) + "|" + action;
        long now = System.currentTimeMillis();
        Long asked = confirmations.get(key);
        if (hasConfirm && asked != null && now - asked < 30_000) {
            confirmations.remove(key);
            return true;
        }
        confirmations.put(key, now);
        msg().send(sender, "admin.confirm");
        return false;
    }

    static String[] stripConfirm(String[] args) {
        if (args.length > 0 && args[args.length - 1].equalsIgnoreCase("confirm")) {
            String[] a = new String[args.length - 1];
            System.arraycopy(args, 0, a, 0, a.length);
            return a;
        }
        return args;
    }

    static String join(String[] args, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) b.append(' ');
            b.append(args[i]);
        }
        return b.toString();
    }
}
