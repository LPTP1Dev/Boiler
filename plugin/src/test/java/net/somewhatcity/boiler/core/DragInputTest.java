package net.somewhatcity.boiler.core;

import net.somewhatcity.boiler.api.IBoilerSource;
import net.somewhatcity.boiler.api.util.DragPhase;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DragInputTest {
    @Test void existingSourcesReceiveExactlyOneClickPerGesture() {
        int[] clicks={0};
        IBoilerSource source=new IBoilerSource() {
            public void load(net.somewhatcity.boiler.api.display.IBoilerDisplay display,com.google.gson.JsonObject data) {}
            public void unload() {}
            public void onClick(org.bukkit.command.CommandSender sender,int x,int y,boolean right) {
                assertEquals(10,x);assertEquals(20,y);assertTrue(right);clicks[0]++;
            }
        };
        for(DragPhase phase:DragPhase.values())source.onDrag(null,10,20,true,phase);
        assertEquals(1,clicks[0]);
    }
}
