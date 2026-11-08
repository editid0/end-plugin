package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.PlayerProfile;
import dev.endfold.service.Result;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Picks a player: heads of online candidates plus a "search by name" button (text input). Choosing a player runs the
 * given service action and then reopens a fresh instance of the picker.
 *
 * @param <R> value type of the service action's result
 */
final class PlayerSelectMenu<R> extends PaginatedMenu<PlayerSelectMenu.Candidate> {
    record Candidate(UUID uuid, String name) {}

    private final Component title;
    private final Supplier<List<Candidate>> candidates;
    private final Function<Candidate, CompletableFuture<Result<R>>> select;
    private final Supplier<Menu> reopen;
    private final Supplier<CompletableFuture<Result<Set<UUID>>>> excludedLoader;
    private final String hintBase;
    private Set<UUID> excluded = Set.of();

    PlayerSelectMenu(MenuManager mgr, Player viewer, Runnable back, Component title, String hintBase, Supplier<List<Candidate>> candidates,
                     Function<Candidate, CompletableFuture<Result<R>>> select, Supplier<Menu> reopen,
                     Supplier<CompletableFuture<Result<Set<UUID>>>> excludedLoader) {
        super(mgr, viewer, back, "gui.select.empty", 0);
        this.title = title;
        this.hintBase = hintBase;
        this.candidates = candidates;
        this.select = select;
        this.reopen = reopen;
        this.excludedLoader = excludedLoader;
    }

    @Override
    protected Component title() { return title; }

    @Override
    protected void onOpened() {
        if (excludedLoader != null) async(excludedLoader.get(), set -> excluded = new HashSet<>(set));
    }

    @Override
    protected List<Candidate> entries() { return candidates.get().stream().filter(c -> !excluded.contains(c.uuid())).toList(); }

    @Override
    protected ItemStack icon(Candidate c) {
        return ui.builder("player", Material.PLAYER_HEAD, hintBase, Messages.arg("player", c.name())).head(c.uuid()).build();
    }

    @Override
    protected void clicked(Candidate c, Click click) { choose(click.player(), c); }

    @Override
    protected void toolbar() {
        set(4, ui.item("search", Material.NAME_TAG, "gui.select.search"), c -> search(c.player(), ""));
    }

    private void choose(Player p, Candidate c) {
        mgr.exec(p, () -> select.apply(c), r -> reopen.get().open());
    }

    private Optional<Candidate> resolve(String text) {
        Player online = Bukkit.getPlayerExact(text);
        if (online != null) return Optional.of(new Candidate(online.getUniqueId(), online.getName()));
        Optional<PlayerProfile> pr = plugin.services().dir().profileByName(text);
        return pr.map(profile -> new Candidate(profile.playerUuid(), profile.lastName()));
    }

    private void search(Player p, String initial) {
        mgr.prompts().ask(p, new TextPrompt.Request(
                msg().get("gui.select.search-title"),
                msg().get("gui.select.search-hint"),
                initial, 16,
                text -> text.isEmpty() || text.length() > 16 || resolve(text).isEmpty()
                        ? msg().get("gui.select.not-found", Messages.arg("name", text)) : null,
                text -> resolve(text).ifPresentOrElse(c -> choose(p, c), () -> reopen.get().open()),
                () -> reopen.get().open()));
    }
}
