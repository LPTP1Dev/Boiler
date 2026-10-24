package net.somewhatcity.boiler.core.smarttv;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.somewhatcity.boiler.core.BoilerPlugin;
import net.somewhatcity.boiler.core.commands.DialogCommand;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmartTvInputsTest {
    private SmartTvInputs inputs;
    private Server server;
    private final ConcurrentLinkedQueue<Runnable> main = new ConcurrentLinkedQueue<>();
    private final AtomicReference<JsonObject> shown = new AtomicReference<>();

    @BeforeEach void setup() {
        BoilerPlugin plugin = mock(BoilerPlugin.class);
        server = mock(Server.class); BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server); when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("DialogTest"));
        when(server.dispatchCommand(any(),anyString())).thenAnswer(invocation -> {
            String command = invocation.getArgument(1);
            if (command.startsWith("minecraft:dialog show ")) shown.set(JsonParser.parseString(command.split(" ",4)[3]).getAsJsonObject());
            return true;
        });
        when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),anyLong(),anyLong())).thenReturn(mock(org.bukkit.scheduler.BukkitTask.class));
        doAnswer(invocation -> { main.add(invocation.getArgument(0)); return null; }).when(plugin).onMain(any());
        inputs = new SmartTvInputs(plugin);
    }
    @AfterEach void teardown() { inputs.close(); }
    private Player player() {
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        String name = "P" + player.getUniqueId().toString().replace("-", "").substring(0,15);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true); return player;
    }
    private String show(Object owner,Player player) {
        inputs.show(owner,player,"SmartTV","Text","","Invalid input");
        return shown.get().getAsJsonObject("yes").getAsJsonObject("action").get("template").getAsString().split(" ")[2];
    }
    private void drain() { Runnable task; while ((task = main.poll()) != null) task.run(); }

    @Test void responsesArePlayerBoundSingleUseAndDeliveredOnTheServerThread() throws Exception {
        Object owner = new Object(); Player player = player(), other = player();
        AtomicReference<String> value = new AtomicReference<>();
        assertTrue(inputs.request(owner,player,value::set,() -> {}));
        assertFalse(inputs.request(new Object(),player,value::set,() -> {}));
        String token = show(owner,player);
        inputs.respond(other,token,"wrong player",false);
        Thread worker = new Thread(() -> inputs.respond(player,token,"private URL",false)); worker.start(); worker.join(2000);
        assertNull(value.get()); drain(); assertEquals("private URL",value.get());
        inputs.respond(player,token,"replay",false); drain(); assertEquals("private URL",value.get());
        String clearCommand = "minecraft:dialog clear @a[name=\"" + player.getName() + "\",limit=1]";
        verify(server).dispatchCommand(any(),eq(clearCommand));
    }

    @Test void oldResponsesCannotReachANewFieldOrReplacementSession() {
        Object owner = new Object(); Player player = player(); AtomicInteger calls = new AtomicInteger();
        inputs.request(owner,player,input -> calls.incrementAndGet(),() -> {});
        String old = show(owner,player);
        inputs.respond(player,old,"queued",false);
        String fresh = show(owner,player);
        drain(); assertEquals(0,calls.get());
        inputs.respond(player,old,"stale",false); drain(); assertEquals(0,calls.get());
        inputs.respond(player,fresh,"fresh",false); drain(); assertEquals(1,calls.get());
        inputs.release(owner);
        inputs.request(new Object(),player,input -> calls.incrementAndGet(),() -> {});
        inputs.respond(player,fresh,"late",false); drain(); assertEquals(1,calls.get());
    }

    @Test void escapeCancelAndTimeoutCloseOnlyOwnedDialogsExactlyOnce() {
        Player first = player(), second = player(); Object owner = new Object(), otherOwner = new Object();
        AtomicInteger cancelled = new AtomicInteger();
        inputs.request(owner,first,input -> fail(),cancelled::incrementAndGet);
        inputs.request(otherOwner,second,input -> fail(),cancelled::incrementAndGet);
        String token = show(owner,first); show(otherOwner,second);
        inputs.respond(first,token,"",true); drain(); inputs.cancelPlayer(first);
        assertEquals(1,cancelled.get());
        inputs.expire(Long.MAX_VALUE); inputs.expire(Long.MAX_VALUE); assertEquals(2,cancelled.get());
        assertTrue(shown.get().get("can_close_with_escape").getAsBoolean());
        assertEquals("minecraft:run_command",shown.get().getAsJsonObject("no").getAsJsonObject("action").get("type").getAsString());
    }

    @Test void definitionsEscapeUserDataAndMacroDecodingPreservesText() {
        String value = " O'Reilly \"hello\" C:\\Videos\\movie ";
        JsonObject dialog = SmartTvInputs.dialogDefinition("Title","URL",value,"Invalid","nonce");
        assertEquals(value,JsonParser.parseString(dialog.toString()).getAsJsonObject().getAsJsonArray("inputs").get(0).getAsJsonObject().get("initial").getAsString());
        assertEquals("boiler dialog nonce submit \"$(value)\"",dialog.getAsJsonObject("yes").getAsJsonObject("action").get("template").getAsString());
        String encoded = "\"" + value.replace("\\","\\\\").replace("'","\\'").replace("\"","\\\"") + "\"";
        assertEquals(value,DialogCommand.decodeText(encoded)); assertEquals("",DialogCommand.decodeText("\"\""));
        assertThrows(IllegalArgumentException.class,() -> DialogCommand.decodeText("unquoted"));
        assertThrows(IllegalArgumentException.class,() -> DialogCommand.decodeText("\"bad\\q\""));
    }

    @Test void vanillaMacroResponseTraversesBrigadierWithQuotesAndSpaces() throws Exception {
        Player player = player(); Object owner = new Object(); AtomicReference<String> received = new AtomicReference<>();
        inputs.request(owner,player,received::set,() -> {});
        String token = show(owner,player);
        BoilerPlugin plugin = mock(BoilerPlugin.class); when(plugin.smartTvInputs()).thenReturn(inputs);
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<io.papermc.paper.command.brigadier.CommandSourceStack>();
        dispatcher.register(io.papermc.paper.command.brigadier.Commands.literal("boiler").then(DialogCommand.build(plugin)));
        var source = mock(io.papermc.paper.command.brigadier.CommandSourceStack.class);
        when(source.getSender()).thenReturn(player); when(source.getExecutor()).thenReturn(player);
        String value = "  O'Reilly \"quoted\" \\path  ";
        String encoded = "\"" + value.replace("\\","\\\\").replace("'","\\'").replace("\"","\\\"") + "\"";
        assertEquals(1,dispatcher.execute("boiler dialog " + token + " submit " + encoded,source));
        drain(); assertEquals(value,received.get());
    }

    @Test void dialogCommandsUseAnExplicitSinglePlayerSelector() {
        Player player = player(); Object owner = new Object();
        inputs.request(owner,player,value -> {},() -> {}); show(owner,player);
        String target = SmartTvInputs.playerTarget(player);
        assertEquals("@a[name=\"" + player.getName() + "\",limit=1]",target);
        var commands = org.mockito.ArgumentCaptor.forClass(String.class);
        inputs.release(owner);
        verify(server,times(2)).dispatchCommand(any(),commands.capture());
        assertTrue(commands.getAllValues().get(0).startsWith("minecraft:dialog show " + target + " "));
        assertEquals("minecraft:dialog clear " + target,commands.getAllValues().get(1));
        when(player.getName()).thenReturn("name\"],limit=9");
        String quoted = SmartTvInputs.playerTarget(player);
        assertEquals(player.getName(),JsonParser.parseString(quoted.substring(8,quoted.length()-9)).getAsString());
    }

    @Test void failedOpenReleasesOwnershipWithoutTryingToCloseAnUnopenedDialog() {
        Player player = player(); Object owner = new Object(); AtomicInteger cancelled = new AtomicInteger();
        inputs.request(owner,player,value -> {},cancelled::incrementAndGet);
        when(server.dispatchCommand(any(),anyString())).thenThrow(new org.bukkit.command.CommandException("Rejected player target"));
        assertDoesNotThrow(() -> inputs.show(owner,player,"Title","Text","",""));
        assertEquals(1,cancelled.get());
        verify(server,times(1)).dispatchCommand(any(),anyString());
        assertTrue(inputs.request(new Object(),player,value -> {},() -> {}));
    }

    @Test void failedClearDoesNotHideCancellationOrLeaveThePlayerLocked() {
        Player player = player(); Object owner = new Object(); AtomicInteger cancelled = new AtomicInteger();
        inputs.request(owner,player,value -> {},cancelled::incrementAndGet); show(owner,player);
        when(server.dispatchCommand(any(),anyString())).thenThrow(new org.bukkit.command.CommandException("Clear failed"));
        assertDoesNotThrow(() -> inputs.cancelPlayer(player)); inputs.cancelPlayer(player);
        assertEquals(1,cancelled.get());
        assertTrue(inputs.request(new Object(),player,value -> {},() -> {}));
    }
}
