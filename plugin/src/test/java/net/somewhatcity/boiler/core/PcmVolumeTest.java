package net.somewhatcity.boiler.core;

import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.audio.PcmVolume;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class PcmVolumeTest {
    @Test void gainPreservesSignedPcmAndNeverChangesOriginalData() {
        byte[] samples = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(Short.MIN_VALUE).putShort(Short.MAX_VALUE).putShort((short)-1000).putShort((short)1000).array();
        byte[] original = samples.clone();
        var half = ByteBuffer.wrap(PcmVolume.scale(samples,50,false)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(-16384,half.getShort()); assertEquals(16383,half.getShort());
        assertEquals(-500,half.getShort()); assertEquals(500,half.getShort());
        assertArrayEquals(new byte[8],PcmVolume.scale(samples,100,true));
        assertArrayEquals(new byte[8],PcmVolume.scale(samples,0,false));
        assertSame(samples,PcmVolume.scale(samples,100,false));
        assertArrayEquals(original,samples);
    }
    @Test void volumeIsClampedAndMuteIsStoredIndependentlyWithoutSettingsPermission() throws Exception {
        try (SourceTestEnvironment env = new SourceTestEnvironment()) {
            assertEquals(100,PcmVolume.volume(env.display.settings()));
            PcmVolume.change(env.display,RemoteAction.VOLUME_UP);
            assertEquals(100,PcmVolume.volume(env.display.settings()));
            PcmVolume.change(env.display,RemoteAction.MUTE);
            for (int i=0;i<21;i++) PcmVolume.change(env.display,RemoteAction.VOLUME_DOWN);
            assertEquals(0,PcmVolume.volume(env.display.settings())); assertTrue(PcmVolume.muted(env.display.settings()));
            PcmVolume.change(env.display,RemoteAction.VOLUME_UP);
            assertEquals(5,PcmVolume.volume(env.display.settings())); assertTrue(PcmVolume.muted(env.display.settings()));
            PcmVolume.change(env.display,RemoteAction.MUTE); assertFalse(PcmVolume.muted(env.display.settings()));
            assertFalse(PcmVolume.change(env.display,RemoteAction.HOME));
        }
    }
}
