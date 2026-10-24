package net.somewhatcity.boiler.core.dj;

import org.bytedeco.ffmpeg.avfilter.*;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.Pointer;
import java.nio.ByteBuffer;
import static org.bytedeco.ffmpeg.global.avfilter.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/** All native calls, including runtime tempo commands, belong to the mixer worker. */
public final class DjTempo implements AutoCloseable {
    private AVFilterGraph graph;
    private AVFilterContext source, tempo, sink;
    private AVFrame input, output;
    private long timestamp;

    public DjTempo(double rate) {
        try {
            graph = avfilter_graph_alloc();
            if (graph == null || graph.isNull()) throw new IllegalStateException("Kein Audiofilter-Speicher");
            graph.nb_threads(1);
            source = create("abuffer", "input", "time_base=1/48000:sample_rate=48000:sample_fmt=flt:channel_layout=mono");
            tempo = create("atempo", "tempo", Double.toString(rate));
            sink = create("abuffersink", "output", null);
            check(avfilter_link(source, 0, tempo, 0)); check(avfilter_link(tempo, 0, sink, 0));
            check(avfilter_graph_config(graph, null));
            input = av_frame_alloc(); output = av_frame_alloc();
            if (input == null || output == null || input.isNull() || output.isNull()) throw new IllegalStateException("Kein Audioframe-Speicher");
        } catch (RuntimeException error) { close(); throw error; }
    }
    private AVFilterContext create(String filter, String name, String args) {
        AVFilterContext context = new AVFilterContext((Pointer)null);
        check(avfilter_graph_create_filter(context, avfilter_get_by_name(filter), name, args, null, graph));
        return context;
    }
    private static void check(int result) {
        if (result < 0) throw new IllegalStateException("FFmpeg Audiofilter: " + result);
    }
    public void rate(double rate) {
        check(avfilter_process_command(tempo, "tempo", Double.toString(rate), (ByteBuffer)null, 0, 0));
    }
    public void push(float[] samples, int count) {
        av_frame_unref(input);
        input.format(AV_SAMPLE_FMT_FLT).sample_rate(48000).nb_samples(count).pts(timestamp);
        av_channel_layout_default(input.ch_layout(), 1);
        check(av_frame_get_buffer(input, 0));
        input.data(0).capacity(count * 4L).asByteBuffer().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(samples, 0, count);
        check(av_buffersrc_add_frame_flags(source, input, AV_BUFFERSRC_FLAG_KEEP_REF)); timestamp += count;
    }
    public void finish() { check(av_buffersrc_add_frame_flags(source, null, 0)); }
    public float[] pull() {
        av_frame_unref(output);
        int result = av_buffersink_get_frame(sink, output);
        if (result == AVERROR_EAGAIN() || result == AVERROR_EOF) return null;
        check(result);
        float[] samples = new float[output.nb_samples()];
        output.data(0).capacity(samples.length * 4L).asByteBuffer().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().get(samples);
        return samples;
    }
    @Override public void close() {
        if (input != null) { av_frame_free(input); input = null; }
        if (output != null) { av_frame_free(output); output = null; }
        if (graph != null) { avfilter_graph_free(graph); graph = null; }
    }
}
