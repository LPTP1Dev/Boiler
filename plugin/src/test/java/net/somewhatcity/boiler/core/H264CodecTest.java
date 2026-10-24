package net.somewhatcity.boiler.core;

import net.somewhatcity.boiler.stream.H264Codec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class H264CodecTest {
    @Test void lowLatencyVideoDecodesEveryFrameAndAllowsFreshDecoderAtRecoveryKeyframe() {
        int width=128,height=128;int[] pixels=new int[width*height];
        try(H264Codec encoder=new H264Codec(width,height,true);H264Codec decoder=new H264Codec(width,height,false)) {
            for(int frame=0;frame<40;frame++) {
                int red=50+frame*3;java.util.Arrays.fill(pixels,0xff000000|red<<16|90<<8|160);
                byte[] packet=encoder.encode(pixels,frame==0||frame==17);
                assertTrue(packet.length>0);
                int[] decoded=decoder.decode(packet);assertEquals(pixels.length,decoded.length);
                int color=decoded[width*height/2];assertEquals(red,(color>>16)&255,8);assertEquals(90,(color>>8)&255,8);assertEquals(160,color&255,8);
                if(frame==17)try(H264Codec fresh=new H264Codec(width,height,false)) {assertEquals(decoded.length,fresh.decode(packet).length);}
            }
        }
    }
}
