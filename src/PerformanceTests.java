import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.Arrays;

class PerformanceTests {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void run()throws Exception{
        StreamQuality.State state=new StreamQuality.State();check(state.size(3840,2160).equals(new Dimension(640,360)),"default preview is bounded");
        state.configure(new String[]{"VIDEO","AUTO","3840","2160","120"});check(state.size(3840,2160).equals(new Dimension(1920,1080)),"automatic resolution bounded to Full HD");
        state.configure(new String[]{"VIDEO","PREVIEW","640","360","1000"});check(state.pause()>=1000,"hidden preview slows down");state.interact();check(state.pause()==33,"input resumes quicker refresh");state.interactiveUntil=0;
        state.config=new StreamQuality.Config("NATIVE",800,450);FrameCache cache=new FrameCache();BufferedImage first=VisualTests.sample(800,450);BufferedImage second=new BufferedImage(800,450,BufferedImage.TYPE_INT_RGB);second.setRGB(0,0,800,450,first.getRGB(0,0,800,450,null,0,800),0,800);
        byte[] encoded=cache.encode(first,state);check((encoded[0]&255)==137,"first frame encoded");byte[] same=cache.encode(second,state);check(LanControl.string(same).equals("FRAME_SAME"),"unchanged image skips encoder");
        BufferedImage changed=new BufferedImage(800,450,BufferedImage.TYPE_INT_RGB);changed.setRGB(0,0,800,450,first.getRGB(0,0,800,450,null,0,800),0,800);changed.setRGB(799,449,first.getRGB(799,449)^0x00ffffff);check(!Arrays.equals(cache.encode(changed,state),same),"single-pixel change is transmitted");
        state.config=new StreamQuality.Config("HD",800,450);check(!Arrays.equals(cache.encode(changed,state),same),"quality change forces new frame");cache.clear();check(!Arrays.equals(cache.encode(changed,state),same),"new session always receives initial image");
        long begin=System.nanoTime();long originalBytes=0;for(int i=0;i<8;i++)originalBytes+=StreamQuality.encode(first,state).length;long originalMs=(System.nanoTime()-begin)/1_000_000;
        cache.clear();begin=System.nanoTime();long optimizedBytes=0;for(int i=0;i<8;i++)optimizedBytes+=cache.encode(first,state).length;long optimizedMs=(System.nanoTime()-begin)/1_000_000;
        System.out.println("PASS: bounded preview/auto resolution, visibility pacing, exact static-frame suppression, quality/session refresh. Static 8-frame sample: "+originalMs+" -> "+optimizedMs+" ms, "+originalBytes+" -> "+optimizedBytes+" bytes (Linux sample).");
    }
}
