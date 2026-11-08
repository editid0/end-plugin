package dev.endfold.command;

import dev.endfold.EndfoldPlugin;
import dev.endfold.action.Actions;
import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.JoinMode;
import dev.endfold.model.Membership;
import dev.endfold.model.MembershipRequest;
import dev.endfold.model.Role;
import dev.endfold.model.SpaceType;
import dev.endfold.service.Failure;
import dev.endfold.util.Sync;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /end} - player commands. Without arguments every command opens the matching menu; arguments make it a
 * shortcut. All logic goes through {@link Actions}, exactly like the GUI.
 */
public final class EndCommand implements BasicCommand {
    private static final List<String> SUBS = List.of("help", "solo", "create", "invite", "accept", "decline", "invites", "spaces", "list",
            "select", "enter", "exit", "members", "leave", "disband", "public", "join", "request", "requests", "approve", "deny",
            "achievements", "claim", "rename", "visibility", "kick", "promote", "demote", "transfer", "info");

    private final EndfoldPlugin plugin;
    private final CommandSupport sup;

    public EndCommand(EndfoldPlugin plugin) {
        this.plugin = plugin;
        this.sup = new CommandSupport(plugin);
    }

    @Override public String permission() { return "endfold.use"; }

    private Messages msg() { return plugin.messages(); }

    private Actions act() { return plugin.actions(); }

