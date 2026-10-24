package net.somewhatcity.boiler.stream;

import java.io.*;
import java.util.zip.*;

/** Boiler stream v1. Kept byte-for-byte identical in the standalone client project. */
public final class StreamProtocol {
    private StreamProtocol() {}
    public static final int VERSION = 1, MAX_PIXELS = 2048 * 2048, MAX_MESSAGE = MAX_PIXELS * 4 + 65536;
    public static final int HELLO = 1, OFFER = 2, DISPLAY = 3, REMOVE = 4, FRAME = 5, ACK = 6, INPUT = 7;
    public static final int RGB = 0, H264 = 1;
    public static final int CAP_DRAG = 4;
    /** Drag actions use value=0 for left and value=1 for right. */
    public static final int DRAG_START = 6, DRAG_MOVE = 7, DRAG_END = 8;
    public static final String CHANNEL = "boiler:stream_v1";
    public record Vec(double x, double y, double z) {
        public Vec add(Vec b) { return new Vec(x+b.x,y+b.y,z+b.z); }
        public Vec scale(double s) { return new Vec(x*s,y*s,z*s); }
        public Vec subtract(Vec b) { return add(b.scale(-1)); }
        public double dot(Vec b) { return x*b.x+y*b.y+z*b.z; }
        public Vec cross(Vec b) { return new Vec(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x); }
    }
    /** Origin is the top left; right/down vectors contain the complete screen extent. */
    public record Display(int id, long generation, String world, int width, int height, Vec origin, Vec right, Vec down) {
        public Display {
            checkSize(width,height);
            if (world.length()>256 || !finite(origin) || !finite(right) || !finite(down)
                    || right.dot(right)<0.01 || down.dot(down)<0.01) throw new IllegalArgumentException("Invalid display geometry");
        }
        public Hit intersect(Vec eye, Vec direction, double maxDistance) {
            Vec n=right.cross(down);
            double denominator=n.dot(direction);
            if (Math.abs(denominator)<1e-8) return null;
            double distance=n.dot(origin.subtract(eye))/denominator;
            if (distance<0 || distance>maxDistance) return null;
            Vec point=eye.add(direction.scale(distance)).subtract(origin);
            double u=point.dot(right)/right.dot(right), v=point.dot(down)/down.dot(down);
            return u>=0 && u<1 && v>=0 && v<1 ? new Hit(u,v,distance) : null;
        }
    }
    public record Hit(double u,double v,double distance) {}
    public record Frame(int id,long generation,long sequence,long base,int codec,boolean key,
                        int width,int height,int x,int y,int patchWidth,int patchHeight,byte[] data) {
        public Frame {
            checkSize(width,height);
            if ((codec!=RGB && codec!=H264) || sequence<1 || base<0 || x<0 || y<0 || patchWidth<1 || patchHeight<1
                    || (long)x+patchWidth>width || (long)y+patchHeight>height || data.length>MAX_MESSAGE-128)
                throw new IllegalArgumentException("Invalid frame");
            if (key && (x!=0 || y!=0 || patchWidth!=width || patchHeight!=height)) throw new IllegalArgumentException("Partial keyframe");
        }
    }
    public record Input(int id,long generation,int action,double u,double v,int value,String text) {
        public Input {
            if (action<0 || action>DRAG_END || !Double.isFinite(u) || !Double.isFinite(v) || u<0 || u>=1 || v<0 || v>=1
                    || (action>=DRAG_START && value!=0 && value!=1) || Math.abs((long)value)>1000 || text.length()>1024) throw new IllegalArgumentException("Invalid input");
        }
    }
    @FunctionalInterface public interface Writer { void write(DataOutputStream out) throws IOException; }
    public static byte[] message(int type,Writer writer) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            DataOutputStream out=new DataOutputStream(bytes);
            out.writeByte(type); out.writeInt(VERSION); writer.write(out); out.flush();
            if (bytes.size()>MAX_MESSAGE) throw new IllegalArgumentException("Message too large");
            return bytes.toByteArray();
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    public static DataInputStream read(byte[] bytes) throws IOException {
        if (bytes.length<5 || bytes.length>MAX_MESSAGE) throw new IOException("Invalid message size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        in.mark(5); in.readByte();
        if(in.readInt()!=VERSION) throw new IOException("Unsupported Boiler protocol");
        in.reset(); return in;
    }
    public static int type(DataInputStream in) throws IOException { int type=in.readUnsignedByte(); in.readInt(); return type; }
    public static void end(DataInputStream in) throws IOException { if(in.available()!=0) throw new IOException("Trailing data"); }
    public static byte[] display(Display d) { return message(DISPLAY,out->{
        out.writeInt(d.id);out.writeLong(d.generation);out.writeUTF(d.world);out.writeInt(d.width);out.writeInt(d.height);
        vector(out,d.origin);vector(out,d.right);vector(out,d.down);
    }); }
    public static Display display(DataInputStream in) throws IOException {
        return new Display(in.readInt(),in.readLong(),in.readUTF(),in.readInt(),in.readInt(),vector(in),vector(in),vector(in));
    }
    public static byte[] frame(Frame f) { return message(FRAME,out->{
        out.writeInt(f.id);out.writeLong(f.generation);out.writeLong(f.sequence);out.writeLong(f.base);
        out.writeByte(f.codec);out.writeBoolean(f.key);out.writeInt(f.width);out.writeInt(f.height);
        out.writeInt(f.x);out.writeInt(f.y);out.writeInt(f.patchWidth);out.writeInt(f.patchHeight);
        out.writeInt(f.data.length);out.write(f.data);
    }); }
    public static Frame frame(DataInputStream in) throws IOException {
        int id=in.readInt(); long gen=in.readLong(),seq=in.readLong(),base=in.readLong();int codec=in.readUnsignedByte();boolean key=in.readBoolean();
        int w=in.readInt(),h=in.readInt(),x=in.readInt(),y=in.readInt(),pw=in.readInt(),ph=in.readInt(),len=in.readInt();
        if(len<0 || len>in.available()) throw new IOException("Invalid frame length");
        return new Frame(id,gen,seq,base,codec,key,w,h,x,y,pw,ph,in.readNBytes(len));
    }
    public static byte[] input(Input i) { return message(INPUT,out->{
        out.writeInt(i.id);out.writeLong(i.generation);out.writeByte(i.action);out.writeDouble(i.u);out.writeDouble(i.v);out.writeInt(i.value);out.writeUTF(i.text);
    }); }
    public static Input input(DataInputStream in) throws IOException { return new Input(in.readInt(),in.readLong(),in.readUnsignedByte(),in.readDouble(),in.readDouble(),in.readInt(),in.readUTF()); }
    public static byte[] compress(int[] pixels,int width,int x,int y,int w,int h) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            Deflater deflater=new Deflater(1);
            try(DeflaterOutputStream zip=new DeflaterOutputStream(bytes,deflater)) {
                byte[] row=new byte[w*3];
                for(int py=y;py<y+h;py++) {
                    for(int px=0;px<w;px++) {int color=pixels[py*width+x+px];row[px*3]=(byte)(color>>16);row[px*3+1]=(byte)(color>>8);row[px*3+2]=(byte)color;}
                    zip.write(row);
                }
            } finally { deflater.end(); }
            return bytes.toByteArray();
        } catch(IOException e) {throw new UncheckedIOException(e);}
    }
    public static void applyRgb(Frame f,int[] pixels) throws IOException {
        if(pixels.length!=f.width*f.height) throw new IOException("Pixel size mismatch");
        try(InflaterInputStream zip=new InflaterInputStream(new ByteArrayInputStream(f.data))) {
            byte[] row=new byte[f.patchWidth*3];
            for(int y=f.y;y<f.y+f.patchHeight;y++) {
                new DataInputStream(zip).readFully(row);
                for(int x=0;x<f.patchWidth;x++) pixels[y*f.width+f.x+x]=0xff000000 | (row[x*3]&255)<<16 | (row[x*3+1]&255)<<8 | row[x*3+2]&255;
            }
            if(zip.read()!=-1) throw new IOException("Excess pixel data");
        }
    }
    public static void checkSize(int w,int h) { if(w<1 || h<1 || w>2048 || h>2048 || (long)w*h>MAX_PIXELS) throw new IllegalArgumentException("Unsupported frame dimensions"); }
    private static boolean finite(Vec v) { return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z); }
    private static void vector(DataOutputStream out,Vec v) throws IOException {out.writeDouble(v.x);out.writeDouble(v.y);out.writeDouble(v.z);}
    private static Vec vector(DataInputStream in) throws IOException {return new Vec(in.readDouble(),in.readDouble(),in.readDouble());}
}
