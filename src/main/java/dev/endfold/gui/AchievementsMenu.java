package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.Achievement;
import dev.endfold.model.EndSpace;
import dev.endfold.model.PlayerProgress;
import dev.endfold.service.AchievementType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The viewer's individual achievements and lifetime statistics (shared progress lives in each space's menu). */
final class AchievementsMenu extends PaginatedMenu<AchievementType> {
    private Map<String, Achievement> granted = Map.of();
    private List<PlayerProgress> stats = List.of();
    private boolean statsLoaded;

    AchievementsMenu(MenuManager mgr, Player viewer, Runnable back, int page) {
        super(mgr, viewer, back, "gui.achievements.empty", page);
        this.loading = true;
    }

    @Override
    protected Component title() { return msg().get("gui.achievements.title"); }

    @Override
    protected void onOpened() {
        load(plugin.services().progress.achievements(viewerId), list -> {
            Map<String, Achievement> m = new HashMap<>();
            for (Achievement a : list) m.put(a.key(), a);
            granted = m;
        });
        async(plugin.services().progress.playerAll(viewerId), list -> {
            stats = list;
            statsLoaded = true;
        });
    }

    @Override
    protected void reload() { onOpened(); }

    @Override
    protected List<AchievementType> entries() {
        return Arrays.stream(AchievementType.values()).filter(plugin.config().enabledAchievements::contains).toList();
    }

    @Override
    protected ItemStack icon(AchievementType t) {
        Achievement a = granted.get(t.key());
        Component name = Component.text(mgr.achievementName(t));
        Component hint = Component.text(mgr.achievementHint(t));
        if (a == null) {
            return ui.item("ach-locked", Material.GRAY_DYE, "gui.achievements.locked", Messages.comp("name", name), Messages.comp("hint", hint));
        }
        String where = plugin.services().dir().space(a.spaceId()).map(EndSpace::name).orElse(msg().raw("gui.achievements.unknown-space"));
        return ui.builder("ach-" + t.key(), Material.ENDER_PEARL, "gui.achievements.unlocked", Messages.comp("name", name), Messages.comp("hint", hint),
                Messages.arg("date", ui.date(a.grantedAt())), Messages.arg("space", where)).glow(true).build();
    }

    @Override
    protected void clicked(AchievementType t, Click c) {}

    @Override
    protected void toolbar() {
        int total = entries().size();
        long unlocked = entries().stream().filter(t -> granted.containsKey(t.key())).count();
        set(4, ui.item("dragon", Material.DRAGON_HEAD, "gui.achievements.header", Messages.arg("unlocked", unlocked), Messages.arg("total", total)));
        if (statsLoaded) {
            set(2, ui.item("stats", Material.BOOK, "gui.achievements.stats",
                    Messages.arg("participations", stats.stream().mapToInt(PlayerProgress::dragonParticipations).sum()),
                    Messages.arg("kills", stats.stream().mapToInt(PlayerProgress::dragonKills).sum()),
                    Messages.arg("chests", stats.stream().mapToInt(PlayerProgress::chestsLooted).sum()),
                    Messages.arg("elytra", stats.stream().mapToInt(PlayerProgress::elytraClaimed).sum()),
                    Messages.arg("shulkers", stats.stream().mapToInt(PlayerProgress::shulkersKilled).sum()),
                    Messages.arg("worlds", stats.size())));
        } else {
            set(2, ui.item("loading", Material.CLOCK, "gui.common.loading"));
        }
        set(6, ui.item("shared", Material.END_PORTAL_FRAME, "gui.achievements.shared-note"));
    }
}
