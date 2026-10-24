/*
 * Copyright (c) 2023.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.somewhatcity.boiler.core.listener;

import de.pianoman911.mapengine.api.event.MapClickEvent;
import de.pianoman911.mapengine.api.util.MapClickType;
import de.pianoman911.mapengine.api.util.MapTraceResult;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.core.BoilerPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;

import static net.somewhatcity.boiler.core.BoilerPlugin.MAP_ENGINE;

public class BoilerListener implements Listener {
    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent e) {
        // Fast scrolling can skip slots, including across the hotbar boundary.
        // Interpret the nine slots as a ring rather than comparing slot numbers.
        int delta = e.getNewSlot() - e.getPreviousSlot();
        if (delta > 4) delta -= 9;
        else if (delta < -4) delta += 9;
        if (delta == 0) return;

        MapTraceResult result = MAP_ENGINE.traceDisplayInView(e.getPlayer(), 10);
        if(result == null) return;
        IBoilerDisplay display = BoilerPlugin.getPlugin().displayManager().display(result.display());
        if(display == null) return;

        // Existing sources consume one +/-1 step per call. Preserve skipped steps
        // without changing their behavior, and trace the aimed display only once.
        int direction = Integer.signum(delta);
        for (int step = 0; step < Math.abs(delta); step++) {
            display.onScroll(e.getPlayer(), result.viewPos().x(), result.viewPos().y(), direction);
        }

    }

    @EventHandler
    public void onMapClick(MapClickEvent e) {
        IBoilerDisplay display = BoilerPlugin.getPlugin().displayManager().display(e.display());
        if(display != null) display.onClick(e.player(), e.x(), e.y(), e.clickType().equals(MapClickType.RIGHT_CLICK));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if(!e.getPlayer().getWorld().getName().equals("boilerui")) return;
        e.setCancelled(true);
    }

}
