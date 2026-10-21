import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

/**
 * Fast image resize on plain int[] pixels (TYPE_INT_RGB). Java2D's bilinear drawImage runs on generic software loops
 * and took ~80 ms for 1920x1080 -> 1600x900; this takes a few ms. Large reductions are first halved with a 2x2 average
 * (keeps small text readable, no shimmering), then finished with bilinear filtering. Rows are split across CPU cores.
 */
final class FastScale {
    private FastScale(){}
    static BufferedImage resize(BufferedImage source,int width,int height){
        BufferedImage src=StreamQuality.rgb(source);int sw=src.getWidth(),sh=src.getHeight();
        if(sw==width&&sh==height)return src;
        int[] pixels=StreamQuality.pixels(src);
        while(sw>=width*2&&sh>=height*2){pixels=halve(pixels,sw,sh);sw/=2;sh/=2;}
        BufferedImage out=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        bilinear(pixels,sw,sh,StreamQuality.pixels(out),width,height);return out;
    }
    static int[] halve(int[] s,int sw,int sh){
        int dw=sw/2,dh=sh/2;int[] d=new int[dw*dh];
        rows(dh,y->{int a=2*y*sw,b=a+sw,o=y*dw;for(int x=0;x<dw;x++,a+=2,b+=2){int p=s[a],q=s[a+1],r=s[b],t=s[b+1];
            int rb=((p&0xff00ff)+(q&0xff00ff)+(r&0xff00ff)+(t&0xff00ff)+0x020002)>>>2&0xff00ff,g=((p&0xff00)+(q&0xff00)+(r&0xff00)+(t&0xff00)+0x200)>>>2&0xff00;d[o+x]=rb|g;}});
        return d;
    }
    static void bilinear(int[] s,int sw,int sh,int[] d,int dw,int dh){bilinear(s,sw,sh,d,dw,dh,0,0,dw,dh);}
    /** Bilinear resize of the destination region [dx0,dx1) x [dy0,dy1) only (used to refresh just the changed part of a scaled frame). */
    static void bilinear(int[] s,int sw,int sh,int[] d,int dw,int dh,int dx0,int dy0,int dx1,int dy1){
        int cols=dx1-dx0;if(cols<=0||dy1<=dy0)return;int[] x0=new int[cols],fx=new int[cols];
        for(int c=0;c<cols;c++){int x=dx0+c;double sx=Math.max(0,(x+.5)*sw/dw-.5);int i=Math.min(sw-1,(int)sx);x0[c]=i;fx[c]=i>=sw-1?0:(int)((sx-i)*256);}
        rows(dy1-dy0,row->{int y=dy0+row;double sy=Math.max(0,(y+.5)*sh/dh-.5);int j=Math.min(sh-1,(int)sy),fy=j>=sh-1?0:(int)((sy-j)*256),gy=256-fy;int r0=j*sw,r1=Math.min(sh-1,j+1)*sw,o=y*dw+dx0;
            for(int x=0;x<cols;x++){int i=x0[x],f=fx[x],g=256-f,i1=f==0?i:i+1;
                int p=s[r0+i],q=s[r0+i1],r=s[r1+i],t=s[r1+i1];
                int top=((((p&0xff00ff)*g+(q&0xff00ff)*f)>>>8)&0xff00ff)|((((p&0xff00)*g+(q&0xff00)*f)>>>8)&0xff00);
                int bottom=((((r&0xff00ff)*g+(t&0xff00ff)*f)>>>8)&0xff00ff)|((((r&0xff00)*g+(t&0xff00)*f)>>>8)&0xff00);
                d[o+x]=((((top&0xff00ff)*gy+(bottom&0xff00ff)*fy)>>>8)&0xff00ff)|((((top&0xff00)*gy+(bottom&0xff00)*fy)>>>8)&0xff00);}});
    }
    /**
     * Updates {@code scaled} (a resize of the previous frame) for the changed source rectangles of {@code frame} and returns a new image.
     * Returns null when a full resize is needed (strong reduction that uses 2x2 averaging).
     */
    static BufferedImage update(BufferedImage scaled,BufferedImage frame,java.util.List<java.awt.Rectangle> changed){
        BufferedImage src=StreamQuality.rgb(frame);int sw=src.getWidth(),sh=src.getHeight(),dw=scaled.getWidth(),dh=scaled.getHeight();
        if(sw>=dw*2&&sh>=dh*2)return null;
        BufferedImage out=new BufferedImage(dw,dh,BufferedImage.TYPE_INT_RGB);int[] d=StreamQuality.pixels(out),s=StreamQuality.pixels(src);
        System.arraycopy(StreamQuality.pixels(StreamQuality.rgb(scaled)),0,d,0,d.length);
        double kx=dw/(double)sw,ky=dh/(double)sh;
        for(java.awt.Rectangle r:changed){int x0=Math.max(0,(int)Math.floor(r.x*kx)-2),y0=Math.max(0,(int)Math.floor(r.y*ky)-2),x1=Math.min(dw,(int)Math.ceil((r.x+r.width)*kx)+2),y1=Math.min(dh,(int)Math.ceil((r.y+r.height)*ky)+2);
            bilinear(s,sw,sh,d,dw,dh,x0,y0,x1,y1);}
        return out;
    }
    interface Row {void run(int y);}
    static void rows(int count,Row row){
        int parts=Math.min(Runtime.getRuntime().availableProcessors(),Math.max(1,count/64));
        if(parts<=1){for(int y=0;y<count;y++)row.run(y);return;}
        IntStream.range(0,parts).parallel().forEach(p->{int from=count*p/parts,to=count*(p+1)/parts;for(int y=from;y<to;y++)row.run(y);});
    }
}
