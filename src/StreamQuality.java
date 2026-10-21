import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.util.Iterator;
import java.util.concurrent.*;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageOutputStream;

class StreamQuality {
    record Config(String mode,int width,int height,boolean tiles) {
        Config {if(!java.util.Set.of("AUTO","HD","NATIVE","HEMAT","PREVIEW").contains(mode))mode="AUTO";width=Math.max(320,Math.min(7680,width));height=Math.max(180,Math.min(4320,height));}
        Config(String mode,int width,int height){this(mode,width,height,false);}
    }
    static Dimension fit(int width,int height,int maxWidth,int maxHeight){
        double scale=Math.min(1,Math.min(maxWidth/(double)width,maxHeight/(double)height));return new Dimension(Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale)));
    }
    static class State {
        /** Frames allowed in flight: hides network round-trip time without letting a slow link build a backlog. */
        static final int WINDOW=2;
        volatile Config config=new Config("PREVIEW",640,360);volatile int interval=300;
        final Semaphore ready=new Semaphore(WINDOW);final ConcurrentLinkedQueue<Long> inFlight=new ConcurrentLinkedQueue<>();
        volatile double milliseconds=60;volatile double factor=1;
        volatile long interactiveUntil=0;volatile boolean forceKey;
        /** Earliest moment a frame was requested by input (click, key, scroll); 0 = none. */
        volatile long wakeAt=0;final Object signal=new Object();
        static final long NUDGE_DELAY=9_000_000L,MIN_GAP=12_000_000L;
        int slow=0,fast=0;
        void configure(String[] p){try{config=new Config(p[1],Integer.parseInt(p[2]),Integer.parseInt(p[3]),p.length>5&&p[5].equals("TILES"));interval=p.length>4?Math.max(16,Math.min(2000,Integer.parseInt(p[4]))):120;}catch(RuntimeException ignored){}}
        void begin(){inFlight.add(System.nanoTime());}
        void ack(){Long sent=inFlight.poll();if(sent!=null){sample((System.nanoTime()-sent)/1_000_000.0);ready.release();synchronized(signal){signal.notifyAll();}}}
        /** Round-trip of a frame (send, decode on the master, acknowledgement). Persistent slowness lowers Otomatis resolution; it recovers when fast again. */
        synchronized void sample(double ms){
            milliseconds=milliseconds*.7+ms*.3;
            if(milliseconds>260){slow++;fast=0;if(slow>=4){factor=Math.max(.5,factor*.85);slow=0;}}
            else if(milliseconds<110){fast++;slow=0;if(fast>=10){factor=Math.min(1,factor+.1);fast=0;}}
            else {slow=0;fast=0;}
        }
        Dimension size(int w,int h){Config c=config;
            if(c.mode.equals("PREVIEW"))return fit(w,h,640,360);
            if(c.mode.equals("NATIVE"))return fit(w,h,3840,2160);
            if(c.mode.equals("HD"))return fit(w,h,1920,1080);
            if(c.mode.equals("HEMAT"))return fit(w,h,960,540);
            // Otomatis: follow the viewer's window up to Full HD, lowered automatically on a congested link.
            return fit(w,h,Math.max(640,(int)(Math.min(1920,c.width)*factor)),Math.max(360,(int)(Math.min(1080,c.height)*factor)));
        }
        /** Input keeps "moving" quality for 1 s; afterwards one sharper frame is sent. */
        void interact(){interactiveUntil=System.nanoTime()+1_000_000_000L;}
        boolean interactive(){return System.nanoTime()<interactiveUntil;}
        /** Fixed-rate pacing: the next frame starts one interval after this one started (33 ms = 30 fps), not after an extra pause. */
        void waitNext(long begun)throws InterruptedException{
            long target=begun+pause()*1_000_000L;
            while(true){long now=System.nanoTime(),due=target,wake=wakeAt;
                // Input arrived: capture shortly after it (time for the application to redraw) instead of waiting for the next tick.
                if(wake!=0&&wake<due)due=Math.max(wake,begun+MIN_GAP);
                if(now>=due){if(wake!=0&&wake<=now)wakeAt=0;return;}
                synchronized(signal){signal.wait(Math.max(1,(due-now)/1_000_000));}}
        }
        /** Called by the client after applying a click, key or scroll. */
        void nudge(){long at=System.nanoTime()+NUDGE_DELAY;long w=wakeAt;if(w==0||at<w)wakeAt=at;synchronized(signal){signal.notifyAll();}}
        /** Target time between frame starts (ms). 33 ms = 30 fps. */
        int pause(){return interactive()?Math.min(interval,interval<=50?25:33):interval;}
        float quality(){boolean moving=interactive();return switch(config.mode){case "PREVIEW"->.62f;case "HEMAT"->.68f;case "HD"->moving?.82f:.92f;case "NATIVE"->.92f;default->moving?.76f:.88f;};}
        boolean lossless(){return config.mode.equals("NATIVE")&&!interactive();}
    }
    static BufferedImage scale(BufferedImage raw,Dimension size){
        if(size.width==raw.getWidth()&&size.height==raw.getHeight())return rgb(raw);
        return FastScale.resize(raw,size.width,size.height);
    }
    /** Image backed by a plain int[] (fast comparisons and copies). */
    static BufferedImage rgb(BufferedImage image){
        if(image.getType()==BufferedImage.TYPE_INT_RGB&&image.getRaster().getDataBuffer() instanceof DataBufferInt d&&d.getNumBanks()==1&&d.getOffset()==0&&d.getData().length==image.getWidth()*image.getHeight())return image;
        BufferedImage copy=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=copy.createGraphics();g.drawImage(image,0,0,null);g.dispose();return copy;
    }
    static int[] pixels(BufferedImage rgb){return ((DataBufferInt)rgb.getRaster().getDataBuffer()).getData();}
    static byte[] encode(BufferedImage raw,State state)throws IOException {return encodeScaled(scale(raw,state.size(raw.getWidth(),raw.getHeight())),state);}
    static byte[] encodeScaled(BufferedImage img,State state)throws IOException {
        if(state.lossless()){byte[] png=png(img);if(png.length<LanControl.MAX-1024)return png;}
        byte[] jpeg=jpeg(img,state.quality());
        if(jpeg.length>=LanControl.MAX-1024)jpeg=jpeg(img,.70f);
        if(jpeg.length>=LanControl.MAX-1024)throw new IOException("Gambar melampaui batas paket.");return jpeg;
    }
    static byte[] png(BufferedImage image)throws IOException{ByteArrayOutputStream png=new ByteArrayOutputStream();ImageIO.write(image,"png",png);return png.toByteArray();}
    /** One reusable JPEG writer per thread: creating writers per frame was a measurable cost at 30 fps. */
    static final ThreadLocal<ImageWriter> WRITER=ThreadLocal.withInitial(()->{Iterator<ImageWriter> it=ImageIO.getImageWritersByFormatName("jpeg");return it.next();});
    static byte[] jpeg(BufferedImage image,float quality)throws IOException {
        ImageWriter writer=WRITER.get();ByteArrayOutputStream bytes=new ByteArrayOutputStream(64*1024);
        try(MemoryCacheImageOutputStream stream=new MemoryCacheImageOutputStream(bytes)){
            writer.setOutput(stream);ImageWriteParam p=writer.getDefaultWriteParam();p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);p.setCompressionQuality(quality);
            writer.write(null,new IIOImage(image,null,null),p);stream.flush();
        }catch(RuntimeException e){WRITER.remove();throw new IOException(e);}finally{writer.setOutput(null);}
        return bytes.toByteArray();
    }
}
