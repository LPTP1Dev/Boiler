package net.somewhatcity.boiler.core.media.playback;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.media.PlaybackState;
import java.awt.*;
import java.util.Set;

/** One playback lifetime. Start runs on a loader; controls/close must not block callers. */
public interface PlaybackBackend extends AutoCloseable {
    void start(IBoilerDisplay display, JsonObject data, PlaybackOutput output);
    void draw(Graphics2D graphics, Rectangle viewport);
    PlaybackState playbackState();
    Set<RemoteAction> remoteActions();
    void action(RemoteAction action);
    @Override void close();
}
