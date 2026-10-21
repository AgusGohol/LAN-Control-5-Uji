import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** v1.7.0 streaming: changed-tile frames, exact reconstruction, keyframes, compatibility with older masters, 30 fps pacing. */
class StreamTests {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static BufferedImage copy(BufferedImage s){BufferedImage c=new BufferedImage(s.getWidth(),s.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=c.createGraphics();g.drawImage(s,0,0,null);g.dispose();return c;}
    static int maxDiff(BufferedImage a,BufferedImage b){int m=0;for(int y=0;y<a.getHeight();y+=3)for(int x=0;x<a.getWidth();x+=3){int p=a.getRGB(x,y),q=b.getRGB(x,y);for(int s=0;s<24;s+=8)m=Math.max(m,Math.abs(((p>>s)&255)-((q>>s)&255)));}return m;}
    static double meanDiff(BufferedImage a,BufferedImage b){long sum=0,n=0;for(int y=0;y<a.getHeight();y+=2)for(int x=0;x<a.getWidth();x+=2){int p=a.getRGB(x,y),q=b.getRGB(x,y);for(int s=0;s<24;s+=8){sum+=Math.abs(((p>>s)&255)-((q>>s)&255));n++;}}return sum/(double)n;}
    static BufferedImage show(BufferedImage shown,byte[] d)throws Exception{if(FrameCache.isTiles(d))return FrameCache.apply(shown,d);if(LanControl.string(d).equals("FRAME_SAME"))return shown;return ImageIO.read(new ByteArrayInputStream(d));}
    static void run()throws Exception{
        int[] a=new int[256*192],b=a.clone();b[10*256+10]=1;b[150*256+250]=1;b[10*256+70]=1;
        List<Rectangle> r=FrameCache.dirty(a,b,256,192);check(r.size()==2&&r.contains(new Rectangle(0,0,128,64))&&r.contains(new Rectangle(192,128,64,64)),"adjacent tiles merge, distant tiles separate: "+r);
        int[] c=a.clone();for(int y=0;y<192;y++)c[y*256+5]=7;check(FrameCache.dirty(a,c,256,192).equals(List.of(new Rectangle(0,0,64,192))),"vertical runs merge into one rectangle");
        check(FrameCache.dirty(a,a.clone(),256,192).isEmpty(),"no change, no tiles");

        StreamQuality.State st=new StreamQuality.State();st.configure(new String[]{"VIDEO","NATIVE","1600","900","50","TILES"});check(st.config.tiles()&&st.interval==50,"TILES flag and interval parsed");
        FrameCache cache=new FrameCache();BufferedImage screen=copy(VisualTests.sample(1600,900)),shown=null;
        byte[] first=cache.encode(screen,st);check(!FrameCache.isTiles(first),"session starts with a full frame");shown=show(shown,first);
        Graphics2D g=screen.createGraphics();g.setColor(Color.RED);g.setFont(new Font("SansSerif",Font.BOLD,20));g.drawString("Teks baru diketik",300,620);g.fillRect(1500,40,30,30);g.dispose();
        byte[] partial=cache.encode(screen,st);check(FrameCache.isTiles(partial)&&partial.length<first.length/5,"small change sends only tiles: "+partial.length+" vs "+first.length);
        shown=show(shown,partial);check(maxDiff(shown,screen)==0,"lossless tiles rebuild the screen pixel-exact");
        check(LanControl.string(cache.encode(screen,st)).equals("FRAME_SAME"),"unchanged screen stays tiny");
        st.forceKey=true;check(!FrameCache.isTiles(cache.encode(screen,st))&&!st.forceKey,"VIDEO_KEY forces one full frame");
        Graphics2D big=screen.createGraphics();big.setColor(Color.BLUE);big.fillRect(0,0,1600,700);big.dispose();check(!FrameCache.isTiles(cache.encode(screen,st)),"large change sends a full frame");
        check(FrameCache.apply(new BufferedImage(800,450,BufferedImage.TYPE_INT_RGB),partial)==null,"tiles never painted onto a wrong-size frame");

        StreamQuality.State jpeg=new StreamQuality.State();jpeg.configure(new String[]{"VIDEO","AUTO","1920","1080","33","TILES"});jpeg.interact();FrameCache lossy=new FrameCache();BufferedImage s2=copy(VisualTests.sample(1920,1080)),view=show(null,lossy.encode(s2,jpeg));
        for(int i=0;i<20;i++){Graphics2D t=s2.createGraphics();t.setColor(new Color(20*i%255,90,160));t.fillRect(200+i*30,500,24,24);t.dispose();jpeg.interact();view=show(view,lossy.encode(s2,jpeg));}
        // Baseline: one full JPEG of the final screen at the same quality. Tile updates must not drift beyond normal JPEG error.
        BufferedImage baseline=ImageIO.read(new ByteArrayInputStream(StreamQuality.jpeg(s2,jpeg.quality())));
        check(lossy.tileFrames==20&&meanDiff(view,s2)<=meanDiff(baseline,s2)*1.5+0.5&&maxDiff(view,s2)<=maxDiff(baseline,s2)+12,"20 JPEG tile updates stay as faithful as a full JPEG (mean "+meanDiff(view,s2)+" vs "+meanDiff(baseline,s2)+", max "+maxDiff(view,s2)+" vs "+maxDiff(baseline,s2)+")");

        StreamQuality.State old=new StreamQuality.State();old.configure(new String[]{"VIDEO","AUTO","1920","1080","120"});FrameCache legacy=new FrameCache();BufferedImage s3=copy(VisualTests.sample(1280,720));legacy.encode(s3,old);s3.setRGB(5,5,0xff0000);
        check(!FrameCache.isTiles(legacy.encode(s3,old)),"older masters (no TILES) always receive full frames");
        check(old.interval==120&&new StreamQuality.State().interval==300,"older master interval honoured");

        StreamQuality.State w=new StreamQuality.State();for(int i=0;i<StreamQuality.State.WINDOW;i++){check(w.ready.tryAcquire(),"window slot "+i);w.begin();}check(!w.ready.tryAcquire(),"at most "+StreamQuality.State.WINDOW+" frames in flight");w.ack();check(w.ready.tryAcquire(),"acknowledgement frees a slot");
        // Master: re-scaling only changed areas must give exactly the same picture as re-scaling the whole frame, and be much cheaper.
        BufferedImage before=copy(VisualTests.sample(1920,1080)),after=copy(before);Graphics2D q=after.createGraphics();q.setColor(Color.MAGENTA);q.fillRect(700,400,130,70);q.fillRect(64,1000,64,80);q.dispose();
        List<Rectangle> areas=List.of(new Rectangle(640,384,256,128),new Rectangle(64,960,64,120));
        BufferedImage incremental=FastScale.update(FastScale.resize(before,1600,900),after,areas),full=FastScale.resize(after,1600,900);
        check(Arrays.equals(StreamQuality.pixels(incremental),StreamQuality.pixels(full)),"partial re-scale is pixel-identical to a full re-scale");
        check(FastScale.update(FastScale.resize(before,800,450),after,areas)==null,"strong reductions fall back to the full (averaged) resize");
        for(int i=0;i<20;i++){FastScale.update(full,after,areas);FastScale.resize(after,1600,900);}
        long t0=System.nanoTime();for(int i=0;i<20;i++)FastScale.update(full,after,areas);long t1=System.nanoTime();for(int i=0;i<20;i++)FastScale.resize(after,1600,900);long t2=System.nanoTime();
        check((t1-t0)<(t2-t1),"partial re-scale faster than full ("+(t1-t0)/20_000_000.0+" vs "+(t2-t1)/20_000_000.0+" ms)");
        BufferedImage java2d=new BufferedImage(1600,900,BufferedImage.TYPE_INT_RGB);Graphics2D lg=java2d.createGraphics();lg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);lg.drawImage(after,0,0,1600,900,null);lg.dispose();
        check(meanDiff(full,java2d)<1.0,"fast scaler looks the same as Java2D bilinear (mean diff "+meanDiff(full,java2d)+")");
        FrameCache.Applied applied=FrameCache.applyTiles(shown,partial);check(applied!=null&&!applied.changed().isEmpty(),"tile frames report their changed areas");
        pacing();
        System.out.println("PASS: v1.7.0 streaming: tile merge, tiles-only updates, pixel-exact lossless rebuild, JPEG tile fidelity, FRAME_SAME, keyframe request, large-change fallback, old-master compatibility, 2-frame window, ~30 fps over encrypted loopback.");
    }
    /** Real Wire + Peer + Card at 1080p with a moving region: the master must see close to 30 updates per second. */
    static void pacing()throws Exception{
        try(ServerSocket server=new ServerSocket(0,8,InetAddress.getLoopbackAddress())){
            Future<?> loop=LanControl.WORK.submit(()->{try{LanControl.serve(server,"tiga-puluh-fps",()->true,(s,w)->{
                StreamQuality.State st=new StreamQuality.State();FrameCache cache=new FrameCache();BufferedImage screen=copy(VisualTests.sample(1920,1080));
                Future<?> reader=LanControl.WORK.submit(()->{try{while(!s.isClosed()){String m=LanControl.string(w.read());if(m.equals("VIDEO_ACK"))st.ack();else if(m.equals("VIDEO_KEY"))st.forceKey=true;}}catch(Exception e){}});
                st.configure(new String[]{"VIDEO","AUTO","1920","1080","33","TILES"});int i=0;
                try{while(!s.isClosed()){if(!st.ready.tryAcquire(10,TimeUnit.SECONDS))break;Graphics2D g=screen.createGraphics();g.setColor(new Color((i*9)%255,120,200));g.fillOval(300+(i*23)%1200,300+(i*11)%400,220,160);g.dispose();i++;
                    long b=System.nanoTime();st.begin();w.send(cache.encode(screen,st));long rest=st.pause()-(System.nanoTime()-b)/1_000_000;if(rest>0)Thread.sleep(rest);}}finally{reader.cancel(true);}});}catch(Exception ignored){}});
            LanControl.Controller[] h=new LanControl.Controller[1];
            SwingUtilities.invokeAndWait(()->{LanControl.Controller c=new LanControl.Controller(true);c.port=server.getLocalPort();c.password.setText("tiga-puluh-fps");c.autoUpdate.setSelected(false);c.cards.get(0).ip.setText("127.0.0.1");c.cards.get(0).connect();h[0]=c;});
            LanControl.Card card=h[0].cards.get(0);for(int i=0;i<100&&card.peer==null;i++)Thread.sleep(50);Thread.sleep(3500);double fps=card.peer.fps();
            check(fps>=20&&fps<=31&&card.image.getWidth()==1920,"Full HD stream reaches ~30 fps, measured "+fps);
            SwingUtilities.invokeAndWait(card::disconnect);server.close();loop.get(5,TimeUnit.SECONDS);
            System.out.println("     (loopback Full HD: "+fps+" fps)");
        }
    }
}
