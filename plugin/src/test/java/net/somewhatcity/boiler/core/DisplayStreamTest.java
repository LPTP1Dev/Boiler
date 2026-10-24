package net.somewhatcity.boiler.core;

import net.somewhatcity.boiler.core.client.*;
import net.somewhatcity.boiler.stream.StreamProtocol.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;
import static net.somewhatcity.boiler.stream.StreamProtocol.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DisplayStreamTest {
    @Test void asynchronousEncodingOwnsItsPixelsAndDoesNotQueueAnotherSnapshot() throws Exception {
        ClientStreamServer server=mock(ClientStreamServer.class);
        AtomicReference<Runnable> task=new AtomicReference<>();
        Display metadata=new Display(1,1,"minecraft:overworld",128,128,new Vec(0,1,0),new Vec(1,0,0),new Vec(0,-1,0));
        try(DisplayStream stream=new DisplayStream(metadata,"rgb",server,job->{assertTrue(task.compareAndSet(null,job));})) {
            BufferedImage reused=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);
            reused.setRGB(0,0,0xff123456);stream.submit(reused);
            reused.setRGB(0,0,0xffabcdef);stream.submit(reused);
            task.getAndSet(null).run();
            ArgumentCaptor<Frame> frame=ArgumentCaptor.forClass(Frame.class);verify(server).publish(eq(stream),frame.capture());
            int[] pixels=new int[128*128];applyRgb(frame.getValue(),pixels);assertEquals(0xff123456,pixels[0]);
            stream.close();stream.submit(reused);assertNull(task.get());
        }
    }
}
