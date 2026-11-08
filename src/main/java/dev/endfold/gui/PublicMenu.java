package dev.endfold.gui;

import dev.endfold.config.Messages;
import dev.endfold.model.EndSpace;
import dev.endfold.model.JoinMode;
import dev.endfold.model.MembershipRequest;
import dev.endfold.model.SpaceStatus;
import dev.endfold.model.SpaceType;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Browse public group expeditions; filter by join mode and sort by size, age or name. */
final class PublicMenu extends PaginatedMenu<EndSpace> {
    enum Filter { ALL, OPEN, APPROVAL }

    enum Sort { MEMBERS, NEWEST, NAME }

    record View(Filter filter, Sort sort) {}

    private Filter filter;
    private Sort sort;
    private final ProgressLoader progress = new ProgressLoader(this);
    private final Set<UUID> pending = new HashSet<>();

    PublicMenu(MenuManager mgr, Player viewer, Runnable back, View view, int page) {
        super(mgr, viewer, back, "gui.public.empty", page);
        this.filter = view.filter();
        this.sort = view.sort();
    }

    @Override
    protected Component title() { return msg().get("gui.public.title"); }

    @Override
    protected void onOpened() {
        progress.ensure(visible().stream().map(EndSpace::id).toList());
        async(plugin.services().members.requestsOf(viewerId), list -> {
            pending.clear();
            for (MembershipRequest r : list) pending.add(r.spaceId());
        });
    }

    @Override
    protected void pageChanged(List<EndSpace> visible) { progress.ensure(visible.stream().map(EndSpace::id).toList()); }

    @Override
    protected List<EndSpace> entries() {
        Comparator<EndSpace> cmp = switch (sort) {
            case MEMBERS -> Comparator.comparingInt((EndSpace s) -> -plugin.services().dir().memberCount(s.id()));
            case NEWEST -> Comparator.comparingLong((EndSpace s) -> -s.createdAt());
            case NAME -> Comparator.comparing((EndSpace s) -> s.name().toLowerCase(Locale.ROOT));
        };
        return plugin.services().dir().allSpaces().stream()
                .filter(s -> s.status() == SpaceStatus.ACTIVE && s.isPublic() && s.type() == SpaceType.GROUP)
                .filter(s -> filter == Filter.ALL || (filter == Filter.OPEN) == (s.joinMode() == JoinMode.OPEN))
                .sorted(cmp.thenComparing(s -> s.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    @Override
    protected ItemStack icon(EndSpace s) {
        var dir = plugin.services().dir();
        int members = dir.memberCount(s.id());
        String status;
        if (dir.isMember(s.id(), viewerId)) status = "gui.state.exp-member";
        else if (pending.contains(s.id())) status = "gui.state.exp-pending";
        else if (members >= s.maxMembers()) status = "gui.state.exp-full";
        else status = "gui.state.exp-open";
        String desc = s.description() == null ? "" : s.description().strip();
        if (desc.length() > 40) desc = desc.substring(0, 40) + "...";
        Component descLine = desc.isEmpty() ? Component.empty() : msg().get("gui.public.desc-line", Messages.arg("desc", desc));
        return ui.builder(s.joinMode() == JoinMode.OPEN ? "join-open" : "join-approval",
                s.joinMode() == JoinMode.OPEN ? Material.OAK_DOOR : Material.IRON_DOOR, "gui.public.entry",
                Messages.arg("space", s.name()), Messages.arg("owner", ui.ownerName(s)), Messages.arg("members", members),
                Messages.arg("max", s.maxMembers()), Messages.comp("dragon", ui.dragon(progress.get(s.id()))),
                Messages.comp("join", ui.join(s.joinMode())), Messages.arg("created", ui.date(s.createdAt())),
                Messages.comp("status", msg().get(status)), Messages.comp("desc", descLine)).build();
    }

    @Override
    protected void clicked(EndSpace s, Click c) {
        Player p = c.player();
        View v = new View(filter, sort);
        int pg = page;
        new ExpeditionMenu(mgr, p, s.id(), () -> new PublicMenu(mgr, p, back, v, pg).open()).open();
    }

    @Override
    protected void toolbar() {
        Items.Builder fb = ui.builder("filter", Material.HOPPER, "gui.public.filter");
        for (Filter f : Filter.values()) fb.line(option(f == filter, "gui.public.filter-" + f.name().toLowerCase(Locale.ROOT)));
        set(2, fb.build(), c -> {
            filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
            page = 0;
            refresh();
            pageChanged(visible());
        });
        set(4, ui.item("public", Material.COMPASS, "gui.public.header", Messages.arg("count", entries().size())));
        Items.Builder sb = ui.builder("sort", Material.COMPARATOR, "gui.public.sort");
        for (Sort o : Sort.values()) sb.line(option(o == sort, "gui.public.sort-" + o.name().toLowerCase(Locale.ROOT)));
        set(6, sb.build(), c -> {
            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
            page = 0;
            refresh();
            pageChanged(visible());
        });
    }

    private Component option(boolean active, String nameKey) {
        return msg().get(active ? "gui.public.option-active" : "gui.public.option", Messages.comp("name", msg().get(nameKey)));
    }

    @Override
    protected void renderEmpty() {
        super.renderEmpty();
        if (filter != Filter.ALL) {
            set(centerSlot() + 9, ui.item("reset", Material.BARRIER, "gui.public.reset"), c -> {
                filter = Filter.ALL;
                refresh();
            });
        }
    }
}
