package net.somewhatcity.boiler.core;

import net.somewhatcity.boiler.core.media.playback.VlcNativeLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.factory.discovery.strategy.WindowsNativeDiscoveryStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class VlcNativeLoaderTest {
    @Test @EnabledOnOs(OS.WINDOWS)
    void discoversInstalledVlcEvenWhenTheServerContextLoaderCannotSeePluginServices() {
        String programs=System.getenv("ProgramFiles");
        assumeTrue(programs!=null && Files.isRegularFile(Path.of(programs,"VideoLAN","VLC","libvlc.dll")),"Native VLC is not installed in the standard Windows directory");
        ClassLoader previous=Thread.currentThread().getContextClassLoader();
        ClassLoader serverLoader=new ClassLoader(null) {};
        Thread.currentThread().setContextClassLoader(serverLoader);
        try {
            // Model Paper's context loader, including a failed initial provider search.
            assertTrue(new WindowsNativeDiscoveryStrategy().discoveryDirectories().isEmpty());
            assertFalse(new NativeDiscovery().discover());
            MediaPlayerFactory factory=VlcNativeLoader.createFactory("--no-video-title-show","--no-audio");
            try {
                assertSame(serverLoader,Thread.currentThread().getContextClassLoader());
                assertNotNull(factory.nativeLibraryPath());
                assertTrue(Files.isRegularFile(Path.of(factory.nativeLibraryPath(),"libvlc.dll")));
            } finally { factory.release(); }
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }
}
