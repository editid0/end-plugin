package dev.endfold.gui;

import dev.endfold.EndfoldPlugin;
import dev.endfold.model.EndSpace;
import dev.endfold.service.AchievementType;
import dev.endfold.service.Result;
import dev.endfold.util.Sync;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Entry point of the GUI layer. Commands and other modules open menus only through these methods. Owns the single
 * listener, click rate limiting, the per-player action lock, text prompts and the optional access item.
 */
public final class MenuManager {
    private static final long LOCK_MAX_MILLIS = 15_000L;
    private final EndfoldPlugin plugin;
    private final Ui ui;
    private final TextPrompt prompts;
    private final NamespacedKey accessKey;
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private final Map<UUID, Long> locks = new ConcurrentHashMap<>();

    public MenuManager(EndfoldPlugin plugin) {
        this.plugin = plugin;
        this.ui = new Ui(plugin);
        this.prompts = new TextPrompt(this);
        this.accessKey = new NamespacedKey(plugin, "access_item");
    }

    EndfoldPlugin plugin() { return plugin; }

    Ui ui() { return ui; }

    TextPrompt prompts() { return prompts; }

    // ------------------------------------------------------------------ lifecycle

    public void register() {
        var pm = plugin.getServer().getPluginManager();
        pm.registerEvents(new MenuListener(this), plugin);
        pm.registerEvents(prompts, plugin);
    }

    public void shutdown() {
        closeAll();
        prompts.cancelAll();
        lastClick.clear();
        locks.clear();
    }

    /** Closes all open menus so no menu keeps stale texts or layout after a reload. */
    public void reload() {
        closeAll();
        prompts.cancelAll();
    }

