package net.somewhatcity.boiler.core;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.RemoteAction;
import net.somewhatcity.boiler.core.audio.BAudioPlayer;
import net.somewhatcity.boiler.core.runtime.BoilerRuntime;
import net.somewhatcity.boiler.core.media.playback.FfmpegBackend;
import net.somewhatcity.boiler.core.media.playback.PlaybackOutput;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativePlaybackTest {
    @Test void threadedH264VideoWithAudioIsPresentedContinuously(@TempDir Path directory) throws Exception {
        Path video=directory.resolve("moving-av.mp4");
        try(FFmpegFrameRecorder recorder=new FFmpegFrameRecorder(video.toString(),128,64,1);
            Java2DFrameConverter converter=new Java2DFrameConverter()) {
            recorder.setFormat("mp4"); recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setVideoOption("preset","ultrafast"); recorder.setFrameRate(30);
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC); recorder.setSampleRate(48000);
            recorder.start();
            BufferedImage frame=new BufferedImage(128,64,BufferedImage.TYPE_3BYTE_BGR);
            for(int i=0;i<90;i++) {
                Graphics2D g=frame.createGraphics();
                try { g.setColor(new Color((i*29)%256,(i*61)%256,(i*97)%256)); g.fillRect(0,0,128,64); }
                finally { g.dispose(); }
                recorder.record(converter.convert(frame));
                recorder.recordSamples(48000,1,ShortBuffer.wrap(new short[1600]));
            }
        }
        withPlugin((plugin,runtime) -> {
            TestSource source=new TestSource(mock(BAudioPlayer.class));
            BufferedImage output=new BufferedImage(32,16,BufferedImage.TYPE_INT_RGB);
            try {
                source.load(display(),data(video));
                PerformancePrimitivesTest.await(() -> source.positionMicros()>0);
                long start=System.nanoTime(); int previous=0,changes=0; long lastChange=start,maxGap=0;
                while(System.nanoTime()-start<1_300_000_000L) {
                    Graphics2D g=output.createGraphics();
                    try { source.draw(g,new Rectangle(0,0,32,16)); } finally { g.dispose(); }
                    int pixel=output.getRGB(16,8);
                    if(pixel!=previous) { long now=System.nanoTime(); maxGap=Math.max(maxGap,now-lastChange); lastChange=now; previous=pixel; changes++; }
                    Thread.sleep(5);
                }
                maxGap=Math.max(maxGap,System.nanoTime()-lastChange);
                assertTrue(changes>=24,"30 FPS A/V playback only presented "+changes+" distinct images in 1.3 seconds");
                assertTrue(maxGap<250_000_000L,"Video stalled for "+maxGap/1_000_000+" ms while audio advanced");
            } finally { source.unload(); }
        });
    }

    @Test void spectrumAndProgressFollowNativeAudioAndFreezeWhenPaused(@TempDir Path directory) throws Exception {
        Path file=directory.resolve("spectrum.wav");
        try(FFmpegFrameRecorder recorder=new FFmpegFrameRecorder(file.toString(),1)) {
            recorder.setFormat("wav"); recorder.setAudioCodec(avcodec.AV_CODEC_ID_PCM_S16LE); recorder.setSampleRate(48000); recorder.start();
            for(int block=0;block<30;block++) {
                short[] samples=new short[4800]; for(int i=0;i<samples.length;i++) samples[i]=(short)(Math.sin(2*Math.PI*1000*i/48000)*12000);
                recorder.recordSamples(48000,1,ShortBuffer.wrap(samples));
            }
        }
        withPlugin((plugin,runtime) -> {
            TestSource source=new TestSource(mock(BAudioPlayer.class));
            var sender=mock(org.bukkit.command.CommandSender.class); when(sender.hasPermission("boiler.command.action")).thenReturn(true);
            JsonObject input=data(file); input.addProperty("visualizer",true);
            try {
                source.load(display(),input);
                PerformancePrimitivesTest.await(() -> source.positionMicros()>0 && source.playbackState().levels()[12]>.1f);
                source.onRemoteAction(sender,RemoteAction.PLAY_PAUSE);
                var state=source.playbackState(); assertTrue(state.paused()); assertEquals(3_000_000,state.duration());
                Thread.sleep(100);
                assertEquals(state.position(),source.playbackState().position()); assertArrayEquals(state.levels(),source.playbackState().levels());
                source.onRemoteAction(sender,RemoteAction.SEEK_FORWARD);
                assertArrayEquals(new float[24],source.playbackState().levels());
                assertEquals(state.position(),source.playbackState().position());
                source.onRemoteAction(sender,RemoteAction.PLAY_PAUSE);
                PerformancePrimitivesTest.await(() -> source.positionMicros()>state.position()+50_000);
            } finally { source.unload(); }
        });
    }
    @Test void audioSeekFlushesOldSamplesAndResumesOnTheNewMediaPosition(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("seek-audio.wav");
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(file.toString(),1)) {
            recorder.setFormat("wav"); recorder.setAudioCodec(avcodec.AV_CODEC_ID_PCM_S16LE); recorder.setSampleRate(48000); recorder.start();
            for (int block=0;block<140;block++) {
                short[] samples = new short[4800]; java.util.Arrays.fill(samples,(short)(block<40 ? 1000 : 4000));
                recorder.recordSamples(48000,1,ShortBuffer.wrap(samples));
            }
        }
        withPlugin((plugin,runtime) -> {
            BAudioPlayer audio = mock(BAudioPlayer.class);
            java.util.concurrent.atomic.AtomicInteger sample = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(invocation -> {
                byte[] bytes = invocation.getArgument(0); sample.set((short)((bytes[0]&255)|(bytes[1]&255)<<8)); return null;
            }).when(audio).play(any());
            TestSource source = new TestSource(audio);
            var sender = mock(org.bukkit.command.CommandSender.class); when(sender.hasPermission("boiler.command.action")).thenReturn(true);
            try {
                source.load(display(),data(file));
                PerformancePrimitivesTest.await(() -> source.remoteActions().contains(RemoteAction.SEEK_FORWARD) && sample.get() == 1000);
                source.onRemoteAction(sender,RemoteAction.PLAY_PAUSE);
                verify(audio).suspended(true);
                source.onRemoteAction(sender,RemoteAction.SEEK_FORWARD);
                source.onRemoteAction(sender,RemoteAction.PLAY_PAUSE);
                PerformancePrimitivesTest.await(() -> source.positionMicros() >= 10_000_000 && sample.get() == 4000);
                source.onRemoteAction(sender,RemoteAction.SEEK_BACKWARD);
                PerformancePrimitivesTest.await(() -> source.positionMicros() < 2_000_000 && sample.get() == 1000);
                verify(audio,atLeast(2)).clear(); verify(audio,atLeastOnce()).suspended(false);
            } finally { source.unload(); }
            verify(audio).stop();
        });
    }
    @Test void nativePausePreservesPositionAndSeekingResetsTheTimeline(@TempDir Path directory) throws Exception {
        Path video = directory.resolve("seek.mp4"); createVideo(video,140);
        withPlugin((plugin,runtime) -> {
            TestSource source = new TestSource(mock(BAudioPlayer.class));
            org.bukkit.command.CommandSender sender = mock(org.bukkit.command.CommandSender.class);
            when(sender.hasPermission("boiler.command.action")).thenReturn(true);
            try {
                source.load(display(),data(video));
                PerformancePrimitivesTest.await(() -> source.remoteActions().contains(RemoteAction.SEEK_FORWARD) && source.positionMicros() >= 100_000);
                source.onRemoteAction(sender,RemoteAction.PLAY_PAUSE);
                long pausedPosition = source.positionMicros();
                Thread.sleep(180);
                assertTrue(source.paused()); assertEquals(pausedPosition,source.positionMicros());
                source.onRemoteAction(sender,RemoteAction.SEEK_FORWARD);
                Thread.sleep(80); assertEquals(pausedPosition,source.positionMicros());
                source.onRemoteAction(sender,RemoteAction.PLAY_PAUSE);
                PerformancePrimitivesTest.await(() -> source.positionMicros() >= 10_000_000);
                source.onRemoteAction(sender,RemoteAction.SEEK_BACKWARD);
                PerformancePrimitivesTest.await(() -> source.positionMicros() < 2_000_000);
                assertFalse(source.paused());
            } finally { source.unload(); }
        });
    }

    @Test void liveMediaNeverAdvertisesPauseOrSeek(@TempDir Path directory) throws Exception {
        Path video = directory.resolve("live.mp4"); createVideo(video,30);
        withPlugin((plugin,runtime) -> {
            TestSource source = new TestSource(mock(BAudioPlayer.class));
            JsonObject input = data(video); input.addProperty("live",true);
            try {
                source.load(display(),input);
                PerformancePrimitivesTest.await(() -> source.positionMicros() > 0);
                assertFalse(source.remoteActions().contains(RemoteAction.PLAY_PAUSE));
                assertFalse(source.remoteActions().contains(RemoteAction.SEEK_FORWARD));
            } finally { source.unload(); }
        });
    }
    @Test void videoWithoutAudioIsPacedAndReachesLastFrame(@TempDir Path directory) throws Exception {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        Path video = directory.resolve("video.mp4");
        createVideo(video, 4);
        withPlugin((plugin, runtime) -> {
            IBoilerDisplay display = display();
            CountDownLatch ended = new CountDownLatch(1);
            doAnswer(invocation -> { ended.countDown(); return null; }).when(display).source(eq("error"), any());
            {
                BAudioPlayer audio = mock(BAudioPlayer.class);
                TestSource source = new TestSource(audio);
                long start = System.nanoTime();
                try {
                    source.load(display, data(video));
                    assertTrue(ended.await(4, TimeUnit.SECONDS), "Video-only playback never reached EOF");
                    assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(200), "Playback ignored media timestamps");
                    BufferedImage output = new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = output.createGraphics();
                    try { source.draw(graphics, new Rectangle(0, 0, 32, 16)); }
                    finally { graphics.dispose(); }
                    Color pixel = new Color(output.getRGB(16, 8));
                    assertTrue(pixel.getBlue() > 200 && pixel.getRed() < 30, "Final decoded frame was not displayed");
                    verify(audio, never()).play(any());
                    assertEquals(0, ((TestSource) source).audioCreations, "Video-only source created an audio encoder");
                } finally { source.unload(); }
            }
        });
    }

    @Test void nativeAudioIsConvertedTo48KhzMonoLittleEndian(@TempDir Path directory) throws Exception {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        Path audioFile = directory.resolve("audio.wav");
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(audioFile.toString(), 2)) {
            recorder.setFormat("wav");
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_PCM_S16LE);
            recorder.setSampleRate(44100);
            recorder.start();
            short[] samples = new short[8820 * 2];
            java.util.Arrays.fill(samples, (short) 4000);
            recorder.recordSamples(44100, 2, ShortBuffer.wrap(samples));
        }
        withPlugin((plugin, runtime) -> {
            IBoilerDisplay display = display();
            CountDownLatch ended = new CountDownLatch(1);
            doAnswer(invocation -> { ended.countDown(); return null; }).when(display).source(eq("error"), any());
            {
                BAudioPlayer audio = mock(BAudioPlayer.class);
                TestSource source = new TestSource(audio);
                try {
                    source.load(display, data(audioFile));
                    assertTrue(ended.await(4, TimeUnit.SECONDS));
                    var chunks = org.mockito.ArgumentCaptor.forClass(byte[].class);
                    verify(audio, atLeastOnce()).play(chunks.capture());
                    int total = chunks.getAllValues().stream().mapToInt(bytes -> bytes.length).sum();
                    assertTrue(total > 18000 && total <= 19200, "Unexpected resampled byte count: " + total);
                    for (byte[] bytes : chunks.getAllValues()) {
                        assertEquals(0, bytes.length % 2);
                        short sample = (short) ((bytes[0] & 255) | (bytes[1] & 255) << 8);
                        assertTrue(sample > 3000 && sample < 6500, "PCM byte order or mono conversion is incorrect");
                    }
                } finally { source.unload(); }
            }
        });
    }

    @Test void repeatedNativeCancellationReleasesDecodeAndPlaybackThreads(@TempDir Path directory) throws Exception {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        Path video = directory.resolve("long.mp4");
        createVideo(video, 30);
        withPlugin((plugin, runtime) -> {
            {
                BAudioPlayer audio = mock(BAudioPlayer.class);
                for (int i = 0; i < 10; i++) {
                    TestSource source = new TestSource(audio);
                    source.load(display(), data(video));
                    Thread.sleep(15);
                    source.unload();
                    source.unload();
                    PerformancePrimitivesTest.await(() -> Thread.getAllStackTraces().keySet().stream()
                            .noneMatch(thread -> thread.isAlive() && (thread.getName().equals("Boiler-decode-99") || thread.getName().equals("Boiler-playback-99"))));
                }
            }
        });
    }

    @Test void loopingKeepsTheFinalFrameForItsFrameDuration(@TempDir Path directory) throws Exception {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        Path video = directory.resolve("loop.mp4");
        createVideo(video, 4);
        withPlugin((plugin, runtime) -> {
            IBoilerDisplay display = display();
            TestSource source = new TestSource(mock(BAudioPlayer.class));
            JsonObject data = data(video);
            data.addProperty("loop", true);
            BufferedImage output = new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB);
            try {
                source.load(display, data);
                PerformancePrimitivesTest.await(() -> {
                    Graphics2D graphics = output.createGraphics();
                    try { source.draw(graphics, new Rectangle(0, 0, 32, 16)); }
                    finally { graphics.dispose(); }
                    return new Color(output.getRGB(16, 8)).getBlue() > 200;
                });
                Thread.sleep(35);
                Graphics2D graphics = output.createGraphics();
                try { source.draw(graphics, new Rectangle(0, 0, 32, 16)); }
                finally { graphics.dispose(); }
                assertTrue(new Color(output.getRGB(16, 8)).getBlue() > 200, "Loop restarted before the final 100 ms frame finished");
                verify(display, never()).source(anyString(), any());
            } finally { source.unload(); }
        });
    }

    static class TestSource extends FfmpegBackend {
        private final BAudioPlayer audio;
        volatile int audioCreations;
        TestSource(BAudioPlayer audio) { this.audio = audio; }
        void load(IBoilerDisplay display,JsonObject data) {
            boolean visualizer=data.has("visualizer") && data.get("visualizer").getAsBoolean();
            start(display,data,new PlaybackOutput(() -> { audioCreations++; return audio; },visualizer,new PlaybackOutput.Listener() {
                @Override public void ended() { display.source("error",new JsonObject()); }
                @Override public void failed(String message) { throw new AssertionError(message); }
            }));
        }
        void unload() { close(); }
        void onRemoteAction(org.bukkit.command.CommandSender sender,RemoteAction action) { action(action); }
    }

    private static JsonObject data(Path file) {
        JsonObject data = new JsonObject();
        data.addProperty("url", file.toString());
        data.addProperty("buffer", 1);
        return data;
    }
    private static IBoilerDisplay display() {
        IBoilerDisplay display = mock(IBoilerDisplay.class);
        when(display.id()).thenReturn(99);
        when(display.width()).thenReturn(32);
        when(display.height()).thenReturn(16);
        return display;
    }
    private static void createVideo(Path target, int frames) throws Exception {
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(target.toString(), 32, 16, 0);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            recorder.setFormat("mp4");
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
            recorder.setFrameRate(10);
            recorder.start();
            BufferedImage image = new BufferedImage(32, 16, BufferedImage.TYPE_3BYTE_BGR);
            for (int i = 0; i < frames; i++) {
                Graphics2D graphics = image.createGraphics();
                try {
                    graphics.setColor(i == frames - 1 ? Color.BLUE : Color.RED);
                    graphics.fillRect(0, 0, 32, 16);
                } finally { graphics.dispose(); }
                recorder.record(converter.convert(image));
            }
        }
    }
    @FunctionalInterface interface Check { void run(BoilerPlugin plugin, BoilerRuntime runtime) throws Exception; }
    private void withPlugin(Check check) throws Exception {
        BoilerPlugin previous = BoilerPlugin.getPlugin();
        Field singleton = BoilerPlugin.class.getDeclaredField("plugin");
        singleton.setAccessible(true);
        try (BoilerRuntime runtime = new BoilerRuntime()) {
            BoilerPlugin plugin = mock(BoilerPlugin.class);
            singleton.set(null, plugin);
            when(plugin.runtime()).thenReturn(runtime);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("BoilerNativeTest"));
            when(plugin.callOnMain(any())).thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());
            doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; }).when(plugin).onMain(any());
            check.run(plugin, runtime);
        } finally { singleton.set(null, previous); }
    }
}
