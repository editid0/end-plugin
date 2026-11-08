package dev.endfold.testkit;

import dev.endfold.EndfoldApi;
import dev.endfold.model.EndSpace;
import dev.endfold.model.Membership;
import dev.endfold.model.PlayerProgress;
import dev.endfold.model.SpaceType;
import dev.endfold.service.Result;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Hopper;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StructureSearchResult;

/**
 * Real-server integration tests for Endfold. Started with {@code -Dendfold.testkit.phase=1} (setup and gameplay) and
 * then, after a full server restart, {@code -Dendfold.testkit.phase=2} (persistence). Results are written to
 * {@code plugins/EndfoldTestkit/results-phase<N>.txt}; the server shuts itself down afterwards.
 */
public final class TestkitPlugin extends JavaPlugin {
    private final FakePlayers fakes = new FakePlayers();
    private final Properties state = new Properties();
    private Scenario sc;
    private EndfoldApi api;
    private World ow;
    private Player alice, bob, carol;
    private EndSpace aliceSolo, bobGroup;
    private Location chestLoc;
    private List<String> bobChestPhase1;

    @Override
    public void onEnable() {
        int phase = Integer.getInteger("endfold.testkit.phase", 0);
        if (phase == 0) {
            getLogger().warning("EndfoldTestkit loaded without -Dendfold.testkit.phase; doing nothing.");
            return;
        }
        Bukkit.getScheduler().runTaskTimer(this, fakes::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            api = Bukkit.getServicesManager().load(EndfoldApi.class);
            ow = Bukkit.getWorlds().getFirst();
            sc = new Scenario(this);
            if (phase == 1) phase1();
            else phase2();
            sc.then(() -> {
                sc.write(getDataFolder().toPath().resolve("results-phase" + phase + ".txt"), "Endfold integration results, phase " + phase
                        + ", server " + Bukkit.getVersion());
                saveState();
                fakes.removeAll();
                Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 40L);
            }).start();
        }, 60L);
    }

    // ------------------------------------------------------------------ helpers

    private static <T> T await(CompletableFuture<Result<T>> f) {
        try {
            Result<T> r = f.get(15, TimeUnit.SECONDS);
            if (!r.isOk()) throw new IllegalStateException("service failure " + r.failure());
            return r.value();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> Result<T> awaitRaw(CompletableFuture<Result<T>> f) {
        try {
            return f.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fmt(Location l) {
        return l == null ? "null" : l.getWorld().getName() + "@" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    private void buildOverworldPortal(int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                ow.getBlockAt(x + dx, y - 1, z + dz).setType(Material.STONE);
                for (int dy = 0; dy <= 3; dy++) ow.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR);
            }
        }
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) ow.getBlockAt(x + dx, y, z + dz).setType(Material.END_PORTAL);
    }

    private Location portalCenter() { return new Location(ow, 0.5, 200.1, 0.5); }

    private void stepInto(Player p, Location l) {
        p.setFlying(false);
        p.teleport(l);
    }

    private Optional<EndSpace> spaceOf(Player p) { return api.spaceOf(p.getWorld()); }

    private Block findBlock(World w, Location around, int radius, int minY, int maxY, Material m) {
        for (int x = around.getBlockX() - radius; x <= around.getBlockX() + radius; x++) {
            for (int z = around.getBlockZ() - radius; z <= around.getBlockZ() + radius; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() == m) return b;
                }
            }
        }
        return null;
    }

    private static List<String> snapshot(Inventory inv) {
        List<String> out = new ArrayList<>();
        for (ItemStack i : inv.getContents()) out.add(i == null ? "-" : i.getType() + "x" + i.getAmount());
        return out;
    }

    private int count(Player p, Material m) {
        int n = 0;
        for (ItemStack i : p.getInventory().getContents()) if (i != null && i.getType() == m) n += i.getAmount();
        return n;
    }

    private void saveState() {
        try (Writer w = Files.newBufferedWriter(getDataFolder().toPath().resolve("state.properties"))) {
            state.store(w, "Endfold testkit state");
        } catch (IOException e) {
            getLogger().severe("state save failed " + e);
        }
    }

    private void loadState() {
        Path f = getDataFolder().toPath().resolve("state.properties");
        if (!Files.exists(f)) return;
        try (Reader r = Files.newBufferedReader(f)) {
            state.load(r);
        } catch (IOException e) {
            getLogger().severe("state load failed " + e);
        }
    }

    private void setupPlayers() {
        buildOverworldPortal(0, 200, 0);
        alice = fakes.spawn("Alice", ow);
        bob = fakes.spawn("Bob", ow);
        carol = fakes.spawn("Carol", ow);
        Location safe = new Location(ow, 6.5, 200, 6.5);
        for (Player p : List.of(alice, bob, carol)) {
            p.teleport(safe);
            p.setAllowFlight(true);
            p.setFlying(true);
        }
    }

    // ------------------------------------------------------------------ phase 1

    private void phase1() {
        sc.run("setup", () -> {
            sc.check("api available", api != null, "");
            setupPlayers();
        }).waitTicks(40);

        // --- Solo creation through a real End portal (create-on-first-entry) ---
        sc.run("alice enters portal", () -> stepInto(alice, portalCenter()))
                .waitUntil("alice arrives in a managed End", () -> api.isManagedWorld(alice.getWorld()), 400)
                .run("alice solo checks", () -> {
                    Optional<EndSpace> s = spaceOf(alice);
                    sc.check("first portal entry created a SOLO EndSpace owned by Alice", s.isPresent() && s.get().type() == SpaceType.SOLO
                            && s.get().ownerUuid().equals(alice.getUniqueId()), s.map(EndSpace::worldName).orElse("none"));
                    aliceSolo = s.orElse(null);
                    sc.check("arrival platform is obsidian", alice.getWorld().getBlockAt(100, 48, 0).getType() == Material.OBSIDIAN, fmt(alice.getLocation()));
                    sc.check("world name derived from space id", aliceSolo != null && aliceSolo.worldName().equals("endspace_" + aliceSolo.shortId()), "");
                    alice.getWorld().getBlockAt(400, 100, 400).setType(Material.DIAMOND_BLOCK);
                });

        // --- Bob creates a group and enters it ---
        sc.run("bob creates group", () -> {
            bobGroup = await(api.services().spaces.create(bob.getUniqueId(), bob.getName(), SpaceType.GROUP, "Bob Crew"));
            sc.check("bob's first space is auto-selected", bobGroup.id().equals(api.services().dir().selectedId(bob.getUniqueId()).orElse(null)), "");
            // portals.direct-teleport defaults to AFTER_PORTAL: no teleporting in before the first real portal trip.
            sc.check("direct teleport locked before the first End portal trip", api.router().directAccess(bob)
                    == dev.endfold.portal.PortalRouter.DirectAccess.NEEDS_PORTAL, String.valueOf(api.router().directAccess(bob)));
            var locked = api.router().enter(bob, bobGroup, false);
            sc.check("menu/command enter refused while locked; player stays put", locked == dev.endfold.portal.PortalRouter.Outcome.LOCKED
                    && bob.getWorld() == ow, locked.name());
            stepInto(bob, portalCenter());
        }).waitUntil("bob arrives in his group world", () -> spaceOf(bob).map(s -> s.id().equals(bobGroup.id())).orElse(false), 400)
                .run("two independent End worlds", () -> {
                    World a = alice.getWorld(), b = bob.getWorld();
                    sc.check("both worlds are THE_END and distinct", a != b && a.getEnvironment() == World.Environment.THE_END
                            && b.getEnvironment() == World.Environment.THE_END, a.getName() + " / " + b.getName());
                    DragonBattle ba = a.getEnderDragonBattle(), bb = b.getEnderDragonBattle();
                    sc.check("each world has its own dragon battle", ba != null && bb != null && ba != bb, "");
                    sc.check("seeds differ (RANDOM seed mode)", a.getSeed() != b.getSeed(), a.getSeed() + " vs " + b.getSeed());
                    b.getBlockAt(400, 100, 400).setType(Material.GOLD_BLOCK);
                    sc.check("first real portal trip unlocks direct teleporting", api.router().directAccess(bob)
                            == dev.endfold.portal.PortalRouter.DirectAccess.ALLOWED, "");
                    sc.check("block changes are independent", a.getBlockAt(400, 100, 400).getType() == Material.DIAMOND_BLOCK
                            && b.getBlockAt(400, 100, 400).getType() == Material.GOLD_BLOCK, "");
                });

        // --- Invitation, multiple membership and selection ---
        sc.run("alice joins bob's group", () -> {
            var inv = await(api.services().members.invite(bob.getUniqueId(), bobGroup.id(), alice.getUniqueId(), "Alice"));
            await(api.services().members.acceptInvite(alice.getUniqueId(), "Alice", inv.id()));
            await(api.services().profiles.select(alice.getUniqueId(), bobGroup.id()));
            sc.check("alice belongs to 2 EndSpaces", api.spacesOf(alice.getUniqueId()).size() == 2, "");
            sc.check("bob's selection unaffected by alice", bobGroup.id().equals(api.services().dir().selectedId(bob.getUniqueId()).orElse(null)), "");
            sc.check("alice still in her solo world after selecting (select != enter)", spaceOf(alice).map(s -> s.id().equals(aliceSolo.id())).orElse(false), "");
            api.router().exit(alice, false);
        }).waitUntil("alice returns to overworld", () -> alice.getWorld() == ow, 200)
                .run("alice return location", () -> {
                    Location l = alice.getLocation();
                    sc.check("returned next to her entry point (not inside the portal)", l.distance(portalCenter()) < 8
                            && l.getBlock().getType() != Material.END_PORTAL, fmt(l));
                    stepInto(alice, portalCenter());
                }).waitUntil("alice enters bob's group via portal", () -> spaceOf(alice).map(s -> s.id().equals(bobGroup.id())).orElse(false), 400);

        // --- Access control ---
        sc.run("non-member denied", () -> {
            Location target = new Location(bob.getWorld(), 0, 90, 0);
            carol.teleport(target, TeleportCause.COMMAND);
            sc.check("command teleport of non-member into a private EndSpace is cancelled", carol.getWorld() == ow, fmt(carol.getLocation()));
            carol.teleport(target, TeleportCause.PLUGIN);
            sc.check("plugin teleport of non-member is cancelled", carol.getWorld() == ow, fmt(carol.getLocation()));
            var outcome = api.router().enter(carol, bobGroup, false);
            sc.check("router refuses non-member", outcome == dev.endfold.portal.PortalRouter.Outcome.DENIED, outcome.name());
            stepInto(carol, portalCenter());
        }).waitUntil("carol routed to her OWN new solo End, not bob's", () -> spaceOf(carol).isPresent(), 400)
                .run("carol destination", () -> sc.check("carol's portal led to a space she owns", spaceOf(carol).map(s -> s.ownerUuid()
                        .equals(carol.getUniqueId()) && !s.id().equals(bobGroup.id())).orElse(false), carol.getWorld().getName()));

        // --- Dragon fight in Bob's group (shared), Alice's solo untouched ---
        sc.run("go to fight", () -> {
            for (Player p : List.of(alice, bob)) {
                p.teleport(new Location(bob.getWorld(), 0.5, 85, 20.5));
                p.setAllowFlight(true);
                p.setFlying(true);
            }
        }).waitUntil("dragon spawns in bob's world", () -> bob.getWorld().getEnderDragonBattle().getEnderDragon() != null, 1200)
                .run("damage dragon", () -> {
                    EnderDragon d = bob.getWorld().getEnderDragonBattle().getEnderDragon();
                    // Damage to the dragon's body is reduced (only head hits count fully), so use large values.
                    d.damage(400, alice);
                    d.damage(4000, bob);
                }).waitUntil("dragon death completes", () -> bob.getWorld().getEnderDragonBattle().hasBeenPreviouslyKilled(), 2400)
                .waitTicks(60)
                .run("dragon results", () -> {
                    DragonBattle bb = bob.getWorld().getEnderDragonBattle();
                    sc.check("gateway spawned after kill", bb.getGatewayCount() >= 1, "gateways=" + bb.getGatewayCount());
                    var prog = await(api.services().progress.space(bobGroup.id()));
                    sc.check("shared space progress records the kill", prog.dragonKills() == 1 && prog.dragonDefeated(), "kills=" + prog.dragonKills());
                    PlayerProgress pa = await(api.services().progress.player(bobGroup.id(), alice.getUniqueId()));
                    PlayerProgress pb = await(api.services().progress.player(bobGroup.id(), bob.getUniqueId()));
                    sc.check("both fighters credited with participation", pa.dragonParticipations() == 1 && pb.dragonParticipations() == 1,
                            "alice=" + pa.dragonParticipations() + " bob=" + pb.dragonParticipations());
                    World soloWorld = api.instances().worldOf(aliceSolo).orElse(null);
                    sc.check("alice's solo dragon is unaffected", soloWorld != null && !soloWorld.getEnderDragonBattle().hasBeenPreviouslyKilled(), "");
                    var aliceSoloProgress = await(api.services().progress.space(aliceSolo.id()));
                    sc.check("solo space progress untouched", aliceSoloProgress.dragonKills() == 0, "");
                });

        // --- Gateway stays inside the instance ---
        sc.run("use gateway", () -> {
            Block gw = findBlock(bob.getWorld(), new Location(bob.getWorld(), 0, 75, 0), 100, 70, 80, Material.END_GATEWAY);
            state.setProperty("gateway", gw == null ? "" : fmt(gw.getLocation()));
            if (gw == null) {
                sc.skip("gateway stays in instance", "no gateway block found");
                return;
            }
            stepInto(bob, gw.getLocation().add(0.5, 0, 0.5));
        }).waitTicks(120).run("gateway result", () -> {
            if (state.getProperty("gateway", "").isEmpty()) return;
            sc.check("gateway teleport stayed in bob's EndSpace", spaceOf(bob).map(s -> s.id().equals(bobGroup.id())).orElse(false)
                    && bob.getLocation().distance(new Location(bob.getWorld(), 0, 75, 0)) > 300, fmt(bob.getLocation()));
        });

        // --- Exit portal leads to the entry point stored for this space ---
        sc.run("alice exit portal", () -> {
            Location exit = bob.getWorld().getEnderDragonBattle().getEndPortalLocation();
            Block pb = exit == null ? null : findBlock(bob.getWorld(), exit, 3, exit.getBlockY() - 3, exit.getBlockY() + 3, Material.END_PORTAL);
            if (pb == null) {
                sc.check("exit portal found", false, "no END_PORTAL near " + fmt(exit));
                return;
            }
            state.setProperty("exitPortal", fmt(pb.getLocation()));
            stepInto(alice, pb.getLocation().add(0.5, 0.2, 0.5));
        }).waitUntil("alice leaves via exit portal", () -> alice.getWorld() == ow, 300)
                .run("alice exit result", () -> sc.check("exit portal returned alice to her overworld entry point", alice.getWorld() == ow
                        && alice.getLocation().distance(portalCenter()) < 8, fmt(alice.getLocation())));

        // --- Non-player entities ---
        sc.run("entities", () -> {
            ow.dropItem(portalCenter(), new ItemStack(Material.STICK)).setPickupDelay(10_000);
            Location exit = bob.getWorld().getEnderDragonBattle().getEndPortalLocation();
            Block pb = findBlock(bob.getWorld(), exit, 3, exit.getBlockY() - 3, exit.getBlockY() + 3, Material.END_PORTAL);
            if (pb != null) bob.getWorld().spawn(pb.getLocation().add(0.5, 0.2, 0.5), Pig.class, pig -> pig.setAI(false));
        }).waitTicks(100).run("entity results", () -> {
            boolean stickInEnd = false;
            for (World w : Bukkit.getWorlds()) {
                if (w.getEnvironment() != World.Environment.THE_END) continue;
                for (Item i : w.getEntitiesByClass(Item.class)) if (i.getItemStack().getType() == Material.STICK) stickInEnd = true;
            }
            sc.check("items thrown into an overworld End portal do not reach any End", !stickInEnd, "");
            sc.check("pig in an EndSpace exit portal stays in that EndSpace", !bob.getWorld().getEntitiesByClass(Pig.class).isEmpty()
                    && ow.getEntitiesByClass(Pig.class).stream().noneMatch(p -> !p.hasAI()), "");
        });

        // --- Switching back to the solo End keeps everything ---
        sc.run("alice switches back", () -> {
            await(api.services().profiles.select(alice.getUniqueId(), aliceSolo.id()));
            stepInto(alice, portalCenter());
        }).waitUntil("alice back in her solo End", () -> spaceOf(alice).map(s -> s.id().equals(aliceSolo.id())).orElse(false), 400)
                .run("switch results", () -> {
                    sc.check("solo world kept its blocks", alice.getWorld().getBlockAt(400, 100, 400).getType() == Material.DIAMOND_BLOCK, "");
                    sc.check("alice is still a member of bob's group", api.services().dir().isMember(bobGroup.id(), alice.getUniqueId()), "");
                    sc.check("bob's selection still his group", bobGroup.id().equals(api.services().dir().selectedId(bob.getUniqueId()).orElse(null)), "");
                });

        // --- Personal End City loot ---
        sc.run("travel to end city", () -> {
            World b = api.instances().worldOf(bobGroup).orElseThrow();
            StructureSearchResult res = b.locateNearestStructure(new Location(b, 1000, 64, 0), Structure.END_CITY, 100, false);
            if (res == null) {
                sc.skip("end city loot", "no end city found");
                return;
            }
            Location c = res.getLocation();
            state.setProperty("city", fmt(c));
            for (Player p : List.of(alice, bob)) {
                p.teleport(new Location(b, c.getX(), 120, c.getZ()));
                p.setAllowFlight(true);
                p.setFlying(true);
            }
        }).waitTicks(300).run("find chest", () -> {
            if (!state.containsKey("city")) return;
            World b = bob.getWorld();
            Block chest = null;
            Location origin = bob.getLocation();
            for (int attempt = 0; attempt < 8 && chest == null; attempt++) {
                if (attempt > 0) {
                    StructureSearchResult next = b.locateNearestStructure(new Location(b, (attempt % 2 == 0 ? 1 : -1) * (1200 + attempt * 700), 64,
                            (attempt % 3 == 0 ? 1 : -1) * attempt * 700), Structure.END_CITY, 100, false);
                    if (next == null) continue;
                    origin = next.getLocation();
                }
                for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) b.getChunkAt((origin.getBlockX() >> 4) + dx, (origin.getBlockZ() >> 4) + dz);
                chest = findBlock(b, origin, 128, 40, 250, Material.CHEST);
            }
            if (chest == null) {
                sc.skip("end city loot", "no chest found in 8 searched cities");
                return;
            }
            chestLoc = chest.getLocation();
            state.setProperty("chest", chestLoc.getBlockX() + "," + chestLoc.getBlockY() + "," + chestLoc.getBlockZ());
            bob.teleport(chestLoc.clone().add(0.5, 1, 0.5));
            alice.teleport(chestLoc.clone().add(1.5, 1, 0.5));
        }).waitTicks(40).run("chest registration", () -> {
            if (chestLoc == null) return;
            Chest st = (Chest) chestLoc.getBlock().getState(false);
            sc.check("vanilla loot table removed from the physical chest", !st.hasLootTable(), "");
            sc.check("physical chest inventory is empty", st.getBlockInventory().isEmpty(), "");
            var containers = await(api.services().loot.containers(bobGroup.id()));
            sc.check("End City chest registered as personal-loot container", containers.stream().anyMatch(cn -> cn.pos().x() == chestLoc.getBlockX()
                    && cn.pos().y() == chestLoc.getBlockY() && cn.pos().z() == chestLoc.getBlockZ()), containers.size() + " registered");
        }).waitTicks(20).run("bob opens", () -> {
            if (chestLoc == null) return;
            Bukkit.getPluginManager().callEvent(new PlayerInteractEvent(bob, Action.RIGHT_CLICK_BLOCK, null, chestLoc.getBlock(), BlockFace.UP, EquipmentSlot.HAND));
        }).waitTicks(40).run("bob personal inventory", () -> {
            if (chestLoc == null) return;
            Inventory top = bob.getOpenInventory().getTopInventory();
            bobChestPhase1 = snapshot(top);
            sc.check("bob sees a personal inventory (not the block inventory)", !(top.getHolder() instanceof Chest) && top.getSize() == 27
                    && bobChestPhase1.stream().anyMatch(s -> !s.equals("-")), String.join(" ", bobChestPhase1));
            // Take the first item out (simulated player move) and close.
            for (int i = 0; i < top.getSize(); i++) {
                ItemStack it = top.getItem(i);
                if (it != null) {
                    top.setItem(i, null);
                    bob.getInventory().addItem(it);
                    state.setProperty("bobTook", it.getType().name());
                    break;
                }
            }
            bobChestPhase1 = snapshot(top);
            bob.closeInventory();
            Bukkit.getPluginManager().callEvent(new PlayerInteractEvent(alice, Action.RIGHT_CLICK_BLOCK, null, chestLoc.getBlock(), BlockFace.UP, EquipmentSlot.HAND));
        }).waitTicks(40).run("alice personal inventory", () -> {
            if (chestLoc == null) return;
            List<String> a = snapshot(alice.getOpenInventory().getTopInventory());
            sc.check("alice gets her own, different loot from the same chest", !a.equals(bobChestPhase1) && a.stream().anyMatch(s -> !s.equals("-")),
                    String.join(" ", a));
            alice.closeInventory();
            Bukkit.getPluginManager().callEvent(new PlayerInteractEvent(bob, Action.RIGHT_CLICK_BLOCK, null, chestLoc.getBlock(), BlockFace.UP, EquipmentSlot.HAND));
        }).waitTicks(40).run("bob reopen", () -> {
            if (chestLoc == null) return;
            List<String> again = snapshot(bob.getOpenInventory().getTopInventory());
            sc.check("bob's changes persisted and loot was not re-rolled", again.equals(bobChestPhase1), String.join(" ", again));
            state.setProperty("bobChest", String.join(" ", again));
            bob.closeInventory();
            Block below = chestLoc.clone().add(0, -1, 0).getBlock();
            state.setProperty("belowType", below.getType().name());
            below.setType(Material.HOPPER);
            BlockBreakEvent brk = new BlockBreakEvent(chestLoc.getBlock(), bob);
            Bukkit.getPluginManager().callEvent(brk);
            sc.check("breaking a protected End City chest is cancelled", brk.isCancelled(), "");
        }).waitTicks(100).run("hopper result", () -> {
            if (chestLoc == null) return;
            Block below = chestLoc.clone().add(0, -1, 0).getBlock();
            boolean empty = below.getState() instanceof Hopper h && h.getInventory().isEmpty();
            sc.check("hopper below the chest extracts nothing", empty, "");
            below.setType(Material.valueOf(state.getProperty("belowType", "AIR")));
        });

        // --- Renewable shulkers (test config: 36 s cooldown, 5 s checks) ---
        org.bukkit.NamespacedKey pointKey = new org.bukkit.NamespacedKey("endfold", "shulker_point");
        Location[] shulkerPoint = new Location[1];
        sc.run("kill a city shulker", () -> {
            World b = api.instances().worldOf(bobGroup).orElseThrow();
            org.bukkit.entity.Shulker target = null;
            for (org.bukkit.entity.Shulker sh : b.getEntitiesByClass(org.bukkit.entity.Shulker.class)) {
                if (sh.getPersistentDataContainer().has(pointKey)) {
                    target = sh;
                    break;
                }
            }
            if (target == null) {
                sc.skip("shulker respawn", "no tracked End City shulker loaded");
                return;
            }
            shulkerPoint[0] = target.getLocation().toBlockLocation();
            bob.teleport(shulkerPoint[0].clone().add(0, 3, 0));
            bob.setAllowFlight(true);
            bob.setFlying(true);
            target.damage(1000, bob);
        }).waitTicks(40).run("shulker dead", () -> {
            if (shulkerPoint[0] == null) return;
            boolean stillThere = shulkerPoint[0].getWorld().getNearbyEntitiesByType(org.bukkit.entity.Shulker.class, shulkerPoint[0].clone().add(0.5, 0.5, 0.5), 0.9).stream()
                    .anyMatch(e -> !e.isDead());
            sc.check("tracked shulker was killed", !stillThere, "");
        }).waitUntil("shulker respawns at its End City spot after the cooldown", () -> shulkerPoint[0] == null
                || shulkerPoint[0].getWorld().getNearbyEntitiesByType(org.bukkit.entity.Shulker.class, shulkerPoint[0].clone().add(0.5, 0.5, 0.5), 0.9)
                .stream().anyMatch(e -> e.isValid() && e.getPersistentDataContainer().has(pointKey)), 2400)
                .run("shulker respawn result", () -> {
                    if (shulkerPoint[0] != null) sc.check("shulker respawned in place (tagged, same spawn point)", true, fmt(shulkerPoint[0]));
                });

        // --- GUI exploit resistance (real menu, synthetic clicks) ---
        sc.run("open main menu", () -> {
            alice.closeInventory();
            alice.getInventory().setItem(0, new ItemStack(Material.DIRT, 5));
            api.plugin().menus().openMain(alice);
        }).waitTicks(10).run("menu clicks", () -> {
            var view = alice.getOpenInventory();
            Inventory top = view.getTopInventory();
            sc.check("main menu is open as a plugin menu", top.getHolder(false) != null && top.getHolder(false).getClass().getPackageName().equals("dev.endfold.gui"), String.valueOf(top.getHolder(false)));
            List<String> before = snapshot(top);
            int dirtBefore = count(alice, Material.DIRT);
            Object[][] clicks = {
                {org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 13, org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL, -1},
                {org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 13, org.bukkit.event.inventory.ClickType.SHIFT_LEFT, org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY, -1},
                {org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 13, org.bukkit.event.inventory.ClickType.NUMBER_KEY, org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP, 0},
                {org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 13, org.bukkit.event.inventory.ClickType.SWAP_OFFHAND, org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP, 40},
                {org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 13, org.bukkit.event.inventory.ClickType.DOUBLE_CLICK, org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR, -1},
                {org.bukkit.event.inventory.InventoryType.SlotType.QUICKBAR, top.getSize() + 27, org.bukkit.event.inventory.ClickType.SHIFT_LEFT, org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY, -1},
                {org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 13, org.bukkit.event.inventory.ClickType.DROP, org.bukkit.event.inventory.InventoryAction.DROP_ONE_SLOT, -1},
            };
            boolean allCancelled = true;
            for (Object[] c : clicks) {
                var ev = new org.bukkit.event.inventory.InventoryClickEvent(view, (org.bukkit.event.inventory.InventoryType.SlotType) c[0], (int) c[1],
                        (org.bukkit.event.inventory.ClickType) c[2], (org.bukkit.event.inventory.InventoryAction) c[3], (int) c[4]);
                Bukkit.getPluginManager().callEvent(ev);
                if (!ev.isCancelled()) allCancelled = false;
            }
            var drag = new org.bukkit.event.inventory.InventoryDragEvent(view, null, new ItemStack(Material.DIRT), false, java.util.Map.of(10, new ItemStack(Material.DIRT)));
            Bukkit.getPluginManager().callEvent(drag);
            sc.check("every click type on a menu is cancelled (pickup, shift, number key, offhand, double-click, bottom shift, drop)", allCancelled, "");
            sc.check("drag into a menu is cancelled", drag.isCancelled(), "");
            sc.check("menu contents unchanged and player items untouched", snapshot(top).equals(before) && count(alice, Material.DIRT) == dirtBefore, "");
            alice.closeInventory();
        });

        // --- Elytra ---
        sc.run("find elytra frame", () -> elytraSearch(0)).waitTicks(200).run("elytra frame", this::elytraTry);

        // --- Performance sample ---
        List<CompletableFuture<World>> perf = new ArrayList<>();
        long[] perfStart = new long[2];
        sc.run("perf: create and load 5 more EndSpaces", () -> {
            Runtime rt = Runtime.getRuntime();
            perfStart[1] = rt.totalMemory() - rt.freeMemory();
            perfStart[0] = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                UUID owner = UUID.nameUUIDFromBytes(("perf" + i).getBytes());
                EndSpace s = await(api.services().spaces.create(owner, "perf" + i, SpaceType.SOLO, "Perf " + i));
                perf.add(api.instances().ensureLoaded(s));
            }
        }).waitUntil("perf worlds loaded", () -> perf.stream().allMatch(CompletableFuture::isDone), 1200).run("perf result", () -> {
            Runtime rt = Runtime.getRuntime();
            long ms = (System.nanoTime() - perfStart[0]) / 1_000_000;
            long after = rt.totalMemory() - rt.freeMemory();
            boolean ok = perf.stream().noneMatch(CompletableFuture::isCompletedExceptionally);
            sc.check("perf sample: 5 new EndSpace worlds generated (informational)", ok, "total " + ms + "ms (~" + (ms / 5) + "ms each, includes tick waits), heap delta ~"
                    + ((after - perfStart[1]) / 1024 / 1024) + "MB, loaded EndSpace worlds=" + api.instances().lifecycle().loadedCount()
                    + ", TPS=" + String.format("%.1f", Bukkit.getTPS()[0]));
        });

        // --- Unload while empty, reload keeps state ---
        @SuppressWarnings("unchecked")
        CompletableFuture<World>[] reload = new CompletableFuture[1];
        sc.run("unload solo", () -> {
            World w = api.instances().worldOf(aliceSolo).orElseThrow();
            alice.teleport(new Location(ow, 6.5, 200, 6.5));
            Bukkit.getScheduler().runTaskLater(this, () -> {
                boolean ok = api.instances().lifecycle().unload(w, "test");
                sc.check("empty EndSpace world unloads", ok && !api.instances().isLoaded(aliceSolo), "");
            }, 20L);
        }).waitTicks(60).run("reload solo", () -> reload[0] = api.instances().ensureLoaded(aliceSolo))
                .waitUntil("solo reloads", () -> reload[0] != null && reload[0].isDone(), 400)
                .run("reload result", () -> sc.check("reloaded world kept its blocks", reload[0].join().getBlockAt(400, 100, 400).getType() == Material.DIAMOND_BLOCK, ""));

        // --- Logout inside an EndSpace for restore after restart ---
        sc.run("record state", () -> {
            state.setProperty("alice", alice.getUniqueId().toString());
            state.setProperty("bob", bob.getUniqueId().toString());
            state.setProperty("aliceSolo", aliceSolo.id().toString());
            state.setProperty("bobGroup", bobGroup.id().toString());
            state.setProperty("bobElytra", String.valueOf(count(bob, Material.ELYTRA)));
            Location l = new Location(api.instances().worldOf(bobGroup).orElseThrow(), 3.5, 90, 3.5);
            bob.teleport(l);
            bob.setFlying(true);
            state.setProperty("bobLogout", fmt(l));
        }).waitTicks(20).run("bob logs out inside his EndSpace", () -> fakes.remove(bob)).waitTicks(20);
    }

    private void elytraSearch(int attempt) {
        World b = api.instances().worldOf(bobGroup).orElse(null);
        if (b == null) return;
        StructureSearchResult res = b.locateNearestStructure(new Location(b, 1000 + attempt * 700, 64, attempt * 700), Structure.END_CITY, 100, false);
        if (res == null) return;
        state.setProperty("elytraCity" + attempt, fmt(res.getLocation()));
        for (Player p : List.of(alice, bob)) {
            p.teleport(new Location(b, res.getLocation().getX(), 140, res.getLocation().getZ()));
            p.setFlying(true);
        }
    }

    private ItemFrame findElytraFrame(World w) {
        for (ItemFrame f : w.getEntitiesByClass(ItemFrame.class)) {
            if (f.getItem().getType() == Material.ELYTRA && f.getLocation().distance(bob.getLocation()) < 200) return f;
        }
        return null;
    }

    private void elytraTry() {
        World b = bob.getWorld();
        ItemFrame frame = findElytraFrame(b);
        int attempt = 1;
        while (frame == null && attempt < 10) {
            // Load further cities synchronously (test-only); real gameplay loads chunks as players explore.
            StructureSearchResult res = b.locateNearestStructure(new Location(b, (attempt % 2 == 0 ? 1 : -1) * (1000 + attempt * 800), 64, (attempt % 3 == 0 ? 1 : -1) * attempt * 800), Structure.END_CITY, 100, false);
            attempt++;
            if (res == null) continue;
            Location c = res.getLocation();
            for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) b.getChunkAt((c.getBlockX() >> 4) + dx, (c.getBlockZ() >> 4) + dz);
            bob.teleport(new Location(b, c.getX(), 140, c.getZ()));
            alice.teleport(new Location(b, c.getX(), 140, c.getZ()));
            frame = findElytraFrame(b);
        }
        if (frame == null) {
            sc.skip("elytra claims", "no End Ship elytra frame found in the searched cities");
            return;
        }
        ItemFrame f = frame;
        state.setProperty("frame", fmt(f.getLocation()));
        int before = count(bob, Material.ELYTRA);
        Bukkit.getPluginManager().callEvent(new PlayerInteractEntityEvent(bob, f, EquipmentSlot.HAND));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            sc.check("bob claims his own elytra", count(bob, Material.ELYTRA) == before + 1, "had " + before + " now " + count(bob, Material.ELYTRA));
            sc.check("frame still displays the elytra", f.isValid() && f.getItem().getType() == Material.ELYTRA, "");
            Bukkit.getPluginManager().callEvent(new PlayerInteractEntityEvent(bob, f, EquipmentSlot.HAND));
            Bukkit.getPluginManager().callEvent(new PlayerInteractEntityEvent(alice, f, EquipmentSlot.HAND));
            Bukkit.getScheduler().runTaskLater(this, () -> {
                sc.check("repeated claim gives nothing", count(bob, Material.ELYTRA) == before + 1, "now " + count(bob, Material.ELYTRA));
                sc.check("alice independently claims the same ship's elytra", count(alice, Material.ELYTRA) == 1, "");
                sc.check("elytra frame is protected (fixed + invulnerable)", f.isFixed() && f.isInvulnerable(), "");
            }, 40L);
        }, 40L);
    }

    // ------------------------------------------------------------------ phase 2

    private void phase2() {
        loadState();
        UUID aliceId = UUID.fromString(state.getProperty("alice"));
        UUID bobId = UUID.fromString(state.getProperty("bob"));
        UUID soloId = UUID.fromString(state.getProperty("aliceSolo"));
        UUID groupId = UUID.fromString(state.getProperty("bobGroup"));
        sc.run("records after restart", () -> {
            var dir = api.services().dir();
            sc.check("both spaces still exist", dir.space(soloId).isPresent() && dir.space(groupId).isPresent(), "");
            sc.check("alice still belongs to both", dir.isMember(soloId, aliceId) && dir.isMember(groupId, aliceId), "");
            sc.check("bob's selection persisted", groupId.equals(dir.selectedId(bobId).orElse(null)), "");
            sc.check("alice's selection persisted", soloId.equals(dir.selectedId(aliceId).orElse(null)), "");
            sc.check("portal-trip unlock persisted across restart", api.services().profiles.hasFlag(aliceId, dev.endfold.service.ProfileService.FLAG_END_PORTAL_USED)
                    && api.services().profiles.hasFlag(bobId, dev.endfold.service.ProfileService.FLAG_END_PORTAL_USED), "");
            List<Membership> members = dir.members(groupId);
            sc.check("group membership roles persisted", members.size() == 2, members.toString());
            sc.check("worlds are not loaded until needed (lazy)", !api.instances().isLoaded(dir.space(soloId).get()), "");
        });
        @SuppressWarnings("unchecked")
        CompletableFuture<World>[] both = new CompletableFuture[2];
        sc.run("load both", () -> {
            both[0] = api.instances().ensureLoaded(api.services().dir().space(soloId).get());
            both[1] = api.instances().ensureLoaded(api.services().dir().space(groupId).get());
        }).waitUntil("both worlds load", () -> both[0].isDone() && both[1].isDone(), 600).run("loaded checks", () -> {
            World a = both[0].join();
            World b = both[1].join();
            sc.check("solo world survived restart", a.getBlockAt(400, 100, 400).getType() == Material.DIAMOND_BLOCK, "");
            sc.check("group world survived restart", b.getBlockAt(400, 100, 400).getType() == Material.GOLD_BLOCK, "");
            sc.check("group dragon remains defeated, solo dragon untouched", b.getEnderDragonBattle().hasBeenPreviouslyKilled()
                    && !a.getEnderDragonBattle().hasBeenPreviouslyKilled(), "");
            sc.check("gateways persisted", b.getEnderDragonBattle().getGatewayCount() >= 1, "");
            var ret = await(api.services().profiles.findReturn(aliceId, groupId));
            sc.check("per-space return location persisted", ret.isPresent() && ret.get().worldName().equals(ow.getName()), String.valueOf(ret));
        }).run("bob rejoins", () -> {
            bob = fakes.spawn("Bob", ow);
            alice = fakes.spawn("Alice", ow);
        }).waitUntil("bob restored into his EndSpace after restart", () -> api.spaceOf(bob.getWorld()).map(s -> s.id().equals(groupId)).orElse(false), 400)
                .run("bob restore", () -> sc.check("logout position restored", fmt(bob.getLocation()).equals(state.getProperty("bobLogout")),
                        fmt(bob.getLocation()) + " expected " + state.getProperty("bobLogout")))
                .run("loot after restart", () -> {
                    if (!state.containsKey("chest")) {
                        sc.skip("loot after restart", "no chest in phase 1");
                        return;
                    }
                    String[] c = state.getProperty("chest").split(",");
                    Location l = new Location(bob.getWorld(), Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
                    bob.teleport(l.clone().add(0.5, 1, 0.5));
                    bob.setAllowFlight(true);
                    bob.setFlying(true);
                    chestLoc = l;
                }).waitTicks(60).run("bob reopens chest", () -> {
                    if (chestLoc == null) return;
                    Bukkit.getPluginManager().callEvent(new PlayerInteractEvent(bob, Action.RIGHT_CLICK_BLOCK, null, chestLoc.getBlock(), BlockFace.UP, EquipmentSlot.HAND));
                }).waitTicks(40).run("loot persisted", () -> {
                    if (chestLoc == null) return;
                    String now = String.join(" ", snapshot(bob.getOpenInventory().getTopInventory()));
                    sc.check("personal chest contents survived restart without re-roll", now.equals(state.getProperty("bobChest")), now);
                    bob.closeInventory();
                    sc.check("item taken from the chest is still in bob's inventory", !state.containsKey("bobTook")
                            || count(bob, Material.valueOf(state.getProperty("bobTook"))) > 0, "");
                }).run("elytra after restart", () -> {
                    if (!state.containsKey("frame")) {
                        sc.skip("elytra after restart", "no frame in phase 1");
                        return;
                    }
                    int have = count(bob, Material.ELYTRA);
                    sc.check("bob kept exactly the elytra he claimed", String.valueOf(have).equals(state.getProperty("bobElytra")), "have " + have);
                    var claimed = await(api.services().elytra.totalClaims(bobId));
                    sc.check("elytra claim persisted", claimed >= 1, "claims=" + claimed);
                    var pending = await(api.services().elytra.pending(bobId));
                    sc.check("no undelivered elytra left in the outbox", pending.isEmpty(), "pending=" + pending.size());
                });
        // --- Admin backup / restore / delete on the real server ---
        Path[] backupFile = new Path[1];
        sc.run("admin backup", () -> {
            EndSpace solo = api.services().dir().space(soloId).orElseThrow();
            api.plugin().admin().backup(Bukkit.getConsoleSender(), solo, "it").whenComplete((p, t) -> backupFile[0] = p);
        }).waitUntil("backup written", () -> backupFile[0] != null, 600).run("modify after backup", () -> {
            sc.check("backup zip exists", Files.isRegularFile(backupFile[0]), String.valueOf(backupFile[0]));
            World a = api.instances().worldOf(api.services().dir().space(soloId).orElseThrow()).orElseThrow();
            a.getBlockAt(400, 100, 400).setType(Material.EMERALD_BLOCK);
            api.plugin().admin().restore(Bukkit.getConsoleSender(), api.services().dir().space(soloId).orElseThrow(), backupFile[0].getFileName().toString());
        }).waitTicks(200);
        @SuppressWarnings("unchecked")
        CompletableFuture<World>[] restored = new CompletableFuture[1];
        sc.run("reload restored", () -> restored[0] = api.instances().ensureLoaded(api.services().dir().space(soloId).orElseThrow()))
                .waitUntil("restored world loads", () -> restored[0].isDone(), 600)
                .run("restore result", () -> {
                    World a = restored[0].join();
                    sc.check("restore brought back the backed-up blocks", a.getBlockAt(400, 100, 400).getType() == Material.DIAMOND_BLOCK,
                            a.getBlockAt(400, 100, 400).getType().name());
                });
        EndSpace[] victim = new EndSpace[1];
        Path[] victimFolder = new Path[1];
        sc.run("admin delete", () -> {
            victim[0] = api.services().dir().allSpaces().stream().filter(x -> x.name().equals("Perf 0")).findFirst().orElse(null);
            if (victim[0] == null) {
                sc.skip("admin delete", "no perf space");
                return;
            }
            victimFolder[0] = api.instances().lifecycle().files().folderOf(victim[0]);
            sc.check("space to delete has a world folder", Files.isDirectory(victimFolder[0]), victimFolder[0].toString());
            api.plugin().admin().delete(Bukkit.getConsoleSender(), victim[0]);
        }).waitTicks(300).run("delete result", () -> {
            if (victim[0] == null) return;
            sc.check("deleted space removed from the directory", api.services().dir().space(victim[0].id()).isEmpty(), "");
            sc.check("world folder moved out of the level", !Files.exists(victimFolder[0]), "");
            Path trash = getDataFolder().toPath().getParent().resolve("Endfold/trash");
            boolean inTrash;
            try (var list = Files.list(trash)) {
                inTrash = list.anyMatch(f -> f.getFileName().toString().startsWith(victim[0].shortId() + "-"));
            } catch (IOException e) {
                inTrash = false;
            }
            sc.check("world folder kept in plugins/Endfold/trash (never deleted outright)", inTrash, "");
            sc.check("nobody can enter a deleted space", !api.services().dir().canEnter(victim[0].id(), victim[0].ownerUuid()), "");
            sc.check("other spaces untouched by the delete", api.services().dir().space(groupId).isPresent() && api.services().dir().space(soloId).isPresent(), "");
        });
        sc.run("args", () -> getLogger().info("state=" + Arrays.toString(state.stringPropertyNames().toArray())));
    }

    @Override
    public void onDisable() { fakes.removeAll(); }

    @SuppressWarnings("unused")
    private static boolean isFake(Entity e) { return e instanceof Player; }
}
