package dev.endfold;

import dev.endfold.action.Actions;
import dev.endfold.admin.AdminOps;
import dev.endfold.command.AdminCommand;
import dev.endfold.command.EndCommand;
import dev.endfold.config.EndfoldConfig;
import dev.endfold.config.Messages;
import dev.endfold.db.Database;
import dev.endfold.db.MigrationRunner;
import dev.endfold.db.SqliteDatabase;
import dev.endfold.db.sql.Repositories;
import dev.endfold.gui.MenuManager;
import dev.endfold.loot.LootModule;
import dev.endfold.portal.AccessGuard;
import dev.endfold.portal.PortalListener;
import dev.endfold.portal.PortalRouter;
import dev.endfold.portal.SessionListener;
import dev.endfold.progression.DragonModule;
import dev.endfold.service.AchievementType;
import dev.endfold.service.ProgressService;
import dev.endfold.service.Result;
import dev.endfold.service.ServiceContext;
import dev.endfold.service.ServiceSettings;
import dev.endfold.service.Services;
import dev.endfold.service.SpaceDirectory;
import dev.endfold.util.Sync;
import dev.endfold.world.InstanceManager;
import dev.endfold.world.WorldGenerator;
import dev.endfold.world.WorldLifecycleManager;
import dev.endfold.world.WorldRepository;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Endfold entry point and composition root. Wires configuration, database, services, world management, routing,
 * gameplay modules, GUIs and commands using constructor injection; contains no business logic itself.
 */
public final class EndfoldPlugin extends JavaPlugin {
    private volatile EndfoldConfig config;
    private volatile ServiceSettings settings;
    private Messages messages;
    private Database db;
    private Services services;
    private ExecutorService io;
    private WorldLifecycleManager lifecycle;
    private InstanceManager instances;
    private PortalRouter router;
    private AccessGuard guard;
    private SessionListener sessions;
    private Actions actions;
    private AdminOps admin;
    private MenuManager menus;
    private LootModule loot;
    private DragonModule dragons;
    private boolean healthy;

    @Override
    public void onEnable() {
        Sync.init(this);
        saveDefaultConfig();
        loadConfig();
        messages = new Messages(this);
        messages.reload();

        try {
            db = new SqliteDatabase(getDataFolder().toPath().resolve("endfold.db"), getLogger());
            if (config.databaseBackups && getDataFolder().toPath().resolve("endfold.db").toFile().length() > 0) {
                backupDatabase("startup").join(); // before migrations touch anything
            }
            new MigrationRunner(db, getLogger(), this::getResource).migrate();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Endfold could not open or migrate its database. The plugin will stay disabled to protect your data.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        SpaceDirectory dir = new SpaceDirectory();
        ServiceContext ctx = new ServiceContext(db, Repositories.sql(), dir, Clock.systemUTC(), getLogger(), () -> settings);
        services = new Services(ctx, () -> config.enabledAchievements);
        services.loadDirectory();

        io = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Endfold-IO");
            t.setDaemon(true);
            return t;
        });
        Path level = WorldRepository.levelFolderOf(Bukkit.getWorlds().getFirst().getWorldPath());
        WorldRepository files = new WorldRepository(level, getDataFolder().toPath());
        lifecycle = new WorldLifecycleManager(services, new WorldGenerator(), files, getLogger(), io,
                () -> config.unloadAfterMinutes, () -> config.maxLoadedWorlds);
        instances = new InstanceManager(dir, lifecycle);
        lifecycle.recoverOnStartup();

