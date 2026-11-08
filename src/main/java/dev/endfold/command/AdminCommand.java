package dev.endfold.command;

import dev.endfold.EndfoldPlugin;
import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.SpaceType;
import dev.endfold.service.Failure;
import dev.endfold.util.Sync;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /endfold} - administration. Works from the console. Destructive sub-commands require a second invocation
 * with {@code confirm} appended within 30 seconds, and every action is written to the audit log.
 */
public final class AdminCommand implements BasicCommand {
    private static final List<String> SUBS = List.of("reload", "gui", "list", "info", "members", "load", "unload", "tp", "backup", "backups",
            "restore", "disband", "activate", "delete", "repair", "orphans", "claims", "addmember", "removemember", "transfer", "audit");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final EndfoldPlugin plugin;
    private final CommandSupport sup;

    public AdminCommand(EndfoldPlugin plugin) {
        this.plugin = plugin;
        this.sup = new CommandSupport(plugin);
    }

    @Override public String permission() { return "endfold.admin"; }

    private Messages msg() { return plugin.messages(); }

    @Override
    public void execute(CommandSourceStack source, String[] rawArgs) {
        CommandSender s = source.getSender();
        String[] args = CommandSupport.stripConfirm(rawArgs);
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!perm(s, "endfold.admin.reload")) return;
                List<String> problems = plugin.reload();
                plugin.services().audit.log(null, s instanceof Player p ? p.getUniqueId() : null, "ADMIN_RELOAD", "by " + s.getName());
                msg().send(s, "admin.reloaded", Messages.comp("problems", problems.isEmpty() ? Component.empty()
                        : msg().get("admin.reload-problems", Messages.arg("count", problems.size()))));
            }
            case "gui" -> {
                if (s instanceof Player p && perm(s, "endfold.admin.worlds")) plugin.menus().openAdmin(p);
            }
            case "list" -> {
                if (perm(s, "endfold.admin.worlds")) list(s, args.length > 1 ? args[1] : null);
            }
            case "info" -> withSpace(s, args, 1, "endfold.admin.worlds", sp -> info(s, sp));
            case "members" -> withSpace(s, args, 1, "endfold.admin.worlds", sp -> {
                msg().send(s, "space.members-header", Messages.arg("space", sp.name()));
                plugin.services().dir().members(sp.id()).forEach(m -> s.sendMessage(msg().get("space.members-entry",
                        Messages.arg("player", sup.nameOf(m.playerUuid())), Messages.comp("role", msg().get("common.role-" + m.role().name().toLowerCase(Locale.ROOT))))));
            });
            case "load" -> withSpace(s, args, 1, "endfold.admin.worlds", sp -> plugin.admin().load(s, sp));
            case "unload" -> withSpace(s, args, 1, "endfold.admin.worlds", sp -> plugin.admin().unload(s, sp));
            case "tp" -> withSpace(s, args, 1, "endfold.admin.teleport", sp -> {
                if (s instanceof Player p) plugin.admin().teleport(p, sp);
                else msg().send(s, "errors.players-only");
            });
            case "backup" -> withSpace(s, args, 1, "endfold.admin.backup", sp -> {
                if (!plugin.lifecycle().files().exists(sp)) msg().send(s, "admin.backup-failed", Messages.arg("reason", "world not generated yet"));
                else plugin.admin().backup(s, sp, "manual");
            });
            case "backups" -> withSpace(s, args, 1, "endfold.admin.backup", sp -> backups(s, sp));
            case "restore" -> withSpace(s, args, 1, "endfold.admin.backup", sp -> {
                if (args.length < 3) {
                    msg().send(s, "errors.usage", Messages.arg("usage", "/endfold restore <space> <backup-file> confirm"));
                    return;
                }
                if (sup.confirmed(s, "restore:" + sp.id() + args[2], rawArgs)) plugin.admin().restore(s, sp, args[2]);
            });
            case "disband" -> withSpace(s, args, 1, "endfold.admin.delete", sp -> {
                if (sup.confirmed(s, "disband:" + sp.id(), rawArgs)) plugin.admin().archive(s, sp);
            });
            case "activate" -> withSpace(s, args, 1, "endfold.admin.worlds", sp -> plugin.admin().activate(s, sp));
            case "delete" -> withSpace(s, args, 1, "endfold.admin.delete", sp -> {
                if (sup.confirmed(s, "delete:" + sp.id(), rawArgs)) plugin.admin().delete(s, sp);
            });
            case "repair" -> {
                if (perm(s, "endfold.admin.worlds")) plugin.admin().repair(s);
            }
            case "orphans" -> {
                if (perm(s, "endfold.admin.worlds")) orphans(s);
            }
            case "claims" -> {
                if (!perm(s, "endfold.admin.worlds")) return;
                if (args.length < 3) {
                    msg().send(s, "errors.usage", Messages.arg("usage", "/endfold claims <player> <space>"));
                    return;
                }
                var target = sup.player(args[1]);
                Optional<EndSpace> sp = plugin.services().dir().resolve(args[2]);
                if (target.isEmpty() || sp.isEmpty()) {
                    msg().fail(s, Failure.NOT_FOUND);
                    return;
                }
                sup.reply(s, plugin.services().loot.claims(sp.get().id(), target.get().uuid()), list -> {
                    msg().send(s, "admin.claims-header", Messages.arg("player", target.get().name()), Messages.arg("space", sp.get().name()));
                    list.forEach(c -> s.sendMessage(msg().get("admin.claims-entry", Messages.arg("claim", c))));
                });
            }
            case "addmember", "removemember", "transfer" -> withSpace(s, args, 1, "endfold.admin.worlds", sp -> {
                if (args.length < 3) {
                    msg().send(s, "errors.usage", Messages.arg("usage", "/endfold " + sub + " <space> <player>"));
                    return;
                }
                var target = sup.player(args[2]);
                if (target.isEmpty()) {
                    msg().send(s, "errors.player-not-found", Messages.arg("player", args[2]));
                    return;
                }
                var actor = s instanceof Player p ? p.getUniqueId() : null;
                var t = target.get();
                switch (sub) {
                    case "addmember" -> sup.reply(s, plugin.services().members.adminAdd(actor, sp.id(), t.uuid(), t.name()),
                            m -> msg().send(s, "admin.member-added", Messages.arg("player", t.name()), Messages.arg("space", sp.name())));
                    case "removemember" -> {
                        if (sup.confirmed(s, "removemember:" + sp.id() + t.uuid(), rawArgs)) {
                            sup.reply(s, plugin.services().members.adminRemove(actor, sp.id(), t.uuid()), r -> {
                                msg().send(s, "admin.member-removed", Messages.arg("player", t.name()), Messages.arg("space", sp.name()));
                                plugin.router().evictUnauthorised(sp);
                            });
                        }
                    }
                    default -> {
                        if (sup.confirmed(s, "transfer:" + sp.id() + t.uuid(), rawArgs)) {
                            sup.reply(s, plugin.services().spaces.adminTransfer(actor, sp.id(), t.uuid(), t.name()),
                                    r -> msg().send(s, "admin.transferred", Messages.arg("player", t.name()), Messages.arg("space", sp.name())));
                        }
                    }
                }
            });
            case "audit" -> {
                if (!perm(s, "endfold.admin.worlds")) return;
                Optional<EndSpace> sp = args.length > 1 ? plugin.services().dir().resolve(args[1]) : Optional.empty();
                sup.reply(s, plugin.services().audit.recent(sp.map(EndSpace::id).orElse(null), 15), list -> {
                    msg().send(s, "admin.audit-header");
                    list.forEach(e -> s.sendMessage(msg().get("admin.audit-entry", Messages.arg("time", TIME.format(Instant.ofEpochMilli(e.ts()))),
                            Messages.arg("action", e.action()), Messages.arg("detail", e.detail() == null ? "" : e.detail()))));
                });
            }
            default -> msg().send(s, "errors.usage", Messages.arg("usage", "/endfold " + String.join("|", SUBS)));
        }
    }

    private boolean perm(CommandSender s, String node) {
        if (s.hasPermission(node)) return true;
        msg().fail(s, Failure.NO_PERMISSION);
        return false;
    }

    private void withSpace(CommandSender s, String[] args, int idx, String node, java.util.function.Consumer<EndSpace> then) {
        if (!perm(s, node)) return;
        if (args.length <= idx) {
            msg().send(s, "errors.usage", Messages.arg("usage", "/endfold " + args[0] + " <space-id>"));
            return;
        }
        Optional<EndSpace> sp = plugin.services().dir().resolve(args[idx]);
        if (sp.isEmpty()) {
            // Names are not unique: only accept a name when it matches exactly one space.
            List<EndSpace> byName = plugin.services().dir().allSpaces().stream().filter(x -> x.name().equalsIgnoreCase(args[idx])).toList();
            if (byName.size() > 1) {
                msg().send(s, "errors.usage", Messages.arg("usage", "ambiguous name - use one of the ids: "
                        + String.join(", ", byName.stream().map(EndSpace::shortId).toList())));
                return;
            }
            sp = byName.stream().findFirst();
        }
        if (sp.isEmpty()) msg().fail(s, Failure.NOT_FOUND);
        else then.accept(sp.get());
    }

    private void list(CommandSender s, String filter) {
        List<EndSpace> all = new ArrayList<>(plugin.services().dir().allSpaces());
        if (filter != null) {
            String f = filter.toLowerCase(Locale.ROOT);
            all.removeIf(sp -> !(sp.status().name().equalsIgnoreCase(f) || sp.name().toLowerCase(Locale.ROOT).contains(f)
                    || sup.nameOf(sp.ownerUuid()).equalsIgnoreCase(f)));
        }
        all.sort(Comparator.comparingLong(EndSpace::lastActiveAt).reversed());
        msg().send(s, "admin.list-header", Messages.arg("count", all.size()));
        for (EndSpace sp : all.subList(0, Math.min(50, all.size()))) {
            s.sendMessage(msg().get("admin.list-entry", Messages.arg("space", sp.name()), Messages.arg("id", sp.shortId()),
                    Messages.comp("type", msg().get(sp.type() == SpaceType.SOLO ? "common.solo" : "common.group")),
                    Messages.comp("status", msg().get("common.status-" + sp.status().name().toLowerCase(Locale.ROOT))),
                    Messages.arg("owner", sup.nameOf(sp.ownerUuid())), Messages.arg("members", plugin.services().dir().memberCount(sp.id())),
                    Messages.comp("loaded", msg().get(plugin.instances().isLoaded(sp) ? "common.loaded" : "common.unloaded"))));
        }
    }

    private void info(CommandSender s, EndSpace sp) {
        sup.reply(s, plugin.services().progress.space(sp.id()), prog -> s.sendMessage(msg().get("admin.info",
                Messages.arg("space", sp.name()), Messages.arg("id", sp.id().toString()), Messages.arg("world", sp.worldName()),
                Messages.comp("loaded", msg().get(plugin.instances().isLoaded(sp) ? "common.loaded" : "common.unloaded")),
                Messages.comp("type", msg().get(sp.type() == SpaceType.SOLO ? "common.solo" : "common.group")),
                Messages.comp("status", msg().get("common.status-" + sp.status().name().toLowerCase(Locale.ROOT))),
                Messages.arg("owner", sup.nameOf(sp.ownerUuid())), Messages.arg("members", plugin.services().dir().memberCount(sp.id())),
                Messages.arg("max", sp.maxMembers()), Messages.arg("seed", sp.seed()), Messages.arg("kills", prog.dragonKills()),
                Messages.arg("gateways", prog.gatewaysActivated()))));
    }

    private void backups(CommandSender s, EndSpace sp) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<Path> list = plugin.lifecycle().files().listBackups(sp);
                Sync.run(() -> {
                    msg().send(s, "admin.backups-header", Messages.arg("space", sp.name()));
                    for (Path p : list) {
                        long kb = p.toFile().length() / 1024;
                        s.sendMessage(msg().get("admin.backups-entry", Messages.arg("file", p.getFileName().toString()), Messages.arg("size", kb + " KB")));
                    }
                });
            } catch (Exception e) {
                Sync.run(() -> msg().send(s, "admin.backup-failed", Messages.arg("reason", String.valueOf(e.getMessage()))));
            }
        });
    }

    private void orphans(CommandSender s) {
        Set<String> known = new HashSet<>();
        plugin.services().dir().allSpaces().forEach(sp -> known.add(sp.shortId()));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> list = plugin.lifecycle().files().orphanFolders(known);
                Sync.run(() -> {
                    msg().send(s, "admin.orphans-header");
                    list.forEach(f -> s.sendMessage(msg().get("admin.orphans-entry", Messages.arg("folder", f))));
                });
            } catch (Exception e) {
                Sync.run(() -> msg().send(s, "admin.backup-failed", Messages.arg("reason", String.valueOf(e.getMessage()))));
            }
        });
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) return filter(SUBS.stream(), args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        String cur = args[args.length - 1];
        if (args.length == 2 && !List.of("reload", "gui", "repair", "orphans", "claims", "list", "audit").contains(sub)) {
            return filter(plugin.services().dir().allSpaces().stream().map(EndSpace::shortId), cur);
        }
        if (args.length == 2 && (sub.equals("claims"))) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), cur);
        if (args.length == 2 && sub.equals("audit")) return filter(plugin.services().dir().allSpaces().stream().map(EndSpace::shortId), cur);
        if (args.length == 3 && List.of("addmember", "removemember", "transfer").contains(sub)) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), cur);
        }
        if (args.length == 3 && sub.equals("claims")) return filter(plugin.services().dir().allSpaces().stream().map(EndSpace::shortId), cur);
        if (args.length == 3 && sub.equals("restore")) {
            Optional<EndSpace> sp = plugin.services().dir().resolve(args[1]);
            if (sp.isPresent()) {
                try {
                    return filter(plugin.lifecycle().files().listBackups(sp.get()).stream().map(p -> p.getFileName().toString()), cur);
                } catch (Exception ignored) {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    private static List<String> filter(Stream<String> s, String prefix) {
        String pre = prefix.toLowerCase(Locale.ROOT);
        return s.filter(x -> x.toLowerCase(Locale.ROOT).startsWith(pre)).sorted().limit(50).toList();
    }
}
