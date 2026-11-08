package dev.endfold.gui;

import dev.endfold.model.SpaceProgress;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Lazily fetches dragon progression for the spaces visible in a menu and re-renders it as results arrive. */
final class ProgressLoader {
    private final Menu menu;
    private final Map<UUID, SpaceProgress> cache = new HashMap<>();
    private final Set<UUID> requested = new HashSet<>();

    ProgressLoader(Menu menu) { this.menu = menu; }

    SpaceProgress get(UUID spaceId) { return cache.get(spaceId); }

    void ensure(Collection<UUID> ids) {
        for (UUID id : ids) {
            if (!requested.add(id)) continue;
            menu.async(menu.plugin.services().progress.space(id), p -> cache.put(id, p));
        }
    }
}
