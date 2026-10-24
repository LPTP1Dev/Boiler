package net.somewhatcity.boiler.core.media;

import net.somewhatcity.boiler.core.smarttv.MinecraftText;
import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AudioPlayerViewTest {
    @Test void spectrumUsesActualFrequencyContentAndNeverChangesPcm() {
        byte[] low=tone(250),high=tone(8000),original=low.clone();
        float[] bass=AudioSpectrum.analyze(low),treble=AudioSpectrum.analyze(high);
        assertTrue(peak(bass)<peak(treble));
        assertTrue(bass[peak(bass)]>.5f); assertTrue(treble[peak(treble)]>.5f);
        assertArrayEquals(original,low);
        assertArrayEquals(new float[24],AudioSpectrum.analyze(new byte[2048]));
        byte[] dc=new byte[2048]; for(int i=0;i<dc.length;i+=2) { dc[i]=(byte)0xa0; dc[i+1]=15; }
        assertArrayEquals(new float[24],AudioSpectrum.analyze(dc));
        for(float value:treble) assertTrue(Float.isFinite(value) && value>=0 && value<=1);
    }
    @Test void progressClampsAndFormatsMediaTimeAndSnapshotsAreImmutable() {
        assertEquals(25,AudioPlayerView.filledPixels(45,180,100));
        assertEquals(0,AudioPlayerView.filledPixels(-1,180,100));
        assertEquals(100,AudioPlayerView.filledPixels(999,180,100));
        assertEquals(0,AudioPlayerView.filledPixels(9,0,100));
        assertEquals("1:05",AudioPlayerView.time(65_000_000));
        assertEquals("1:01:01",AudioPlayerView.time(3661_000_000L));
        float[] levels=new float[24]; levels[0]=.5f;
        PlaybackState state=new PlaybackState(1,2,false,levels); levels[0]=1;
        assertEquals(.5f,state.levels()[0]); float[] copy=state.levels(); copy[0]=0; assertEquals(.5f,state.levels()[0]);
    }
    @Test void titleWrappingAndCenteringUseMinecraftGlyphWidths() {
        BufferedImage image=new BufferedImage(384,40,BufferedImage.TYPE_INT_ARGB); Graphics2D g=image.createGraphics();
        try {
            MinecraftText.font(g,Font.PLAIN,16); g.setColor(Color.WHITE);
            for(int width:new int[]{112,240,368}) {
                var lines=AudioPlayerView.titleLines(g,"KUKO & Tokio Hotel - Catharsis.mp3",width);
                assertTrue(lines.size()<=2); for(String line:lines) assertTrue(MinecraftText.width(g,line)<=width);
            }
            AudioPlayerView.centered(g,"ABC123",24,384,8);
        } finally { g.dispose(); }
        int min=384,max=-1;
        for(int y=0;y<40;y++) for(int x=0;x<384;x++) if(image.getRGB(x,y)!=0) { min=Math.min(min,x); max=Math.max(max,x); }
        assertTrue(Math.abs((min+max)/2.0-191.5)<=1);
    }
    @Test void generatesAudioPreviewsAtAllMapSizes() throws Exception {
        Path directory=Path.of("build/reports/mediaplayer-preview"); Files.createDirectories(directory);
        float[] spectrum=AudioSpectrum.analyze(tone(1000));
        for(int[] size:new int[][]{{128,128},{384,128},{384,256},{256,384},{768,512}}) {
            BufferedImage image=new BufferedImage(size[0],size[1],BufferedImage.TYPE_INT_RGB); Graphics2D g=image.createGraphics();
            try { AudioPlayerView.draw(g,size[0],size[1],"KUKO & Tokio Hotel - Catharsis.mp3","",new PlaybackState(83_000_000,219_000_000,false,spectrum),false); }
            finally { g.dispose(); }
            javax.imageio.ImageIO.write(image,"png",directory.resolve("audio-"+size[0]+"x"+size[1]+".png").toFile());
        }
    }
    private static int peak(float[] bands) { int best=0; for(int i=1;i<bands.length;i++) if(bands[i]>bands[best]) best=i; return best; }
    private static byte[] tone(double frequency) {
        byte[] bytes=new byte[2048];
        for(int i=0;i<1024;i++) { short value=(short)(Math.sin(2*Math.PI*frequency*i/48000)*20000); bytes[i*2]=(byte)value; bytes[i*2+1]=(byte)(value>>8); }
        return bytes;
    }
}
