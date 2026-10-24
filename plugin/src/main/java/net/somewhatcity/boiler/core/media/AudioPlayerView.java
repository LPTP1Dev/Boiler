package net.somewhatcity.boiler.core.media;

import net.somewhatcity.boiler.core.smarttv.MinecraftText;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import static net.somewhatcity.boiler.core.smarttv.SmartTvStyle.*;

/** Pixel-aligned audio screen and shared playback timeline. */
public final class AudioPlayerView {
    private AudioPlayerView() {}
    public static void centered(Graphics2D g,String value,int y,int width,int margin) {
        String fitted=MinecraftText.fit(g,value,Math.max(0,width-margin*2));
        drawText(g,fitted,(width-textWidth(g,fitted))/2,y);
    }
    public static List<String> titleLines(Graphics2D g,String value,int width) {
        List<String> lines=new ArrayList<>(); String remaining=value.trim();
        while(!remaining.isEmpty() && lines.size()<2) {
            if(textWidth(g,remaining)<=width) { lines.add(remaining); break; }
            if(lines.size()==1) { lines.add(MinecraftText.fit(g,remaining,width)); break; }
            int count=0; while(count<remaining.length() && textWidth(g,remaining.substring(0,count+1))<=width) count++;
            if(count==0) { lines.add(MinecraftText.fit(g,remaining,width)); break; }
            int space=remaining.lastIndexOf(' ',count-1); if(space>0) count=space;
            lines.add(remaining.substring(0,count).stripTrailing()); remaining=remaining.substring(count).stripLeading();
        }
        return List.copyOf(lines);
    }
    public static void draw(Graphics2D g,int width,int height,String title,String status,PlaybackState state,boolean stopped) {
        pixels(g); g.setColor(new Color(12,17,27)); g.fillRect(0,0,width,height);
        int s=Math.max(1,Math.min(width/384,height/256)); boolean compact=height<192;
        int titleScale=Math.max(1,Math.min(width/192,height/128));
        int iconSize=(compact ? 12 : 32)*s,iconY=compact ? 7 : Math.max(8*s,height/6-iconSize/2);
        icon(g,"audio",(width-iconSize)/2,iconY,iconSize,new Color(112,210,222));
        font(g,Font.PLAIN,8*titleScale); g.setColor(WHITE);
        List<String> lines=titleLines(g,title,width-16*s);
        int baseline=iconY+iconSize+(compact ? 10 : 20)*s;
        for(String line:lines) { centered(g,line,baseline,width,8*s); baseline+=10*titleScale; }
        font(g,Font.PLAIN,8*s); g.setColor(MUTED);
        int statusY=baseline+2*s;
        centered(g,stopped ? status : state.paused() ? "Pausiert" : status,statusY,width,8*s);
        int top=statusY+8*s,bottom=height-52*s;
        float[] levels=state.levels();
        int count=Math.min(levels.length,Math.max(4,(width-24*s)/(5*s))),gap=2*s;
        int total=Math.min(width-24*s,count*10*s),bar=Math.max(1,(total-gap*(count-1))/count);
        int left=(width-(bar*count+gap*(count-1)))/2;
        if(bottom>top) for(int i=0;i<count;i++) {
            float level=stopped ? 0 : levels[i*levels.length/count];
            int barHeight=Math.max(s,(int)(level*(bottom-top))/s*s);
            g.setColor(state.paused() || stopped ? new Color(65,104,119) : new Color(83,211,198));
            g.fillRect(left+i*(bar+gap),bottom-barHeight,bar,barHeight);
        }
        progress(g,width,height,state);
    }
    public static int filledPixels(long position,long duration,int width) {
        return duration<=0 ? 0 : (int)Math.round(Math.min(1,Math.max(0,position/(double)duration))*Math.max(0,width));
    }
    public static String time(long micros) {
        long seconds=Math.max(0,micros)/1_000_000;
        return seconds>=3600 ? String.format(java.util.Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60) : String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
    }
    public static void progress(Graphics2D g,int width,int height,PlaybackState state) {
        int s=Math.max(1,Math.min(width/384,height/256)),margin=8*s,barY=height-39*s,barWidth=Math.max(1,width-2*margin);
        g.setColor(new Color(30,42,57)); g.fillRect(margin,barY,barWidth,3*s);
        g.setColor(new Color(83,211,198)); g.fillRect(margin,barY,filledPixels(state.position(),state.duration(),barWidth),3*s);
        font(g,Font.PLAIN,8*s); g.setColor(MUTED);
        String elapsed=time(state.position()),duration=state.duration()>0 ? time(state.duration()) : "--:--";
        text(g,elapsed,margin,height-22*s,barWidth/2);
        String end=MinecraftText.fit(g,duration,barWidth/2); drawText(g,end,width-margin-textWidth(g,end),height-22*s);
    }
}
