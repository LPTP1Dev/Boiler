package net.somewhatcity.boiler.core;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.IBoilerSource;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.remote.RemotePanel;
import net.somewhatcity.boiler.core.sources.RemoteSource;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteSourceTest {
    @Test void everyLayoutHasDistinctCompleteClickableButtonsAndRendersPreviews() throws Exception {
        Path directory = Path.of("build/reports/remote-preview"); Files.createDirectories(directory);
        for (int[] size : new int[][]{{128,128}, {128,256}, {128,384}, {384,128}, {256,768}, {768,256}}) {
            int width = size[0], height = size[1];
            Set<String> ids = new HashSet<>();
            for (int page = 0; page < (RemotePanel.full(width,height) ? 1 : 4); page++) {
                var buttons = RemotePanel.buttons(width,height,page);
                for (var button : buttons) {
                    assertTrue(ids.add(button.id()), button.id());
                    assertTrue(new Rectangle(0,0,width,height).contains(button.bounds()), button.toString());
                    assertTrue(button.bounds().width > 0 && button.bounds().height > 0);
                    for (var other : buttons) if (other != button) assertFalse(button.bounds().intersects(other.bounds()), button.id() + " overlaps " + other.id());
                }
                BufferedImage image = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                try { new RemotePanel().draw(g,new Rectangle(0,0,width,height), new RemotePanel.View("TV 2 · Remote", "Verbunden", true, Set.of(RemoteAction.values())),page, ""); }
                finally { g.dispose(); }
                javax.imageio.ImageIO.write(image,"png",directory.resolve(width+"x"+height+"-"+page+".png").toFile());
            }
            assertEquals(32, ids.size());
        }
    }

    @Test void resolvesLateTargetsRefreshesCapabilitiesAndKeepsKeysSeparateFromHome() throws Exception {
        try (SourceTestEnvironment env = new SourceTestEnvironment()) {
            when(env.display.height()).thenReturn(384);
            RemoteSource source = new RemoteSource();
            JsonObject data = new JsonObject(); data.addProperty("link",2);
            source.load(env.display,data);
            var player = env.player();
            click(source,player,"home");
            IBoilerDisplay target = mock(IBoilerDisplay.class);
            when(target.source()).thenReturn(new SourceSessionTest.Leaf());
            when(env.plugin.displayManager().display(2)).thenReturn(target);
            when(target.remoteActions()).thenReturn(Set.of(RemoteAction.HOME));
            click(source,player,"home"); verify(target).onRemoteAction(player,RemoteAction.HOME);
            click(source,player,"up"); verify(target).onKey(player,"ARROW_UP");
            click(source,player,"PLAY_PAUSE"); verify(target,never()).onRemoteAction(player,RemoteAction.PLAY_PAUSE);
            when(target.remoteActions()).thenReturn(Set.of(RemoteAction.PLAY_PAUSE));
            click(source,player,"PLAY_PAUSE"); verify(target).onRemoteAction(player,RemoteAction.PLAY_PAUSE);
            when(player.hasPermission("boiler.command.action")).thenReturn(false);
            click(source,player,"digit1"); verify(target,never()).onKey(player,"1");
            when(player.hasPermission("boiler.command.action")).thenReturn(true);
            when(env.plugin.displayManager().display(2)).thenReturn(null);
            click(source,player,"up"); verify(target,times(1)).onKey(player,"ARROW_UP");
            source.unload(); source.unload();
            when(env.plugin.displayManager().display(2)).thenReturn(target);
            click(source,player,"up"); verify(target,times(1)).onKey(player,"ARROW_UP");
            verify(target,never()).source(anyString(),any());
        }
    }

    @Test void rejectsSelfLinksAndLegacySourcesHaveNoTvActions() throws Exception {
        try (SourceTestEnvironment env = new SourceTestEnvironment()) {
            JsonObject data = new JsonObject(); data.addProperty("link",1);
            assertThrows(IllegalArgumentException.class, () -> new RemoteSource().load(env.display,data));
            assertTrue(new SourceSessionTest.Leaf().remoteActions().isEmpty());
        }
    }
    private static void click(RemoteSource source, org.bukkit.command.CommandSender sender, String id) {
        Rectangle bounds = RemotePanel.buttons(128,384,0).stream().filter(button -> button.id().equals(id)).findFirst().orElseThrow().bounds();
        source.onClick(sender,bounds.x+bounds.width/2,bounds.y+bounds.height/2,false);
    }
}
