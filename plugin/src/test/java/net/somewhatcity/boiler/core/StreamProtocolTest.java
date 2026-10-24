package net.somewhatcity.boiler.core;

import net.somewhatcity.boiler.stream.StreamProtocol;
import net.somewhatcity.boiler.stream.StreamProtocol.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static net.somewhatcity.boiler.stream.StreamProtocol.*;

class StreamProtocolTest {
    @Test void dragActionsRoundTripAndRejectInvalidButtons() throws Exception {
        for(int action:new int[]{DRAG_START,DRAG_MOVE,DRAG_END})for(int button=0;button<2;button++) {
            Input expected=new Input(10,42,action,.25,.75,button,"");
            var in=read(input(expected));assertEquals(INPUT,type(in));assertEquals(expected,input(in));end(in);
            assertThrows(IllegalArgumentException.class,()->new Input(10,42,action,.25,.75,2,""));
        }
        assertThrows(IllegalArgumentException.class,()->new Input(10,42,9,.25,.75,0,""));
    }
    @Test void fullRgbAndPatchRoundTripPreserveAllColorChannels() throws Exception {
        int w=128,h=128;int[] source=new int[w*h];Random random=new Random(17);
        Arrays.setAll(source,i->0xff000000|random.nextInt(0x1000000));
        Frame full=new Frame(3,42,1,0,RGB,true,w,h,0,0,w,h,compress(source,w,0,0,w,h));
        DataInputStream in=read(StreamProtocol.frame(full));assertEquals(FRAME,type(in));Frame decoded=frame(in);end(in);
        int[] result=new int[source.length];applyRgb(decoded,result);assertArrayEquals(source,result);
        source[17*w+19]=0xff123456;
        Frame patch=new Frame(3,42,2,1,RGB,false,w,h,19,17,1,1,compress(source,w,19,17,1,1));
        applyRgb(patch,result);assertArrayEquals(source,result);
    }
    @Test void malformedPayloadsCannotOverflowPixelBuffers() {
        assertThrows(IllegalArgumentException.class,()->checkSize(Integer.MAX_VALUE,2));
        assertThrows(IllegalArgumentException.class,()->new Frame(1,1,1,0,RGB,true,128,128,127,0,128,128,new byte[0]));
        assertThrows(IllegalArgumentException.class,()->new Input(1,1,0,Double.NaN,0,0,""));
        assertThrows(IOException.class,()->read(new byte[]{1,0,0,0,99}));
        Frame truncated=new Frame(1,1,1,0,RGB,true,128,128,0,0,128,128,new byte[]{1,2,3});
        assertThrows(IOException.class,()->applyRgb(truncated,new int[128*128]));
        byte[] extra=compress(new int[128*129],128,0,0,128,129);
        Frame oversized=new Frame(1,1,1,0,RGB,true,128,128,0,0,128,128,extra);
        assertThrows(IOException.class,()->applyRgb(oversized,new int[128*128]));
    }
    @Test void geometryFindsCorrectPixelsAndRejectsOutOfBoundsRays() throws Exception {
        Display d=new Display(8,123,"minecraft:overworld",256,128,new Vec(4,3,2),new Vec(-2,0,0),new Vec(0,-1,0));
        Hit hit=d.intersect(new Vec(3,2.5,0),new Vec(0,0,1),6);
        assertNotNull(hit);assertEquals(.5,hit.u());assertEquals(.5,hit.v());assertEquals(2,hit.distance());
        assertNull(d.intersect(new Vec(5,2.5,0),new Vec(0,0,1),6));
        assertNull(d.intersect(new Vec(3,2.5,0),new Vec(0,0,-1),6));
        assertNull(d.intersect(new Vec(3,2.5,0),new Vec(0,0,1),1));
        var in=read(StreamProtocol.display(d));assertEquals(DISPLAY,type(in));assertEquals(d,display(in));end(in);
    }
}
