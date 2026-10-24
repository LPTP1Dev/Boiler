package net.somewhatcity.boiler.core.media.playback;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.factory.discovery.strategy.LinuxNativeDiscoveryStrategy;
import uk.co.caprica.vlcj.factory.discovery.strategy.OsxNativeDiscoveryStrategy;
import uk.co.caprica.vlcj.factory.discovery.strategy.WindowsNativeDiscoveryStrategy;

/** vlcj discovers directory providers with ServiceLoader, which otherwise sees Paper's loader. */
public final class VlcNativeLoader {
    private VlcNativeLoader() {}

    public static MediaPlayerFactory createFactory(String... arguments) {
        Thread thread=Thread.currentThread();
        ClassLoader previous=thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(MediaPlayerFactory.class.getClassLoader());
            // Fresh strategies also recover from an earlier discovery under the server loader.
            NativeDiscovery discovery=new NativeDiscovery(
                    new LinuxNativeDiscoveryStrategy(),
                    new OsxNativeDiscoveryStrategy(),
                    new WindowsNativeDiscoveryStrategy());
            return new MediaPlayerFactory(discovery,arguments);
        } catch(LinkageError error) {
            throw new IllegalStateException("libVLC konnte nicht geladen werden. VLC muss auf dem Serverrechner "
                    +"installiert sein und zur Architektur der Java-JVM passen. Bei einem eigenen Installationspfad "
                    +"-Djna.library.path=<VLC-Verzeichnis> verwenden. Ursache: "+error.getMessage(),error);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
