package net.somewhatcity.boiler.core;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.somewhatcity.boiler.api.IBoilerSource;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.commands.display.DisplaySourceCommand;
import net.somewhatcity.boiler.core.display.SourceSession;
import net.somewhatcity.boiler.core.media.PlaybackState;
import net.somewhatcity.boiler.core.media.playback.*;
import net.somewhatcity.boiler.core.runtime.BoilerRuntime;
import net.somewhatcity.boiler.core.sources.*;
import okhttp3.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.*;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnifiedPlaybackTest {
    private String previous;
    @BeforeEach void defaults() { previous=BoilerConfig.mediaPlayerBackend; BoilerConfig.mediaPlayerBackend="ffmpeg"; }
    @AfterEach void restore() { BoilerConfig.mediaPlayerBackend=previous; }

    @Test void factoryNormalizesValidNamesAndRejectsUnknownBackendsWithoutFallback() {
        assertEquals("gstreamer",PlaybackBackends.normalize(" GStreamer "));
        for(String name:new String[]{"ffmpeg","gstreamer","vlc"}) {
            PlaybackBackend backend=PlaybackBackends.create(name);
            assertFalse(backend instanceof IBoilerSource);
        }
        assertThrows(IllegalArgumentException.class,() -> PlaybackBackends.create("auto"));
        assertThrows(IllegalArgumentException.class,() -> PlaybackBackends.create(null));
    }
    @Test void mediaLocationsKeepUriSchemesAndAcceptWindowsPathsWithSpaces() {
        assertEquals("https",MediaLocator.uri("https://example.invalid/a b.mp4").getScheme());
        assertTrue(MediaLocator.uri("https://example.invalid/a b.mp4").toString().contains("a%20b.mp4"));
        assertEquals("file",MediaLocator.uri("C:\\media\\track with spaces.mp3").getScheme());
        assertEquals("file",MediaLocator.uri("relative movie.mp4").getScheme());
    }
    @Test void configReloadReadsBackendWithoutOverwritingFileChanges() throws Exception {
        try(SourceTestEnvironment env=new SourceTestEnvironment()) {
            YamlConfiguration config=new YamlConfiguration(); when(env.plugin.getConfig()).thenReturn(config);
            BoilerConfig settings=new BoilerConfig(env.plugin);
            assertEquals("ffmpeg",BoilerConfig.mediaPlayerBackend);
            config.set("boiler.mediaplayer.backend","VLC"); settings.reloadConfig();
            assertEquals("vlc",BoilerConfig.mediaPlayerBackend);
            config.set("boiler.mediaplayer.backend","invalid"); settings.reloadConfig();
            assertThrows(IllegalArgumentException.class,() -> PlaybackBackends.create(BoilerConfig.mediaPlayerBackend));
            verify(env.plugin,never()).saveConfig();
        }
    }
    @Test void directUrlsUseSelectedBackendAndStopReplayReadsNewConfig(@TempDir Path directory) throws Exception {
        try(SourceTestEnvironment env=new SourceTestEnvironment()) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile());
            MediaPlayerTest.Leaf.loads.set(0); MediaPlayerTest.Leaf.unloads.set(0);
            JsonObject data=request("https://example.invalid/movie.mp4"); data.addProperty("loop",true); data.addProperty("buffer",3);
            MediaPlayerTest.FakePlayerSource source=new MediaPlayerTest.FakePlayerSource();
            AtomicReference<JsonObject> saved=new AtomicReference<>();
            doAnswer(call -> { saved.set(call.getArgument(0)); return null; }).when(env.display).saveSourceData(any());
            var sender=env.player();
            try {
                source.load(env.display,data); env.await(() -> source.remoteActions().contains(RemoteAction.SEEK_FORWARD));
                assertEquals("ffmpeg",source.selectedBackend);
                assertEquals(data,saved.get()); assertTrue(MediaPlayerTest.Leaf.last.data.get("loop").getAsBoolean());
                assertEquals(3,MediaPlayerTest.Leaf.last.data.get("buffer").getAsInt());
                BoilerConfig.mediaPlayerBackend="vlc"; assertEquals("ffmpeg",source.selectedBackend);
                source.onRemoteAction(sender,RemoteAction.STOP); assertEquals(1,MediaPlayerTest.Leaf.unloads.get());
                source.onRemoteAction(sender,RemoteAction.PLAY_PAUSE);
                env.await(() -> MediaPlayerTest.Leaf.loads.get()==2 && source.remoteActions().contains(RemoteAction.SEEK_FORWARD));
                assertEquals("vlc",source.selectedBackend);
                source.onRemoteAction(sender,RemoteAction.BACK); env.await(() -> saved.get().has("directory"));
                assertFalse(saved.get().has("url")); assertEquals(2,MediaPlayerTest.Leaf.unloads.get());
            } finally { source.unload(); }
        }
    }
    @Test void directRtspIsPlaybackOnlyAndInferredLive(@TempDir Path directory) throws Exception {
        try(SourceTestEnvironment env=new SourceTestEnvironment()) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile());
            MediaPlayerTest.FakePlayerSource source=new MediaPlayerTest.FakePlayerSource();
            try {
                source.load(env.display,request("rtsp://example.invalid/live/stream"));
                env.await(() -> source.remoteActions().contains(RemoteAction.SEEK_FORWARD));
                assertTrue(MediaPlayerTest.Leaf.last.data.get("live").getAsBoolean());
                assertFalse(MediaPlayerTest.Leaf.last.data.has("options"));
            } finally { source.unload(); }
        }
    }
    @Test void synchronousEndDuringStartPublishesDurationAndReleasesBackend() throws Exception {
        try(SourceTestEnvironment env=new SourceTestEnvironment()) {
            FakeBackend backend=new FakeBackend() {
                @Override public void start(IBoilerDisplay display,JsonObject data,PlaybackOutput output) { super.start(display,data,output); output.ended(); }
            };
            AtomicReference<PlaybackSession> ref=new AtomicReference<>(); AtomicLong duration=new AtomicLong();
            PlaybackSession session=new PlaybackSession(env.plugin,env.display,request("movie.mp4"),() -> backend,() -> {
                PlaybackSession current=ref.get(); if(current!=null && current.terminal()) duration.set(current.playbackState().duration());
            }); ref.set(session);
            try { env.await(() -> duration.get()==1_000_000); assertEquals("Ende der Datei",session.status()); }
            finally { session.close(); }
            assertEquals(1,backend.closes.get());
        }
    }
    @Test void failedNativeLoadIsVisibleAndDoesNotChooseAnotherBackend() throws Exception {
        try(SourceTestEnvironment env=new SourceTestEnvironment()) {
            AtomicInteger factories=new AtomicInteger(); FakeBackend backend=new FakeBackend() {
                @Override public void start(IBoilerDisplay display,JsonObject data,PlaybackOutput output) { throw new UnsatisfiedLinkError("missing-native-library"); }
            };
            PlaybackSession session=new PlaybackSession(env.plugin,env.display,request("movie.mp4"),() -> { factories.incrementAndGet(); return backend; },() -> {});
            try { env.await(() -> session.terminal() && backend.closes.get()==1); assertTrue(session.status().contains("missing-native-library")); assertTrue(session.remoteActions().isEmpty()); }
            finally { session.close(); }
            assertEquals(1,factories.get()); assertEquals(1,backend.closes.get());
        }
    }
    @Test void cancelledLoadAndLateEventsCannotAffectReplacement() throws Exception {
        try(SourceTestEnvironment env=new SourceTestEnvironment()) {
            CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
            AtomicReference<PlaybackOutput> oldOutput=new AtomicReference<>(); AtomicInteger changed=new AtomicInteger();
            FakeBackend backend=new FakeBackend() {
                @Override public void start(IBoilerDisplay display,JsonObject data,PlaybackOutput output) {
                    oldOutput.set(output); started.countDown();
                    boolean done=false;
                    while(!done) try { done=release.await(2,TimeUnit.SECONDS); } catch(InterruptedException ignored) {}
                }
            };
            PlaybackSession old=new PlaybackSession(env.plugin,env.display,request("old"),() -> backend,changed::incrementAndGet);
            assertTrue(started.await(2,TimeUnit.SECONDS)); old.close();
            PlaybackSession replacement=new PlaybackSession(env.plugin,env.display,request("new"),FakeBackend::new,() -> {});
            try {
                oldOutput.get().ended(); oldOutput.get().failed(new IllegalStateException("late")); release.countDown();
                env.await(() -> backend.closes.get()==1 && replacement.remoteActions().contains(RemoteAction.PLAY_PAUSE));
                assertEquals(0,changed.get()); assertFalse(replacement.terminal()); assertTrue(old.remoteActions().isEmpty());
            } finally { release.countDown(); old.close(); replacement.close(); }
        }
    }
    @Test void sharedPcmOutputFreezesSpectrumFlushesAndClosesOnlyOnce() {
        var audio=mock(net.somewhatcity.boiler.core.audio.BAudioPlayer.class);
        AtomicInteger ended=new AtomicInteger();
        PlaybackOutput output=new PlaybackOutput(() -> audio,true,new PlaybackOutput.Listener() {
            @Override public void ended() { ended.incrementAndGet(); }
            @Override public void failed(String message) { fail(message); }
        });
        byte[] pcm=new byte[2048]; for(int i=0;i<1024;i++) { short value=(short)(Math.sin(2*Math.PI*1000*i/48000)*12000); pcm[2*i]=(byte)value; pcm[2*i+1]=(byte)(value>>8); }
        output.play(pcm); float[] levels=output.levels(); assertTrue(java.util.stream.IntStream.range(0,24).anyMatch(i -> levels[i]>.01));
        output.suspended(true); output.play(pcm); assertArrayEquals(levels,output.levels());
        output.clear(); assertArrayEquals(new float[24],output.levels());
        output.close(); output.close(); output.play(pcm); output.ended();
        verify(audio).play(pcm); verify(audio).stop(); assertEquals(0,ended.get());
    }
    @Test void commandSupportsBrowserAndOptionalGreedyUrl() throws Exception {
        try(SourceTestEnvironment env=new SourceTestEnvironment()) {
            env.register("mediaplayer",MediaPlayerSource.class);
            when(env.plugin.displayManager().display(1)).thenReturn(env.display);
            var sender=env.player(); var stack=mock(CommandSourceStack.class); when(stack.getSender()).thenReturn(sender);
            CommandDispatcher<CommandSourceStack> dispatcher=new CommandDispatcher<>(); dispatcher.register(new DisplaySourceCommand(env.plugin).build());
            dispatcher.execute("source 1 mediaplayer",stack);
            verify(env.display).source(eq("mediaplayer"),eq(new JsonObject()));
            dispatcher.execute("source 1 mediaplayer https://example.invalid/video with spaces.mp4",stack);
            verify(env.display).source(eq("mediaplayer"),eq(request("https://example.invalid/video with spaces.mp4")));
        }
    }
    @Test void youtubeAndTwitchRedirectThroughMediaplayerAndPreserveProviderParameters(@TempDir Path directory) throws Exception {
        for(String provider:new String[]{"youtube","twitch"}) try(SourceTestEnvironment env=new SourceTestEnvironment()) {
            when(env.plugin.getDataFolder()).thenReturn(directory.toFile());
            String resolved="https://example.invalid/resolved.mp4";
            OkHttpClient http=new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(MediaType.get("application/json"),provider.equals("youtube") ? "{\"url\":\""+resolved+"\"}" : "{\"urls\":{\"480p\":\""+resolved+"\"}}" )).build()).build();
            BoilerRuntime runtime=spy(env.runtime); when(runtime.http()).thenReturn(http); when(env.plugin.runtime()).thenReturn(runtime);
            env.register("mediaplayer",MediaPlayerTest.FakePlayerSource.class);
            env.register(provider,provider.equals("youtube") ? YoutubeSource.class : TwitchSource.class);
            JsonObject original=request("https://example.invalid/provider"); original.addProperty("quality","480");
            AtomicReference<JsonObject> saved=new AtomicReference<>(); AtomicReference<String> savedName=new AtomicReference<>();
            try(SourceSession session=new SourceSession(env.plugin,env.display,() -> {},(name,data) -> { savedName.set(name); saved.set(data); })) {
                session.source(provider,original);
                env.await(() -> "mediaplayer".equals(session.sourceName()) && session.remoteActions().contains(RemoteAction.SEEK_FORWARD));
                assertEquals(resolved,MediaPlayerTest.Leaf.last.data.get("url").getAsString());
                assertEquals(provider,savedName.get()); assertEquals(original,saved.get());
                assertEquals(provider.equals("twitch"),MediaPlayerTest.Leaf.last.data.get("live").getAsBoolean());
            } finally { http.dispatcher().executorService().shutdownNow(); http.connectionPool().evictAll(); }
        }
    }
    private static JsonObject request(String url) { JsonObject data=new JsonObject(); data.addProperty("url",url); return data; }
    private static class FakeBackend implements PlaybackBackend {
        final AtomicInteger closes=new AtomicInteger();
        @Override public void start(IBoilerDisplay display,JsonObject data,PlaybackOutput output) {}
        @Override public void draw(Graphics2D graphics,Rectangle viewport) {}
        @Override public PlaybackState playbackState() { return new PlaybackState(500_000,1_000_000,false,new float[24]); }
        @Override public Set<RemoteAction> remoteActions() { return Set.of(RemoteAction.PLAY_PAUSE); }
        @Override public void action(RemoteAction action) {}
        @Override public void close() { closes.incrementAndGet(); }
    }
}
