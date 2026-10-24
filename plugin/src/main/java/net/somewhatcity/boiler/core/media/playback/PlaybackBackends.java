package net.somewhatcity.boiler.core.media.playback;

import java.util.Locale;

public final class PlaybackBackends {
    private PlaybackBackends() {}
    public static String normalize(String name) {
        String value=name==null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if(!java.util.Set.of("ffmpeg","gstreamer","vlc").contains(value))
            throw new IllegalArgumentException("Ungültiges Mediaplayer-Backend: "+name+" (ffmpeg, gstreamer oder vlc erwartet)");
        return value;
    }
    public static PlaybackBackend create(String name) {
        return switch(normalize(name)) {
            case "ffmpeg" -> new FfmpegBackend();
            case "gstreamer" -> new GstreamerBackend();
            case "vlc" -> new VlcBackend();
            default -> throw new AssertionError();
        };
    }
}