    private void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) p.closeInventory();
        }
    }

    void purge(Player p) {
        lastClick.remove(p.getUniqueId());
        locks.remove(p.getUniqueId());
        prompts.cancelChat(p.getUniqueId());
    }

    public void onPlayerReady(Player p) { giveAccessItem(p); }

    // ------------------------------------------------------------------ click guard

    boolean allowClick(UUID id) {
        long now = System.currentTimeMillis();
        Long last = lastClick.get(id);
        if (last != null && now - last < plugin.config().clickCooldownMs) return false;
        lastClick.put(id, now);
        return true;
    }

    boolean isLocked(UUID id) {
        Long since = locks.get(id);
        return since != null && System.currentTimeMillis() - since < LOCK_MAX_MILLIS;
    }

    /**
     * Runs a service action under the per-player lock (a second click while it runs is ignored). {@code then} runs on
     * the server thread when the action finished and the player is still online.
     */
    <T> void exec(Player p, Supplier<CompletableFuture<Result<T>>> call, Consumer<Result<T>> then) {
        UUID id = p.getUniqueId();
        if (isLocked(id)) return;
        locks.put(id, System.currentTimeMillis());
        CompletableFuture<Result<T>> f;
        try {
            f = call.get();
        } catch (RuntimeException e) {
            locks.remove(id);
            throw e;
        }
        f.whenCompleteAsync((r, t) -> {
            locks.remove(id);
            if (t != null) {
                plugin.getLogger().log(Level.WARNING, "Menu action failed", t);
                return;
            }
            if (r != null && Bukkit.getPlayer(id) != null) then.accept(r);
        }, Sync.main());
    }

    void clickSound(Player p) {
        if (plugin.config().guiSounds) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
    }

    void pageSound(Player p) {
        if (plugin.config().guiSounds) p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
    }

    // ------------------------------------------------------------------ access item

    ItemStack accessItem() {
        Material mat = plugin.config().accessItemMaterial;
        ItemStack stack = Items.of(mat).name(plugin.messages().item("gui.access-item.name")).lore(plugin.messages().lore("gui.access-item.lore")).build();
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(accessKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    boolean isAccessItem(ItemStack s) {
        if (s == null || s.isEmpty() || !s.hasItemMeta()) return false;
        return s.getItemMeta().getPersistentDataContainer().has(accessKey, PersistentDataType.BYTE);
    }

    void giveAccessItem(Player p) {
        if (!plugin.config().guiEnabled || !plugin.config().useAccessItem) return;
        for (ItemStack s : p.getInventory().getContents()) {
            if (isAccessItem(s)) return;
        }
        if (isAccessItem(p.getInventory().getItemInOffHand())) return;
        p.getInventory().addItem(accessItem());
    }

    // ------------------------------------------------------------------ public API

    private boolean enabled(Player p) {
        if (plugin.config().guiEnabled) return true;
        plugin.messages().send(p, "gui.disabled");
        return false;
    }

    public void openMain(Player p) {
        if (enabled(p)) new MainMenu(this, p).open();
    }

    public void openSpaces(Player p) {
        if (enabled(p)) new SpacesMenu(this, p, () -> openMain(p), 0).open();
    }

    public void openSpace(Player p, EndSpace space) {
        if (enabled(p)) new SpaceMenu(this, p, space.id(), () -> openSpaces(p)).open();
    }

    public void openCreate(Player p) {
        if (enabled(p)) new CreateMenu(this, p, () -> openMain(p)).open();
    }

    public void openMembers(Player p, EndSpace space) {
        if (enabled(p)) new MembersMenu(this, p, space.id(), () -> openSpace(p, space), 0).open();
    }

    public void openInvitePicker(Player p, EndSpace space) {
        if (enabled(p)) invitePicker(p, space.id(), () -> openSpace(p, space)).open();
    }

    public void openInvitations(Player p) {
        if (enabled(p)) new InvitationsMenu(this, p, () -> openMain(p), false, null, 0).open();
    }

    public void openPublic(Player p) {
        if (!enabled(p)) return;
        if (!p.hasPermission("endfold.public")) {
            plugin.messages().fail(p, dev.endfold.service.Failure.NO_PERMISSION);
            return;
        }
        new PublicMenu(this, p, () -> openMain(p), new PublicMenu.View(PublicMenu.Filter.ALL, PublicMenu.Sort.MEMBERS), 0).open();
    }

    public void openAchievements(Player p) {
        if (enabled(p)) new AchievementsMenu(this, p, () -> openMain(p), 0).open();
    }

    public void openRequests(Player p, EndSpace space) {
        if (enabled(p)) new RequestsMenu(this, p, space.id(), () -> openSpace(p, space)).open();
    }

    public void openAdmin(Player p) {
        if (!enabled(p)) return;
        if (!p.hasPermission("endfold.admin.worlds")) {
            plugin.messages().fail(p, dev.endfold.service.Failure.NO_PERMISSION);
            return;
        }
        new AdminMenu(this, p, () -> openMain(p), AdminMenu.Filter.ALL, 0).open();
    }

    /** Display name of an achievement (configurable in gui.yml). */
    public String achievementName(AchievementType type) {
        String key = "gui.achievements.entries." + type.key() + ".name";
        if (plugin.messages().has(key)) return plugin.messages().raw(key);
        String n = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    String achievementHint(AchievementType type) {
        String key = "gui.achievements.entries." + type.key() + ".hint";
        return plugin.messages().has(key) ? plugin.messages().raw(key) : type.hint();
    }

    // ------------------------------------------------------------------ shared helpers

    /** Local format check for names; the service validates again. Returns an error message or {@code null}. */
    net.kyori.adventure.text.Component validateName(String text) {
        int min = plugin.config().nameMin;
        int max = plugin.config().nameMax;
        if (text.length() >= min && text.length() <= max) return null;
        return plugin.messages().get("gui.errors.name-length", dev.endfold.config.Messages.arg("min", min), dev.endfold.config.Messages.arg("max", max));
    }


    PlayerSelectMenu<dev.endfold.model.Invitation> invitePicker(Player p, UUID spaceId, Runnable back) {
        return new PlayerSelectMenu<>(this, p, back, plugin.messages().get("gui.invite.title"), "gui.invite.player",
                () -> {
                    Player viewer = Bukkit.getPlayer(p.getUniqueId());
                    if (viewer == null) return java.util.List.of();
                    var dir = plugin.services().dir();
                    return Bukkit.getOnlinePlayers().stream()
                            .filter(o -> !o.getUniqueId().equals(p.getUniqueId()) && !dir.isMember(spaceId, o.getUniqueId()))
                            .map(o -> new PlayerSelectMenu.Candidate(o.getUniqueId(), o.getName()))
                            .sorted(java.util.Comparator.comparing(c -> c.name().toLowerCase(Locale.ROOT)))
                            .toList();
                },
                c -> {
                    EndSpace s = plugin.services().dir().space(spaceId).orElse(null);
                    if (s == null) return CompletableFuture.completedFuture(Result.fail(dev.endfold.service.Failure.NOT_FOUND));
                    return plugin.actions().invite(p, s, c.uuid(), c.name());
                },
                () -> invitePicker(p, spaceId, back),
                () -> plugin.services().members.spaceInvites(spaceId)
                        .thenApply(r -> r.map(list -> list.stream().map(dev.endfold.model.Invitation::inviteeUuid)
                                .collect(java.util.stream.Collectors.toSet()))));
    }
}
