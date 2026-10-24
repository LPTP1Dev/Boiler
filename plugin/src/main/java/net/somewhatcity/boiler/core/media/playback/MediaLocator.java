package net.somewhatcity.boiler.core.media.playback;

import java.net.URI;
import java.nio.file.Path;

/** Distinguishes native Windows paths from URI schemes before parsing. */
public final class MediaLocator {
    private MediaLocator() {}
    public static URI uri(String location) {
        if(location.matches("^[A-Za-z]:[\\\\/].*") || !location.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*"))
            return Path.of(location).toUri();
        return URI.create(location.replace(" ","%20"));
    }
}
