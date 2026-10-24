package net.somewhatcity.boiler.core.client;

import net.somewhatcity.boiler.core.BoilerConfig;
import net.somewhatcity.boiler.core.BoilerPlugin;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import static net.somewhatcity.boiler.stream.StreamProtocol.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClientStreamServerTest {
    private ClientStreamServer server;
    private BoilerPlugin plugin;
    private Player player;
    private int previousPort;
    private String previousBind,previousUrl;
    @BeforeEach void setup() {
        previousPort=BoilerConfig.streamPort;previousBind=BoilerConfig.streamBind;previousUrl=BoilerConfig.streamPublicUrl;
        BoilerConfig.streamPort=0;BoilerConfig.streamBind="127.0.0.1";BoilerConfig.streamPublicUrl="ws://127.0.0.1/boiler/v1";
        plugin=mock(BoilerPlugin.class,RETURNS_DEEP_STUBS);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("BoilerStreamTest"));
        player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(plugin.getServer().getPlayer(player.getUniqueId())).thenReturn(player);
        server=new ClientStreamServer(plugin);
    }
    @AfterEach void stop() {
        if(server!=null)server.close();BoilerConfig.streamPort=previousPort;BoilerConfig.streamBind=previousBind;BoilerConfig.streamPublicUrl=previousUrl;
    }
    private String ticket() throws Exception {
        return ticket(3);
    }
    private String ticket(int capabilities) throws Exception {
        server.onPluginMessageReceived(CHANNEL,player,message(HELLO,o->o.writeInt(capabilities)));
        ArgumentCaptor<byte[]> bytes=ArgumentCaptor.forClass(byte[].class);
        verify(player).sendPluginMessage(eq(plugin),eq(CHANNEL),bytes.capture());
        var in=read(bytes.getValue());assertEquals(OFFER,type(in));in.readUTF();return in.readUTF();
    }
    private URI endpoint(){return URI.create("ws://127.0.0.1:"+server.port()+"/boiler/v1");}
    @Test void forwardsModClicksOnLargeDisplayToTheSourceIncludingTheTabBar() throws Exception {
        var world=mock(org.bukkit.World.class);
        when(world.getKey()).thenReturn(org.bukkit.NamespacedKey.minecraft("overworld"));
        when(player.getWorld()).thenReturn(world);
        var eye=new org.bukkit.Location(world,-14.5,67.9,-8,180,0);
        when(player.getEyeLocation()).thenReturn(eye);
        var display=mock(net.somewhatcity.boiler.api.display.IBoilerDisplay.class,RETURNS_DEEP_STUBS);
        when(plugin.displayManager().display(10)).thenReturn(display);
        when(display.viewers()).thenReturn(Set.of(player));
        when(display.width()).thenReturn(896);when(display.height()).thenReturn(512);
        when(display.mapDisplay().interactDistance()).thenReturn(6d);
        doAnswer(invocation->{invocation.<Runnable>getArgument(0).run();return null;}).when(plugin).onMain(any(Runnable.class));
        String token=ticket(3 | CAP_DRAG);Receiver receiver=new Receiver();
        WebSocket ws=HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization","Bearer "+token).buildAsync(endpoint(),receiver).get(3,TimeUnit.SECONDS);
        var metadata=new Display(10,42,"minecraft:overworld",896,512,new Vec(-18,68,-10.9921875),new Vec(7,0,0),new Vec(0,-4,0));
        try(DisplayStream stream=new DisplayStream(metadata,"rgb",server,Runnable::run)) {
            waitFor(()->server.available(player));assertTrue(server.attach(player,stream));receiver.next();
            ws.sendBinary(ByteBuffer.wrap(input(new Input(10,42,0,.5,.025,0,""))),true).join();
            verify(display,timeout(3000)).onClick(player,448,12,false);
            ws.sendBinary(ByteBuffer.wrap(input(new Input(10,42,1,.5,.025,0,""))),true).join();
            verify(display,timeout(3000)).onClick(player,448,12,true);
            var phases=net.somewhatcity.boiler.api.util.DragPhase.values();
            ws.sendBinary(ByteBuffer.wrap(input(new Input(10,42,DRAG_START,.5,.025,0,""))),true).join();
            verify(display,timeout(3000)).onDrag(player,448,12,false,phases[0]);
            ws.sendBinary(ByteBuffer.wrap(input(new Input(10,42,DRAG_MOVE,.5,.025,0,""))),true).join();
            verify(display,timeout(3000)).onDrag(player,448,12,false,phases[1]);
            // Release must work even after looking away; never leave a button held.
            when(player.getEyeLocation()).thenReturn(new org.bukkit.Location(world,100,100,100));
            ws.sendBinary(ByteBuffer.wrap(input(new Input(10,42,DRAG_END,.5,.025,0,""))),true).join();
            verify(display,timeout(3000)).onDrag(player,448,12,false,phases[2]);
            // Stale generations and MOVE without START cannot create a capture.
            ws.sendBinary(ByteBuffer.wrap(input(new Input(10,41,DRAG_START,.5,.025,0,""))),true).join();
            when(player.getEyeLocation()).thenReturn(eye);
            ws.sendBinary(ByteBuffer.wrap(input(new Input(10,42,DRAG_START,.5,.025,1,""))),true).join();
            verify(display,timeout(3000)).onDrag(player,448,12,true,phases[0]);
            server.detach(player,10);
            verify(display,timeout(3000)).onDrag(player,448,12,true,phases[2]);
            assertTrue(server.attach(player,stream));
            ws.sendBinary(ByteBuffer.wrap(input(new Input(10,42,DRAG_START,.5,.025,1,""))),true).join();
            verify(display,timeout(3000).times(2)).onDrag(player,448,12,true,phases[0]);
            ws.abort();
            verify(display,timeout(3000).times(2)).onDrag(player,448,12,true,phases[2]);
        } finally {ws.abort();}
    }
    @Test void authenticatesOneUseTicketStreamsRgbAndRecoversAfterDroppedDelta() throws Exception {
        String token=ticket(3 | CAP_DRAG);Receiver receiver=new Receiver();
        WebSocket ws=HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization","Bearer "+token).buildAsync(endpoint(),receiver).get(3,TimeUnit.SECONDS);
        try {
            waitFor(()->server.available(player));
            var display=new Display(7,123,"minecraft:overworld",128,128,new Vec(0,1,0),new Vec(1,0,0),new Vec(0,-1,0));
            try(DisplayStream stream=new DisplayStream(display,"rgb",server,Runnable::run)) {
                assertTrue(server.attach(player,stream));assertEquals(DISPLAY,type(read(receiver.next())));
                int[] pixels=new int[128*128];Arrays.fill(pixels,0xff123456);
                var image=new java.awt.image.BufferedImage(128,128,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0,0,128,128,pixels,0,128);stream.submit(image);
                var in=read(receiver.next());assertEquals(FRAME,type(in));Frame received=frame(in);
                int[] actual=new int[pixels.length];applyRgb(received,actual);assertArrayEquals(pixels,actual);
                // An unacknowledged frame cannot accumulate another large frame on this connection.
                pixels[0]=0xffabcdef;image.setRGB(0,0,pixels[0]);stream.submit(image);
                assertNull(receiver.messages.poll(100,TimeUnit.MILLISECONDS));
                ws.sendBinary(ByteBuffer.wrap(message(ACK,out->{out.writeInt(7);out.writeLong(123);out.writeLong(received.sequence());})),true).join();
                waitFor(()->{stream.submit(image);return !receiver.messages.isEmpty();});
                var recovery=read(receiver.next());assertEquals(FRAME,type(recovery));Frame recovered=frame(recovery);assertTrue(recovered.key());
                applyRgb(recovered,actual);assertArrayEquals(pixels,actual);
                server.detach(player,7);assertEquals(REMOVE,type(read(receiver.next())));
                assertFalse(server.subscribed(player,stream));
            }
            Receiver second=new Receiver();WebSocket reused=HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization","Bearer "+token).buildAsync(endpoint(),second).get(3,TimeUnit.SECONDS);
            assertEquals(1008,second.closed.get(3,TimeUnit.SECONDS));reused.abort();
        } finally {ws.abort();}
        waitFor(()->!server.available(player));
    }
    @Test void rejectsConnectionsWithoutMinecraftTicketAndMalformedHandshake() throws Exception {
        server.onPluginMessageReceived(CHANNEL,player,new byte[]{1,2,3});verify(player,never()).sendPluginMessage(any(),anyString(),any());
        Receiver receiver=new Receiver();WebSocket ws=HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(endpoint(),receiver).get(3,TimeUnit.SECONDS);
        assertEquals(1008,receiver.closed.get(3,TimeUnit.SECONDS));assertFalse(server.available(player));ws.abort();
    }
    @Test void honorsRgbOnlyCapabilityAndFallsBackWhenTextureBudgetIsFull() throws Exception {
        String token=ticket(1);Receiver receiver=new Receiver();
        WebSocket ws=HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization","Bearer "+token).buildAsync(endpoint(),receiver).get(3,TimeUnit.SECONDS);
        java.util.List<DisplayStream> streams=new java.util.ArrayList<>();
        try {
            waitFor(()->server.available(player));
            for(int id=0;id<4;id++) {
                var display=new Display(id,1,"minecraft:overworld",2048,2048,new Vec(0,1,0),new Vec(1,0,0),new Vec(0,-1,0));
                DisplayStream stream=new DisplayStream(display,"auto",server,Runnable::run);streams.add(stream);
                assertTrue(server.attach(player,stream));receiver.next();assertFalse(server.videoSupported(stream));
            }
            assertFalse(server.canAttach(player,5,128,128));
            server.detach(player,0);receiver.next();assertTrue(server.canAttach(player,5,128,128));
        } finally {streams.forEach(DisplayStream::close);ws.abort();}
    }
    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(condition.getAsBoolean());
    }
    private static class Receiver implements WebSocket.Listener {
        final LinkedBlockingQueue<byte[]> messages=new LinkedBlockingQueue<>();final CompletableFuture<Integer> closed=new CompletableFuture<>();
        private final ByteArrayOutputStream fragments=new ByteArrayOutputStream();
        @Override public void onOpen(WebSocket ws){ws.request(1);}
        @Override public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer data,boolean last){byte[] b=new byte[data.remaining()];data.get(b);fragments.writeBytes(b);if(last){messages.add(fragments.toByteArray());fragments.reset();}ws.request(1);return null;}
        @Override public CompletionStage<?> onClose(WebSocket ws,int status,String reason){closed.complete(status);return null;}
        byte[] next() throws Exception {byte[] next=messages.poll(3,TimeUnit.SECONDS);assertNotNull(next);return next;}
    }
}
