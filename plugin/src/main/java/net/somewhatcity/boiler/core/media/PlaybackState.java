package net.somewhatcity.boiler.core.media;

/** Immutable render snapshot; never exposes decoder buffers. Times are microseconds. */
public record PlaybackState(long position,long duration,boolean paused,float[] levels) {
    public static final PlaybackState EMPTY=new PlaybackState(0,0,false,new float[24]);
    public PlaybackState { levels=levels.clone(); }
    @Override public float[] levels() { return levels.clone(); }
}
