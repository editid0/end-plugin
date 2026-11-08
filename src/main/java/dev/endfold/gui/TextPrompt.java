package dev.endfold.gui;

import dev.endfold.config.EndfoldConfig;
import dev.endfold.config.Messages;
import dev.endfold.util.Sync;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

/**
 * Text input for menus. Uses the Paper dialog API (a real input window) and falls back to a chat prompt when the
 * dialog cannot be shown or the configuration asks for chat.
 */
public final class TextPrompt implements Listener {
    private static final long CHAT_TIMEOUT_TICKS = 20L * 60;

    /**
     * @param validator returns an error message for invalid text, or {@code null} when the text is acceptable
     * @param onSubmit  called on the server thread with trimmed, validated text
     * @param onCancel  called on the server thread when the player cancels or the prompt times out
     */
    record Request(Component title, Component hint, String initial, int maxLength, Function<String, Component> validator,
                   Consumer<String> onSubmit, Runnable onCancel) {}

    private record Chat(Request request, BukkitTask timeout) {}

    private final MenuManager mgr;
    private final Map<UUID, Chat> chats = new ConcurrentHashMap<>();
    private boolean dialogBroken;

    TextPrompt(MenuManager mgr) { this.mgr = mgr; }

    void ask(Player p, Request r) {
        cancelChat(p.getUniqueId());
        p.closeInventory();
        EndfoldConfig cfg = mgr.plugin().config();
        if (cfg.textInput == EndfoldConfig.TextInput.DIALOG && !dialogBroken) {
            try {
                dialog(p, r, r.initial());
                return;
            } catch (RuntimeException | LinkageError e) {
                dialogBroken = true;
                mgr.plugin().getLogger().warning("Dialog text input unavailable, falling back to chat prompts: " + e);
            }
        }
        chat(p, r);
    }

    void cancelAll() {
        for (UUID id : List.copyOf(chats.keySet())) cancelChat(id);
    }

    void cancelChat(UUID id) {
        Chat c = chats.remove(id);
        if (c != null) c.timeout().cancel();
    }

    // ------------------------------------------------------------------ dialog

    private void dialog(Player p, Request r, String initial) {
        Messages m = mgr.plugin().messages();
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();
        ActionButton ok = ActionButton.builder(m.get("gui.prompt.dialog-ok"))
                .action(DialogAction.customClick((view, audience) -> {
                    String text = view.getText("value");
                    Sync.run(() -> submit(p, r, text == null ? "" : text));
                }, once)).build();
        ActionButton cancel = ActionButton.builder(m.get("gui.prompt.dialog-cancel"))
                .action(DialogAction.customClick((view, audience) -> Sync.run(r.onCancel()), once)).build();
        Dialog d = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(r.title())
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(r.hint())))
                        .inputs(List.of(DialogInput.text("value", m.get("gui.prompt.dialog-label"))
                                .width(250)
                                .initial(initial == null ? "" : initial)
                                .maxLength(Math.max(1, r.maxLength()))
                                .build()))
                        .build())
                .type(DialogType.confirmation(ok, cancel)));
        p.showDialog(d);
    }

    private void submit(Player p, Request r, String raw) {
        if (!p.isOnline()) return;
        String text = raw.trim();
        Component error = r.validator().apply(text);
        if (error != null) {
            mgr.plugin().messages().sendRaw(p, error);
            try {
                dialog(p, r, text);
            } catch (RuntimeException e) {
                chat(p, r);
            }
            return;
        }
        r.onSubmit().accept(text);
    }

    // ------------------------------------------------------------------ chat fallback

    private void chat(Player p, Request r) {
        Messages m = mgr.plugin().messages();
        m.sendRaw(p, r.title());
        m.sendRaw(p, r.hint());
        m.send(p, "gui.prompt.chat", Messages.arg("seconds", CHAT_TIMEOUT_TICKS / 20));
        UUID id = p.getUniqueId();
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(mgr.plugin(), () -> {
            Chat c = chats.get(id);
            if (c == null || c.request() != r) return;
            chats.remove(id);
            Player pl = Bukkit.getPlayer(id);
            if (pl != null) mgr.plugin().messages().send(pl, "gui.prompt.timeout");
            r.onCancel().run();
        }, CHAT_TIMEOUT_TICKS);
        chats.put(id, new Chat(r, timeout));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!chats.containsKey(p.getUniqueId())) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Sync.run(() -> handleChat(p, text));
    }

    private void handleChat(Player p, String text) {
        Chat c = chats.get(p.getUniqueId());
        if (c == null) return;
        Messages m = mgr.plugin().messages();
        if (text.equalsIgnoreCase(m.raw("gui.prompt.cancel-word"))) {
            cancelChat(p.getUniqueId());
            m.send(p, "gui.prompt.cancelled");
            c.request().onCancel().run();
            return;
        }
        Component error = c.request().validator().apply(text);
        if (error != null) {
            m.sendRaw(p, error);
            return;
        }
        cancelChat(p.getUniqueId());
        c.request().onSubmit().accept(text);
    }
}
