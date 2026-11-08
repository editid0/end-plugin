package dev.endfold.testkit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Server-side fake players for integration tests: real {@code ServerPlayer} instances joined through
 * {@code PlayerList#placeNewPlayer} with an in-memory netty {@code EmbeddedChannel}. Uses reflection against the
 * Mojang-mapped server (Paper 1.20.5+), so the testkit has no compile-time dependency on server internals.
 *
 * <p>Fake players are ticked manually ({@code ServerPlayer#doTick}) because no client sends movement packets. Real
 * portal blocks, dragon fights, chunk loading and Bukkit events all behave as for real players; client-only behaviour
 * (rendering, client-side prediction, GUI clicks) is not covered.</p>
 */
public final class FakePlayers {
    private final List<Object[]> players = new ArrayList<>(); // [Player, ServerPlayer, EmbeddedChannel]

    private static Class<?> cls(String n) throws ClassNotFoundException { return Class.forName(n, true, Bukkit.getServer().getClass().getClassLoader()); }

    public Player spawn(String name, World world) {
        try {
            Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Object profile = cls("com.mojang.authlib.GameProfile").getConstructor(UUID.class, String.class).newInstance(id, name);
            Object info = cls("net.minecraft.server.level.ClientInformation").getMethod("createDefault").invoke(null);
            Class<?> sp = cls("net.minecraft.server.level.ServerPlayer");
            Constructor<?> spc = null;
            for (Constructor<?> c : sp.getConstructors()) if (c.getParameterCount() == 4) spc = c;
            Object player = spc.newInstance(server, level, profile, info);

            Class<?> flow = cls("net.minecraft.network.protocol.PacketFlow");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object serverbound = Enum.valueOf((Class) flow, "SERVERBOUND");
            Class<?> connCls = cls("net.minecraft.network.Connection");
            Object conn = connCls.getConstructor(flow).newInstance(serverbound);
            Object channel = cls("io.netty.channel.embedded.EmbeddedChannel").getConstructor().newInstance();
            Field ch = connCls.getField("channel");
            ch.set(conn, channel);

            Object cookie = cls("net.minecraft.server.network.CommonListenerCookie").getMethod("createInitial", profile.getClass(), boolean.class)
                    .invoke(null, profile, false);
            Object playerList = server.getClass().getMethod("getPlayerList").invoke(server);
            Method place = null;
            for (Method m : playerList.getClass().getMethods()) if (m.getName().equals("placeNewPlayer") && m.getParameterCount() == 3) place = m;
            place.invoke(playerList, conn, player, cookie);
            Player bukkit = (Player) sp.getMethod("getBukkitEntity").invoke(player);
            // A real login reads <uuid>.dat during the configuration phase, which this shortcut skips. Load the saved
            // inventory explicitly so restarts behave like a real reconnect (location is handled by Endfold itself).
            java.nio.file.Path levelDir = dev.endfold.world.WorldRepository.levelFolderOf(Bukkit.getWorlds().getFirst().getWorldPath());
            if (java.nio.file.Files.exists(levelDir.resolve("players/data/" + id + ".dat"))) {
                org.bukkit.Location keep = bukkit.getLocation();
                bukkit.loadData();
                Bukkit.getLogger().info("[Testkit] loaded saved data for " + name + ": " + bukkit.getInventory().all(org.bukkit.Material.ELYTRA).size() + " elytra stacks");
                if (!bukkit.getWorld().equals(keep.getWorld())) bukkit.teleport(keep);
            }
            players.add(new Object[] {bukkit, player, channel});
            return bukkit;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not create fake player " + name, e);
        }
    }

    /** Ticks every fake player once and drains their outbound packet queues. Call every server tick. */
    public void tick() {
        for (Object[] p : List.copyOf(players)) {
            Player b = (Player) p[0];
            if (!b.isOnline()) continue;
            try {
                p[1].getClass().getMethod("doTick").invoke(p[1]);
                Object ch = p[2];
                ch.getClass().getMethod("runPendingTasks").invoke(ch);
                ch.getClass().getMethod("releaseOutbound").invoke(ch);
            } catch (ReflectiveOperationException e) {
                Throwable c = e.getCause() == null ? e : e.getCause();
                Bukkit.getLogger().warning("[Testkit] fake tick failed for " + b.getName() + ": " + c);
            }
        }
    }

    public void remove(Player p) {
        for (Object[] o : List.copyOf(players)) {
            if (o[0] != p) continue;
            try {
                Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
                Object playerList = server.getClass().getMethod("getPlayerList").invoke(server);
                for (Method m : playerList.getClass().getMethods()) {
                    if (m.getName().equals("remove") && m.getParameterCount() == 1) {
                        m.invoke(playerList, o[1]);
                        break;
                    }
                }
            } catch (ReflectiveOperationException e) {
                Bukkit.getLogger().warning("[Testkit] remove failed: " + e);
            }
            players.remove(o);
        }
    }

    public void removeAll() {
        for (Object[] o : List.copyOf(players)) remove((Player) o[0]);
    }
}
