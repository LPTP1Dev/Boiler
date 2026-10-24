/*
 * Copyright (c) 2023.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.somewhatcity.boiler.core;

import org.bukkit.configuration.file.FileConfiguration;

public class BoilerConfig {
    private BoilerPlugin plugin;
    public static int viewDistance = 100;
    public static boolean guiEnabled = false;
    public static String svcChannelName = "boiler";
    public static boolean useSvcGroups = false;

    public static String mediaPlayerBackend = "ffmpeg";
    public static boolean streamEnabled = false;
    public static String streamBind = "127.0.0.1";
    public static int streamPort = 25576;
    public static String streamPublicUrl = "ws://{server}:25576/boiler/v1";
    public static String streamCodec = "auto";
    public static int streamFps = 30;
    public static int streamResolutionScale = 1;

    public BoilerConfig(BoilerPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        reloadConfig();
    }

    public void reloadConfig() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        viewDistance = config.getInt("boiler.settings.view_distance", 100);
        guiEnabled = config.getBoolean("boiler.settings.gui_enabled", false);
        svcChannelName = config.getString("boiler.settings.svc_channel_name", "boiler");
        //useSvcGroups = config.getBoolean("boiler.settings.use_svc_groups", false);

        mediaPlayerBackend = config.getString("boiler.mediaplayer.backend", "ffmpeg");
        try { mediaPlayerBackend = net.somewhatcity.boiler.core.media.playback.PlaybackBackends.normalize(mediaPlayerBackend); }
        catch (IllegalArgumentException error) { plugin.getLogger().warning(error.getMessage()); }
        streamEnabled = config.getBoolean("boiler.stream.enabled", false);
        streamBind = config.getString("boiler.stream.bind", "127.0.0.1");
        streamPort = config.getInt("boiler.stream.port", 25576);
        streamPublicUrl = config.getString("boiler.stream.public_url", "ws://{server}:25576/boiler/v1");
        streamCodec = config.getString("boiler.stream.codec", "auto");
        if (!java.util.Set.of("auto", "rgb", "h264").contains(streamCodec)) streamCodec = "auto";
        streamFps = Math.max(1, Math.min(60, config.getInt("boiler.stream.fps", 30)));
        streamResolutionScale = Math.max(1, Math.min(4, config.getInt("boiler.stream.resolution_scale", 1)));

    }
}
