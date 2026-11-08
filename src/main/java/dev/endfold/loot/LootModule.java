package dev.endfold.loot;

import dev.endfold.EndfoldPlugin;
import org.bukkit.entity.Player;

/** Personal End City loot, Elytra claims, reward delivery and renewable shulkers. */
public final class LootModule {
    private final EndfoldPlugin plugin;
    private PersonalChestManager chests;
    private ElytraManager elytra;
    private ShulkerRespawner shulkers;

    public LootModule(EndfoldPlugin plugin) { this.plugin = plugin; }

    public void register() {
        chests = new PersonalChestManager(plugin);
        elytra = new ElytraManager(plugin);
        shulkers = new ShulkerRespawner(plugin);
        var pm = plugin.getServer().getPluginManager();
        pm.registerEvents(chests, plugin);
        pm.registerEvents(elytra, plugin);
        pm.registerEvents(shulkers, plugin);
        chests.start();
        elytra.start();
        shulkers.start();
    }

    /** Saves open personal inventories synchronously and stops periodic tasks. */
    public void shutdown() {
        if (chests != null) chests.shutdown();
        if (shulkers != null) shulkers.shutdown();
    }

    /** Delivers outbox items (e.g. Elytra that did not fit). */
    public void deliverPending(Player p, boolean notifyIfNone) {
        if (elytra != null) elytra.deliverPending(p, notifyIfNone);
    }

    /** Re-reads the shulker check interval after a configuration reload. */
    public void onReload() {
        if (shulkers != null) shulkers.schedule();
    }

    EndfoldPlugin plugin() { return plugin; }
}
