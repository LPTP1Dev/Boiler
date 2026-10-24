package net.somewhatcity.boiler.stream;

import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import java.nio.ByteOrder;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/** One low-delay H.264 stream. Confined to its encode/decode worker. No file/container buffers. */
public final class H264Codec implements AutoCloseable {
    private AVCodecContext context;
    private AVFrame frame, packed;
    private AVPacket packet;
    private BytePointer pixels;
    private SwsContext scaler;
    private final int width,height;
    private final boolean encode;
    private long pts;
    public H264Codec(int width,int height,boolean encode) {
        StreamProtocol.checkSize(width,height);
        this.width=width;this.height=height;this.encode=encode;
        try {
            AVCodec codec=encode ? avcodec_find_encoder_by_name("libx264") : avcodec_find_decoder(AV_CODEC_ID_H264);
            if(encode && (codec==null || codec.isNull())) codec=avcodec_find_encoder_by_name("libopenh264");
            if(codec==null || codec.isNull()) throw new IllegalStateException("H.264 codec is unavailable");
            context=avcodec_alloc_context3(codec);
            context.thread_count(1).max_pixels(StreamProtocol.MAX_PIXELS);
            if(encode) {
                context.width(width).height(height).pix_fmt(AV_PIX_FMT_YUV420P).gop_size(30).max_b_frames(0);
                context.bit_rate(Math.max(500_000L,(long)width*height*6));
                context.time_base().num(1).den(30);
                av_opt_set(context.priv_data(),"preset","ultrafast",0);
                av_opt_set(context.priv_data(),"tune","zerolatency",0);
                av_opt_set(context.priv_data(),"crf","20",0);
                av_opt_set(context.priv_data(),"x264-params","repeat-headers=1:annexb=1:scenecut=0",0);
                context.colorspace(AVCOL_SPC_BT709).color_range(AVCOL_RANGE_MPEG);
            }
            check(avcodec_open2(context,codec,(AVDictionary)null));
            frame=av_frame_alloc(); packed=av_frame_alloc();packet=av_packet_alloc();pixels=new BytePointer((long)width*height*4);
            av_image_fill_arrays(packed.data(),packed.linesize(),pixels,AV_PIX_FMT_BGRA,width,height,1);
            if(encode) {
                frame.width(width).height(height).format(AV_PIX_FMT_YUV420P);
                check(av_frame_get_buffer(frame,32));
                scaler=sws_getContext(width,height,AV_PIX_FMT_BGRA,width,height,AV_PIX_FMT_YUV420P,SWS_BILINEAR,null,null,(double[])null);
                check(sws_setColorspaceDetails(scaler,sws_getCoefficients(SWS_CS_ITU709),1,sws_getCoefficients(SWS_CS_ITU709),0,0,1<<16,1<<16));
            }
        } catch(Throwable e) { close();throw e; }
    }
    public byte[] encode(int[] rgb,boolean key) {
        if(!encode) throw new IllegalStateException();
        pixels.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(rgb);
        check(av_frame_make_writable(frame));
        sws_scale(scaler,packed.data(),packed.linesize(),0,height,frame.data(),frame.linesize());
        frame.pts(pts++).pict_type(key ? AV_PICTURE_TYPE_I : AV_PICTURE_TYPE_NONE);
        check(avcodec_send_frame(context,frame));
        try {
            check(avcodec_receive_packet(context,packet));
            byte[] bytes=new byte[packet.size()];packet.data().get(bytes);return bytes;
        } finally {av_packet_unref(packet);}
    }
    public int[] decode(byte[] bytes) {
        if(encode) throw new IllegalStateException();
        check(av_new_packet(packet,bytes.length));packet.data().put(bytes);
        try {check(avcodec_send_packet(context,packet));} finally {av_packet_unref(packet);}
        int result=avcodec_receive_frame(context,frame);
        if(result<0) throw new IllegalStateException("H.264 decoder did not produce a frame: "+result);
        try {
            if(frame.width()!=width || frame.height()!=height) throw new IllegalStateException("Unexpected H.264 dimensions");
            scaler=sws_getCachedContext(scaler,width,height,frame.format(),width,height,AV_PIX_FMT_BGRA,SWS_BILINEAR,null,null,(double[])null);
            check(sws_setColorspaceDetails(scaler,sws_getCoefficients(SWS_CS_ITU709),0,sws_getCoefficients(SWS_CS_ITU709),1,0,1<<16,1<<16));
            sws_scale(scaler,frame.data(),frame.linesize(),0,height,packed.data(),packed.linesize());
            int[] rgb=new int[width*height];pixels.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(rgb);return rgb;
        } finally {av_frame_unref(frame);}
    }
    private static void check(int code) {if(code<0) throw new IllegalStateException("FFmpeg error: "+code);}
    @Override public void close() {
        if(scaler!=null) {sws_freeContext(scaler);scaler=null;}
        if(packet!=null) {av_packet_free(packet);packet=null;}
        if(frame!=null) {av_frame_free(frame);frame=null;}
        if(packed!=null) {av_frame_free(packed);packed=null;}
        if(context!=null) {avcodec_free_context(context);context=null;}
        if(pixels!=null) {pixels.close();pixels=null;}
    }
}
