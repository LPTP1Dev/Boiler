package net.somewhatcity.boiler.core.client;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.core.BoilerPlugin;
import net.somewhatcity.boiler.core.BoilerConfig;
import net.somewhatcity.boiler.stream.StreamProtocol;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.eclipse.jetty.websocket.api.WriteCallback;

import java.io.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static net.somewhatcity.boiler.stream.StreamProtocol.*;

/** Optional media connection. A valid Minecraft handshake is required before a one-use WS ticket exists. */
public final class ClientStreamServer implements AutoCloseable,Listener,PluginMessageListener {
    private record Ticket(UUID player,long expires,int capabilities) {}
    private final BoilerPlugin plugin;
    private final Map<String,Ticket> tickets=new ConcurrentHashMap<>();
    private final Map<UUID,Client> clients=new ConcurrentHashMap<>();
    private final Map<String,Client> sockets=new ConcurrentHashMap<>();
    private final Map<UUID,Long> offers=new ConcurrentHashMap<>();
    private final Javalin app;
    private final org.bukkit.scheduler.BukkitTask maintenance;
    private volatile boolean closed;
    public ClientStreamServer(BoilerPlugin plugin) {
        this.plugin=plugin;
        app=Javalin.create(config->{
            config.showJavalinBanner=false;
            config.jetty.modifyWebSocketServletFactory(factory->{factory.setMaxBinaryMessageSize(8192);factory.setMaxTextMessageSize(256);factory.setIdleTimeout(Duration.ofSeconds(20));});
        });
        app.ws("/boiler/v1",ws->{
            ws.onConnect(ctx->{
                String auth=ctx.header("Authorization");
                Ticket ticket=auth==null || !auth.startsWith("Bearer ") ? null : tickets.remove(auth.substring(7));
                if(closed || ticket==null || ticket.expires<System.currentTimeMillis()) {ctx.closeSession(1008,"Invalid or expired ticket");return;}
                Client client=new Client(ticket.player,ctx,ticket.capabilities);
                Client old=clients.put(ticket.player,client);if(old!=null) old.close();
                sockets.put(ctx.sessionId(),client);ctx.enableAutomaticPings(5,TimeUnit.SECONDS);
            });
            ws.onBinaryMessage(ctx->{
                Client c=sockets.get(ctx.sessionId());if(c==null) {ctx.closeSession(1008,"Handshake required");return;}
                if(ctx.length()>8192) {c.close();return;}
                try {
                    if(!c.allowMessage()) {c.close();return;}
                    DataInputStream in=read(Arrays.copyOfRange(ctx.data(),ctx.offset(),ctx.offset()+ctx.length()));
                    int type=type(in);
                    if(type==ACK) {int id=in.readInt();long generation=in.readLong(),sequence=in.readLong();end(in);c.ack(id,generation,sequence);}
                    else if(type==INPUT) {Input input=input(in);end(in);if(c.allowInput() || input.action()==DRAG_END)plugin.onMain(()->handleInput(c,input));}
                    else c.close();
                } catch(Exception e) {c.close();}
            });
            ws.onMessage(ctx->ctx.closeSession(1003,"Binary protocol required"));
            ws.onClose(ctx->disconnect(ctx.sessionId()));
            ws.onError(ctx->disconnect(ctx.sessionId()));
        });
        app.start(BoilerConfig.streamBind,BoilerConfig.streamPort);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin,CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin,CHANNEL,this);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        maintenance=plugin.getServer().getScheduler().runTaskTimer(plugin,this::maintain,20,20);
    }
    private void maintain() {
        long now=System.currentTimeMillis();tickets.entrySet().removeIf(e->e.getValue().expires<now);
        for(Client c:clients.values()) if(c.stalled(now) || plugin.getServer().getPlayer(c.player)==null) c.close();
        for(Player player:plugin.getServer().getOnlinePlayers()) {
            if(clients.containsKey(player.getUniqueId())) continue;
            long last=offers.getOrDefault(player.getUniqueId(),0L);
            if(now-last>=10000) {offers.put(player.getUniqueId(),now);player.sendPluginMessage(plugin,CHANNEL,message(HELLO,o->o.writeInt(3 | CAP_DRAG)));}
        }
    }
    int port() {return app.port();}
    @Override public void onPluginMessageReceived(String channel,Player player,byte[] bytes) {
        if(closed || bytes.length!=9) return;
        try {
            DataInputStream in=read(bytes);if(type(in)!=HELLO) return;int capabilities=in.readInt();end(in);
            if((capabilities&1)==0 || (capabilities&~(3 | CAP_DRAG))!=0)return;
            long now=System.currentTimeMillis();
            if(clients.containsKey(player.getUniqueId()) || tickets.values().stream().anyMatch(t->t.player.equals(player.getUniqueId())&&t.expires>now)) return;
            if(tickets.size()>2048) return;
            String token=UUID.randomUUID().toString()+UUID.randomUUID();
            tickets.put(token,new Ticket(player.getUniqueId(),now+15000,capabilities));
            player.sendPluginMessage(plugin,CHANNEL,message(OFFER,o->{o.writeUTF(BoilerConfig.streamPublicUrl);o.writeUTF(token);}));
        } catch(IOException ignored) {}
    }
    public boolean available(Player player) {Client c=clients.get(player.getUniqueId());return c!=null && c.open();}
    public boolean videoSupported(DisplayStream stream) {return clients.values().stream().noneMatch(c->c.has(stream)&&(c.capabilities&2)==0);}
    public boolean canAttach(Player player,int id,int width,int height) {Client c=clients.get(player.getUniqueId());return c!=null&&c.room(id,width,height);}
    public boolean attach(Player player,DisplayStream stream) {
        Client c=clients.get(player.getUniqueId());return c!=null && c.attach(stream);
    }
    public void detach(Player player,int displayId) {Client c=clients.get(player.getUniqueId());if(c!=null)c.detach(displayId);}
    public boolean subscribed(Player player,DisplayStream stream) {Client c=clients.get(player.getUniqueId());return c!=null&&c.has(stream);}
    public void publish(DisplayStream stream,Frame frame) {
        byte[] packet=StreamProtocol.frame(frame);
        for(Client c:clients.values()) c.frame(stream,frame,packet);
    }
    public void fail(DisplayStream stream,Exception error) {
        plugin.getLogger().warning("Client stream "+stream.display.id()+": "+error.getMessage());
        for(Client c:clients.values()) if(c.has(stream)) c.close();
    }
    private void handleInput(Client c,Input input) {
        if(closed || clients.get(c.player)!=c) return;
        if(input.action()==DRAG_END) {
            Drag drag=c.drags.get(input.value());
            if(drag!=null && drag.id==input.id() && drag.generation==input.generation()) endDrag(c,input.value());
            return;
        }
        DisplayStream stream=c.stream(input.id(),input.generation());if(stream==null)return;
        Player player=plugin.getServer().getPlayer(c.player);
        IBoilerDisplay display=plugin.displayManager().display(input.id());
        if(player==null || display==null || !display.viewers().contains(player) || !player.getWorld().getKey().toString().equals(stream.display.world())) return;
        org.bukkit.Location eye=player.getEyeLocation();org.bukkit.util.Vector dir=eye.getDirection();
        Hit hit=stream.display.intersect(new Vec(eye.getX(),eye.getY(),eye.getZ()),new Vec(dir.getX(),dir.getY(),dir.getZ()),Math.min(10,display.mapDisplay().interactDistance()));
        if(hit==null || Math.abs(hit.u()-input.u())>.05 || Math.abs(hit.v()-input.v())>.05)return;
        org.bukkit.util.RayTraceResult wall=player.getWorld().rayTraceBlocks(eye,dir,hit.distance(),org.bukkit.FluidCollisionMode.NEVER,true);
        if(wall!=null && wall.getHitPosition().distance(eye.toVector())+.02<hit.distance())return;
        int x=Math.min(display.width()-1,(int)(hit.u()*display.width())),y=Math.min(display.height()-1,(int)(hit.v()*display.height()));
        if(input.action()>=DRAG_START) {
            if((c.capabilities&CAP_DRAG)==0)return;
            Drag drag=c.drags.get(input.value());
            if(input.action()==DRAG_START) {
                endDrag(c,input.value());
                drag=new Drag(input.id(),input.generation(),display,display.source(),player,x,y);
                c.drags.put(input.value(),drag);
                display.onDrag(player,x,y,input.value()==1,net.somewhatcity.boiler.api.util.DragPhase.START);
            } else if(drag!=null && drag.id==input.id() && drag.generation==input.generation()) {
                if(drag.source!=display.source()) {endDrag(c,input.value());return;}
                drag.x=x;drag.y=y;
                display.onDrag(player,x,y,input.value()==1,net.somewhatcity.boiler.api.util.DragPhase.MOVE);
            }
            return;
        }
        switch(input.action()) {
            case 0,1 -> display.onClick(player,x,y,input.action()==1);
            case 2 -> display.onCursorMove(player,x,y);
            case 3 -> {for(int i=0;i<Math.min(5,Math.abs(input.value()));i++)display.onScroll(player,x,y,Integer.signum(input.value()));}
            case 4 -> {if(Set.of("Escape","Home","Backspace","Enter","ARROW_LEFT","ARROW_RIGHT","ARROW_UP","ARROW_DOWN"," ").contains(input.text()))display.onKey(player,input.text());}
            case 5 -> display.onInput(player,input.text());
        }
    }
    private static final class Drag {
        final int id;final long generation;final IBoilerDisplay display;
        final net.somewhatcity.boiler.api.IBoilerSource source;final Player player;int x,y;
        Drag(int id,long generation,IBoilerDisplay display,net.somewhatcity.boiler.api.IBoilerSource source,Player player,int x,int y) {
            this.id=id;this.generation=generation;this.display=display;this.source=source;this.player=player;this.x=x;this.y=y;
        }
    }
    // Runs on the main thread, including releases after the player looks away.
    private void endDrag(Client c,int button) {
        Drag drag=c.drags.remove(button);
        if(drag!=null && drag.source==drag.display.source())
            drag.display.onDrag(drag.player,drag.x,drag.y,button==1,net.somewhatcity.boiler.api.util.DragPhase.END);
    }
    private void releaseDrags(Client c,int id) {
        plugin.onMain(()->{for(int button:new int[]{0,1}) {Drag drag=c.drags.get(button);if(drag!=null&&(id<0||drag.id==id))endDrag(c,button);}});
    }
    private void disconnect(String id) {Client c=sockets.remove(id);if(c!=null)c.close();}
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id=event.getPlayer().getUniqueId();Client c=clients.remove(id);if(c!=null)c.close();offers.remove(id);tickets.entrySet().removeIf(e->e.getValue().player.equals(id));
    }
    @Override public void close() {
        closed=true;maintenance.cancel();clients.values().forEach(Client::close);clients.clear();sockets.clear();tickets.clear();offers.clear();app.stop();HandlerList.unregisterAll(this);
    }
    private static final class Subscription {
        final DisplayStream stream;long sequence,pending,sentAt;boolean needsKey=true;
        Subscription(DisplayStream stream){this.stream=stream;}
    }
    private final class Client {
        final UUID player;final WsContext socket;final int capabilities;
        final Map<Integer,Subscription> subscriptions=new HashMap<>();
        final ArrayDeque<byte[]> controls=new ArrayDeque<>();
        final Map<Integer,Drag> drags=new HashMap<>();
        boolean sending,dead;long inputWindow,messageWindow;int inputs,messages;
        Client(UUID player,WsContext socket,int capabilities){this.player=player;this.socket=socket;this.capabilities=capabilities;}
        synchronized boolean open(){return !dead&&socket.session.isOpen();}
        synchronized boolean allowMessage(){long now=System.currentTimeMillis();if(now-messageWindow>=1000){messages=0;messageWindow=now;}return ++messages<=4096;}
        synchronized boolean allowInput(){long now=System.currentTimeMillis();if(now-inputWindow>=1000){inputs=0;inputWindow=now;}return ++inputs<=120;}
        synchronized boolean has(DisplayStream s){Subscription sub=subscriptions.get(s.display.id());return open()&&sub!=null&&sub.stream==s;}
        synchronized boolean room(int id,int width,int height) {
            long used=subscriptions.values().stream().filter(s->s.stream.display.id()!=id).mapToLong(s->(long)s.stream.display.width()*s.stream.display.height()).sum();
            return open()&&(subscriptions.containsKey(id)||subscriptions.size()<32)&&used+(long)width*height<=16L*1024*1024;
        }
        synchronized DisplayStream stream(int id,long generation){Subscription sub=subscriptions.get(id);return open()&&sub!=null&&sub.stream.display.generation()==generation ? sub.stream:null;}
        synchronized boolean attach(DisplayStream s) {
            if(!open())return false;
            Subscription old=subscriptions.get(s.display.id());if(old!=null&&old.stream==s)return true;
            if(!room(s.display.id(),s.display.width(),s.display.height()))return false;
            subscriptions.put(s.display.id(),new Subscription(s));s.requestKeyframe();control(StreamProtocol.display(s.display));return true;
        }
        synchronized void detach(int id) {
            releaseDrags(this,id);
            Subscription sub=subscriptions.remove(id);
            if(sub!=null)control(message(REMOVE,o->{o.writeInt(id);o.writeLong(sub.stream.display.generation());}));
        }
        synchronized void ack(int id,long generation,long sequence) {
            Subscription sub=subscriptions.get(id);
            if(sub==null||sub.stream.display.generation()!=generation||sub.pending!=sequence)return;
            sub.pending=0;if(sub.needsKey)sub.stream.requestKeyframe();
        }
        synchronized void frame(DisplayStream s,Frame f,byte[] bytes) {
            Subscription sub=subscriptions.get(f.id());if(!open()||sub==null||sub.stream!=s)return;
            if(f.codec()==H264&&(capabilities&2)==0){sub.needsKey=true;s.requestKeyframe();return;}
            if(sending||!controls.isEmpty()||sub.pending!=0){
                sub.needsKey=true;
                // Metadata/another display can occupy the socket before this display
                // ever sends a frame. There is no frame ACK in that case to request recovery.
                if(sub.pending==0)s.requestKeyframe();
                return;
            }
            if((sub.needsKey||sub.sequence!=f.base())&&!f.key()){s.requestKeyframe();return;}
            sub.needsKey=false;sub.sequence=f.sequence();sub.pending=f.sequence();sub.sentAt=System.currentTimeMillis();send(bytes);
        }
        synchronized boolean stalled(long now){return subscriptions.values().stream().anyMatch(s->s.pending!=0&&now-s.sentAt>10000);}
        synchronized void control(byte[] bytes){if(!open())return;if(controls.size()>=64){close();return;}controls.add(bytes);drain();}
        synchronized void drain(){if(!sending&&!controls.isEmpty()&&open())send(controls.remove());}
        synchronized void send(byte[] bytes) {
            sending=true;
            try {socket.session.getRemote().sendBytes(ByteBuffer.wrap(bytes),new WriteCallback(){
                @Override public void writeSuccess(){synchronized(Client.this){sending=false;drain();}}
                @Override public void writeFailed(Throwable error){close();}
            });}catch(Exception e){close();}
        }
        synchronized void markClosed(){releaseDrags(this,-1);dead=true;controls.clear();subscriptions.clear();}
        synchronized void close(){if(dead)return;markClosed();clients.remove(player,this);sockets.remove(socket.sessionId(),this);socket.closeSession(1000,"Stream closed");}
    }
}
