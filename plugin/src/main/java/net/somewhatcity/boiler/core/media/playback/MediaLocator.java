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
    /** Local media may only read files; remote media may never fall back to local files (e.g. via HLS playlists). */
    public static String ffmpegProtocols(String location) {
        boolean local=location.matches("^[A-Za-z]:[\\\\/].*") || !location.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")
                || location.regionMatches(true,0,"file:",0,5);
        return local ? "file,crypto" : "http,https,tls,tcp,udp,rtp,srtp,rtmp,rtmps,rtmpt,rtmpts,rtmpe,rtmpte,srt,crypto,httpproxy";
    }
    /** Media URLs returned by third-party lookup services must be plain HTTPS. */
    public static String requireHttps(String location) {
        URI uri=URI.create(location);
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null) throw new IllegalArgumentException("Unexpected media URL from lookup service");
        return location;
    }
}
