/*
 * Copyright (c) 2023.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.somewhatcity.boiler.core.sources;

import com.google.gson.JsonObject;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import net.somewhatcity.boiler.api.CreateArgument;
import net.somewhatcity.boiler.api.CreateCommandArguments;
import net.somewhatcity.boiler.api.IBoilerSource;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.CommandArgumentType;
import net.somewhatcity.boiler.core.BoilerPlugin;
import org.bukkit.Bukkit;

@CreateCommandArguments(arguments = {
        @CreateArgument(name = "id", type = CommandArgumentType.INTEGER)
})
public class CloneSource implements IBoilerSource, CancellableLoad {

    private volatile boolean stopped;
    private volatile boolean attached;
    private IBoilerDisplay display;
    private IBoilerDisplay toClone;
    private final net.somewhatcity.boiler.core.display.RasterRenderer overlayRaster = new net.somewhatcity.boiler.core.display.RasterRenderer();
    private volatile boolean overlay;
    private final IBoilerSource overlayPainter = new IBoilerSource() {
        @Override public void load(IBoilerDisplay display,JsonObject data) {}
        @Override public void unload() {}
        @Override public void draw(java.awt.Graphics2D graphics,java.awt.Rectangle viewport) { CloneSource.this.draw(graphics,viewport); }
    };
    @Override
    public void load(IBoilerDisplay display, JsonObject data) {
        this.display = display;
        int cloneId = data.get("id").getAsInt();
        BoilerPlugin.getPlugin().callOnMain(() -> {
            if (stopped) return null;
            toClone = BoilerPlugin.getPlugin().displayManager().display(cloneId);
            if (toClone == null || toClone.id() == display.id() || display.width() != toClone.width() || display.height() != toClone.height()) {
                JsonObject error = new JsonObject();
                error.addProperty("message", "Clone target must exist and have the same dimensions");
                display.source("error", error);
                return null;
            }
            display.renderPaused(true);
            display.mapDisplay().cloneGroupIds(toClone.mapDisplay());
            attached = true;
            Bukkit.getOnlinePlayers().forEach(player -> display.mapDisplay().mapId(player, 0));
            return null;
        });
    }

    @Override public void unload() {
        stopped = true;
        overlayRaster.clear();
        if (!attached || display == null) return;
        Runnable detach = () -> {
            if (attached) {
                display.mapDisplay().cutOffCloneGroupIds();
                display.renderPaused(false);
                Bukkit.getOnlinePlayers().forEach(player -> display.mapDisplay().mapId(player, 0));
                attached = false;
            }
        };
        if (Bukkit.isPrimaryThread()) detach.run();
        else BoilerPlugin.getPlugin().callOnMain(() -> { detach.run(); return null; });
    }

    @Override public void cancelLoad() { unload(); }

    @Override
    public void draw(IDrawingSpace drawingSpace) {
        if (overlay) drawingSpace.image(overlayRaster.drawImage(overlayPainter,display.width(),display.height()),0,0);
    }

    public void overlay(boolean enabled) {
        if (stopped || overlay == enabled || toClone == null) return;
        overlay = enabled;
        if (enabled && attached) { display.mapDisplay().cutOffCloneGroupIds(); attached = false; }
        else if (!enabled && !attached) { display.mapDisplay().cloneGroupIds(toClone.mapDisplay()); attached = true; }
        Bukkit.getOnlinePlayers().forEach(player -> display.mapDisplay().mapId(player,0));
        display.respawn();
    }

    @Override public void draw(java.awt.Graphics2D graphics,java.awt.Rectangle viewport) {
        if (toClone == null) return;
        if (toClone instanceof net.somewhatcity.boiler.core.display.ImplBoilerDisplay target)
            target.drawSection(graphics,0,0,display.width(),display.height());
        else if (toClone.source() != null) toClone.source().draw(graphics,viewport);
    }

}
