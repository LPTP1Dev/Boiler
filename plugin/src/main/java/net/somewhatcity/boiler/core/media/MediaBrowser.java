package net.somewhatcity.boiler.core.media;

import java.awt.*;
import java.util.List;
import static net.somewhatcity.boiler.core.smarttv.SmartTvStyle.*;

public final class MediaBrowser {
    public record View(String directory,List<MediaLibrary.Entry> entries,int selected,String status,boolean loading) {
        public View { entries = List.copyOf(entries); }
    }
    public record Layout(int width,int height) {
        public int scale() { return Math.max(1,Math.min(width/384,height/256)); }
        public int rowHeight() { return 20*scale(); }
        public int top() { return 44*scale(); }
        public int rows() { return Math.max(1,(height-top()-24*scale())/rowHeight()); }
        public Rectangle back() { return new Rectangle(4,4,20*scale(),18*scale()); }
        public Rectangle refresh() { return new Rectangle(width-24*scale(),4,20*scale(),18*scale()); }
        public Rectangle row(int slot) { return new Rectangle(4,top()+slot*rowHeight(),width-8,rowHeight()-2); }
        public Rectangle previous() { return new Rectangle(0,height-18*scale(),width/2,18*scale()); }
        public Rectangle next() { return new Rectangle(width/2,height-18*scale(),width-width/2,18*scale()); }
    }
    private MediaBrowser() {}
    public static void draw(Graphics2D g,int width,int height,View view) {
        pixels(g); Layout layout = new Layout(width,height); int s=layout.scale();
        g.setColor(new Color(12,17,27)); g.fillRect(0,0,width,height);
        g.setColor(new Color(29,42,61)); g.fillRect(0,0,width,24*s);
        font(g,Font.PLAIN,8*s); g.setColor(WHITE);
        text(g,"<",8,15*s,14*s); text(g,"R",width-19*s,15*s,14*s);
        text(g,"Mediaplayer",28*s,15*s,width-56*s);
        g.setColor(MUTED); text(g,"media / "+view.directory(),6,35*s,width-12);
        int page = view.selected()/layout.rows();
        if (view.loading() || view.entries().isEmpty()) {
            g.setColor(WHITE); lines(g,view.status().isBlank() ? "Keine Dateien. Dateien in plugins/Boiler/media ablegen." : view.status(),6,layout.top()+12*s,width-12,3,11*s);
        } else for (int slot=0;slot<layout.rows();slot++) {
            int index=page*layout.rows()+slot; if (index>=view.entries().size()) break;
            MediaLibrary.Entry entry=view.entries().get(index); Rectangle row=layout.row(slot);
            boolean selected=index==view.selected();
            g.setColor(selected ? new Color(38,105,135) : new Color(24,34,49)); g.fillRect(row.x,row.y,row.width,row.height);
            if(selected) { g.setColor(new Color(117,222,241)); g.drawRect(row.x,row.y,row.width-1,row.height-1); }
            String tag=switch(entry.kind()) { case DIRECTORY -> "DIR"; case IMAGE -> "IMG"; case VIDEO -> "VID"; case AUDIO -> "TON"; default -> "???"; };
            g.setColor(entry.kind()==MediaLibrary.Kind.DIRECTORY ? new Color(247,201,101) : entry.kind()==MediaLibrary.Kind.OTHER ? MUTED : new Color(112,210,222));
            text(g,tag,row.x+4*s,row.y+13*s,24*s);
            g.setColor(entry.kind()==MediaLibrary.Kind.OTHER ? MUTED : WHITE);
            int nameWidth=row.width-36*s-(width>=384 ? 60*s : 0);
            text(g,entry.name(),row.x+32*s,row.y+13*s,nameWidth);
            if(width>=384 && entry.kind()!=MediaLibrary.Kind.DIRECTORY) { g.setColor(MUTED); text(g,size(entry.bytes()),row.x+row.width-56*s,row.y+13*s,52*s); }
        }
        int pages=Math.max(1,(view.entries().size()+layout.rows()-1)/layout.rows());
        g.setColor(MUTED); text(g,view.status().isBlank() ? "< "+(page+1)+"/"+pages+" >  OK: Oeffnen" : view.status(),6,height-7*s,width-12);
    }
    private static String size(long bytes) { return bytes<1024 ? bytes+" B" : bytes<1024*1024 ? bytes/1024+" KB" : bytes/(1024*1024)+" MB"; }
}
