import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

class LatencyTests {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void run()throws Exception {
        ControlQueue queue=new ControlQueue();
        for(int i=0;i<100000;i++)check(queue.offer("MOVE "+i+" 0"),"motion should not overflow queue");
        check(queue.poll(1).equals("MOVE 99999 0"),"latest pointer replaces 99999 stale moves");check(queue.poll(1)==null,"no motion backlog");
        queue.offer("RPC a LIST -");queue.offer("VIDEO AUTO 640 360");queue.offer("VIDEO AUTO 1920 1080");queue.offer("VIDEO_ACK");queue.offer("VIDEO_ACK");
        queue.offer("MOVE 0.1 0.2");queue.offer("DOWN 1024");queue.offer("MOVE 0.7 0.8");queue.offer("UP 1024");queue.offer("KEYDOWN 65");queue.offer("KEYUP 65");
        for(String expected:new String[]{"MOVE 0.1 0.2","DOWN 1024","MOVE 0.7 0.8","UP 1024","KEYDOWN 65","KEYUP 65","VIDEO_ACK","VIDEO AUTO 1920 1080","RPC a LIST -"})check(expected.equals(queue.poll(1)),"control order and priority: "+expected);
        check(queue.poll(1)==null,"duplicate video confirmations coalesced");
        StreamQuality.State quality=new StreamQuality.State();quality.config=new StreamQuality.Config("NATIVE",1920,1080);BufferedImage source=VisualTests.sample(800,450);
        byte[] idle=StreamQuality.encode(source,quality);check((idle[0]&255)==137,"idle native uses lossless PNG");quality.interact();byte[] interactive=StreamQuality.encode(source,quality);check((interactive[0]&255)==255,"input switches to lightweight JPEG");check(quality.pause()==33,"active input runs at 30 fps");
        check(ImageIO.read(new ByteArrayInputStream(interactive)).getWidth()==800,"interactive keeps native image dimensions");quality.interactiveUntil=0;byte[] restored=StreamQuality.encode(source,quality);check((restored[0]&255)==137,"lossless restored after input stops");
        networkOrder();
        System.out.println("PASS: 100,000 pointer moves collapse to one, click/drag/key order retained, controls precede bulk work, interactive encoding and encrypted input order.");
    }
    static void networkOrder()throws Exception{
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Future<?> received=executor.submit(()->{try(Socket s=listener.accept();LanControl.Wire wire=new LanControl.Wire(s,"latency-test-code",true)){
                for(String expected:new String[]{"MOVE 0.5 0.5","DOWN 1024","UP 1024","KEYDOWN 65","KEYUP 65","RPC a LIST -"})check(expected.equals(LanControl.string(wire.read())),"wire order: "+expected);
            }catch(Exception e){throw new RuntimeException(e);}});
            try(Socket s=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort());LanControl.Wire wire=new LanControl.Wire(s,"latency-test-code",false)){
                ControlQueue q=new ControlQueue();q.offer("RPC a LIST -");for(int i=0;i<10000;i++)q.offer("MOVE 0.1 0.1");q.offer("MOVE 0.5 0.5");q.offer("DOWN 1024");q.offer("UP 1024");q.offer("KEYDOWN 65");q.offer("KEYUP 65");for(int i=0;i<6;i++)wire.send(LanControl.command(q.poll(1)));
            }received.get(20,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
    }
}