        router = new PortalRouter(this);
        guard = new AccessGuard(this);
        sessions = new SessionListener(this);
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PortalListener(this), this);
        pm.registerEvents(guard, this);
        pm.registerEvents(sessions, this);

        actions = new Actions(this);
        admin = new AdminOps(this);
        menus = new MenuManager(this);
        menus.register();
        loot = new LootModule(this);
        loot.register();
        dragons = new DragonModule(this);
        dragons.register();

        EndCommand end = new EndCommand(this);
        AdminCommand admin = new AdminCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ev -> {
            ev.registrar().register("end", "Open the Endfold menu and manage your EndSpaces", List.of("endspace", "endspaces"), end);
            ev.registrar().register("endfold", "Endfold administration", List.of("endadmin"), admin);
        });

        var sched = getServer().getScheduler();
        sched.runTaskTimer(this, lifecycle::tick, 20L * 30, 20L * 30);
        sched.runTaskTimer(this, sessions::sweep, 20L * 5, 20L * 5);
        sched.runTaskTimer(this, () -> services.members.expireInvitations(), 20L * 60, 20L * 300);
        if (config.databaseBackups) {
            sched.runTaskTimerAsynchronously(this, () -> backupDatabase("daily"), 20L * 3600, 20L * 3600 * 24);
        }

        getServer().getServicesManager().register(EndfoldApi.class, new EndfoldApi(this), this, ServicePriority.Normal);
        for (Player p : Bukkit.getOnlinePlayers()) services.profiles.seen(p.getUniqueId(), p.getName());
        healthy = true;
        getLogger().info("Endfold enabled: " + dir.allSpaces().size() + " EndSpace(s), schema V" + new MigrationRunner(db, getLogger(), this::getResource).currentVersion());
    }

    @Override
    public void onDisable() {
        if (!healthy) {
            if (db != null) db.close();
            return;
        }
        try {
            if (menus != null) menus.shutdown();
            if (loot != null) loot.shutdown();
            if (dragons != null) dragons.shutdown();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error while shutting down Endfold modules", e);
        }
        // Record where players inside EndSpaces are so they can be restored after the restart.
        for (Player p : Bukkit.getOnlinePlayers()) {
            instances.currentSpace(p).ifPresent(s -> {
                var l = p.getLocation();
                services.profiles.saveSession(new dev.endfold.model.ReturnLocation(p.getUniqueId(), s.id(), null, null, l.getX(), l.getY(), l.getZ(),
                        l.getYaw(), l.getPitch()));
            });
        }
        if (io != null) io.shutdown();
        db.close();
        getLogger().info("Endfold disabled; all pending database work flushed.");
    }

    /**
     * Copies the database to {@code backups/database/endfold-<timestamp>-<tag>.db}, keeping the newest 14 copies.
     * Failures are logged but never prevent the plugin from running.
     */
    private java.util.concurrent.CompletableFuture<Void> backupDatabase(String tag) {
        Path dir = getDataFolder().toPath().resolve("backups/database");
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return db.backupTo(dir.resolve("endfold-" + ts + "-" + tag + ".db")).handle((v, t) -> {
            if (t != null) {
                getLogger().log(Level.WARNING, "Database backup (" + tag + ") failed; continuing without it", t);
                return null;
            }
            try (var files = java.nio.file.Files.list(dir)) {
                List<Path> all = files.filter(f -> f.getFileName().toString().startsWith("endfold-") && f.toString().endsWith(".db"))
                        .sorted(java.util.Comparator.comparing((Path f) -> f.getFileName().toString()).reversed()).toList();
                for (int i = 14; i < all.size(); i++) java.nio.file.Files.deleteIfExists(all.get(i));
            } catch (java.io.IOException e) {
                getLogger().log(Level.WARNING, "Could not prune old database backups", e);
            }
            return null;
        });
    }

    private void loadConfig() {
        reloadConfig();
        EndfoldConfig c = new EndfoldConfig(getConfig());
        for (String problem : c.problems()) getLogger().warning("config.yml: " + problem);
        long overworldSeed = Bukkit.getWorlds().isEmpty() ? 0L : Bukkit.getWorlds().getFirst().getSeed();
        this.config = c;
        this.settings = c.toServiceSettings(overworldSeed);
    }

    /** Reloads config.yml, messages.yml and gui.yml. Returns the configuration problems found. */
    public List<String> reload() {
        loadConfig();
        messages.reload();
        if (menus != null) menus.reload();
        if (loot != null) loot.onReload();
        return config.problems();
    }

    /** Announces newly granted achievements to online players. Safe to call with failed results. */
    public void announceGrants(Result<ProgressService.Grants> r) {
        if (r == null || !r.isOk()) return;
        for (Map.Entry<UUID, List<AchievementType>> e : r.value().byPlayer().entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            for (AchievementType a : e.getValue()) {
                messages.send(p, "achievement.granted", Messages.arg("name", menus.achievementName(a)), Messages.arg("hint", a.hint()));
            }
        }
    }

    /** Called shortly after join, once the profile is loaded: deliver pending items, give access item. */
    public void onPlayerReady(Player p) {
        loot.deliverPending(p, false);
        menus.onPlayerReady(p);
    }

    public void broadcastToSpace(UUID spaceId, Component message) {
        services.dir().members(spaceId).forEach(m -> {
            Player p = Bukkit.getPlayer(m.playerUuid());
            if (p != null) messages.sendRaw(p, message);
        });
    }

    public EndfoldConfig config() { return config; }

    public Messages messages() { return messages; }

    public Services services() { return services; }

    public InstanceManager instances() { return instances; }

    public WorldLifecycleManager lifecycle() { return lifecycle; }

    public PortalRouter router() { return router; }

    public AccessGuard guard() { return guard; }

    public Actions actions() { return actions; }

    public AdminOps admin() { return admin; }

    public MenuManager menus() { return menus; }

    public LootModule loot() { return loot; }

    public DragonModule dragons() { return dragons; }

    public Database database() { return db; }
}
