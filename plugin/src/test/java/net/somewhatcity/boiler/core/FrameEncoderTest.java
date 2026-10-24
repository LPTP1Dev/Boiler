package net.somewhatcity.boiler.core;

import net.somewhatcity.boiler.core.client.FrameEncoder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static net.somewhatcity.boiler.stream.StreamProtocol.*;

class FrameEncoderTest {
    @Test void stillImagesAreNotResentAndDeltasHaveRecoverableReferences() throws Exception {
        try(FrameEncoder encoder=new FrameEncoder(1,77,128,128,"rgb")) {
            int[] pixels=new int[128*128];java.util.Arrays.fill(pixels,0xff113355);
            Frame first=encoder.encode(pixels.clone());assertTrue(first.key());assertEquals(0,first.base());
            assertNull(encoder.encode(pixels.clone()));
            pixels[5*128+9]=0xffabcdef;Frame patch=encoder.encode(pixels.clone());assertFalse(patch.key());
            assertEquals(first.sequence(),patch.base());assertEquals(9,patch.x());assertEquals(5,patch.y());assertEquals(1,patch.patchWidth());assertEquals(1,patch.patchHeight());
            encoder.requestKeyframe();Frame recovery=encoder.encode(pixels.clone());assertTrue(recovery.key());assertEquals(0,recovery.base());
            int[] decoded=new int[pixels.length];applyRgb(recovery,decoded);assertArrayEquals(pixels,decoded);
        }
    }
    @Test void autoSwitchesMotionToVideoAndCanReturnToLossless() {
        try(FrameEncoder encoder=new FrameEncoder(1,1,128,128,"auto")) {
            int[] still=null;
            for(int frame=0;frame<8;frame++) {
                int[] pixels=new int[128*128];java.util.Arrays.fill(pixels,0xff000000|frame*23<<16|0x3355);
                Frame encoded=encoder.encode(pixels);
                still=pixels;
                if(frame==7)assertEquals(H264,encoded.codec());
            }
            Frame restored=null;
            for(int i=0;i<35;i++) {Frame frame=encoder.encode(still.clone());if(frame!=null)restored=frame;}
            assertNotNull(restored);assertEquals(RGB,restored.codec());assertTrue(restored.key());
        }
    }
    @Test void rgbOnlyCapabilityOverridesVideoPreference() {
        try(FrameEncoder encoder=new FrameEncoder(1,1,128,128,"h264")) {
            encoder.videoAllowed(false);
            for(int i=0;i<8;i++) {
                int[] pixels=new int[128*128];java.util.Arrays.fill(pixels,0xff000000|i*17<<16);
                assertEquals(RGB,encoder.encode(pixels).codec());
            }
        }
    }
}
