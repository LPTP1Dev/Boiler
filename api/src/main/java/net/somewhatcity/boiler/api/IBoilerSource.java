/*
 * Copyright (c) 2023.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.somewhatcity.boiler.api;

import com.google.gson.JsonObject;
import de.pianoman911.mapengine.api.clientside.IDisplay;
import de.pianoman911.mapengine.api.clientside.IMapDisplay;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.CommandArgumentType;
import net.somewhatcity.boiler.api.util.GraphicUtils;
import net.somewhatcity.boiler.api.util.Key;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

public interface IBoilerSource {
    /**
     * called when the source gets loaded by a display
     * @param display
     * @param data
     */
    void load(IBoilerDisplay display, JsonObject data);

    /**
     * called when the source gets unloaded by a display
     */
    void unload();
    default void draw(IDrawingSpace drawingSpace) {
        drawingSpace.clear();
        IDisplay display = drawingSpace.ctx().getDisplay();
        BufferedImage img = new BufferedImage(display.pixelWidth(), display.pixelHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            draw(g, new Rectangle(0, 0, display.pixelWidth(), display.pixelHeight()));
        } finally {
            g.dispose();
        }
        drawingSpace.image(img, 0, 0);
    }

    /**
     * called on rendering. Here you can draw things that will show up on the display
     * @param g2
     * @param viewport
     */
    default void draw(Graphics2D g2, Rectangle viewport) {
        g2.setColor(Color.BLACK);
        g2.fill(viewport);
        g2.setColor(Color.WHITE);
        GraphicUtils.centeredString(g2, viewport, "Drawing not implemented!");
    }
    default byte[] provide20msAudio() {
        return null;
    }
    default void onResize() {}

    /**
     * called when someone clicks the display
     * @param sender the sender that clicked the display
     * @param x X position of the cursor
     * @param y Y position of the cursor
     * @param right if the click was a right click
     */
    default void onClick(CommandSender sender, int x, int y, boolean right) {}

    /**
     * Client-mod mouse gesture in display pixels. START is button-down, MOVE is
     * movement while held, END is button-up (also on capture loss/disconnect).
     * Override this to handle held buttons; no separate onClick is sent for it.
     * The default preserves existing sources by treating START as one click.
     */
    default void onDrag(CommandSender sender, int x, int y, boolean right,
                        net.somewhatcity.boiler.api.util.DragPhase phase) {
        if (phase == net.somewhatcity.boiler.api.util.DragPhase.START) onClick(sender, x, y, right);
    }

    /**
     * called when someone scrolls while looking at the display
     * @param sender the sender that scrolled
     * @param x X position of the cursor
     * @param y Y position of the cursor
     * @param delta the amount of scroll
     */
    default void onScroll(CommandSender sender, int x, int y, int delta) {}

    /**
     * Called for ordinary app key input, including keyboard and visible remote buttons.
     * TV actions such as Home arrive separately through onRemoteAction.
     * @param sender
     * @param key
     */
    default void onKey(CommandSender sender, String key) {}

    /** A non-blocking snapshot; unsupported TV operations must not be advertised. */
    default Set<net.somewhatcity.boiler.api.util.RemoteAction> remoteActions() { return Set.of(); }
    default void onRemoteAction(CommandSender sender, net.somewhatcity.boiler.api.util.RemoteAction action) {}

    /**
     * called when someone inputs a string
     * @param sender the sender that inputed the string
     * @param input the input string
     */
    default void onInput(CommandSender sender, String input) {}
    default void onCursorMove(CommandSender sender, int x, int y) {}

    default Map<String, CommandArgumentType> command() {
        return Collections.emptyMap();
    }
}
