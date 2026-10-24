package net.somewhatcity.boiler.core.media;

/** 24 logarithmic spectrum bands from signed PCM16LE, with a Hann window and 1024-point FFT. */
public final class AudioSpectrum {
    private static final int N=1024,BANDS=24;
    private AudioSpectrum() {}
    public static float[] analyze(byte[] pcm) {
        double[] real=new double[N],imaginary=new double[N];
        int count=Math.min(N,pcm.length/2);
        if(count<2) return new float[BANDS];
        int start=pcm.length/2-count;
        double mean=0;
        for(int i=0;i<count;i++) { int at=(start+i)*2; mean+=(short)((pcm[at]&255)|(pcm[at+1]&255)<<8)/32768.0; }
        mean/=count;
        for(int i=0;i<count;i++) {
            int offset=(start+i)*2;
            short sample=(short)((pcm[offset]&255)|(pcm[offset+1]&255)<<8);
            real[i]=(sample/32768.0-mean)*(.5-.5*Math.cos(2*Math.PI*i/(count-1)));
        }
        for(int i=1,j=0;i<N;i++) {
            int bit=N>>1; for(; (j&bit)!=0;bit>>=1) j^=bit; j^=bit;
            if(i<j) { double swap=real[i]; real[i]=real[j]; real[j]=swap; }
        }
        for(int size=2;size<=N;size<<=1) {
            double angle=-2*Math.PI/size,stepReal=Math.cos(angle),stepImaginary=Math.sin(angle);
            for(int startBlock=0;startBlock<N;startBlock+=size) {
                double wr=1,wi=0;
                for(int j=0;j<size/2;j++) {
                    int a=startBlock+j,b=a+size/2;
                    double re=real[b]*wr-imaginary[b]*wi,im=real[b]*wi+imaginary[b]*wr;
                    real[b]=real[a]-re; imaginary[b]=imaginary[a]-im; real[a]+=re; imaginary[a]+=im;
                    double next=wr*stepReal-wi*stepImaginary; wi=wr*stepImaginary+wi*stepReal; wr=next;
                }
            }
        }
        float[] result=new float[BANDS];
        for(int band=0;band<BANDS;band++) {
            int from=Math.max(1,(int)Math.floor(45*Math.pow(16000.0/45,band/(double)BANDS)*N/48000));
            int to=Math.min(N/2,Math.max(from+1,(int)Math.ceil(45*Math.pow(16000.0/45,(band+1)/(double)BANDS)*N/48000)));
            double peak=0;
            for(int bin=from;bin<to;bin++) peak=Math.max(peak,Math.hypot(real[bin],imaginary[bin])*4/count);
            result[band]=(float)Math.max(0,Math.min(1,(20*Math.log10(Math.max(1e-8,peak))+65)/65));
        }
        return result;
    }
}