    private boolean gui() { return plugin.config().guiEnabled; }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!(source.getExecutor() instanceof Player p)) {
            msg().send(sender, "errors.players-only");
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String a1 = args.length > 1 ? args[1] : null;
        switch (sub) {
            case "" -> {
                if (gui()) plugin.menus().openMain(p);
                else help(p);
            }
            case "help" -> help(p);
            case "solo" -> {
                if (perm(p, "endfold.create")) act().solo(p);
            }
            case "create" -> {
                if (!perm(p, "endfold.create")) return;
                if (a1 == null) {
                    if (gui()) plugin.menus().openCreate(p);
                    else usage(p, "/end create <name>");
                } else {
                    act().createGroup(p, CommandSupport.join(args, 1));
                }
            }
            case "invite" -> invite(p, args);
            case "accept", "decline" -> {
                if (a1 == null) openOr(p, () -> plugin.menus().openInvitations(p), "/end " + sub + " <invitation>");
                else act().acceptToken(p, a1, sub.equals("accept"));
            }
            case "invites", "invitations" -> openOr(p, () -> plugin.menus().openInvitations(p), "/end accept <invitation>");
            case "spaces" -> {
                if (gui()) plugin.menus().openSpaces(p);
                else list(p);
            }
            case "list" -> list(p);
            case "select" -> {
                if (a1 == null) {
                    if (gui()) plugin.menus().openSpaces(p);
                    else usage(p, "/end select <id>");
                    return;
                }
                withSpace(p, a1, s -> act().select(p, s));
            }
            case "enter" -> {
                if (a1 == null) act().enterSelected(p);
                else withSpace(p, a1, s -> act().enter(p, s));
            }
            case "exit", "return" -> {
                if (!plugin.instances().isManaged(p.getWorld())) msg().send(p, "errors.usage", Messages.arg("usage", "/end exit (only inside an EndSpace)"));
                else act().exit(p);
            }
            case "members" -> withSpaceOrSelected(p, a1, s -> {
                if (gui()) plugin.menus().openMembers(p, s);
                else members(p, s);
            });
            case "info" -> withSpaceOrSelected(p, a1, s -> {
                if (gui()) plugin.menus().openSpace(p, s);
                else members(p, s);
            });
            case "leave" -> withSpaceOrSelected(p, args.length > 1 && !args[1].equalsIgnoreCase("confirm") ? args[1] : null, s -> {
                if (sup.confirmed(p, "leave:" + s.id(), args)) act().leave(p, s);
            });
            case "disband" -> withSpaceOrSelected(p, args.length > 1 && !args[1].equalsIgnoreCase("confirm") ? args[1] : null, s -> {
                if (sup.confirmed(p, "disband:" + s.id(), args)) act().disband(p, s);
            });
            case "public" -> {
                if (!perm(p, "endfold.public")) return;
                if (a1 == null) openOr(p, () -> plugin.menus().openPublic(p), "/end join <id>");
                else if (a1.equalsIgnoreCase("on") || a1.equalsIgnoreCase("off")) visibility(p, a1.equalsIgnoreCase("on"), args.length > 2 ? args[2] : null);
                else publicJoin(p, a1);
            }
            case "join", "request" -> {
                if (!perm(p, "endfold.join")) return;
                if (a1 == null) openOr(p, () -> plugin.menus().openPublic(p), "/end " + sub + " <id>");
                else publicJoin(p, a1);
            }
            case "requests" -> withSpaceOrSelected(p, a1, s -> openOr(p, () -> plugin.menus().openRequests(p, s), "/end approve <player>"));
            case "approve", "deny" -> request(p, args, sub.equals("approve"));
            case "achievements" -> openOr(p, () -> plugin.menus().openAchievements(p), "/end achievements");
            case "claim" -> plugin.loot().deliverPending(p, true);
            case "rename" -> {
                if (a1 == null) {
                    usage(p, "/end rename <new name>");
                    return;
                }
                withSpaceOrSelected(p, null, s -> act().rename(p, s, CommandSupport.join(args, 1)));
            }
            case "visibility" -> {
                if (a1 == null) usage(p, "/end visibility <public|private> [open|approval]");
                else visibility(p, a1.equalsIgnoreCase("public") || a1.equalsIgnoreCase("on"), args.length > 2 ? args[2] : null);
            }
            case "kick", "promote", "demote", "transfer" -> memberAction(p, sub, args);
            default -> help(p);
        }
    }

    private void help(Player p) { msg().lore("help").forEach(p::sendMessage); }

    private boolean perm(Player p, String node) {
        if (p.hasPermission(node)) return true;
        msg().fail(p, Failure.NO_PERMISSION);
        return false;
    }

    private void usage(Player p, String usage) { msg().send(p, "errors.usage", Messages.arg("usage", usage)); }

    private void openOr(Player p, Runnable open, String usage) {
        if (gui()) open.run();
        else usage(p, usage);
    }

    private void withSpace(Player p, String token, java.util.function.Consumer<EndSpace> then) {
        Optional<EndSpace> s = sup.ownSpace(p, token);
        if (s.isEmpty()) msg().fail(p, Failure.NOT_FOUND);
        else then.accept(s.get());
    }

    private void withSpaceOrSelected(Player p, String token, java.util.function.Consumer<EndSpace> then) {
        Optional<EndSpace> s = sup.ownSpace(p, token);
        if (s.isEmpty()) {
            if (token == null) msg().send(p, "errors.no-space-selected");
            else msg().fail(p, Failure.NOT_FOUND);
            return;
        }
        then.accept(s.get());
    }

    private void invite(Player p, String[] args) {
        if (!perm(p, "endfold.invite")) return;
        Optional<EndSpace> space = sup.ownSpace(p, args.length > 2 ? args[2] : null);
        if (args.length < 2) {
            if (space.isPresent() && gui()) plugin.menus().openInvitePicker(p, space.get());
            else if (gui()) plugin.menus().openSpaces(p);
            else usage(p, "/end invite <player> [space]");
            return;
        }
        if (space.isEmpty()) {
            msg().send(p, "errors.no-space-selected");
            return;
        }
        var target = sup.player(args[1]);
        if (target.isEmpty()) {
            msg().send(p, "errors.player-not-found", Messages.arg("player", args[1]));
            return;
        }
        act().invite(p, space.get(), target.get().uuid(), target.get().name());
    }

    private void visibility(Player p, boolean isPublic, String modeArg) {
        JoinMode mode = null;
        if (modeArg != null) mode = modeArg.equalsIgnoreCase("open") ? JoinMode.OPEN : JoinMode.APPROVAL;
        JoinMode m = mode;
        withSpaceOrSelected(p, null, s -> act().setVisibility(p, s, isPublic, m));
    }

    private void publicJoin(Player p, String token) {
        Optional<EndSpace> s = plugin.services().dir().resolve(token);
        if (s.isEmpty()) s = plugin.services().dir().allSpaces().stream().filter(x -> x.isPublic() && x.name().equalsIgnoreCase(token)).findFirst();
        if (s.isEmpty() || !s.get().isPublic()) {
            msg().fail(p, Failure.NOT_PUBLIC);
            return;
        }
        if (s.get().joinMode() == JoinMode.OPEN) act().joinPublic(p, s.get());
        else act().requestJoin(p, s.get());
    }

    private void request(Player p, String[] args, boolean approve) {
        if (args.length < 2) {
            usage(p, "/end " + (approve ? "approve" : "deny") + " <player> [space]");
            return;
        }
        withSpaceOrSelected(p, args.length > 2 ? args[2] : null, s -> {
            var target = sup.player(args[1]);
            if (target.isEmpty()) {
                msg().send(p, "errors.player-not-found", Messages.arg("player", args[1]));
                return;
            }
            Sync.then(plugin.services().members.requestsForSpace(s.id()), r -> {
                if (!r.isOk()) {
                    msg().fail(p, r.failure());
                    return;
                }
                Optional<MembershipRequest> req = r.value().stream().filter(x -> x.playerUuid().equals(target.get().uuid())).findFirst();
                if (req.isEmpty()) msg().fail(p, Failure.NOT_FOUND);
                else if (approve) act().approve(p, req.get());
                else act().deny(p, req.get());
            });
        });
    }

    private void memberAction(Player p, String sub, String[] args) {
        String[] a = CommandSupport.stripConfirm(args);
        if (a.length < 2) {
            usage(p, "/end " + sub + " <player> [space]");
            return;
        }
        withSpaceOrSelected(p, a.length > 2 ? a[2] : null, s -> {
            var target = sup.player(a[1]);
            if (target.isEmpty() || !plugin.services().dir().isMember(s.id(), target.get().uuid())) {
                msg().fail(p, Failure.NOT_MEMBER);
                return;
            }
            var t = target.get().uuid();
            switch (sub) {
                case "kick" -> {
                    if (sup.confirmed(p, "kick:" + s.id() + t, args)) act().kick(p, s, t);
                }
                case "promote" -> act().setRole(p, s, t, Role.ADMIN);
                case "demote" -> act().setRole(p, s, t, Role.MEMBER);
                case "transfer" -> {
                    if (sup.confirmed(p, "transfer:" + s.id() + t, args)) act().transfer(p, s, t);
                }
                default -> { }
            }
        });
    }

    private void list(Player p) {
        msg().send(p, "space.list-header");
        UUID sel = plugin.services().dir().selectedId(p.getUniqueId()).orElse(null);
        for (EndSpace s : plugin.services().dir().spacesOf(p.getUniqueId())) {
            p.sendMessage(msg().get("space.list-entry", Messages.arg("space", s.name()), Messages.arg("id", s.shortId()),
                    Messages.comp("type", msg().get(s.type() == SpaceType.SOLO ? "common.solo" : "common.group")),
                    Messages.comp("selected", s.id().equals(sel) ? msg().get("space.list-selected") : net.kyori.adventure.text.Component.empty())));
        }
    }

    private void members(Player p, EndSpace s) {
        msg().send(p, "space.members-header", Messages.arg("space", s.name()));
        for (Membership m : plugin.services().dir().members(s.id())) {
            p.sendMessage(msg().get("space.members-entry", Messages.arg("player", sup.nameOf(m.playerUuid())),
                    Messages.comp("role", msg().get("common.role-" + m.role().name().toLowerCase(Locale.ROOT)))));
        }
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!(source.getExecutor() instanceof Player p)) return List.of();
        if (args.length <= 1) return filter(SUBS.stream(), args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        String cur = args[args.length - 1];
        if (args.length == 2) {
            return switch (sub) {
                case "select", "enter", "members", "leave", "disband", "info", "requests" -> filter(ownIds(p), cur);
                case "invite", "approve", "deny" -> filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> !n.equals(p.getName())), cur);
                case "kick", "promote", "demote", "transfer" -> filter(plugin.services().profiles.selectedSpace(p.getUniqueId()).stream()
                        .flatMap(s -> plugin.services().dir().members(s.id()).stream()).map(m -> sup.nameOf(m.playerUuid())), cur);
                case "join", "request", "public" -> filter(Stream.concat(Stream.of("on", "off"), plugin.services().dir().allSpaces().stream()
                        .filter(EndSpace::isPublic).map(EndSpace::shortId)), cur);
                case "visibility" -> filter(Stream.of("public", "private"), cur);
                default -> List.of();
            };
        }
        if (args.length == 3) {
            if (sub.equals("visibility") || (sub.equals("public") && (args[1].equalsIgnoreCase("on")))) return filter(Stream.of("open", "approval"), cur);
            if (List.of("invite", "approve", "deny", "kick", "promote", "demote", "transfer").contains(sub)) return filter(ownIds(p), cur);
        }
        return List.of();
    }

    private Stream<String> ownIds(Player p) { return plugin.services().dir().spacesOf(p.getUniqueId()).stream().map(EndSpace::shortId); }

    private static List<String> filter(Stream<String> s, String prefix) {
        String pre = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        s.filter(x -> x.toLowerCase(Locale.ROOT).startsWith(pre)).sorted().limit(50).forEach(out::add);
        return out;
    }
}
