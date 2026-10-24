package net.somewhatcity.boiler.core.smarttv;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.somewhatcity.boiler.core.BoilerPlugin;
import net.somewhatcity.boiler.core.Util;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Player-bound dialog forms. Vanilla commands keep this compatible with Paper 1.21.6. */
public final class SmartTvInputs implements Listener, AutoCloseable {
    private static final long TIMEOUT = java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
    private static final class Pending {
        final Object owner;
        final Player player;
        final Consumer<String> input;
        final Runnable cancel;
        long deadline = System.nanoTime() + TIMEOUT;
        String token;
        boolean visible;
        Pending(Object owner, Player player, Consumer<String> input, Runnable cancel) {
            this.owner = owner; this.player = player; this.input = input; this.cancel = cancel;
        }
    }
    private final BoilerPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final BukkitTask timer;

    public SmartTvInputs(BoilerPlugin plugin) {
        this.plugin = plugin;
        timer = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> expire(System.nanoTime()), 20, 20);
    }

    public boolean request(Object owner, Player player, Consumer<String> input, Runnable cancel) {
        return pending.putIfAbsent(player.getUniqueId(), new Pending(owner, player, input, cancel)) == null;
    }

    /** A new nonce is issued for each field/retry, even when its owning session stays the same. */
    public void show(Object owner, Player player, String title, String label, String initial, String error) {
        Pending capture = pending.get(player.getUniqueId());
        if (capture == null || capture.owner != owner) return;
        capture.token = UUID.randomUUID().toString();
        capture.deadline = System.nanoTime() + TIMEOUT;
        String definition = dialogDefinition(title, label, initial, error, capture.token).toString();
        try {
            if (!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "minecraft:dialog show " + playerTarget(player) + " " + definition))
                throw new IllegalStateException("Dialog command failed");
            capture.visible = true;
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Opening SmartTV dialog", e);
            cancelPlayer(player);
            Util.sendErrMsg(player, "Der Eingabedialog konnte nicht geöffnet werden.");
        }
    }

    static JsonObject dialogDefinition(String title, String label, String initial, String error, String token) {
        JsonObject dialog = new JsonObject();
        dialog.addProperty("type", "minecraft:confirmation");
        dialog.addProperty("title", title);
        dialog.addProperty("pause", false);
        dialog.addProperty("can_close_with_escape", true);
        dialog.addProperty("after_action", "wait_for_response");
        JsonArray body = new JsonArray();
        JsonObject description = new JsonObject(); description.addProperty("type", "minecraft:plain_message");
        description.addProperty("contents", error.isBlank() ? "Nur du kannst diese Eingabe bestätigen. Escape oder Abbrechen schließt den Dialog." : error);
        body.add(description); dialog.add("body", body);
        JsonObject input = new JsonObject(); input.addProperty("type", "minecraft:text"); input.addProperty("key", "value");
        input.addProperty("label", label); input.addProperty("initial", initial); input.addProperty("max_length", 4096); input.addProperty("width", 300);
        JsonArray fields = new JsonArray(); fields.add(input); dialog.add("inputs", fields);
        dialog.add("yes", button("Übernehmen", "minecraft:dynamic/run_command", "template", "boiler dialog " + token + " submit \"$(value)\""));
        dialog.add("no", button("Abbrechen", "minecraft:run_command", "command", "boiler dialog " + token + " cancel"));
        return dialog;
    }
    private static JsonObject button(String label, String type, String key, String command) {
        JsonObject button = new JsonObject(); button.addProperty("label", label); button.addProperty("width", 150);
        JsonObject action = new JsonObject(); action.addProperty("type", type); action.addProperty(key, command); button.add("action", action);
        return button;
    }

    /** Executed by a dialog button as that player; callbacks still pass through the server thread. */
    public void respond(Player player, String token, String value, boolean cancelled) {
        Pending capture = pending.get(player.getUniqueId());
        if (capture == null || !token.equals(capture.token)) return;
        plugin.onMain(() -> {
            if (pending.get(player.getUniqueId()) != capture || !token.equals(capture.token)) return;
            if (!player.isOnline() || System.nanoTime() >= capture.deadline || cancelled) { cancelPlayer(player); return; }
            closeDialog(capture);
            capture.deadline = System.nanoTime() + TIMEOUT;
            capture.input.accept(value);
        });
    }

    private void closeDialog(Pending capture) {
        capture.token = null;
        if (!capture.visible) return;
        capture.visible = false;
        if (!capture.player.isOnline()) return;
        try {
            if (!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "minecraft:dialog clear " + playerTarget(capture.player)))
                throw new IllegalStateException("Dialog clear command failed");
        } catch (RuntimeException e) {
            // Releasing ownership must succeed even if the client dialog could not be cleared.
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Closing SmartTV dialog", e);
        }
    }

    static String playerTarget(Player player) {
        // A bare UUID may be parsed as an entity target by newer player-only command arguments.
        return "@a[name=" + new com.google.gson.JsonPrimitive(player.getName()) + ",limit=1]";
    }

    public void cancelPlayer(Player player) {
        Pending capture = pending.remove(player.getUniqueId());
        if (capture != null) { closeDialog(capture); capture.cancel.run(); }
    }

    public void release(Object owner) {
        for (Pending capture : pending.values()) if (capture.owner == owner && pending.remove(capture.player.getUniqueId(), capture)) closeDialog(capture);
    }

    public void touch(Object owner, Player player) {
        Pending capture = pending.get(player.getUniqueId());
        if (capture != null && capture.owner == owner) capture.deadline = System.nanoTime() + TIMEOUT;
    }

    void expire(long now) {
        for (Pending capture : pending.values()) if (now >= capture.deadline) {
            if (!pending.remove(capture.player.getUniqueId(), capture)) continue;
            closeDialog(capture);
            capture.cancel.run();
            Util.sendMsg(capture.player, "SmartTV-Eingabe wegen Zeitüberschreitung abgebrochen.");
        }
    }

    @EventHandler public void quit(PlayerQuitEvent event) { cancelPlayer(event.getPlayer()); }
    @EventHandler public void death(PlayerDeathEvent event) { cancelPlayer(event.getEntity()); }
    @EventHandler(ignoreCancelled = true) public void teleport(PlayerTeleportEvent event) { cancelPlayer(event.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void inventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) cancelPlayer(player);
    }
    @EventHandler(ignoreCancelled = true) public void inventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) cancelPlayer(player);
    }

    @Override public void close() {
        timer.cancel();
        for (Pending capture : pending.values()) cancelPlayer(capture.player);
        pending.clear();
        HandlerList.unregisterAll(this);
    }
}
