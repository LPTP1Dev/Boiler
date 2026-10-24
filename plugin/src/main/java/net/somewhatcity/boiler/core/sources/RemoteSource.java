package net.somewhatcity.boiler.core.sources;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.*;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.CommandArgumentType;
import net.somewhatcity.boiler.core.BoilerPlugin;
import net.somewhatcity.boiler.core.Util;
import net.somewhatcity.boiler.core.remote.RemotePanel;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.awt.*;
import java.util.Set;

@CreateCommandArguments(arguments = {@CreateArgument(name = "link", type = CommandArgumentType.INTEGER)})
public final class RemoteSource implements IBoilerSource, CancellableLoad {
    private final RemotePanel panel = new RemotePanel();
    private volatile RemotePanel.View view = new RemotePanel.View("Boiler Remote", "Ziel nicht bereit", false, Set.of());
    private BoilerPlugin plugin;
    private int linkedId;
    private volatile boolean stopped;
    private volatile BukkitTask refresh;
    private volatile int page;
    private volatile String pressed = "";
    private volatile long pressedUntil;

    @Override public void load(IBoilerDisplay display, JsonObject data) {
        width = display.width(); height = display.height();
        linkedId = data.get("link").getAsInt();
        if (display.id() == linkedId) throw new IllegalArgumentException("Eine Fernbedienung kann nicht sich selbst steuern");
        plugin = BoilerPlugin.getPlugin();
        BukkitTask task = plugin.callOnMain(() -> plugin.getServer().getScheduler().runTaskTimer(plugin, this::update, 1, 5));
        refresh = task;
        if (stopped) task.cancel();
    }
    private IBoilerDisplay target() { return plugin.displayManager().display(linkedId); }
    private void update() {
        if (stopped) return;
        IBoilerDisplay target = target();
        boolean ready = target != null && target.source() != null && !(target.source() instanceof RemoteSource);
        view = new RemotePanel.View("TV " + linkedId + (ready ? " · Remote" : " · nicht bereit"), ready ? "Verbunden · Klicken zum Steuern" : "Ziel nicht bereit", ready, ready ? target.remoteActions() : Set.of());
    }
    @Override public void onClick(CommandSender sender, int x, int y, boolean right) {
        if (stopped || !sender.hasPermission("boiler.command.action")) return;
        if (!RemotePanel.full(width, height)) for (int index = 0; index < 4; index++) if (RemotePanel.tab(width, index).contains(x, y)) { page = index; return; }
        update();
        for (RemotePanel.Button button : RemotePanel.buttons(width, height, page)) if (button.bounds().contains(x, y)) {
            pressed = button.id(); pressedUntil = System.nanoTime() + 200_000_000;
            if (!view.enabled(button)) { Util.sendErrMsg(sender, view.ready() ? button.label() + ": vom Ziel gerade nicht unterstützt." : "Das Ziel-Display ist noch nicht bereit."); return; }
            IBoilerDisplay target = target();
            if (target == null) return;
            if (button.action() != null) target.onRemoteAction(sender, button.action());
            else target.onKey(sender, button.key());
            update(); return;
        }
    }
    private volatile int width = 128, height = 128;
    @Override public void draw(Graphics2D graphics, Rectangle viewport) {
        panel.draw(graphics, viewport, view, page, System.nanoTime() < pressedUntil ? pressed : "");
    }
    @Override public void unload() { stopped = true; if (refresh != null) refresh.cancel(); panel.clear(); }
    @Override public void cancelLoad() { stopped = true; if (refresh != null) refresh.cancel(); }
}
